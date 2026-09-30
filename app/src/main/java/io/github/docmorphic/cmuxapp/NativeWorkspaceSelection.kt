package io.github.docmorphic.cmuxapp

/** One selection axis. Resolve from the current owner snapshot, never another workspace. */
internal data class NativeWorkspacePane(
    val terminal: NativeTerminal? = null,
    val browser: NativeBrowser? = null,
    val surface: NativeSurface? = null
) {
    init { require(listOf(terminal, browser, surface).count { it != null } == 1) }
}

internal val NativeWorkspace.preferredTerminal: NativeTerminal?
    get() = terminals.firstOrNull { it.isReady && it.isFocused }
        ?: terminals.firstOrNull { it.isReady }
        ?: terminals.firstOrNull { it.isFocused }
        ?: terminals.firstOrNull()

/** A known Simulator descriptor upgrades its raw Mac surface without losing host order. */
private fun NativeWorkspace.nonTerminalPane(id: String, discoveredBrowsers: List<NativeBrowser> = emptyList()): NativeWorkspacePane? {
    simulators.firstOrNull { it.panelId == id }?.let { simulator ->
        return NativeWorkspacePane(surface = simulator.surface().copy(
            isFocused = surfaces.firstOrNull { it.id == id }?.isFocused == true))
    }
    (discoveredBrowsers + browsers).firstOrNull { it.id == id }?.let { return NativeWorkspacePane(browser = it) }
    return macSurfaces.firstOrNull { it.id == id }?.let { NativeWorkspacePane(surface = it) }
}

/** Explicit opens never silently switch to an unrelated pane if the target disappeared. */
internal fun NativeWorkspace.explicitPane(terminalId: String? = null, browserId: String? = null,
    surfaceId: String? = null): NativeWorkspacePane? = when {
    terminalId != null -> terminals.firstOrNull { it.id == terminalId }?.let { NativeWorkspacePane(terminal = it) }
    browserId != null -> browsers.firstOrNull { it.id == browserId }?.let { NativeWorkspacePane(browser = it) }
    surfaceId != null -> nonTerminalPane(surfaceId)
    else -> null
}

/** iOS syncDefaultSurfaceForWorkspace followed by preferredTerminal, for a fresh open. */
internal fun NativeWorkspace.defaultPane(discoveredBrowsers: List<NativeBrowser> = emptyList()): NativeWorkspacePane? {
    surfaces.firstOrNull { it.kind != "terminal" && it.isFocused }?.let { focused ->
        nonTerminalPane(focused.id, discoveredBrowsers)?.let { return it }
    }
    if (terminals.isEmpty()) {
        surfaces.firstOrNull { it.kind != "terminal" }?.let { first ->
            nonTerminalPane(first.id, discoveredBrowsers)?.let { return it }
        }
    }
    simulators.firstOrNull()?.let { return NativeWorkspacePane(surface = it.surface()) }
    // A browser-kinded surface is not evidence that mobile.browser.list has
    // discovered a stream. Only the separate discovery inventory wins here.
    discoveredBrowsers.firstOrNull()?.let { return NativeWorkspacePane(browser = it) }
    return preferredTerminal?.let { NativeWorkspacePane(terminal = it) }
}

internal fun NativeWorkspace.paneForRoute(route: NativeWorkspaceRoute): NativeWorkspacePane? =
    if (route.changes) null
    else if (route.terminalId != null || route.browserId != null || route.surfaceId != null)
        explicitPane(route.terminalId, route.browserId, route.surfaceId)
    else defaultPane()
