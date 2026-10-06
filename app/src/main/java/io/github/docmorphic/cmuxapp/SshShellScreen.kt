package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
    ObserveTerminalBells(shell.bells, state.phase == SshShellPhase.RUNNING && !reconnecting)
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
    RetireTerminalInputOnBackground(rawKeyboard)
    var modifiers by remember { mutableStateOf(TerminalInputModifiers()) }
    var scroll by remember(shell.id) { mutableDoubleStateOf(0.0) }
    val fallbackDrafts = remember(shell) { SshComposerPool() }
    val composer = remember(shell) { shell.composer ?: fallbackDrafts.open(shell.id) }
    val drafts by composer.state.collectAsState()
    val draft = drafts[composer.target] ?: TerminalDrafts.Draft()
    val scope = rememberCoroutineScope()
    val attachmentFiles = remember(context) { AttachmentFiles(context) }
    val available by rememberUpdatedState(state.phase == SshShellPhase.RUNNING && !reconnecting)
    val input = remember(shell, composer) {
        SshTerminalInput(shell, composer, scope, { available }) { uri -> attachmentFiles.prepare(uri, image = true) }
    }
    val inputStatus by input.queue.status.collectAsState()
    val pasteMessage by input.message.collectAsState()
    val preparing = inputStatus.pendingBytes > 0
    var pickerTarget by remember(shell) { mutableStateOf<SshTerminal?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        val target = pickerTarget; pickerTarget = null
        if (target === shell && uris.isNotEmpty()) input.paste(TerminalPasteContent(
            uris.map { TerminalPasteContent.Item.Attachment(it, true) }), direct = false)
    }
    var snapshot by remember { mutableStateOf<TerminalTextSnapshot?>(null) }
    var shortcuts by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val hardware = remember(shell.id) { TerminalHardwareInput() }
    val focus = remember { FocusRequester() }
    val display = shell.display
    val canInput = available && inputStatus.error == null && !inputStatus.closed
    val orderedTerminal = remember(shell, input) { object : SshTerminal by shell {
        override fun send(text: String, paste: Boolean) = input.send(text, paste)
        override fun sendBytes(bytes: ByteArray) = input.sendBytes(bytes)
    } }
    val interactions = remember(orderedTerminal) { SshTerminalInteraction(orderedTerminal) }
    // Focus is a current lifecycle signal, not deferred typing. Focus-out must
    // reach the provider even when a disposed view cancels a pending paste.
    ObserveSshTerminalFocus(remember(shell) { SshTerminalInteraction(shell) }, available)
    val motion = rememberTerminalScrollMotion(shell.id, shell)
    fun write(text: String, paste: Boolean = false): Boolean {
        if (!canInput) return false
        motion.stop(); scroll = 0.0
        return input.send(text, paste)
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
    DisposableEffect(shell, input) { onDispose {
        rawKeyboard?.dispose(); motion.stop(); input.close(); fallbackDrafts.close()
    } }
    fun showFiles() {
        rawKeyboard?.finishComposition()
        modifiers = TerminalInputModifiers()
        if (input.queue.status.value.let { it.pendingBytes == 0 && it.error == null && !it.closed }) { direct = false; keyboard?.hide(); onFiles?.invoke() }
    }
    val viewport = TerminalViewport.fit(size.width, size.height, cells)
    val geometry = TerminalGeometry.fit(size.width.toFloat(), size.height.toFloat(), display.columns, display.rows, cells)
    fun scrollTerminal(rows: Double, cell: TerminalGeometry.Cell): Boolean {
        if (reconnecting) return false
        val remote = interactions.scroll(rows, cell)
        if (remote == true) scroll = 0.0
        return remote ?: run {
            scroll = (scroll + rows).coerceIn(0.0, display.historyLineCount.toDouble()); true
        }
    }
    LaunchedEffect(shell, viewport, cells) { viewport?.let { shell.resize(it.columns, it.rows, cells) } }
    LaunchedEffect(state.revision) { scroll = scroll.coerceIn(0.0, display.historyLineCount.toDouble()) }
    snapshot?.let { TerminalTextSheet(it) { snapshot = null } }
    if (shortcuts) TerminalToolbarSettings(toolbar) { shortcuts = false }
    Column(Modifier.fillMaxSize().testTag("ssh.shell")) {
        Row(Modifier.fillMaxWidth().testTag("ssh.shell.identity.${shell.id}"), verticalAlignment = Alignment.CenterVertically) {
            NativeWorkspaceBackControl { TextButton(onClick = { rawKeyboard?.finishComposition(); keyboard?.hide(); onBack() }) { Text("Back") } }
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalDebugTerminalText provides { RenderGrid.plainText(display.visibleLines(scroll.toInt())) }) {
                if (panePicker != null) panePicker(::showText)
                else {
                    val target = SshWorkspaceTarget.Shell(shell.id)
                    SshPanePicker(shell.title, SshPickerLayout(listOf(SshPickerSection(0, "Terminals", listOf(SshPickerRow(target, shell.title))))),
                        target, !reconnecting, onSelect = {}, onText = ::showText,
                        onBrowser = onBrowser?.let { { rawKeyboard?.finishComposition(); keyboard?.hide(); it() } })
                }
                }
            }
            TextButton(onClick = ::showText, modifier = Modifier.testTag("ssh.shell.text")) { Text("Text") }
            if (onFiles != null) TextButton(onClick = ::showFiles, enabled = !preparing && inputStatus.error == null && !inputStatus.closed, modifier = Modifier.testTag("ssh.shell.files")) { Text("Files") }
            TextButton(onClick = {
                modifiers = TerminalInputModifiers()
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
                    scrollBy { _, y ->
                        val grid = geometry
                        if (grid == null || !y.isFinite()) false else {
                            motion.stop()
                            scrollTerminal(-y / grid.cellHeight.toDouble(), TerminalGeometry.Cell(display.columns / 2, display.rows / 2))
                        }
                    }
                }
                .terminalPinchZoom(zoom)
                .pointerInput(shell.id, canInput, geometry, scroll == 0.0) { detectTapGestures(onTap = { point ->
                    if (canInput && scroll == 0.0) geometry?.takeIf { it.contains(point.x, point.y) }
                        ?.cell(point.x, point.y)?.let(interactions::click)
                    showKeyboard()
                }, onLongPress = { showText() }) }
                .terminalScrollGestures(motion, geometry,
                    0, display.activeScreen, linePath = true, enabled = true,
                    onScroll = ::scrollTerminal),
                scrollPosition = scroll)
            if (state.phase == SshShellPhase.OPENING) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (scroll > 0) TextButton(onClick = { motion.stop(); scroll = 0.0 }, modifier = Modifier.align(Alignment.BottomEnd)) { Text("Latest") }
            TerminalZoomOverlay(zoom, preferences, foreground = Color(0xFFE0E5EB), background = Color(0xFF111316), modifier = Modifier.align(Alignment.Center))
        }
        inputStatus.error?.let { error ->
            Text(error, Modifier.padding(horizontal = 12.dp).testTag("ssh.shell.input-error"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { input.resume() }, enabled = available, modifier = Modifier.testTag("ssh.shell.resume-input")) { Text("Resume typing") }
        }
        TerminalToolbarView(toolbar.layout, modifiers, canInput, inputOwner = shell, filesEnabled = onFiles != null && !preparing,
            onModifier = { modifiers = modifiers.tap(it, android.os.SystemClock.uptimeMillis()) },
            onButton = { button ->
                rawKeyboard?.finishComposition()
                when (button) {
                    TerminalToolbarButton.PASTE -> {
                        modifiers = TerminalInputModifiers()
                        try {
                            val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                            if (clip == null) message = "The clipboard is empty."
                            else input.paste(TerminalPasteContent.fromClipboard(context, clip), direct)
                        } catch (_: Exception) { message = "Could not read the clipboard." }
                    }
                    TerminalToolbarButton.ZOOM_IN, TerminalToolbarButton.ZOOM_OUT -> {
                        modifiers = TerminalInputModifiers()
                        zoom.step(if (button == TerminalToolbarButton.ZOOM_IN) 1 else -1)
                    }
                    TerminalToolbarButton.FILES -> showFiles()
                    else -> button.key?.let { write(modifiers.special(it, display.applicationCursorKeys)); modifiers = modifiers.consume() }
                }
            }, onCustom = { rawKeyboard?.finishComposition(); modifiers = TerminalInputModifiers(); write(it.output) },
            onCustomize = { keyboard?.hide(); shortcuts = true })
        if (state.phase == SshShellPhase.ENDED) Text(state.error ?: "Shell ended", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        (message ?: pasteMessage ?: draft.error)?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (direct) AndroidView(factory = { viewContext -> TerminalKeyboardView(viewContext).also { view -> rawKeyboard = view; view.post { view.showKeyboard() } } },
            update = { view ->
                view.isEnabled = canInput
                view.onText = { text -> write(modifiers.text(text)); modifiers = modifiers.consume() }
                view.onKey = ::key
                view.onPaste = { write(it, paste = true) }
                view.onContent = if (input.supportsImages) ({ content -> input.paste(content, direct = true) }) else null
                view.onContentError = { message = it }
                view.onReturn = { write("\r") }
            }, modifier = Modifier.fillMaxWidth().height(36.dp).testTag("ssh.shell.keyboard"),
            onRelease = { it.dispose(); if (rawKeyboard === it) rawKeyboard = null })
        else {
            if (draft.attachments.isNotEmpty() || preparing) SshTerminalAttachmentStrip(composer, draft.attachments,
                canRemove = true, preparing = preparing,
                beforePreview = { rawKeyboard?.finishComposition(); motion.stop(); keyboard?.hide() })
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (input.supportsImages) IconButton(onClick = { pickerTarget = shell; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = canInput && !preparing && draft.operation == null,
                    modifier = Modifier.testTag("ssh.shell.attach").semantics { contentDescription = "Attach image" }) { Text("+", fontSize = 24.sp) }
                RichContentEditor(owner = shell, enabled = canInput && input.supportsImages && draft.operation == null,
                    onContent = { input.paste(it, direct = false) }, onError = { message = it }) { pasteModifier ->
                    OutlinedTextField(draft.text, { composer.edit(it) }, Modifier.weight(1f).then(pasteModifier).testTag("ssh.shell.composer"),
                        placeholder = { Text("Message or command") }, maxLines = 5, enabled = canInput)
                }
                TextButton(onClick = { rawKeyboard?.finishComposition(); motion.stop(); scroll = 0.0; input.submit() },
                    enabled = canInput && !preparing && draft.operation == null && (draft.text.isNotEmpty() || draft.attachments.isNotEmpty()),
                    modifier = Modifier.testTag("ssh.shell.send")) { Text(if (draft.operation == null) "Send" else "Sending…") }
            }
        }
    }
}
