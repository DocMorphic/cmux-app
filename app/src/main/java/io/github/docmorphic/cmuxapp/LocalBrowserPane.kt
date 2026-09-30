package io.github.docmorphic.cmuxapp

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A cancelled chooser cannot deliver its result to another view or a later chooser. */
internal class LocalBrowserFileSelection(private var inFlight: Boolean = false) {
    val awaitingResult get() = inFlight
    private var owner: Any? = null
    private var callback: ValueCallback<Array<Uri>>? = null
    private var multiple = false
    fun begin(view: Any, allowMultiple: Boolean, receive: ValueCallback<Array<Uri>>): Boolean {
        if (inFlight) return false
        inFlight = true; owner = view; multiple = allowMultiple; callback = receive; return true
    }
    fun finish(values: Array<Uri>?) {
        val deliver = callback; callback = null; owner = null; inFlight = false
        val accepted = values?.filter { it.scheme == "content" }?.distinct()?.take(if (multiple) 128 else 1)?.toTypedArray()
        deliver?.onReceiveValue(accepted?.takeIf { it.isNotEmpty() })
    }
    fun cancel(view: Any) {
        if (owner !== view) return
        callback?.onReceiveValue(null); callback = null; owner = null
        // Keep the outstanding Activity result reserved until it arrives.
    }
}

@Composable
internal fun LocalBrowserPane(surface: LocalBrowserSurface, beforeNavigation: (suspend (String?) -> Unit)? = null, onClose: () -> Unit) {
    val state by surface.state.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Reserve an outstanding result across surface changes in this composition.
    val files = rememberSaveable(saver = Saver<LocalBrowserFileSelection, Boolean>(
        save = { it.awaitingResult }, restore = { LocalBrowserFileSelection(it) }
    )) { LocalBrowserFileSelection() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        files.finish(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    fun submit() { if (surface.submitAddress()) { focus.clearFocus(); keyboard?.hide() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0C0E)).imePadding().testTag("LocalBrowserPane")) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF191B1F)).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LocalBrowserButton("Browser Back", "‹", state.canGoBack) { surface.request(LocalBrowserCommand.BACK) }
            LocalBrowserButton("Browser Forward", "›", state.canGoForward) { surface.request(LocalBrowserCommand.FORWARD) }
            BasicTextField(state.address, surface::editAddress, singleLine = true, enabled = !state.closed,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = SolidColor(Color(0xFF76B9FF)),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(Color(0xFF303238))
                    .padding(horizontal = 10.dp, vertical = 11.dp).onFocusChanged { surface.editing(it.isFocused) }
                    .semantics { contentDescription = "Browser address" }.testTag("LocalBrowserAddress"),
                decorationBox = { field -> Box { if (state.address.isEmpty()) Text("Search or enter address", color = Color(0xFFAAAAAE), fontSize = 13.sp); field() } })
            LocalBrowserButton(if (state.loading) "Stop loading" else "Reload page", if (state.loading) "⊗" else "↻", !state.closed) {
                surface.request(if (state.loading) LocalBrowserCommand.STOP else LocalBrowserCommand.RELOAD)
            }
            LocalBrowserButton("Close Browser", "×", true) { focus.clearFocus(); keyboard?.hide(); onClose() }
        }
        if (state.loading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(2.dp)
            .semantics { contentDescription = "Browser loading" }) else Spacer(Modifier.height(2.dp))
        state.error?.let { message -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Color(0xFFFFB2B2), fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { surface.request(LocalBrowserCommand.RELOAD) }, enabled = !state.closed) { Text("Retry") }
        } }
        key(surface.id) {
            AndroidView(factory = { context -> LocalBrowserWebHost(context, surface, chooseFiles = { view, params, callback ->
                if (!files.begin(view, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE, callback)) false
                else {
                    try { launcher.launch(params.createIntent()) }
                    catch (_: Exception) { files.finish(null) }
                    true
                }
            }, cancelFiles = files::cancel, beforeNavigation = beforeNavigation) }, update = { host ->
                // Observe requests even when their navigation snapshot has not changed yet.
                state.workRevision
                host.applyPendingWork(); host.foreground(foreground)
            }, onRelease = { it.release() }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("LocalBrowserPage"))
        }
    }
}

@Composable
private fun LocalBrowserButton(label: String, glyph: String, enabled: Boolean, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(44.dp).semantics { contentDescription = label }) {
        Text(glyph, color = if (enabled) Color(0xFF76B9FF) else Color(0xFF727680), fontSize = 24.sp)
    }
}
