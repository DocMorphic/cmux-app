package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceSelectionTest {
    private fun workspace(terminals: String = "[]", surfaces: String = "[]") =
        parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","title":"Workspace",
            "terminals":$terminals,"surfaces":$surfaces}]}""")).single()
    private val shell = """[{"id":"t","title":"Shell"}]"""
    private val panels = """[{"surface_id":"todo","kind":"todo","title":"Checklist"},
        {"surface_id":"browser","kind":"browser","title":"Browser"}]"""
    private fun simulator(id: String) = NativeSimulator(id, "w", "Simulator", null, null, "streaming",
        true, true, true, true, true, null, null)

    @Test fun terminalReadinessMapsTheWireAndLegacyAbsenceDefaultsToReady() {
        val terminals = workspace("""[{"id":"old"},{"id":"null","is_ready":null},
            {"id":"starting","is_ready":false},{"id":"ready","is_ready":true}]""").terminals
        assertEquals(listOf(true, true, false, true), terminals.map { it.isReady })
    }
    @Test fun preferredTerminalOrdersReadinessThenFocusWithoutSortingTheInventory() {
        val first = NativeTerminal("first", "First", isReady = false)
        val focused = NativeTerminal("focused", "Focused", isFocused = true, isReady = false)
        val ready = NativeTerminal("ready", "Ready")
        val both = NativeTerminal("both", "Both", isFocused = true)
        val workspace = workspace()
        assertEquals(both, workspace.copy(terminals = listOf(first, focused, ready, both)).defaultPane()?.terminal)
        assertEquals(ready, workspace.copy(terminals = listOf(first, focused, ready)).defaultPane()?.terminal)
        assertEquals(focused, workspace.copy(terminals = listOf(first, focused)).defaultPane()?.terminal)
        assertEquals(first, workspace.copy(terminals = listOf(first)).defaultPane()?.terminal)
        assertNull(workspace.defaultPane())
    }
    @Test fun discoveredStreamUpgradesTheFocusedRawSurfaceAndSimulatorStillWinsItsId() {
        val raw = workspace(surfaces = """[{"surface_id":"panel","kind":"custom","is_focused":true}]""")
        val browser = NativeBrowser("panel", "Streamed panel")
        assertEquals(browser, raw.defaultPane(listOf(browser))?.browser)
        assertNotNull(raw.copy(simulators = listOf(simulator("panel"))).defaultPane(listOf(browser))?.surface?.simulator)
    }
    @Test fun focusedNonTerminalWinsAFreshOpenEvenWhenThereIsAReadyTerminal() {
        val workspace = workspace(shell, """[{"surface_id":"todo","kind":"todo","is_focused":true}]""")
        assertEquals("todo", workspace.defaultPane()?.surface?.id)
        assertEquals("t", workspace.explicitPane(terminalId = "t")?.terminal?.id)
    }
    @Test fun focusedBrowserWinsSpatialOrderAndTerminalFallback() {
        val workspace = workspace(shell, """[{"surface_id":"todo","kind":"todo"},
            {"surface_id":"browser","kind":"browser","is_focused":true}]""")
        assertEquals("browser", workspace.defaultPane()?.browser?.id)
    }
    @Test fun noTerminalFallbackUsesHostSpatialOrderAcrossPaneKinds() {
        assertEquals("todo", workspace(surfaces = panels).defaultPane()?.surface?.id)
        val browserFirst = """[{"surface_id":"browser","kind":"browser"},{"surface_id":"todo","kind":"todo"}]"""
        assertEquals("browser", workspace(surfaces = browserFirst).defaultPane()?.browser?.id)
        val terminalThenTodo = """[{"surface_id":"term","kind":"terminal"},{"surface_id":"todo","kind":"todo"}]"""
        assertEquals("todo", workspace(surfaces = terminalThenTodo).defaultPane()?.surface?.id)
    }
    @Test fun unfocusedNonStreamSurfaceDoesNotDisplaceAnAvailableTerminal() {
        assertEquals("t", workspace(shell, """[{"surface_id":"todo","kind":"todo"}]""").defaultPane()?.terminal?.id)
    }
    @Test fun descriptorArrivalUpgradesTheFocusedRawSimulatorSurface() {
        val initial = workspace(surfaces = """[{"surface_id":"first","kind":"simulator"},
            {"surface_id":"focused","kind":"simulator","is_focused":true}]""")
        assertEquals("simulator", initial.defaultPane()?.surface?.kind)
        val updated = initial.copy(simulators = listOf(simulator("first"), simulator("focused")))
        assertEquals("focused", updated.defaultPane()?.surface?.simulator?.panelId)
        assertTrue(updated.defaultPane()!!.surface!!.isFocused)
        assertEquals("focused", updated.explicitPane(surfaceId = "focused")?.surface?.simulator?.panelId)
    }
    @Test fun streamFallbackPrefersSimulatorThenBrowserBeforeTheTerminalHeuristic() {
        val workspace = workspace(shell, panels)
        assertEquals("t", workspace.defaultPane()?.terminal?.id)
        assertEquals("browser", workspace.defaultPane(workspace.browsers)?.browser?.id)
        assertEquals("sim", workspace.copy(simulators = listOf(simulator("sim"))).defaultPane(workspace.browsers)?.surface?.id)
    }
    @Test fun explicitPaneAndChangesRoutesDoNotUseDefaultWhenTheirTargetIsMissing() {
        val workspace = workspace(shell, panels)
        val route = NativeWorkspaceRoute("mac", "w")
        assertEquals("t", workspace.paneForRoute(route.copy(terminalId = "t"))?.terminal?.id)
        assertEquals("browser", workspace.paneForRoute(route.copy(browserId = "browser"))?.browser?.id)
        assertEquals("todo", workspace.paneForRoute(route.copy(surfaceId = "todo"))?.surface?.id)
        assertNull(workspace.paneForRoute(route.copy(terminalId = "closed")))
        assertNull(workspace.paneForRoute(route.copy(browserId = "closed")))
        assertNull(workspace.paneForRoute(route.copy(surfaceId = "closed")))
        assertNull(workspace.paneForRoute(route.copy(changes = true)))
    }
}
