package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceTabNavigationTest {
    private val key = NativeWorkspaceTabKey("account", "team", "mac", "w")
    private val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","terminals":[
        {"id":"fallback","title":"Fallback"},{"id":"waiting","title":"Waiting","is_ready":false}]}]}""")).single()
    private val remembered = NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, "waiting")
    private var memory: NativeWorkspaceTab? = remembered
    private var writes = 0
    private fun navigation() = NativeWorkspaceTabNavigation({ _, _ -> memory }, { _, _, tab -> memory = tab; writes++ }, { _, _ -> memory = null })

    @Test fun fallbackCannotOverwritePendingMemoryAndReadinessResolvesIt() {
        val navigation = navigation()
        val opened = navigation.open("login", key, workspace)
        assertEquals("fallback", opened.pane?.terminal?.id)
        navigation.observe("login", key, opened.pane!!.tab()); navigation.observe("login", key, null)
        assertEquals(remembered, memory); assertEquals(0, writes)
        val ticket = navigation.pending.value!!
        assertNull(navigation.resolve(ticket, workspace, null))
        val ready = workspace.copy(terminals = workspace.terminals.map { it.copy(isReady = true) })
        assertEquals("waiting", navigation.resolve(ticket, ready, null)?.pane?.terminal?.id)
        assertNull(navigation.pending.value); assertEquals(remembered, memory)
    }
    @Test fun explicitlyPickingTheSameFallbackDisarmsAndRecordsIt() {
        val navigation = navigation()
        val fallback = navigation.open("login", key, workspace).pane!!.tab()
        val old = navigation.pending.value!!
        navigation.explicit("login", key, fallback)
        assertNull(navigation.pending.value); assertEquals(fallback, memory)
        assertNull(navigation.resolve(old, workspace.copy(terminals = workspace.terminals.map { it.copy(isReady = true) }), null))
        navigation.observe("login", key, fallback); assertEquals(1, writes)
    }
    @Test fun leavingAccountOrWorkspaceRetiresLateResultsAndNewOpenUsesANewTicket() {
        val navigation = navigation()
        navigation.open("login", key, workspace); val old = navigation.pending.value!!
        navigation.observe(null, null, null); assertNull(navigation.resolve(old, workspace, null))
        navigation.open("login", key, workspace); val next = navigation.pending.value!!
        assertNotEquals(old.id, next.id)
        assertNull(navigation.resolve(old, workspace, emptyList()))
        navigation.observe("new-login", key, null); assertNull(navigation.pending.value)
    }
    @Test fun missingRememberedPaneRecordsActualFallbackAndLocalMemoryReopensWithoutMacPanes() {
        val navigation = navigation()
        val available = workspace.copy(terminals = workspace.terminals.take(1))
        assertEquals("fallback", navigation.open("login", key, available).pane?.terminal?.id)
        assertEquals("fallback", memory?.id)
        memory = NativeWorkspaceTab.LocalBrowser
        assertTrue(navigation.open("login", key, available.copy(terminals = emptyList())).localBrowser)
        navigation.forget("login", key); assertNull(memory)
    }
    @Test fun browserOnlyRestoresAfterValidatedDiscoveryAndLateDiscoveryCannotWin() {
        memory = NativeWorkspaceTab(NativeWorkspaceTabKind.BROWSER_STREAM, "browser")
        val navigation = navigation()
        navigation.open("login", key, workspace); val ticket = navigation.pending.value!!
        assertNull(navigation.resolve(ticket, workspace, null))
        val browser = NativeBrowser("browser", "Discovered")
        assertEquals(browser, navigation.resolve(ticket, workspace, listOf(browser))?.pane?.browser)
        assertEquals(listOf(browser), navigation.withDiscoveredBrowsers(key, workspace).browsers)
        assertTrue(navigation.withDiscoveredBrowsers(key.copy(computerId = "other"), workspace).browsers.isEmpty())
        assertNull(navigation.resolve(ticket, workspace, listOf(NativeBrowser("other", "Other"))))
        assertEquals(listOf(browser), navigation.withDiscoveredBrowsers(key, workspace).browsers)
        navigation.observe(null, null, null)
        assertTrue(navigation.withDiscoveredBrowsers(key, workspace).browsers.isEmpty())
    }
    @Test fun emptyWorkspaceWaitsForADefaultWithoutInventingOrRecordingATab() {
        memory = null
        val navigation = navigation()
        val empty = workspace.copy(terminals = emptyList())
        assertNull(navigation.open("login", key, empty).pane)
        val ticket = navigation.pending.value!!
        assertNull(ticket.tab)
        navigation.observe("login", key, null)
        assertNull(navigation.resolve(ticket, empty, emptyList()))
        assertEquals(0, writes)
        assertEquals("fallback", navigation.resolve(ticket, workspace, null)?.pane?.terminal?.id)
        assertNull(navigation.pending.value); assertEquals("fallback", memory?.id)
    }
    @Test fun freshEmptyWorkspaceCanResolveToADiscoveredBrowserWithoutAWireSurface() {
        memory = null
        val navigation = navigation()
        val empty = workspace.copy(terminals = emptyList())
        navigation.open("login", key, empty)
        val browser = NativeBrowser("late-browser", "Late browser")
        assertEquals(browser, navigation.resolve(navigation.pending.value!!, empty, listOf(browser))?.pane?.browser)
        assertEquals(NativeWorkspaceTab(NativeWorkspaceTabKind.BROWSER_STREAM, browser.id), memory)
        assertEquals(listOf(browser), navigation.withDiscoveredBrowsers(key, empty).browsers)
    }
    @Test fun explicitSelectionCancelsDefaultWaitingAndRejectsItsLateDiscovery() {
        memory = null
        val navigation = navigation()
        navigation.open("login", key, workspace.copy(terminals = emptyList()))
        val ticket = navigation.pending.value!!
        navigation.explicit("login", key, NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, "chosen"))
        assertNull(navigation.resolve(ticket, workspace, listOf(NativeBrowser("late", "Late"))))
        assertEquals("chosen", memory?.id)
    }
    @Test fun discoveryCacheIsScopedToTheVisibleLoginAndWorkspaceAndConfirmedEmptyClearsIt() {
        memory = null
        val navigation = navigation()
        navigation.observe("login", key, NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, "fallback"))
        val browser = NativeBrowser("b", "Browser")
        assertFalse(navigation.discover("old-login", key, listOf(browser)))
        assertFalse(navigation.discover("login", key.copy(computerId = "other"), listOf(browser)))
        assertTrue(navigation.discover("login", key, listOf(browser)))
        assertEquals(listOf(browser), navigation.browsers(key))
        assertTrue(navigation.discover("login", key, emptyList()))
        assertTrue(navigation.withDiscoveredBrowsers(key, workspace).browsers.isEmpty())
        navigation.observe(null, null, null)
        assertFalse(navigation.discover("login", key, listOf(browser)))
    }
    private fun descriptor(id: String = "b", owner: String = "w") = JSONObject().put("panel_id", id)
        .put("workspace_id", owner).put("page_width", 800).put("page_height", 600)
        .put("can_go_back", false).put("can_go_forward", false).put("is_loading", false).put("title", "Browser")
    @Test fun discoveryRequiresCompleteTypedOwnershipAndUniqueIds() {
        fun listing(vararg panels: JSONObject) = JSONObject().put("panels", JSONArray(panels.toList()))
        assertEquals(listOf(NativeBrowser("b", "Browser")), parseWorkspaceBrowserPanels(listing(descriptor()), "w"))
        assertTrue(parseWorkspaceBrowserPanels(listing(), "w").isEmpty())
        val invalid = listOf(JSONObject(), listing(descriptor(owner = "other")), listing(descriptor(), descriptor()),
            listing(descriptor().put("page_width", "800")), listing(descriptor().put("is_loading", "false")),
            listing(descriptor().put("panel_id", "")), listing(descriptor().put("title", true)))
        invalid.forEach { assertTrue(runCatching { parseWorkspaceBrowserPanels(it, "w") }.isFailure) }
    }
}
