package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativeSurfaceShortcut(surface: NativeSurface, color: Color, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).semantics { contentDescription = "Open ${surface.displayTitle}" }
        .padding(start = 80.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (surface.simulator != null) SimulatorGlyph("phone", color, Modifier.size(14.dp))
        else Text("▤", color = color, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(surface.displayTitle, color = color, fontSize = 12.sp)
    }
}

@Composable
internal fun NativeSurfaceView(workspace: NativeWorkspace, surface: NativeSurface, client: MobileRpcClient?,
    capabilities: Set<String>, ready: Boolean, onBack: () -> Unit, onSurface: (NativeSurface) -> Unit,
    onTerminal: (NativeTerminal) -> Unit, onBrowser: (NativeBrowser) -> Unit,
    mutateWorkspace: (suspend (MobileRpcClient, String, org.json.JSONObject) -> org.json.JSONObject)? = null,
    onNewBrowser: (() -> Unit)? = null, onNewWorkspace: (() -> Unit)? = null, onNewTerminal: (() -> Unit)? = null,
    panel: NativePanelViewState? = null) {
    BackHandler(onBack = onBack)
    val currentClient by rememberUpdatedState(client)
    val currentReady by rememberUpdatedState(ready)
    val currentCapabilities by rememberUpdatedState(capabilities)
    val currentMutation by rememberUpdatedState(mutateWorkspace)
    val todo = remember(surface.todoJson) { TodoSnapshot.decode(surface.todoJson) }
    var supportedPanel by remember(workspace.id, surface.id) { mutableStateOf(false) }
    var supportedSimulator by remember(workspace.id, surface.id) { mutableStateOf(false) }
    if (ready) SideEffect { supportedPanel = "panel.artifact.v1" in capabilities }
    val simulatorReady = ready && client != null && ("simulator.stream.v1" in capabilities ||
        (SimStreamWire.CAPABILITY in capabilities && client.supportsSimulatorLanes))
    if (ready) SideEffect { supportedSimulator = simulatorReady }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeWorkspaceBackControl { TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) { Text("‹  Workspaces") } }
            NativePanePicker(surface.displayTitle, workspace, NativeWorkspacePane(surface = surface), Modifier.weight(1f),
                onTerminal, onSurface, onBrowser, onNewWorkspace, onNewTerminal, onNewBrowser,
                browserState = NativeBrowserPickerState.from(ready, capabilities))
        }
        if (!ready) Text("Reconnecting to your Mac…", Modifier.padding(horizontal = 16.dp))
        if (surface.simulator != null && (simulatorReady || supportedSimulator)) key(workspace.id, surface.id) {
            NativeSimulatorView(surface.simulator, client, capabilities, ready)
        } else if (surface.kind == "todo" && todo != null) key(workspace.id, surface.id) {
            NativeTodoView(todo, ready && "todo.v1" in capabilities) { mutation ->
                val active = checkNotNull(currentClient) { "Mac disconnected." }
                check(currentReady && "todo.v1" in currentCapabilities) { "Your Mac isn't ready to update this checklist." }
                val (method, params) = mutation.request(workspace.id)
                val listing = checkNotNull(currentMutation) { "Checklist updates are unavailable." }(active, method, params)
                check(currentClient === active && currentReady) { "Connection changed while refreshing the checklist." }
                val updated = parseWorkspaces(listing).singleOrNull { it.id == workspace.id }
                    ?.macSurfaces?.singleOrNull { it.id == surface.id && it.kind == "todo" }
                checkNotNull(TodoSnapshot.decode(updated?.todoJson)) { "The checklist is no longer available." }
            }
        } else if ((supportedPanel || panel != null) && surface.isPanelFile) {
            val path = requireNotNull(surface.filePath)
            key(workspace.id, surface.id, surface.kind, surface.title, path) {
                val owner = panel?.preview
                if (owner != null) ArtifactPreviewPage(owner.access.rpc, owner.target.authorization, path,
                    forceMarkdown = surface.kind == "markdown", retained = owner.preview,
                    connection = panel.connection, retry = panel.retry)
                else FilesMessage(panel?.title ?: "Connecting to panel…", panel?.detail ?: "Waiting for this Mac's file connection.")
            }
        } else key(workspace.id, surface.id, client) {
            NativeSurfaceCard(workspace, surface, ready && "surface.focus.v1" in capabilities) {
                val active = checkNotNull(currentClient)
                check(currentReady && "surface.focus.v1" in currentCapabilities)
                checkNotNull(currentMutation) { "Workspace updates are unavailable." }(active, "mobile.surface.focus",
                    org.json.JSONObject().put("workspace_id", workspace.id).put("surface_id", surface.id))
                check(currentClient === active && currentReady) { "Connection changed." }
            }
        }
    }
}

@Composable
private fun NativeSurfaceCard(workspace: NativeWorkspace, surface: NativeSurface, enabled: Boolean, focus: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberNativeHaptics()
    var pending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (surface.kind == "browser") {
            val blue = androidx.compose.ui.graphics.Color(0xFF76B9FF)
            Box(Modifier.size(88.dp).background(blue.copy(alpha = .14f), CircleShape)
                .border(1.dp, blue.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_workspace_globe), null, Modifier.size(36.dp), tint = blue)
            }
            Spacer(Modifier.height(20.dp))
        }
        Text(surface.displayTitle, style = MaterialTheme.typography.titleLarge)
        Text("${surface.label} · In “${workspace.title}”", Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center)
        Text(surface.explainer, textAlign = TextAlign.Center)
        Button(onClick = {
            pending = true; failed = false
            scope.launch {
                try { focus() }
                catch (error: Exception) { if (error is CancellationException) throw error; failed = true; haptics.perform(NativeHaptic.ERROR) }
                finally { pending = false }
            }
        }, enabled = enabled && !pending, modifier = Modifier.padding(top = 24.dp)) {
            Text(if (pending) "Opening…" else "Open on Mac")
        }
        if (failed) Text("Couldn't reach your Mac. Try again.", color = MaterialTheme.colorScheme.error)
    }
}
