package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshCmuxInventoryTest {
    private fun tree() = JSONObject("""{
      "generation":"boot-a","registry_id":"registry-a","workspace_revision":5,
      "workspaces":[{"id":1,"key":"workspace-a","resource_id":"ws_a","name":"Unicode λ 中","active":true,
        "screens":[{"id":2,"resource_id":"screen_a","active":true,"active_pane":3,
          "layout":{"type":"split","split":9,"dir":"right","ratio":0.4,
            "a":{"type":"leaf","pane":3},"b":{"type":"stack","panes":[6],"expanded":6}},
          "panes":[{"id":3,"active_tab":0,"tabs":[
            {"surface":4,"kind":"pty","tab_resource_id":"tab_a","terminal_resource_id":"term_a","terminal_id":"host-a",
             "title":"shell","size":{"cols":80,"rows":24}},
            {"surface":5,"kind":"browser","tab_resource_id":"tab_b","content_resource_id":"browser_a",
             "url":"http://localhost:3000","size":{"cols":1920,"rows":1080},"browser_status":"loading","browser_frames_stalled":true}]},
            {"id":6,"dead":true}]}]},
        {"id":10,"key":"empty","name":"Empty","active":false,"screens":[]}]
    }""")
    private fun workspace(value: JSONObject) = value.getJSONArray("workspaces").getJSONObject(0)
    private fun screen(value: JSONObject) = workspace(value).getJSONArray("screens").getJSONObject(0)
    private fun pane(value: JSONObject) = screen(value).getJSONArray("panes").getJSONObject(0)
    private fun tab(value: JSONObject) = pane(value).getJSONArray("tabs").getJSONObject(0)

    @Test fun preservesOrderedHierarchyBrowserMetadataEmptyWorkspaceAndSplitStackLayout() {
        val parsed = SshCmuxInventory.parse(tree())
        assertEquals(5L, parsed.revision)
        val workspace = parsed.workspaces.first()
        assertEquals("Unicode λ 中", workspace.name); assertTrue(workspace.active)
        assertEquals(listOf(4, 5), workspace.tabs.map { it.surface })
        assertTrue(workspace.tabs[0].isTerminal); assertFalse(workspace.tabs[1].isTerminal)
        val browser = workspace.tabs[1]
        assertEquals("http://localhost:3000", browser.url); assertEquals(1920, browser.columns)
        assertEquals("loading", browser.browserStatus); assertTrue(browser.framesStalled)
        assertEquals(2, browser.screen); assertEquals(3, browser.pane)
        assertTrue(workspace.screens.single().panes.last().dead)
        val layout = workspace.screens.single().layout as SshCmuxLayout.Split
        assertTrue(layout.right); assertEquals(0.4, layout.ratio, 0.0001)
        assertEquals(SshCmuxLayout.Leaf(3), layout.a); assertEquals(SshCmuxLayout.Stack(listOf(6), 6), layout.b)
        assertTrue(parsed.workspaces.last().tabs.isEmpty())
    }
    @Test fun additiveFieldsUnknownKindsAndFutureLayoutAreNotMisclassifiedAsTerminals() {
        val value = tree().put("future_field", JSONObject())
        tab(value).put("kind", "future-renderer")
        screen(value).put("layout", JSONObject().put("type", "future-layout"))
        val parsed = SshCmuxInventory.parse(value)
        assertFalse(parsed.tabs.first().isTerminal); assertFalse(parsed.tabs.first().isBrowser)
        assertEquals(SshCmuxLayout.Unknown("future-layout"), parsed.workspaces.first().screens.first().layout)
    }
    @Test fun malformedAndDuplicateIdentityOrTopologyAreRejectedInsteadOfPublishingPartialTrees() {
        val changes: List<(JSONObject) -> Unit> = listOf(
            { tab(it).put("surface", 4294967300L) }, { tab(it).put("surface", 4.1) }, { tab(it).put("surface", "4") },
            { tab(it).put("dead", "false") }, { tab(it).put("size", JSONObject().put("cols", 80).put("rows", 0)) },
            { pane(it).getJSONArray("tabs").getJSONObject(1).put("surface", 4) },
            { pane(it).getJSONArray("tabs").getJSONObject(1).put("tab_resource_id", "tab_a") },
            { it.getJSONArray("workspaces").getJSONObject(1).put("key", "workspace-a") },
            { screen(it).put("active_pane", 999) },
            { screen(it).getJSONObject("layout").put("ratio", "0.4") },
            { screen(it).getJSONObject("layout").getJSONObject("a").put("pane", 6) },
            { screen(it).getJSONObject("layout").getJSONObject("b").put("expanded", 3) },
            { workspace(it).put("screens", "bad") }, { it.remove("workspaces") })
        for (change in changes) {
            val value = tree(); change(value)
            assertThrows(Exception::class.java) { SshCmuxInventory.parse(value) }
        }
    }
    @Test fun boundedInventoryAndLayoutDepthRejectUnboundedTrees() {
        val many = JSONArray(); repeat(10001) { many.put(JSONObject().put("id", it).put("name", "row")) }
        assertThrows(Exception::class.java) { SshCmuxInventory.parse(JSONObject().put("workspaces", many)) }
        var layout = JSONObject().put("type", "future")
        repeat(64) { layout = JSONObject().put("type", "split").put("dir", "right").put("ratio", 0.5)
            .put("a", layout).put("b", JSONObject().put("type", "future")) }
        val value = tree(); screen(value).put("layout", layout)
        assertThrows(Exception::class.java) { SshCmuxInventory.parse(value) }
    }
    @Test fun durableSelectionSurvivesRenumberingButRejectsDifferentSessionRegistryOrTerminal() {
        val original = SshCmuxInventory.parse(tree())
        val selected = SshCmuxSelection.capture("session", original, original.workspaces.first(), original.tabs.first())
        val value = tree().put("generation", "boot-b")
        workspace(value).put("id", 30); tab(value).put("surface", 40)
        val current = SshCmuxInventory.parse(value)
        assertEquals(40, selected.resolve("session", current)?.second?.surface)
        assertNull(selected.resolve("other", current))
        assertNull(selected.resolve("session", current.copy(registry = "replacement")))
        tab(value).put("terminal_resource_id", "term_replacement")
        assertNull(selected.resolve("session", SshCmuxInventory.parse(value)))
        tab(value).put("terminal_resource_id", "term_a").put("dead", true)
        assertNull(selected.resolve("session", SshCmuxInventory.parse(value)))
    }
    @Test fun oldServersOnlyRestoreNumericIdsInTheSameKnownGeneration() {
        val value = tree(); workspace(value).remove("key"); workspace(value).remove("resource_id")
        for (field in listOf("terminal_resource_id", "terminal_id", "tab_resource_id")) tab(value).remove(field)
        val original = SshCmuxInventory.parse(value)
        val selected = SshCmuxSelection.capture("s", original, original.workspaces.first(), original.tabs.first())
        assertNotNull(selected.resolve("s", original))
        assertNull(selected.resolve("s", original.copy(generation = "restarted")))
        assertNull(selected.resolve("s", original.copy(generation = null)))
    }
    @Test fun sharedTerminalViewsRequireExactTabIdentityOrAnUnambiguousMatch() {
        val value = tree()
        pane(value).getJSONArray("tabs").put(JSONObject(tab(value).toString()).put("surface", 8).put("tab_resource_id", "tab_second"))
        val original = SshCmuxInventory.parse(value)
        val selected = SshCmuxSelection.capture("s", original, original.workspaces.first(), original.tabs.first())
        assertEquals(4, selected.resolve("s", original)?.second?.surface)
        assertNull(selected.copy(tabResource = null).resolve("s", original))
    }
}
