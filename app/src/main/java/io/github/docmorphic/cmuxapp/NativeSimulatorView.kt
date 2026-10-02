package io.github.docmorphic.cmuxapp

import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
internal fun NativeSimulatorView(descriptor: NativeSimulator, client: MobileRpcClient?,
    capabilities: Set<String>, ready: Boolean) {
    var videoMode by remember { mutableStateOf(SimStreamWire.CAPABILITY in capabilities && client?.supportsSimulatorLanes == true) }
    val useVideo = if (ready) SimStreamWire.CAPABILITY in capabilities && client?.supportsSimulatorLanes == true else videoMode
    if (ready) SideEffect { videoMode = useVideo }
    if (!useVideo) {
        val source = remember(client, descriptor.panelId) { client?.let { MobileLegacySimulatorSource(it, descriptor.panelId) } }
        LegacySimulatorPane(descriptor, source, capabilities, ready)
        return
    }
    val currentClient by rememberUpdatedState(client)
    val currentReady by rememberUpdatedState(ready)
    val currentCapabilities by rememberUpdatedState(capabilities)
    val source = remember(client, descriptor.panelId) {
        client?.takeIf { it.supportsSimulatorLanes }?.let { MobileSimLaneSource(it, descriptor.panelId) }
    }
    val actions = remember(client, descriptor.workspaceId, descriptor.panelId, capabilities) {
        SimulatorActions("simulator.devices.v1" in capabilities, "simulator.recover.v1" in capabilities,
            descriptor.workspaceId, descriptor.panelId) { method, parameters ->
            val active = checkNotNull(client) { "Mac disconnected" }
            val required = if (method == "mobile.simulator.recover") "simulator.recover.v1" else "simulator.devices.v1"
            check(currentReady && currentClient === active && required in currentCapabilities)
            active.request(method, parameters).also { check(currentReady && currentClient === active) }
        }
    }
    SimulatorPane(source.takeIf { ready && SimStreamWire.CAPABILITY in capabilities }, actions, ready)
}

private class SimulatorSurfaceBinding(scope: CoroutineScope, surface: Surface) {
    val presenter = SimVideoPresenter(surface)
    val controller = SimViewerController(scope, presenter).apply { activate() }
    private var closed = false
    fun close() {
        if (closed) return
        closed = true; controller.close()
        cleanup.launch { try { controller.awaitClosed() } finally { presenter.awaitClosed() } }
    }
    companion object { private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}

@Composable
internal fun SimulatorPane(source: SimLaneSource?, actions: SimulatorActions, ready: Boolean,
    preferencesName: String = "cmux_preferences") {
    val context = LocalContext.current
    val preferences = remember(context, preferencesName) { context.getSharedPreferences(preferencesName, 0) }
    var quality by remember(preferences) { mutableStateOf(runCatching {
        SimQuality.valueOf(preferences.getString("simulator_quality_v1", SimQuality.HIGH.name)!!)
    }.getOrDefault(SimQuality.HIGH)) }
    val scope = rememberCoroutineScope()
    var binding by remember { mutableStateOf<SimulatorSurfaceBinding?>(null) }
    var state by remember(binding) { mutableStateOf(binding?.controller?.state?.value ?: SimViewerState()) }
    LaunchedEffect(binding) { binding?.controller?.state?.collect { state = it } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(binding) { val captured = binding; onDispose { captured?.close() } }
    SideEffect {
        binding?.controller?.let { owner ->
            owner.setQuality(quality)
            if (!foreground) owner.background()
            owner.bindSource(source.takeIf { ready })
            if (foreground) owner.foreground()
        }
    }
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf(emptyList<SimulatorDevice>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    val currentActions by rememberUpdatedState(actions)
    val currentReady by rememberUpdatedState(ready)
    fun refreshDevices() {
        fetchJob?.cancel()
        if (!ready || !actions.supportsDevices) { devices = emptyList(); return }
        val captured = actions
        fetchJob = scope.launch {
            try {
                val result = captured.devices(); ensureActive()
                if (currentActions === captured && currentReady) devices = result
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (currentActions === captured) devices = emptyList() }
        }
    }
    LaunchedEffect(actions, ready) {
        actionJob?.cancelAndJoin(); busy = false; message = null
        refreshDevices()
    }
    DisposableEffect(Unit) { onDispose { fetchJob?.cancel(); actionJob?.cancel() } }
    fun refreshStream() {
        if (busy || !ready) return
        val captured = actions; val owner = binding?.controller ?: return
        busy = true; message = null
        actionJob = scope.launch {
            try {
                if (captured.supportsRecover) captured.recover()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (currentActions === captured) message = "Couldn’t request recovery on the Mac. Try again." }
            finally {
                if (currentCoroutineContext().isActive && currentActions === captured && currentReady) {
                    owner.refresh(); busy = false
                }
            }
        }
    }
    fun selectDevice(device: SimulatorDevice) {
        if (busy || !ready || device.selected) return
        val captured = actions
        fetchJob?.cancel(); busy = true; message = null
        actionJob = scope.launch {
            try {
                captured.select(device)
                val refreshed = captured.devices(); ensureActive()
                if (currentActions === captured && currentReady) devices = refreshed
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (currentActions === captured) message = "Couldn’t confirm the Simulator switch. Refresh the device list before trying again." }
            finally { if (currentActions === captured) busy = false }
        }
    }
    val canInput = ready && foreground && source != null && !state.hostStatus.needsRecovery && state.phase in
        setOf(SimViewerLifecycle.Phase.STARTING, SimViewerLifecycle.Phase.STREAMING)
    fun send(value: SimInput): Boolean = canInput && binding?.controller?.input(value) == true
    fun submitText() { if (text.isNotEmpty() && send(SimInput.Text(text))) text = "" }
    val stalled = !state.hostStatus.needsRecovery && state.phase in setOf(SimViewerLifecycle.Phase.IDLE,
        SimViewerLifecycle.Phase.WAITING, SimViewerLifecycle.Phase.STARTING, SimViewerLifecycle.Phase.RETRYING)
    var revealRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(stalled) { revealRefresh = false; if (stalled) { delay(5000); revealRefresh = true } }

    Column(Modifier.fillMaxSize().background(Color.Black).imePadding().testTag("SimulatorPane")) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { viewContext -> SimulatorVideoView(viewContext,
                surfaceCreated = { surface -> binding?.close(); binding = SimulatorSurfaceBinding(scope, surface) },
                surfaceDestroyed = { binding?.close(); binding = null }) },
                update = { it.update(state, canInput, ::send) },
                onRelease = { it.release() }, modifier = Modifier.fillMaxSize().testTag("SimulatorVideo"))
            if (state.hostStatus.needsRecovery) SimulatorOverlay("Simulator Needs Recovery",
                "The Simulator session on the Mac stopped and is showing its last frame.",
                if (actions.supportsRecover) "Recover" else null, ready && !busy, ::refreshStream, "warning")
            else when (state.phase) {
                SimViewerLifecycle.Phase.IDLE, SimViewerLifecycle.Phase.WAITING,
                SimViewerLifecycle.Phase.STARTING, SimViewerLifecycle.Phase.RETRYING -> SimulatorOverlay(
                    if (state.reconnecting) "Reconnecting to Simulator" else "Waiting for Simulator",
                    if (state.reconnecting) "The video feed stalled. Restoring the stream." else "The first frame will appear when the Mac is ready.",
                    if (revealRefresh) "Refresh" else null, ready && !busy, ::refreshStream, if (state.reconnecting) "refresh" else "phone")
                SimViewerLifecycle.Phase.UNAVAILABLE -> SimulatorOverlay("Simulator Unavailable", simulatorUnavailableDetail(state.reason),
                    if (state.reason == "simulator_disabled") null else "Refresh", ready && !busy, ::refreshStream, "unavailable")
                else -> Unit
            }
        }
        message?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(12.dp)) }
        Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp).fillMaxWidth()
            .clip(RoundedCornerShape(32.dp)).background(Color(0xFF222428)).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(text, { text = it }, enabled = canInput, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 13.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitText() }),
                modifier = Modifier.weight(1f).clip(CircleShape).background(Color(0xFF34363A)).padding(horizontal = 12.dp, vertical = 9.dp)
                    .semantics { contentDescription = "Simulator text" }.testTag("SimulatorText"),
                decorationBox = { field -> Box { if (text.isEmpty()) Text("Text", color = Color(0xFFAAAAAE), fontSize = 13.sp); field() } })
            SimulatorChromeButton("Send Text", "send", canInput && text.isNotEmpty(), ::submitText)
            SimulatorChromeButton("Home", "home", canInput) { send(SimInput.Button(SimButton.HOME)) }
            SimulatorChromeButton("Lock", "lock", canInput) { send(SimInput.Button(SimButton.LOCK)) }
            Box {
                SimulatorChromeButton("More Buttons", "more", true) { menu = "main"; refreshDevices() }
                DropdownMenu(menu != null, onDismissRequest = { menu = null }) {
                    when (menu) {
                        "quality" -> {
                            DropdownMenuItem(text = { Text("‹ Stream Quality") }, onClick = { menu = "main" })
                            for (item in SimQuality.entries) DropdownMenuItem(text = { Text((if (item == quality) "✓ " else "") + when (item) {
                                SimQuality.HIGH -> "High"; SimQuality.BALANCED -> "Balanced"; SimQuality.DATA_SAVER -> "Data Saver"
                            }) }, onClick = {
                                quality = item; preferences.edit().putString("simulator_quality_v1", item.name).apply(); menu = null
                            })
                        }
                        "devices" -> {
                            DropdownMenuItem(text = { Text("‹ Switch Simulator") }, onClick = { menu = "main" })
                            devices.forEach { device -> DropdownMenuItem(text = {
                                Text((if (device.selected) "✓ " else "") + "${device.name} · ${device.runtimeName}")
                            }, enabled = ready && !busy && !device.selected, onClick = { menu = null; selectDevice(device) }) }
                        }
                        else -> {
                            listOf("App Switcher" to SimButton.APP_SWITCHER, "Volume Up" to SimButton.VOLUME_UP,
                                "Volume Down" to SimButton.VOLUME_DOWN, "Siri" to SimButton.SIRI).forEach { (label, button) ->
                                DropdownMenuItem(text = { Text(label) }, enabled = canInput,
                                    onClick = { menu = null; send(SimInput.Button(button)) })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Refresh Simulator") }, enabled = ready && !busy,
                                onClick = { menu = null; refreshStream() })
                            DropdownMenuItem(text = { Text("Stream Quality") }, onClick = { menu = "quality" })
                            if (actions.supportsDevices && devices.isNotEmpty()) DropdownMenuItem(text = { Text("Switch Simulator") },
                                onClick = { menu = "devices"; refreshDevices() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SimulatorOverlay(title: String, detail: String, action: String?, enabled: Boolean, onAction: () -> Unit, glyph: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)).pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SimulatorGlyph(glyph, Color.White, Modifier.size(36.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(detail, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (action != null) OutlinedButton(onClick = onAction, enabled = enabled) { Text(action, color = Color.White) }
        }
    }
}

@Composable
internal fun SimulatorChromeButton(label: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        SimulatorGlyph(glyph, if (enabled) Color.White else Color(0xFF707276), Modifier.size(22.dp))
    }
}

@Composable
internal fun SimulatorGlyph(name: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = size.width / 24f
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x*k, y*k), Offset(xx*k, yy*k), 1.6f*k, StrokeCap.Round)
        when (name) {
            "warning" -> {
                val path = Path().apply { moveTo(12*k, 2*k); lineTo(23*k, 21*k); lineTo(k, 21*k); close() }
                drawPath(path, color, style = Stroke(1.6f*k)); line(12f, 8f, 12f, 13f)
                drawCircle(color, k, Offset(12*k, 17*k))
            }
            "refresh" -> {
                drawArc(color, -45f, 270f, false, Offset(3*k, 3*k), Size(18*k, 18*k), style = Stroke(1.6f*k))
                line(18f, 2f, 19f, 7f); line(19f, 7f, 14f, 6f)
            }
            "home" -> {
                line(3f, 11f, 12f, 3f); line(12f, 3f, 21f, 11f)
                val path = Path().apply { moveTo(5*k, 10*k); lineTo(5*k, 21*k); lineTo(10*k, 21*k); lineTo(10*k, 15*k)
                    lineTo(14*k, 15*k); lineTo(14*k, 21*k); lineTo(19*k, 21*k); lineTo(19*k, 10*k) }
                drawPath(path, color, style = Stroke(1.6f*k))
            }
            "lock" -> {
                drawRoundRect(color, Offset(5*k, 10*k), Size(14*k, 11*k), androidx.compose.ui.geometry.CornerRadius(2*k), style = Stroke(1.6f*k))
                drawArc(color, 180f, 180f, false, Offset(8*k, 2*k), Size(8*k, 14*k), style = Stroke(1.6f*k))
            }
            "send" -> {
                val path = Path().apply { moveTo(3*k, 10*k); lineTo(21*k, 3*k); lineTo(14*k, 21*k); lineTo(10*k, 14*k); close() }
                drawPath(path, color, style = Stroke(1.6f*k)); line(10f, 14f, 21f, 3f)
            }
            "more" -> { drawCircle(color, 10*k, style = Stroke(1.6f*k)); for (x in listOf(7f, 12f, 17f)) drawCircle(color, k, Offset(x*k, 12*k)) }
            else -> {
                drawRoundRect(color, Offset(6*k, k), Size(12*k, 22*k), androidx.compose.ui.geometry.CornerRadius(2*k), style = Stroke(1.6f*k))
                line(10f, 20f, 14f, 20f)
                if (name == "unavailable") line(3f, 3f, 21f, 21f)
            }
        }
    }
}
