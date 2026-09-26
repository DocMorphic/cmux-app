package io.github.docmorphic.cmuxapp

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class BrowserFrame(
    val sequence: Long,
    val image: ImageBitmap,
    val pageWidth: Double,
    val pageHeight: Double
)

/** One streamed Mac browser panel, rendered from the official JPEG/PNG frame events. */
@Composable
fun NativeBrowserView(
    client: MobileRpcClient,
    panelId: String,
    title: String,
    viewportWidth: Int,
    viewportHeight: Int,
    viewportScale: Double,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var frame by remember(panelId) { mutableStateOf<BrowserFrame?>(null) }
    var address by remember(panelId) { mutableStateOf("") }
    var textInput by remember(panelId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client, panelId, viewportWidth, viewportHeight, viewportScale) {
        val collector = launch {
            client.events.collect { event ->
                if (event.topic != "browser.frame" || event.payload.optString("panel_id") != panelId) return@collect
                val decoded = withContext(Dispatchers.Default) { decodeFrame(event.payload) } ?: return@collect
                if (decoded.sequence > (frame?.sequence ?: -1)) frame = decoded
            }
        }
        var streamId: String? = null
        try {
            streamId = client.subscribe(listOf("browser.frame", "browser.state", "browser.dialog"))
                .optString("stream_id").takeIf { it.isNotBlank() }
            val descriptor = client.startBrowserStream(panelId, viewportWidth, viewportHeight, viewportScale)
            address = descriptor.optString("url")
            collector.join()
        } catch (failure: Throwable) {
            error = failure.message ?: "Browser stream disconnected"
        } finally {
            collector.cancel()
            withContext(NonCancellable) { runCatching { client.stopBrowserStream(panelId) } }
            streamId?.let { id -> withContext(NonCancellable) { runCatching { client.unsubscribe(id) } } }
        }
    }

    LaunchedEffect(frame?.sequence) {
        val current = frame ?: return@LaunchedEffect
        runCatching { client.acknowledgeBrowserFrame(panelId, current.sequence) }
            .onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Workspaces") }
            Text(title.ifBlank { "Browser" }, modifier = Modifier.weight(1f), maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { runCatching { client.browserCommand(panelId, "back") }.onFailure { error = it.message } } }) { Text("‹") }
            TextButton(onClick = { scope.launch { runCatching { client.browserCommand(panelId, "forward") }.onFailure { error = it.message } } }) { Text("›") }
            TextButton(onClick = { scope.launch { runCatching { client.browserCommand(panelId, "reload") }.onFailure { error = it.message } } }) { Text("↻") }
            OutlinedTextField(address, { address = it }, Modifier.weight(1f), singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    scope.launch { runCatching { client.browserCommand(panelId, "navigate", address) }
                        .onFailure { error = it.message } }
                }))
        }
        val current = frame
        if (current == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Waiting for browser…", color = Color(0xFF969AA3))
            }
        } else {
            Image(
                bitmap = current.image, contentDescription = "Mac browser page",
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .pointerInput(panelId, current.pageWidth, current.pageHeight) {
                        detectTapGestures { point ->
                            val x = point.x.toDouble() / size.width * current.pageWidth
                            val y = point.y.toDouble() / size.height * current.pageHeight
                            scope.launch { runCatching { client.browserClick(panelId, x, y) }
                                .onFailure { error = it.message } }
                        }
                    }
                    .pointerInput(panelId, current.pageWidth, current.pageHeight) {
                        var total = 0.0
                        detectDragGestures(
                            onDragStart = {
                                total = 0.0
                                scope.launch { runCatching {
                                    client.browserScroll(panelId, 0.0, current.pageWidth / 2, current.pageHeight / 2, "began")
                                }.onFailure { error = it.message } }
                            },
                            onDragEnd = {
                                val delta = total
                                scope.launch { runCatching {
                                    client.browserScroll(panelId, delta, current.pageWidth / 2, current.pageHeight / 2, "changed")
                                    client.browserScroll(panelId, 0.0, current.pageWidth / 2, current.pageHeight / 2, "ended")
                                }.onFailure { error = it.message } }
                            }
                        ) { change, amount ->
                            total -= amount.y.toDouble() / size.height * current.pageHeight
                            change.consume()
                        }
                    },
                contentScale = ContentScale.FillBounds
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(textInput, { textInput = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Type in page") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val value = textInput; textInput = ""
                    scope.launch { runCatching { client.browserText(panelId, value) }
                        .onFailure { error = it.message } }
                }))
            TextButton(onClick = {
                val value = textInput; textInput = ""
                scope.launch { runCatching { client.browserText(panelId, value) }
                    .onFailure { error = it.message } }
            }) { Text("Send") }
        }
        if (error != null) Text(error.orEmpty(), Modifier.fillMaxWidth().padding(8.dp), color = Color(0xFFFF9999))
    }
}

private fun decodeFrame(value: JSONObject): BrowserFrame? = runCatching {
    require(value.optString("format") in setOf("jpeg", "png"))
    val width = value.getInt("pixel_width")
    val height = value.getInt("pixel_height")
    require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 12_000_000)
    val encoded = value.getString("data_b64")
    require(encoded.length <= 12_000_000)
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    require(bytes.size <= 8 * 1024 * 1024)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid browser image")
    BrowserFrame(value.getLong("seq"), bitmap.asImageBitmap(),
        value.getDouble("page_width"), value.getDouble("page_height"))
}.getOrNull()
