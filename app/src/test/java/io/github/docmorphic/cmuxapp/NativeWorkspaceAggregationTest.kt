package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceAggregationTest {
    private fun source(mac: String) = NativeFeedSource(
        NativeCredentialStore.PairedMac(mac, mac, mac),
        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
            {"id":"same","title":"Task","group_id":"same-group","has_unread":true},
            {"id":"second","title":"Read","group_id":"same-group"}]}""")),
        groups = listOf(NativeGroup("same-group", "Group", true, false, "same")))

    @Test fun groupExpansionAndWorkspaceKeysStayLocalToTheirMac() {
        val a = source("a"); val b = source("b")
        val matches = listOf(a, b).flatMap { source -> source.workspaces.map { workspaceSearchId(source, it) } }.toSet()
        val aHeader = WorkspaceListEntry.Header(a, a.groups.single())
        val entries = workspaceEntries(listOf(a, b), matches, false, false, mapOf(aHeader.key to false))
        assertEquals(4, entries.size)
        assertEquals(1, entries.filterIsInstance<WorkspaceListEntry.Workspace>().size)
        assertTrue(entries.filterIsInstance<WorkspaceListEntry.Workspace>().all { it.source.mac == a.mac })
        val all = workspaceEntries(listOf(a, b), matches, true, false, emptyMap())
        assertEquals(4, all.size)
        assertEquals(4, all.map { it.key }.distinct().size)
    }

    @Test fun searchAndUnreadFiltersUseOwnerQualifiedIdsAndRevealCollapsedMembers() {
        val a = source("a"); val b = source("b")
        val matches = b.workspaces.map { workspaceSearchId(b, it) }.toSet()
        val entries = workspaceEntries(listOf(a, b), matches, true, true, emptyMap())
        assertEquals(1, entries.size)
        val row = entries.single() as WorkspaceListEntry.Workspace
        assertEquals(b.mac, row.source.mac)
        assertEquals("same", row.workspace.id)
    }
}
