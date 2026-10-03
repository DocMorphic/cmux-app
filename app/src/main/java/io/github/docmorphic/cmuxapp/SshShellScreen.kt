package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun SshShellScreen(shell: SshTerminal, reconnecting: Boolean = false, reconnectError: String? = null,
    onReconnect: (() -> Unit)? = null, onFiles: (() -> Unit)? = null, onBrowser: (() -> Unit)? = null,
    panePicker: (@Composable (() -> Unit) -> Unit)? = null, onBack: () -> Unit) {
    val state by shell.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(shell, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            shell.visible(true)
            try { awaitCancellation() } finally { shell.visible(false) }
        }
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val preferences = remember(context) { context.getSharedPreferences("native_display", Context.MODE_PRIVATE) }
    val toolbar = rememberTerminalToolbar(preferences)
    val zoom = remember(shell.id) { TerminalZoomState() }
    val cells = remember(density, zoom.size) { TerminalCellMetrics.fromFontSize(with(density) { zoom.size.sp.toPx() }, with(density) { 2.dp.toPx() }) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var direct by remember { mutableStateOf(false) }
    var rawKeyboard by remember { mutableStateOf<TerminalKeyboardView?>(null) }
    var modifiers by remember { mutableStateOf(TerminalInputModifiers()) }
    var scroll by remember(shell.id) { mutableDoubleStateOf(0.0) }
    var draft by remember(shell.id) { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<TerminalTextSnapshot?>(null) }
    var shortcuts by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val hardware = remember(shell.id) { TerminalHardwareInput() }
    val focus = remember { FocusRequester() }
    val display = shell.display
    val canInput = state.phase == SshShellPhase.RUNNING && !reconnecting
    val motion = rememberTerminalScrollMotion(shell.id, shell)
    fun write(text: String, paste: Boolean = false): Boolean {
        if (!canInput) return false
        motion.stop(); scroll = 0.0
        return shell.send(text, paste)
    }
    fun key(event: android.view.KeyEvent): Boolean {
        if (!canInput) return false
        val value = hardware.sequence(event, display.applicationCursorKeys,
            control = modifiers.armed == TerminalInputModifiers.Key.CONTROL,
            alt = modifiers.armed == TerminalInputModifiers.Key.ALT,
            shift = modifiers.armed == TerminalInputModifiers.Key.SHIFT,
            command = modifiers.armed == TerminalInputModifiers.Key.COMMAND) ?: return false
        modifiers = modifiers.consume()
        return write(value)
    }
    fun showKeyboard() { if (canInput) { motion.stop(); direct = true; rawKeyboard?.showKeyboard() } }
    fun showText() { motion.stop(); keyboard?.hide(); snapshot = TerminalTextSnapshot.capture(display) }
    BackHandler { rawKeyboard?.finishComposition(); keyboard?.hide(); onBack() }
    DisposableEffect(shell) { onDispose { rawKeyboard?.dispose(); motion.stop() } }
    val viewport = TerminalViewport.fit(size.width, size.height, cells)
    LaunchedEffect(shell, viewport, cells) { viewport?.let { shell.resize(it.columns, it.rows, cells) } }
    LaunchedEffect(state.revision) { scroll = scroll.coerceIn(0.0, display.historyLineCount.toDouble()) }
    snapshot?.let { TerminalTextSheet(it) { snapshot = null } }
    if (shortcuts) TerminalToolbarSettings(toolbar) { shortcuts = false }
    Column(Modifier.fillMaxSize().testTag("ssh.shell")) {
        Row(Modifier.fillMaxWidth().testTag("ssh.shell.identity.${shell.id}"), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { rawKeyboard?.finishComposition(); keyboard?.hide(); onBack() }) { Text("Back") }
            Box(Modifier.weight(1f)) {
                if (panePicker != null) panePicker(::showText)
                else {
                    val target = SshWorkspaceTarget.Shell(shell.id)
                    SshPanePicker(shell.title, SshPickerLayout(listOf(SshPickerSection(0, "Terminals", listOf(SshPickerRow(target, shell.title))))),
                        target, !reconnecting, onSelect = {}, onText = ::showText,
                        onBrowser = onBrowser?.let { { rawKeyboard?.finishComposition(); keyboard?.hide(); it() } })
                }
            }
            TextButton(onClick = ::showText, modifier = Modifier.testTag("ssh.shell.text")) { Text("Text") }
            if (onFiles != null) TextButton(onClick = { rawKeyboard?.finishComposition(); keyboard?.hide(); onFiles() }, modifier = Modifier.testTag("ssh.shell.files")) { Text("Files") }
            TextButton(onClick = {
                if (direct) { rawKeyboard?.finishComposition(); direct = false; keyboard?.hide() } else showKeyboard()
            }, enabled = canInput) { Text(if (direct) "Compose" else "Keyboard") }
        }
        if (reconnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        reconnectError?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        if (state.phase == SshShellPhase.ENDED && onReconnect != null) {
            TextButton(onClick = onReconnect, enabled = !reconnecting, modifier = Modifier.testTag("ssh.shell.reconnect")) { Text("Reconnect") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RenderGridView(display, cells, state.revision, Modifier.fillMaxSize().testTag("ssh.shell.terminal")
                .onSizeChanged { size = it }.focusRequester(focus).onPreviewKeyEvent { key(it.nativeKeyEvent) }.focusable()
                .semantics {
                    stateDescription = "SSH terminal ${shell.title}, ${state.phase.name.lowercase()}"
                    onClick("Open keyboard") { showKeyboard(); true }
                    customActions = listOf(CustomAccessibilityAction("View as Text") { showText(); true })
                }
                .terminalPinchZoom(zoom)
                .pointerInput(shell.id, canInput) { detectTapGestures(onTap = { showKeyboard() }, onLongPress = { showText() }) }
                .terminalScrollGestures(motion, TerminalGeometry.fit(size.width.toFloat(), size.height.toFloat(), display.columns, display.rows, cells),
                    0, display.activeScreen, linePath = true, enabled = true,
                    onScroll = { rows, _ -> scroll = (scroll + rows).coerceIn(0.0, display.historyLineCount.toDouble()); true }),
                scrollPosition = scroll)
            if (state.phase == SshShellPhase.OPENING) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (scroll > 0) TextButton(onClick = { motion.stop(); scroll = 0.0 }, modifier = Modifier.align(Alignment.BottomEnd)) { Text("Latest") }
            TerminalZoomOverlay(zoom, preferences, foreground = Color(0xFFE0E5EB), background = Color(0xFF111316), modifier = Modifier.align(Alignment.Center))
        }
        TerminalToolbarView(toolbar.layout, modifiers, canInput, filesEnabled = onFiles != null,
            onModifier = { modifiers = modifiers.tap(it, android.os.SystemClock.uptimeMillis()) },
            onButton = { button ->
                rawKeyboard?.finishComposition()
                when (button) {
                    TerminalToolbarButton.PASTE -> {
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                        val text = clip?.let { if (it.itemCount > 0) it.getItemAt(0).text?.toString() else null }
                        if (text != null) write(text, paste = true) else message = "No text in the clipboard."
                    }
                    TerminalToolbarButton.ZOOM_IN, TerminalToolbarButton.ZOOM_OUT -> zoom.step(if (button == TerminalToolbarButton.ZOOM_IN) 1 else -1)
                    TerminalToolbarButton.FILES -> { keyboard?.hide(); onFiles?.invoke() }
                    else -> button.key?.let { write(modifiers.special(it, display.applicationCursorKeys)); modifiers = modifiers.consume() }
                }
            }, onCustom = { rawKeyboard?.finishComposition(); modifiers = TerminalInputModifiers(); write(it.output) },
            onCustomize = { keyboard?.hide(); shortcuts = true })
        if (state.phase == SshShellPhase.ENDED) Text(state.error ?: "Shell ended", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (direct) AndroidView(factory = { viewContext -> TerminalKeyboardView(viewContext).also { view -> rawKeyboard = view; view.post { view.showKeyboard() } } },
            update = { view ->
                view.isEnabled = canInput
                view.onText = { text -> write(modifiers.text(text)); modifiers = modifiers.consume() }
                view.onKey = ::key
                view.onPaste = { write(it, paste = true) }
                view.onContentError = { message = it }
                view.onReturn = { write("\r") }
            }, modifier = Modifier.fillMaxWidth().height(36.dp).testTag("ssh.shell.keyboard"),
            onRelease = { it.dispose(); if (rawKeyboard === it) rawKeyboard = null })
        else Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f).testTag("ssh.shell.composer"),
                placeholder = { Text("Message or command") }, maxLines = 5, enabled = canInput)
            TextButton(onClick = { if (write(TerminalKeyEncoding.paste(draft, display.bracketedPaste) + "\r")) draft = "" },
                enabled = canInput && draft.isNotEmpty(), modifier = Modifier.testTag("ssh.shell.send")) { Text("Send") }
        }
    }
}
