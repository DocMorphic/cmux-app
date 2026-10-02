package io.github.docmorphic.cmuxapp

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class BrowserFrame(val sequence: Long, val image: ImageBitmap, val pageWidth: Double, val pageHeight: Double,
    val generation: Long = 0)

/** One streamed Mac browser panel with the iOS bottom navigation/input controls. */
@Composable
internal fun NativeBrowserView(client: MobileRpcClient, panelId: String, title: String, onBack: () -> Unit,
    recoveryClock: BrowserRecoveryClock = MonotonicBrowserRecoveryClock,
    onOnDevice: ((String) -> Unit)? = null, onDeviceUnavailable: String? = null) {
    val stream = remember(client) { MacBrowserStreamClient(client) }
    NativeBrowserView(stream, panelId, title, onBack, recoveryClock,
        onOnDevice = onOnDevice, onDeviceUnavailable = onDeviceUnavailable)
}

@Composable
internal fun NativeBrowserView(client: BrowserStreamClient, panelId: String, title: String, onBack: () -> Unit,
    recoveryClock: BrowserRecoveryClock = MonotonicBrowserRecoveryClock,
    onReconnect: (() -> Unit)? = null, onOnDevice: ((String) -> Unit)? = null, onDeviceUnavailable: String? = null) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    var frame by remember(client, panelId) { mutableStateOf<BrowserFrame?>(null) }
    var page by remember(client, panelId) { mutableStateOf(BrowserPageState(title = title)) }
    var address by remember(client, panelId) { mutableStateOf("") }
    var addressFocused by remember(client, panelId) { mutableStateOf(false) }
    var policy by remember(client, panelId) { mutableStateOf(BrowserKeyboardPolicy()) }
    var keyboardRequest by remember(client, panelId) { mutableIntStateOf(0) }
    var error by remember(client, panelId) { mutableStateOf<String?>(null) }
    var ready by remember(client, panelId) { mutableStateOf(false) }
    var retry by remember(client, panelId) { mutableIntStateOf(0) }
    var streamGeneration by remember(client, panelId) { mutableLongStateOf(0) }
    var dialog by remember(client, panelId) { mutableStateOf<JSONObject?>(null) }
    var measured by remember(client, panelId) { mutableStateOf(IntSize.Zero) }
    var appliedViewport by remember(client, panelId) { mutableStateOf<Triple<Int, Int, Double>?>(null) }
    val viewport = Triple((measured.width / density.density).roundToInt().coerceIn(1, 4096),
        (measured.height / density.density).roundToInt().coerceIn(1, 4096), density.density.toDouble())
    val back by rememberUpdatedState(onBack)
    val session = remember(client, panelId) { Mutex() }
    val recovery = remember(client, panelId, recoveryClock) { BrowserStreamRecovery(scope, recoveryClock) { retry++ } }
    val queue = remember(client, panelId, recovery) { BrowserInputQueue(scope) {
        recovery.noteInput()
        client.input(panelId, it)
    } }
    val scrollMotion = rememberBrowserScrollMotion(queue)
    val inputError by queue.error.collectAsState()
    DisposableEffect(queue, recovery) { onDispose { recovery.close(); queue.close() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle, recovery, queue) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!foreground) { recovery.stopped(); queue.pauseIfPending(); policy = policy.hide() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler { policy = policy.hide(); focusManager.clearFocus(); keyboard?.hide(); onBack() }
    LaunchedEffect(page.url, addressFocused) { if (!addressFocused) address = page.url }

    LaunchedEffect(client, panelId, retry, foreground, recovery) {
        if (!foreground) return@LaunchedEffect
        snapshotFlow { measured }.first { it.width > 0 && it.height > 0 }
        session.withLock {
            ready = false; error = null; appliedViewport = null
            // SSH attachments must acquire fresh pointer authority. Native Mac
            // reconnects keep their last image while awaiting replacement pixels.
            if (client.clearsFrameOnRestart) frame = null
            val generation = recovery.started().also { streamGeneration = it }
            var newestSequence = -1L
            var stateEvents = 0
            var dialogEvents = 0
            val streamId = java.util.UUID.randomUUID().toString()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { event ->
                    if (event.payload.optString("panel_id") != panelId || event.streamId != streamId) return@collect
                    when (event.topic) {
                        "browser.closed" -> { back(); return@collect }
                        "browser.dialog" -> { dialogEvents++; dialog = event.payload }
                        "browser.dialog.resolved" -> {
                            dialogEvents++
                            if (dialog?.optString("dialog_id") == event.payload.optString("dialog_id")) dialog = null
                        }
                        "browser.state" -> {
                            stateEvents++; page = BrowserPageState.read(event.payload)
                            if (event.payload.has("stream_error")) error = event.payload.opt("stream_error") as? String
                            policy = policy.pageFocus(page.editableFocused)
                        }
                        "browser.frame" -> {
                            val decoded = withContext(Dispatchers.Default) { decodeBrowserFrame(event.payload) } ?: return@collect
                            if (decoded.sequence > newestSequence) {
                                newestSequence = decoded.sequence
                                frame = decoded.copy(generation = generation)
                            }
                        }
                    }
                }
            }
            try {
                // onSizeChanged can wake snapshotFlow before recomposition
                // updates derived state. Read the measured pixels directly.
                val initial = Triple((measured.width / density.density).roundToInt().coerceIn(1, 4096),
                    (measured.height / density.density).roundToInt().coerceIn(1, 4096), density.density.toDouble())
                val descriptor = client.start(panelId, streamId, initial.first, initial.second, initial.third)
                ensureActive()
                // A push received during stream.start is newer than its descriptor.
                if (stateEvents == 0) page = BrowserPageState.read(descriptor)
                if (dialogEvents == 0) dialog = descriptor.optJSONObject("pending_dialog")
                appliedViewport = initial; ready = true
                collector.join()
            } catch (failure: Exception) {
                rethrowBrowserCancellation(failure)
                queue.pause()
                error = failure.message ?: "Browser stream disconnected"
            } finally {
                ready = false; recovery.stopped(); collector.cancel()
                withContext(NonCancellable) {
                    collector.join()
                    runCatching { client.stop(panelId, streamId) }
                }
            }
        }
    }
    LaunchedEffect(client, panelId, recovery) {
        client.disconnected.collect { failure -> ready = false; recovery.stopped(); queue.pause(); policy = policy.hide(); error = failure.message ?: "Browser disconnected" }
    }
    // Keyboard/rotation changes update viewport without restarting or resetting frame sequence.
    LaunchedEffect(client, panelId, ready, viewport, foreground) {
        if (!foreground || !ready || appliedViewport == viewport) return@LaunchedEffect
        delay(60)
        try {
            client.viewport(panelId, viewport.first, viewport.second, viewport.third)
            ensureActive(); appliedViewport = viewport
        } catch (failure: Exception) {
            rethrowBrowserCancellation(failure)
            error = failure.message ?: "Could not resize browser"
        }
    }
    LaunchedEffect(client, panelId, frame?.sequence, frame?.generation, streamGeneration, foreground) {
        val current = frame ?: return@LaunchedEffect
        if (!foreground || current.generation != streamGeneration) return@LaunchedEffect
        withFrameNanos { }
        recovery.noteDisplayedFrame(current.generation)
        try { client.displayed(panelId, current.sequence) }
        catch (failure: Exception) { rethrowBrowserCancellation(failure); error = failure.message }
    }
    val inputEnabled = foreground && ready && inputError == null && dialog == null
    if (dialog != null) BrowserDialog(checkNotNull(dialog), client, panelId,
        onResolved = { id -> if (dialog?.optString("dialog_id") == id) dialog = null }, onError = { error = it })

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { policy = policy.hide(); focusManager.clearFocus(); keyboard?.hide(); onBack() }) { Text("‹  Workspaces") }
            Text(page.title.ifBlank { title.ifBlank { "Browser" } }, modifier = Modifier.weight(1f), maxLines = 1)
            if (onOnDevice != null) BrowserModePicker(BrowserMode.STREAMED, onDeviceUnavailable) { onOnDevice(page.url) }
            // Android requires nonzero bounds for IME focus. Keep this endpoint
            // in the header so its invisible View cannot intercept page taps.
            key(queue) { BrowserKeyboardProxy(queue, policy.focus && !addressFocused && dialog == null, inputEnabled, keyboardRequest, Modifier.size(1.dp)) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { measured = it }, contentAlignment = Alignment.Center) {
            val current = frame
            if (current == null) Text(if (ready && page.url in setOf("", "about:blank")) "Search or enter an address below." else "Waiting for browser…", color = Color(0xFF969AA3))
            else BrowserPageSurface(current, queue, scrollMotion, streamGeneration, inputEnabled,
                onTap = { focusManager.clearFocus() }, pageDescription = client.pageDescription)
        }
        if (error != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error.orEmpty(), Modifier.weight(1f), color = Color(0xFFFF9999), fontSize = 12.sp)
            TextButton(onClick = { queue.pause(); onReconnect?.invoke(); retry++ }) { Text("Reconnect") }
        }
        if (inputError != null) Column(Modifier.padding(horizontal = 12.dp)) {
            Text(inputError.orEmpty(), color = Color(0xFFFF9999), fontSize = 12.sp)
            TextButton(onClick = { queue.resume() }, enabled = ready) { Text("Resume browser input") }
        }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 6.dp, bottom = 10.dp),
            shape = RoundedCornerShape(28.dp), color = Color(0xFF202226)) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrowserChromeButton("Browser Back", R.drawable.ic_browser_back, inputEnabled && (!client.reportsHistory || page.canGoBack)) { queue.offer(BrowserInput.Navigation("back")) }
                    BrowserChromeButton("Browser Forward", R.drawable.ic_browser_forward, inputEnabled && (!client.reportsHistory || page.canGoForward)) { queue.offer(BrowserInput.Navigation("forward")) }
                    BasicTextField(address, { address = it }, Modifier.weight(1f).heightIn(min = 44.dp)
                        .background(Color(0xFF303238), RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 12.dp)
                        .semantics { contentDescription = "Browser address" }
                        .onFocusChanged { state ->
                            if (state.isFocused && !addressFocused) { scrollMotion.stop(); address = page.url }
                            addressFocused = state.isFocused
                        }, singleLine = true, enabled = foreground && ready && inputError == null,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White, textAlign = if (addressFocused) TextAlign.Start else TextAlign.Center),
                        cursorBrush = SolidColor(Color(0xFF76B9FF)), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val target = address.trim()
                            if (target.isNotEmpty()) {
                                queue.offer(BrowserInput.Navigation("navigate", target))
                                policy = policy.hide(); focusManager.clearFocus(); keyboard?.hide()
                            }
                        }), decorationBox = { inner ->
                            if (address.isEmpty()) Text("Search or address", color = Color(0xFF969AA3), fontSize = 12.sp, maxLines = 1)
                            inner()
                        })
                    BrowserChromeButton("Reload browser", R.drawable.ic_browser_reload, inputEnabled) { queue.offer(BrowserInput.Navigation("reload")) }
                    BrowserChromeButton(if (keyboardVisible) "Hide browser keyboard" else "Show browser keyboard", R.drawable.ic_browser_keyboard, inputEnabled) {
                        scrollMotion.stop()
                        if (keyboardVisible) { policy = policy.hide(); focusManager.clearFocus(); keyboard?.hide() }
                        else { focusManager.clearFocus(); policy = policy.show(); keyboardRequest++ }
                    }
                }
                if (page.loading) LinearProgressIndicator(progress = { page.progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = "Browser loading" })
            }
        }
    }
}

@Composable
private fun BrowserChromeButton(label: String, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp).semantics { contentDescription = label }, enabled = enabled) {
        Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(19.dp))
    }
}

@Composable
private fun BrowserDialog(value: JSONObject, client: BrowserStreamClient, panel: String, onResolved: (String) -> Unit, onError: (String) -> Unit) {
    val id = value.optString("dialog_id")
    val scope = rememberCoroutineScope()
    var text by remember(client, panel, id) { mutableStateOf(value.optJSONObject("text_field")?.optString("initial").orEmpty()) }
    var busy by remember(client, panel, id) { mutableStateOf(false) }
    val currentId by rememberUpdatedState(id)
    val resolved by rememberUpdatedState(onResolved)
    val reportError by rememberUpdatedState(onError)
    val buttons = value.optJSONArray("buttons")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
    fun respond(button: JSONObject) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                client.respondDialog(panel, id, button.optString("id"), text.takeIf { value.optJSONObject("text_field") != null })
                ensureActive(); resolved(id)
            } catch (failure: Exception) {
                rethrowBrowserCancellation(failure)
                if (id == currentId) reportError(failure.message ?: "Could not respond to browser dialog")
            } finally { if (id == currentId) busy = false }
        }
    }
    AlertDialog(onDismissRequest = { buttons.firstOrNull { it.optString("role") == "cancel" }?.let(::respond) },
        title = { Text(value.optString("title").ifBlank { value.optString("host", "Browser") }) },
        text = { Column {
            Text(value.optString("message"))
            value.optJSONObject("text_field")?.let { field -> OutlinedTextField(text, { text = it }, enabled = !busy,
                label = { Text(field.optString("placeholder", "Response")) },
                visualTransformation = if (field.optBoolean("secure")) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None) }
        } }, confirmButton = { Column {
            buttons.forEach { button -> TextButton(onClick = { respond(button) }, enabled = !busy) { Text(button.optString("label")) } }
        } })
}

internal fun decodeBrowserFrame(value: JSONObject): BrowserFrame? = runCatching {
    require(value.optString("format") in setOf("jpeg", "png"))
    val width = value.getInt("pixel_width"); val height = value.getInt("pixel_height")
    require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 12_000_000)
    val pageWidth = value.getDouble("page_width"); val pageHeight = value.getDouble("page_height")
    require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0 && pageHeight > 0)
    val sequence = value.getLong("seq"); require(sequence >= 0)
    val encoded = value.getString("data_b64"); require(encoded.length <= 12_000_000)
    val bytes = Base64.decode(encoded, Base64.DEFAULT); require(bytes.size <= 8 * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth == width && bounds.outHeight == height)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid browser image")
    BrowserFrame(sequence, bitmap.asImageBitmap(), pageWidth, pageHeight)
}.getOrNull()
