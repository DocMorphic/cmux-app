package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.UUID

class NativeSshWorkspaceFeedTest {
    private val host = SshHostRecord(name = "Remote box", endpoint = SshEndpoint("fixture.test", 22, "user"))
    private fun tree() = SshCmuxInventory.parse(JSONObject("""{
      "generation":"boot-a","registry_id":"registry-a","workspace_revision":5,
      "workspaces":[{"id":1,"key":"workspace-a","resource_id":"ws_a","name":"Unicode λ 中","active":true,
        "screens":[{"id":2,"resource_id":"screen_a","active":true,"active_pane":3,
          "panes":[{"id":3,"active_tab":0,"tabs":[
            {"surface":4,"kind":"pty","tab_resource_id":"tab_a","terminal_resource_id":"term_a","terminal_id":"host-a","title":"shell"},
            {"surface":5,"kind":"browser","tab_resource_id":"tab_b","content_resource_id":"browser_a","url":"http://localhost:3000"},
            {"surface":6,"kind":"pty","dead":true,"title":"Ended"}]}]}]},
        {"id":10,"key":"empty","name":"Empty","active":false,"screens":[]}]
    }"""))
    @Test fun reconnectKeepsCachedRowsUntilAnAuthoritativeListingAndKeepsActionErrorsAcrossRefresh() {
        val cached = SshFeedSnapshot(host, sshCmuxFeedRows(host, "desktop", tree()))
        val loading = SshFeedSnapshot(host, loading = true)
        assertEquals(cached.rows, mergeSshFeedSnapshot(cached, loading, null).rows)
        assertTrue(mergeSshFeedSnapshot(cached, loading.copy(loading = false), null).rows.isEmpty())
        assertEquals("Contents changed", mergeSshFeedSnapshot(cached, cached, "Contents changed").error)
        val changed = loading.copy(host = host.copy(endpoint = host.endpoint.copy(port = 2222)))
        assertTrue(mergeSshFeedSnapshot(cached, changed, null).rows.isEmpty())
    }
    @Test fun projectsOneRowPerWorkspaceWithKindAndExactLiveTerminalAndBrowserTargets() {
        val rows = sshCmuxFeedRows(host, "desktop", tree())
        assertEquals(2, rows.size); assertEquals("Unicode λ 中", rows.first().title)
        assertEquals("cmux-tui · desktop", rows.first().workspace.preview)
        assertEquals(2, rows.first().targets.size)
        assertTrue(rows.first().targets[0] is SshWorkspaceTarget.Cmux)
        assertTrue(rows.first().targets[1] is SshWorkspaceTarget.Browser)
        assertEquals(1, rows.first().workspace.terminals.size); assertEquals(1, rows.first().workspace.browsers.size)
        assertTrue(rows.last().targets.isEmpty()); assertFalse(rows.first().workspace.hasUnread)
    }
    @Test fun hostKindAndSessionNamespacesCannotCollideEvenWithIdenticalProviderIds() {
        val ids = listOf(
            sshFeedKey(host.id, SshWorkspaceKind.CMUX_TUI, "a:b", "c"),
            sshFeedKey(host.id, SshWorkspaceKind.CMUX_TUI, "a", "b:c"),
            sshFeedKey(UUID.randomUUID(), SshWorkspaceKind.CMUX_TUI, "a:b", "c"),
            sshFeedKey(host.id, SshWorkspaceKind.TMUX, "a:b", "c"))
        assertEquals(ids.size, ids.toSet().size)
    }
    @Test fun renameRetainsIdentityButRouteOrGenerationReplacementRetiresActions() {
        val row = sshCmuxFeedRows(host, "desktop", tree()).first()
        assertTrue(row.sameOwner(row.copy(host = host.copy(name = "Renamed"), title = "New title")))
        assertFalse(row.sameOwner(row.copy(host = host.copy(endpoint = host.endpoint.copy(port = 2222)))))
        assertFalse(row.sameOwner(row.copy(generation = "boot-b")))
        assertFalse(row.sameOwner(row.copy(registry = "registry-b")))
    }
    @Test fun tmuxIdentityIncludesServerAndSessionCreationAndPreservesPaneOrder() {
        val pane = SshTmuxPaneRow(7, 3, 0, "main", 0, 80, 24, 1)
        val original = SshTmuxWorkspace(42, 2, 100L, "Project", listOf(pane, pane.copy(id = 8, window = 4)))
        val rows = sshTmuxFeedRows(host, listOf(original, original.copy(created = 101L)))
        assertNotEquals(rows[0].key, rows[1].key)
        assertEquals(listOf(7, 8), rows.first().targets.map { (it as SshWorkspaceTarget.Tmux).pane })
        assertEquals("tmux", rows.first().preview)
    }
    @Test fun searchFindsHostKindAndUnicodeTitleWithoutCrossingFields() {
        val rows = sshCmuxFeedRows(host, "desktop", tree())
        val index = NativeSearchIndex(rows.map { it.key to listOf(it.title, it.preview, it.host.name) }, Locale.US)
        assertEquals(setOf(rows.first().key), index.matches("λ 中"))
        assertEquals(rows.map { it.key }.toSet(), index.matches("remote box"))
        assertEquals(rows.map { it.key }.toSet(), index.matches("cmux-tui"))
        assertTrue(index.matches("中 cmux").isEmpty())
    }
}
