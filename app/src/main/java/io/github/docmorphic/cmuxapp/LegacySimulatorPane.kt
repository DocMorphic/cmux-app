package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
internal fun LegacySimulatorPane(descriptor: NativeSimulator, source: LegacySimulatorSource?, capabilities: Set<String>, ready: Boolean) {
    var owner by remember { mutableStateOf<LegacySimulatorSession<Bitmap>?>(null) }
    var ownerSource by remember { mutableStateOf<LegacySimulatorSource?>(null) }
    var state by remember { mutableStateOf(LegacySimulatorState<Bitmap>(descriptor.copy(ownedByCurrentConnection = false))) }
    var lastImage by remember { mutableStateOf<LegacySimulatorPresentation<Bitmap>?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val active = foreground && ready && source != null && "simulator.stream.v1" in capabilities
    LaunchedEffect(source, active, capabilities, retry) {
        if (!active) { owner?.retire(); owner = null; return@LaunchedEffect }
        val session = LegacySimulatorSession(descriptor, capabilities, ::decodeLegacySimulatorBitmap, Bitmap::recycle)
        owner = session; ownerSource = source; state = session.state.value
        val updates = launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect {
                if (owner === session) {
                    state = it
                    if (it.phase == LegacySimulatorPhase.CLOSED) lastImage = null
                    else it.presentation?.let { image -> lastImage = image }
                }
            }
        }
        try { checkNotNull(source).use { session.run(it) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (owner === session) state = state.copy(phase = LegacySimulatorPhase.FAILED) }
        finally {
            session.retire(); updates.cancel()
            if (owner === session) {
                if (session.state.value.phase == LegacySimulatorPhase.CLOSED) { state = session.state.value; lastImage = null }
                else if (state.phase != LegacySimulatorPhase.FAILED) state = state.copy(phase = LegacySimulatorPhase.STOPPED,
                    descriptor = state.descriptor.copy(ownedByCurrentConnection = false))
                owner = null
            }
        }
    }
    DisposableEffect(Unit) { onDispose { owner?.retire() } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val inputReady = active && !state.controlPending && !state.inputPaused && state.descriptor.ownedByCurrentConnection == true &&
        state.phase in setOf(LegacySimulatorPhase.STARTING, LegacySimulatorPhase.STREAMING) && "simulator.input.v1" in capabilities && owner != null && ownerSource === source
    val keyboardEnabled = inputReady && state.descriptor.supportsKeyboard
    val buttonsEnabled = inputReady && state.descriptor.supportsHardwareButtons
    fun send(input: LegacySimulatorInput) = active && ownerSource === source && owner?.input(input) == true
    fun submitText() {
        if (text.isNotEmpty() && send(LegacySimulatorInput.Text(text))) {
            text = ""; focus.clearFocus(); keyboard?.hide()
        }
    }
    fun refresh() { if (active && owner?.refresh() != true) retry++ }
    Column(Modifier.fillMaxSize().background(Color(0xFF0E1013)).imePadding().testTag("LegacySimulatorPane")) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { LegacySimulatorImageView(it) }, onRelease = { it.release() },
                update = { it.update(lastImage, owner, inputReady && state.descriptor.supportsTouch, ::send) },
                modifier = Modifier.fillMaxSize().testTag("LegacySimulatorImage"))
            when {
                !ready || source == null || !foreground -> SimulatorOverlay("Reconnecting", "Trying to reach your Mac.", null, false, {}, "refresh")
                state.phase == LegacySimulatorPhase.CLOSED -> SimulatorOverlay("Simulator Unavailable", "The Simulator pane was closed on the Mac.", null, false, {}, "unavailable")
                state.phase == LegacySimulatorPhase.LOCKED -> SimulatorOverlay("Simulator In Use", "Another phone is controlling this Simulator.", null, false, {}, "lock")
                state.phase == LegacySimulatorPhase.STALLED -> SimulatorOverlay("Reconnecting to Simulator", "The video feed stalled. Restoring the stream.", null, false, {}, "refresh")
                state.phase in setOf(LegacySimulatorPhase.FAILED, LegacySimulatorPhase.STOPPED) -> SimulatorOverlay(
                    "Simulator Disconnected", "Reconnect to continue streaming.", "Reconnect", active, ::refresh, "refresh")
                lastImage == null -> SimulatorOverlay("Waiting for Simulator", "The first frame will appear when the Mac is ready.", null, false, {}, "phone")
                else -> Text(when {
                    state.controlPending -> "Connecting"
                    state.descriptor.ownedByCurrentConnection == true -> "Android Control"
                    else -> "View Only"
                }, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .clip(CircleShape).background(Color(0xDD25272B)).padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("SimulatorOwnership"))
            }
        }
        if (state.inputPaused && state.phase != LegacySimulatorPhase.LOCKED) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Input delivery wasn’t confirmed. Check the Simulator before continuing.", color = Color.White,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = ::refresh, enabled = active && !state.controlPending) { Text("Resume Input") }
        }
        Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF222428)).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(text, { text = it }, enabled = keyboardEnabled, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitText() }), modifier = Modifier.weight(1f).clip(CircleShape)
                    .background(Color(0xFF34363A)).padding(horizontal = 12.dp, vertical = 9.dp)
                    .semantics { contentDescription = "Simulator text" }.testTag("LegacySimulatorText"),
                decorationBox = { field -> Box { if (text.isEmpty()) Text("Text", color = Color(0xFFAAAAAE), fontSize = 13.sp); field() } })
            SimulatorChromeButton("Send Text", "send", keyboardEnabled && text.isNotEmpty(), ::submitText)
            SimulatorChromeButton("Home", "home", buttonsEnabled) { send(LegacySimulatorInput.Button(LegacySimulatorButton.HOME)) }
            SimulatorChromeButton("Lock", "lock", buttonsEnabled) { send(LegacySimulatorInput.Button(LegacySimulatorButton.LOCK)) }
            Box {
                SimulatorChromeButton("More Buttons", "more", buttonsEnabled) { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    for ((label, button) in listOf("App Switcher" to LegacySimulatorButton.APP_SWITCHER,
                        "Volume Up" to LegacySimulatorButton.VOLUME_UP, "Volume Down" to LegacySimulatorButton.VOLUME_DOWN,
                        "Siri" to LegacySimulatorButton.SIRI)) DropdownMenuItem(text = { Text(label) }, enabled = buttonsEnabled,
                        onClick = { menu = false; send(LegacySimulatorInput.Button(button)) })
                }
            }
        }
    }
}
