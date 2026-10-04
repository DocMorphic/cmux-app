package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

internal fun sshBrowserWorkspace(target: SshWorkspaceTarget, title: String): NativeWorkspace {
    val id = when (target) {
        is SshWorkspaceTarget.CmuxWorkspace -> target.selection.let { ref -> JSONArray(listOf("cmux", ref.session, ref.registry,
            ref.key ?: ref.resource ?: "${ref.generation}:${ref.workspace}")) }
        is SshWorkspaceTarget.Shell -> JSONArray(listOf("shell", target.id))
        is SshWorkspaceTarget.Tmux -> JSONArray(listOf("tmux", target.workspace))
        is SshWorkspaceTarget.Browser -> target.selection.let { ref -> JSONArray(listOf("cmux", ref.session, ref.registry,
            ref.workspaceKey ?: ref.workspaceResource ?: "${ref.generation}:${ref.workspace}")) }
        is SshWorkspaceTarget.Cmux -> target.selection.let { ref -> JSONArray(listOf("cmux", ref.session, ref.registry,
            ref.workspaceKey ?: ref.workspaceResource ?: "${ref.generation}:${ref.workspace}")) }
    }.toString()
    return NativeWorkspace(id, title,
        if (target is SshWorkspaceTarget.Browser || target is SshWorkspaceTarget.CmuxWorkspace) emptyList() else listOf(NativeTerminal(target.encode(), title)),
        null, false, null, null, false,
        if (target is SshWorkspaceTarget.Browser) listOf(NativeBrowser(target.selection.panelId, title)) else emptyList(), null, null, null)
}

internal fun sshCmuxBrowserWorkspace(session: String, tree: SshCmuxTree, workspace: SshCmuxWorkspace): NativeWorkspace {
    val sample = workspace.tabs.firstOrNull { !it.dead && (it.isTerminal || it.isBrowser) }
    val target = sample?.let {
        if (it.isBrowser) SshWorkspaceTarget.Browser(SshCmuxBrowserSelection.capture(session, tree, workspace, it))
        else SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(session, tree, workspace, it))
    }
    val id = target?.let { sshBrowserWorkspace(it, workspace.name).id }
        ?: JSONArray(listOf("cmux", session, tree.registry, workspace.key ?: workspace.resource ?: "${tree.generation}:${workspace.id}")).toString()
    return NativeWorkspace(id, workspace.name,
        workspace.tabs.filter { it.isTerminal && !it.dead }.map {
            NativeTerminal(SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(session, tree, workspace, it)).encode(), it.name ?: it.title)
        }, null, false, null, null, false,
        workspace.tabs.filter { it.isBrowser && !it.dead }.map {
            NativeBrowser(SshCmuxBrowserSelection.capture(session, tree, workspace, it).panelId, it.name ?: it.title)
        }, null, null, null)
}
internal fun sshLocalBrowserKey(network: SshBrowserNetwork, workspace: NativeWorkspace) =
    LocalBrowserKey(network.storageId, null, "ssh:${network.storageId}", workspace.id)

internal data class SshBrowserPresentation(val network: SshBrowserNetwork, val workspace: NativeWorkspace,
    val linkedPanel: String? = null, val initialUrl: String? = null, val provider: SshCmuxProvider? = null,
    val sidebarSelection: NativeSidebarSelection.Ssh? = null)

/** The dialog retains a terminal composition and its unsent draft behind the browser Activity. */
@Composable
internal fun SshBrowserSheet(presentation: SshBrowserPresentation, onRoute: ((NativeWorkspaceRoute) -> Unit)? = null,
    sshPicker: SshPickerPresentation? = null, onSshCommand: ((SshPickerCommand) -> Unit)? = null,
    sshPickerSource: (() -> SshPickerPresentation?)? = null, onDone: () -> Unit) {
    val providerState = presentation.provider?.state?.collectAsState()
    val state = providerState?.value
    fun menu(): RoutedBrowserMenu? {
        if (presentation.network.retired.isCompleted) return null
        val snapshot = providerState?.value
        val current = snapshot?.tree?.let { tree -> tree.workspaces.map { sshCmuxBrowserWorkspace(checkNotNull(presentation.provider).session, tree, it) }
            .singleOrNull { it.id == presentation.workspace.id } }
        if (snapshot != null && !snapshot.loading && !snapshot.ended && snapshot.error == null && snapshot.tree != null && current == null) return null
        val source = sshPickerSource
        val picker = if (source == null) sshPicker else source() ?: return null
        val base = current ?: presentation.workspace
        val workspace = if (picker == null) base else base.copy(
            terminals = picker.layout.sections.flatMap { it.rows }.map { NativeTerminal(it.target.encode(), it.title) },
            browsers = picker.layout.browsers.mapNotNull { row -> (row.target as? SshWorkspaceTarget.Browser)?.let { NativeBrowser(it.selection.panelId, row.title) } })
        return RoutedBrowserMenu(workspace, sshPicker = picker)
    }
    val liveMenu = menu()
    val workspace = liveMenu?.workspace ?: presentation.workspace
    LaunchedEffect(state) {
        if (state != null && !state.loading && !state.ended && state.error == null && state.tree != null && liveMenu == null) {
            presentation.network.navigation.retainPanels(sshLocalBrowserKey(presentation.network, presentation.workspace),
                presentation.workspace.copy(browsers = emptyList()))
            presentation.network.navigation.leave(close = true); onDone()
        }
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            SshBrowserScreen(presentation.network, workspace, presentation.linkedPanel, presentation.initialUrl, onRoute, onDone, liveMenu?.sshPicker, onSshCommand, ::menu, presentation.sidebarSelection)
        }
    }
}

@Composable
internal fun SshBrowserScreen(network: SshBrowserNetwork, workspace: NativeWorkspace, linkedPanel: String? = null,
    initialUrl: String? = null, onRoute: ((NativeWorkspaceRoute) -> Unit)? = null, onDone: () -> Unit,
    sshPicker: SshPickerPresentation? = null, onSshCommand: ((SshPickerCommand) -> Unit)? = null,
    menuSource: (() -> RoutedBrowserMenu?)? = null, sidebarSelection: NativeSidebarSelection.Ssh? = null) {
    val navigation = network.navigation
    val state by navigation.state.collectAsState()
    var started by remember(network, workspace.id, linkedPanel) { mutableStateOf(false) }
    var routedAway by remember(network, workspace.id, linkedPanel) { mutableStateOf(false) }
    LaunchedEffect(network, workspace.id, linkedPanel) {
        val key = sshLocalBrowserKey(network, workspace)
        if (linkedPanel != null) {
            if (workspace.browsers.none { it.id == linkedPanel }) { onDone(); return@LaunchedEffect }
            navigation.openOnDevice(key, workspace, linkedPanel, initialUrl)
        } else navigation.restoreRemembered(key, workspace)
        started = true
    }
    LaunchedEffect(network, workspace) { navigation.retainPanels(sshLocalBrowserKey(network, workspace), workspace) }
    val destination = state.local
    LaunchedEffect(started, destination) {
        // Opening publishes its destination before collectAsState necessarily
        // observes it. Do not interpret that one-frame lag as a user exit.
        if (started && destination == null && navigation.state.value.local == null && !routedAway) onDone()
    }
    if (destination != null) RoutedLocalBrowserWorkspaceView(destination, navigation, workspace, { network },
        { RoutedBrowserHostLease({}, {}) }, {}, { route ->
            routedAway = true
            if (onRoute != null) onRoute(route) else onDone()
        }, browserModes = true, sshPicker = sshPicker, menuSource = menuSource, onSidebarExit = { routedAway = true }, sidebarSelection = sidebarSelection,
        onSshCommand = onSshCommand?.let { callback -> { command ->
            if ((if (menuSource == null) sshPicker else menuSource()?.sshPicker)?.permits(command) == true && !network.retired.isCompleted) {
                routedAway = true
                navigation.leave(close = true)
                callback(command)
            }
        } })
}
