package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceLastTabsTest {
    private val key = NativeWorkspaceTabKey("account", "team", "mac", "w")
    private val tab = NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, "terminal")
    private fun key(index: Int) = key.copy(workspaceId = "workspace-$index")
    private fun entry(kind: String = "terminal", id: String = "terminal", sequence: Any = 1) =
        JSONObject().put("kind", kind).put("tab_id", id).put("sequence", sequence)

    @Test fun allFiveKindsRoundTripAndUnchangedWritesAreNoOps() {
        val store = NativeWorkspaceLastTabs()
        NativeWorkspaceTabKind.entries.forEachIndexed { index, kind ->
            val selected = if (kind == NativeWorkspaceTabKind.LOCAL_BROWSER) NativeWorkspaceTab.LocalBrowser else NativeWorkspaceTab(kind, "id-$index")
            assertTrue(store.set(key(index), selected))
            assertEquals(selected, NativeWorkspaceLastTabs(JSONObject(store.json().toString())).get(key(index)))
            val before = store.json().toString()
            assertFalse(store.set(key(index), selected)); assertEquals(before, store.json().toString())
        }
    }
    @Test fun evictionUsesUpdatesNotReadsOrUnchangedSelections() {
        val store = NativeWorkspaceLastTabs()
        repeat(512) { store.set(key(it), tab) }
        assertEquals(tab, store.get(key(0))); assertFalse(store.set(key(0), tab))
        store.set(key(512), tab)
        assertNull(store.get(key(0))); assertEquals(512, store.json().length())
        val changed = tab.copy(id = "new-terminal")
        assertTrue(store.set(key(1), changed)); store.set(key(513), tab)
        assertNull(store.get(key(2))); assertEquals(changed, store.get(key(1)))
    }
    @Test fun oversizedLoadedMapPrunesOldestAndUnknownKindsDoNotDestroyKnownEntries() {
        val json = JSONObject()
        repeat(514) { json.put(key(it).encoded, entry(sequence = it)) }
        json.put(key(513).encoded, entry("futurePane", sequence = 513))
        val store = NativeWorkspaceLastTabs(json)
        assertEquals(512, store.json().length()); assertNull(store.get(key(0))); assertNull(store.get(key(1)))
        assertEquals(tab, store.get(key(2))); assertNull(store.get(key(513)))
        assertEquals("futurePane", store.json().getJSONObject(key(513).encoded).getString("kind"))
    }
    @Test fun malformedEntriesAreIsolatedAndSequenceOverflowKeepsOrdering() {
        val json = JSONObject().put(key.encoded, entry(sequence = Long.MAX_VALUE))
            .put(key(1).encoded, entry(sequence = Long.MAX_VALUE - 1))
            .put("bad-type", true).put("fraction", entry(sequence = 1.5))
            .put("negative", entry(sequence = -1)).put("empty-id", entry(id = ""))
        val store = NativeWorkspaceLastTabs(json)
        assertEquals(2, store.json().length())
        assertTrue(store.set(key(2), tab))
        val saved = store.json()
        assertTrue(saved.getJSONObject(key(1).encoded).getLong("sequence") < saved.getJSONObject(key.encoded).getLong("sequence"))
        assertTrue(saved.getJSONObject(key.encoded).getLong("sequence") < saved.getJSONObject(key(2).encoded).getLong("sequence"))
        assertEquals(tab, NativeWorkspaceLastTabs(JSONObject(saved.toString())).get(key))
    }
    @Test fun keyIsStableAcrossRouteAndNameChangesButSeparatesAccountTeamMacBuildAndWorkspace() {
        val id = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        val owner = NativeTeamScope("login", "account", "team", 1)
        val mac = NativeCredentialStore.PairedMac("route-one", id, "Mac", "default", "account", "team")
        val base = workspaceTabKey("login", owner, mac, "w")!!
        assertEquals(base, workspaceTabKey("login", owner.copy(generation = 3),
            mac.copy(code = "route-two", deviceId = id.lowercase(), name = "Renamed"), "w"))
        assertEquals(base, workspaceTabKey("login", null, mac, "w"))
        assertEquals(base, workspaceTabKey("login", owner, mac.copy(instanceTag = " default\n"), "w"))
        assertEquals(workspaceTabKey("login", owner, mac.copy(instanceTag = null), "w"),
            workspaceTabKey("login", owner, mac.copy(instanceTag = " \n"), "w"))
        assertNotEquals(base, workspaceTabKey("login", owner.copy(userId = "other"), mac, "w"))
        assertNotEquals(base, workspaceTabKey("login", owner.copy(teamId = "other"), mac, "w"))
        assertNotEquals(base, workspaceTabKey("login", owner, mac.copy(deviceId = "other"), "w"))
        assertNotEquals(base, workspaceTabKey("login", owner, mac.copy(instanceTag = "debug"), "w"))
        assertNotEquals(base, workspaceTabKey("login", owner, mac, "other"))
        assertNull(workspaceTabKey(null, owner, mac, "w"))
    }
    @Test fun anonymousPairingsAndUnverifiedAccountsCannotCollideAcrossRoutesOrLogins() {
        val mac = NativeCredentialStore.PairedMac("route-one", "", "Anonymous")
        val first = workspaceTabKey("login-one", null, mac, "w")
        assertNotEquals(first, workspaceTabKey("login-two", null, mac, "w"))
        assertNotEquals(first, workspaceTabKey("login-one", null, mac.copy(code = "route-two"), "w"))
        assertNotEquals(key.copy(accountId = "a", teamId = "b:c").encoded, key.copy(accountId = "a:b", teamId = "c").encoded)
        assertNotEquals(key.copy(teamId = null).encoded, key.copy(teamId = "null").encoded)
    }
}
