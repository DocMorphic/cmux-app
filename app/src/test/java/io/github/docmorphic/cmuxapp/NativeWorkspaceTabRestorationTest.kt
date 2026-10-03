package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceTabRestorationTest {
    private val empty = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w"}]}""")).single()
    private val terminal = NativeTerminal("t", "Shell")
    private val key = NativeWorkspaceTabKey("account", "team", "mac", "w")
    private fun tab(kind: NativeWorkspaceTabKind, id: String = "t") = NativeWorkspaceTab(kind, id)
    private fun assertStatus(expected: NativeWorkspaceRestoreStatus, workspace: NativeWorkspace, tab: NativeWorkspaceTab,
        browsers: List<NativeBrowser>? = null) = assertEquals(expected, restoreWorkspaceTab(workspace, tab, browsers).status)

    @Test fun terminalWaitsForHydrationOrReadinessAndMissingPopulatedInventoryIsUnavailable() {
        val remembered = tab(NativeWorkspaceTabKind.TERMINAL)
        assertStatus(NativeWorkspaceRestoreStatus.WAITING, empty, remembered)
        assertStatus(NativeWorkspaceRestoreStatus.UNAVAILABLE, empty.copy(terminals = listOf(terminal.copy(id = "other"))), remembered)
        val starting = empty.copy(terminals = listOf(terminal.copy(isReady = false), terminal.copy(id = "ready")))
        assertStatus(NativeWorkspaceRestoreStatus.WAITING, starting, remembered)
        assertStatus(NativeWorkspaceRestoreStatus.RESTORED, empty.copy(terminals = listOf(terminal.copy(isReady = false))), remembered)
        assertEquals(terminal, restoreWorkspaceTab(empty.copy(terminals = listOf(terminal)), remembered).pane?.terminal)
    }
    @Test fun surfaceRestorationWaitsForHydrationAndDoesNotAcceptATerminalKind() {
        val remembered = tab(NativeWorkspaceTabKind.MAC_SURFACE)
        assertStatus(NativeWorkspaceRestoreStatus.WAITING, empty, remembered)
        assertStatus(NativeWorkspaceRestoreStatus.UNAVAILABLE, empty.copy(surfaces = listOf(NativeSurface("t", "terminal", "Shell"))), remembered)
        val surface = NativeSurface("t", "todo", "Checklist")
        assertEquals(surface, restoreWorkspaceTab(empty.copy(surfaces = listOf(surface)), remembered).pane?.surface)
    }
    @Test fun browserRestorationDistinguishesNeverDiscoveredFromConfirmedMissing() {
        val remembered = tab(NativeWorkspaceTabKind.BROWSER_STREAM, "browser")
        assertStatus(NativeWorkspaceRestoreStatus.WAITING, empty, remembered)
        assertStatus(NativeWorkspaceRestoreStatus.UNAVAILABLE, empty, remembered, emptyList())
        val workspace = empty.copy(surfaces = listOf(NativeSurface("browser", "browser", "Browser")))
        assertStatus(NativeWorkspaceRestoreStatus.WAITING, workspace, remembered, emptyList())
        val browser = NativeBrowser("browser", "Discovered")
        assertEquals(browser, restoreWorkspaceTab(workspace, remembered, listOf(browser)).pane?.browser)
    }
    @Test fun rememberedMacSurfacePromotesToABrowserStreamOnlyAfterDiscovery() {
        val surface = NativeSurface("browser", "browser", "Raw browser")
        val workspace = empty.copy(surfaces = listOf(surface), browsers = listOf(NativeBrowser("browser", "Raw browser")))
        val remembered = tab(NativeWorkspaceTabKind.MAC_SURFACE, "browser")
        assertEquals(surface, restoreWorkspaceTab(workspace, remembered).pane?.surface)
        val discovered = NativeBrowser("browser", "Stream browser")
        assertEquals(discovered, restoreWorkspaceTab(workspace, remembered, listOf(discovered)).pane?.browser)
    }
    @Test fun simulatorRequiresItsDescriptorAndLocalBrowserNeedsNoMacPane() {
        val remembered = tab(NativeWorkspaceTabKind.SIMULATOR_STREAM, "sim")
        val descriptor = NativeSimulator("sim", "w", "Simulator", null, null, "ready", true, true, true, true, true, null, null)
        assertStatus(NativeWorkspaceRestoreStatus.UNAVAILABLE, empty, remembered)
        assertEquals(descriptor, restoreWorkspaceTab(empty.copy(simulators = listOf(descriptor)), remembered).pane?.surface?.simulator)
        val local = restoreWorkspaceTab(empty, NativeWorkspaceTab.LocalBrowser)
        assertEquals(NativeWorkspaceRestoreStatus.RESTORED, local.status); assertTrue(local.localBrowser); assertNull(local.pane)
    }
    @Test fun newerActiveStreamsWinOverDifferentMemoryAndMatchingStreamsRetainTheirPane() {
        val active = NativeWorkspacePane(browser = NativeBrowser("live", "Live browser"))
        assertEquals(NativeWorkspaceRestoreStatus.UNAVAILABLE, restoreWorkspaceTab(empty, tab(NativeWorkspaceTabKind.TERMINAL), activePane = active).status)
        assertEquals(active, restoreWorkspaceTab(empty, active.tab(), activePane = active).pane)
    }
    @Test fun pendingRestoreSurvivesInterimFallbackAndCannotCrossOwningKeys() {
        val controller = NativeWorkspaceTabRestoration()
        val remembered = tab(NativeWorkspaceTabKind.TERMINAL)
        controller.begin(key, remembered)
        assertNull(controller.resolve(key.copy(computerId = "other"), empty))
        assertNull(controller.resolve(key, empty.copy(id = "other")))
        val starting = empty.copy(terminals = listOf(terminal.copy(isReady = false), terminal.copy(id = "fallback")))
        assertEquals(NativeWorkspaceRestoreStatus.WAITING, controller.resolve(key, starting)?.status)
        assertEquals("fallback", starting.defaultPane()?.terminal?.id)
        assertTrue(controller.isWaiting(key))
        assertEquals(terminal, controller.resolve(key, empty.copy(terminals = listOf(terminal)))?.pane?.terminal)
        assertFalse(controller.isWaiting(key)); assertNull(controller.resolve(key, starting))
    }
    @Test fun explicitCancellationAndNewOpenDisarmOldWaitingIntents() {
        val controller = NativeWorkspaceTabRestoration()
        controller.begin(key, tab(NativeWorkspaceTabKind.TERMINAL)); controller.cancel()
        assertNull(controller.resolve(key, empty.copy(terminals = listOf(terminal))))
        controller.begin(key, tab(NativeWorkspaceTabKind.TERMINAL))
        val next = key.copy(computerId = "new-mac")
        controller.begin(next, NativeWorkspaceTab.LocalBrowser)
        assertNull(controller.resolve(key, empty)); assertTrue(controller.resolve(next, empty)!!.localBrowser)
        controller.begin(key, null); assertFalse(controller.isWaiting(key))
    }
}
