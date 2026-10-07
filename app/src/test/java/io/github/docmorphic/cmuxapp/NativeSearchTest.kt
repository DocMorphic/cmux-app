package io.github.docmorphic.cmuxapp

import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSearchTest {
    @Test fun agentFeedSearchKeepsOtherQueriesAndRejectsPreviousKeyboardGeneration() {
        val first = NativeSearchState(workspaceQuery = "workspace", notificationQuery = "alert").begin(NativeSearchScope.FEED)
        val edited = first.edit("  permission  ", NativeSearchScope.FEED, first.generation)
        val switched = edited.begin(NativeSearchScope.NOTIFICATIONS)
        assertEquals("permission", switched.feedQuery)
        assertEquals("workspace", switched.workspaceQuery)
        assertEquals("alert", switched.draft)
        assertEquals(switched, switched.edit("stale", NativeSearchScope.FEED, first.generation))
        assertEquals("", switched.clear(NativeSearchScope.FEED).feedQuery)
        assertEquals("alert", switched.clear(NativeSearchScope.FEED).notificationQuery)
    }

    @Test fun scopesKeepTheirOwnCommittedQueriesAndCancelOnlyCurrentScope() {
        var state = NativeSearchState().begin(NativeSearchScope.WORKSPACES)
        state = state.edit("  project  ", NativeSearchScope.WORKSPACES, state.generation).commit()
        assertEquals("project", state.text(NativeSearchScope.WORKSPACES))
        state = state.begin(NativeSearchScope.NOTIFICATIONS)
        assertEquals("", state.draft)
        state = state.edit("alert", NativeSearchScope.NOTIFICATIONS, state.generation).commit()
        state = state.begin(NativeSearchScope.WORKSPACES)
        assertEquals("project", state.draft)
        state = state.clear(NativeSearchScope.WORKSPACES)
        assertEquals("", state.text(NativeSearchScope.WORKSPACES))
        assertEquals("alert", state.text(NativeSearchScope.NOTIFICATIONS))
    }

    @Test fun staleKeyboardEditsCannotRestoreCancelledOrOtherScopeSearch() {
        val first = NativeSearchState().begin(NativeSearchScope.WORKSPACES)
        val cancelled = first.clear(NativeSearchScope.WORKSPACES)
        assertEquals(cancelled, cancelled.edit("late", NativeSearchScope.WORKSPACES, first.generation))
        val second = cancelled.begin(NativeSearchScope.WORKSPACES)
        assertEquals(second, second.edit("late", NativeSearchScope.WORKSPACES, first.generation))
        val notifications = second.begin(NativeSearchScope.NOTIFICATIONS)
        assertEquals(notifications, notifications.edit("wrong scope", NativeSearchScope.WORKSPACES, notifications.generation))
    }

    @Test fun boundedQueryDoesNotSplitSupplementaryUnicodeAndFitsUpstreamByteBudget() {
        val value = NativeSearchText.boundQuery("😀".repeat(130))
        assertEquals(128, value.codePointCount(0, value.length))
        assertEquals(512, value.toByteArray(Charsets.UTF_8).size)
        assertFalse(value.endsWith("\ud83d"))
        assertEquals("x\ufffdy", NativeSearchText.boundQuery("x\ud83dy"))
        assertEquals("a".repeat(128), NativeSearchText.boundQuery("a".repeat(129)))
    }

    @Test fun notificationSearchFoldsAccentsAndWidthWithoutCrossingFieldBoundaries() {
        val index = NativeSearchIndex(listOf("one" to listOf("Café", "ＲＥＶＩＥＷ", "Editor"),
            "two" to listOf("foo", "bar")), Locale.US, notification = true)
        assertEquals(setOf("one"), index.matches(" CAFE "))
        assertEquals(setOf("one"), index.matches("review"))
        assertEquals(setOf("one"), index.matches("e\u0301ditor"))
        assertTrue(index.matches("foo bar").isEmpty())
        assertEquals(setOf("one", "two"), index.matches("  "))
        val workspace = NativeSearchIndex(listOf("one" to listOf("Café", "Group")), Locale.US)
        assertTrue(workspace.matches("cafe").isEmpty())
        assertEquals(setOf("one"), workspace.matches("cafe\u0301"))
    }

    @Test fun notificationsOnlyRetargetWithProvenanceAndAnUnambiguousLiveOwner() {
        val old = workspace("old", "original")
        val next = workspace("next", "moved")
        val item = NativeNotification("notification", "old", "moved", "Agent", "Body", false)
        assertEquals(old, item.destination(listOf(old, next)))
        val moved = item.copy(retargetsToLiveSurfaceOwner = true)
        assertEquals(next, moved.destination(listOf(old, next)))
        assertNull(moved.destination(listOf(old)))
        assertNull(moved.destination(listOf(next, workspace("ambiguous", "moved"))))
        assertNull(item.copy(workspaceId = "gone").destination(listOf(old, next)))
        assertEquals(next, moved.copy(workspaceId = "gone").destination(listOf(old, next)))
    }

    @Test fun notificationDecoderRetainsSearchMetadataAndDeduplicatesWireIds() {
        val feed = JSONObject("""{"notifications":[
            {"id":"n","workspace_id":"w","surface_id":"s","title":"Agent","subtitle":"Résumé",
             "body":"Ready","workspace_title":"Project","surface_title":"Editor","created_at":1234,
             "is_read":false,"retargets_to_live_surface_owner":true},
            {"id":"n","title":"duplicate"}, {"id":"empty","title":null,"subtitle":null}
        ]}""")
        val items = parseNotifications(feed)
        assertEquals(2, items.size)
        assertEquals("Résumé", items[0].subtitle)
        assertEquals("Editor", items[0].surfaceTitle)
        assertEquals(1234.0, items[0].createdAt!!, 0.0)
        assertTrue(items[0].retargetsToLiveSurfaceOwner)
        assertEquals("Project", items[0].headline(emptyList()))
        assertEquals("Unknown workspace", items[1].headline(emptyList()))
        val index = NativeSearchIndex(items.map { it.id to it.searchFields(emptyList(), "Laptop") }, Locale.US, true)
        assertEquals(setOf("n"), index.matches("resume"))
        assertEquals(setOf("n"), index.matches("editor"))
        assertEquals(setOf("n", "empty"), index.matches("laptop"))
    }

    @Test fun notificationPresentationUsesWorkspaceHeadlineAndNonRedundantSubtitle() {
        val item = NativeNotification("n", "w", null, "CAFÉ", " café ", false,
            subtitle = "Review changes", workspaceTitle = "Café")
        assertEquals(NotificationPresentation("Café", null, "Review changes"), item.presentation(emptyList(), "Laptop", Locale.US))
        assertEquals(NotificationPresentation("Café", "Codex", "Ready"),
            item.copy(title = "Codex", body = "Ready").presentation(emptyList(), "Laptop", Locale.US))
    }

    private fun workspace(id: String, surface: String) = NativeWorkspace(id, id,
        listOf(NativeTerminal(surface, surface)), null, false, null, null, false, emptyList(), null, null, null)
}
