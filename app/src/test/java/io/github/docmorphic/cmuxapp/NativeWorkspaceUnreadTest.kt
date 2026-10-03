package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceUnreadTest {
    @Test fun oldNullMalformedAndLargeWireCountsKeepTheirMeaning() {
        val rows = parseWorkspaces(JSONObject("""{"workspaces":[
          {"id":"read"}, {"id":"legacy","has_unread":true},
          {"id":"null","has_unread":true,"unread_count":null},
          {"id":"exact","has_unread":true,"unread_count":17},
          {"id":"zero","has_unread":true,"unread_count":0},
          {"id":"negative","has_unread":true,"unread_count":-2},
          {"id":"malformed","has_unread":true,"unread_count":"oops"},
          {"id":"fraction","has_unread":true,"unread_count":2.5},
          {"id":"large","has_unread":true,"unread_count":3000000000}
        ]}""")).associateBy { it.id }
        assertEquals(NativeWorkspaceUnread.Read, rows.getValue("read").unreadState)
        listOf("legacy", "null", "negative", "malformed", "fraction").forEach { id ->
            val state = rows.getValue(id).unreadState
            assertNull(id, state.count)
            assertEquals(id, 1L, state.badgeCount)
            assertEquals(id, "Unread", state.accessibilityLabel)
        }
        assertEquals(17L, rows.getValue("exact").unreadState.badgeCount)
        assertEquals("17 unread", rows.getValue("exact").unreadState.accessibilityLabel)
        assertEquals(1L, rows.getValue("zero").unreadState.badgeCount)
        assertEquals(3_000_000_000L, rows.getValue("large").unreadState.badgeCount)
    }

    @Test fun collapsedCountsStayMacLocalAndUnknownContributorsDoNotBecomeZero() {
        fun source(mac: String, count: Int?) = NativeFeedSource(NativeCredentialStore.PairedMac(mac, mac, mac),
            workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
              {"id":"anchor","group_id":"g","has_unread":true,"unread_count":2},
              {"id":"child","group_id":"g","has_unread":true,"unread_count":${count ?: "null"}}
            ]}""")), groups = listOf(NativeGroup("g", "Group", true, false, "anchor")))
        val exact = source("first", 3)
        val unknown = source("second", null)
        val entries = workspaceEntries(listOf(exact, unknown), emptySet(), false, false, emptyMap())
            .filterIsInstance<WorkspaceListEntry.Header>()
        assertEquals(listOf(5L, null), entries.map { it.unread.count })
        assertEquals(listOf("5 unread", "Unread"), entries.map { it.unread.accessibilityLabel })
        val expanded = workspaceHierarchy(unknown, mapOf(entries.last().key to false))
        assertEquals(2L, (expanded.first() as WorkspaceListEntry.Header).unread.count)
        assertNull((expanded[1] as WorkspaceListEntry.Workspace).workspace.unreadState.count)
    }

    @Test fun overflowingRemoteCountsRemainUnreadWithoutCrashingOrWrappingNegative() {
        val state = NativeWorkspaceUnread(true, Long.MAX_VALUE).merging(NativeWorkspaceUnread(true, 1))
        assertTrue(state.isUnread)
        assertNull(state.count)
        assertEquals(1L, state.badgeCount)
    }
}
