package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*

internal data class NativePanelViewState(val preview: NativePanelPresentation?, val title: String, val detail: String)

/** Kept above conditional pane content so leaving a panel releases its retained transfer. */
@Composable
internal fun rememberNativePanel(session: NativeFeedSession, login: String?, team: NativeTeamScope?,
    macs: List<NativeCredentialStore.PairedMac>, code: String, workspace: NativeWorkspace?,
    surface: NativeSurface?, hidden: Boolean): NativePanelViewState? {
    val mac = macs.singleOrNull { it.code == code }
    val target = if (hidden) null else workspace?.let { w -> surface?.let { NativePanelTarget.from(w.id, it) } }
    val key = target?.let { t -> mac?.let { workspaceTabKey(login, team, it, t.workspace) } }
    val retained = session.panelPreview
    // Observe descriptor/account invalidation while retaining completed documents through wire recovery.
    val sources by session.coordinator.sources.collectAsState()
    val source = mac?.let { sources[it.origin] }
    val allowed = source != null && retained?.let { it.matches(login, key, mac, target) && it.current() } == true
    SideEffect {
        if (retained != null && !allowed) session.dismissPanel(retained)
        if (target != null && mac != null && key != null && login != null && source != null)
            session.openPanel(login, key, mac, target)
    }
    if (target == null) return null
    val currentSurface = source?.workspaces?.singleOrNull { it.id == target.workspace }
        ?.macSurfaces?.singleOrNull { it.id == target.surface }
    val message = when {
        source == null || source.mac != mac || !source.hasWorkspaceSnapshot ->
            "Connecting to panel…" to "Waiting for this Mac's file connection."
        source.panelCacheToken == null ->
            "Preview unavailable" to "Reconnect this Mac to load its file panels."
        source.availability != NativeFeedAvailability.CONNECTED ->
            "Connecting to panel…" to "Waiting for this Mac's file connection."
        "panel.artifact.v1" !in source.capabilities ->
            "Update cmux on your Mac" to "This Mac's cmux version can't preview file panels."
        currentSurface?.isPanelFile != true ->
            "Panel closed" to "That file panel is no longer open on your Mac."
        NativePanelTarget.from(target.workspace, currentSurface) != target ->
            "Panel changed" to "Waiting for the current panel file."
        else -> "Connecting to panel…" to "Waiting for this Mac's file connection."
    }
    return NativePanelViewState(retained?.takeIf { allowed }, message.first, message.second)
}
