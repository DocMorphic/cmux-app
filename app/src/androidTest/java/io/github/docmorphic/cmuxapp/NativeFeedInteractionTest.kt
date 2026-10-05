package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeFeedInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun revealedNotificationReadIntentSurvivesRefreshAndRevokedActionIsInert() {
        var value by mutableStateOf(NativeFeedRowValue("notice", NotificationPresentation("Build", "Agent", "Finished"),
            "Mac", NativeFeedAvailability.CONNECTED, false, null))
        var readable by mutableStateOf(true)
        val reads = mutableListOf<Boolean>()
        compose.setContent { CmuxTheme { Surface { Box(Modifier.width(320.dp)) {
            NativeFeedRow(value, NativeFeedRowContext(), 0L, canRead = readable, onOpen = {}, onRead = { reads += it })
        } } } }
        compose.onNodeWithTag("workspace.swipe:notice").performTouchInput {
            swipe(start = center.copy(x = 5f), end = center.copy(x = width * .45f), durationMillis = 400)
        }
        compose.runOnIdle { value = value.copy(isRead = true) }
        compose.onNodeWithText("Mark as Read").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(true), reads) }
        val cached = compose.onNodeWithTag("feed.row:notice").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Mark as Unread" }
        compose.runOnIdle { readable = false }
        compose.waitForIdle()
        compose.runOnUiThread { assertFalse(cached.action()) }
        compose.runOnIdle { assertEquals(listOf(true), reads) }
    }

    @Test fun mainNotificationFeedHoldsMembershipAndRetiresRemovedOpenCallbacks() {
        val source = NativeFeedSource(NativeCredentialStore.PairedMac("fixture", "fixture", "Mac"), availability = NativeFeedAvailability.CONNECTED)
        fun entry(id: String) = NativeFeedEntry(source, NativeNotification(id, "workspace", "surface", id, "Preview $id", false))
        val first = entry("First"); val second = entry("Second")
        var entries by mutableStateOf(listOf(first, second))
        var opened = 0
        compose.setContent { CmuxTheme { Surface { Box(Modifier.width(320.dp).height(500.dp)) {
            val projection = NativeFeedProjection(listOf(NativeFeedDay(null, entries.map { NativeFeedGroup(it.id, listOf(it)) })))
            NativeNotificationFeedView(projection, listOf(source), false, false, false, 0L, Locale.US,
                onOpen = { opened++ }, onRead = { _, _ -> }, onToggle = {}, onMore = {}, onRefresh = {})
        } } } }
        val firstTag = "feed.row:${first.id}"; val secondTag = "feed.row:${second.id}"
        val cached = compose.onNodeWithTag(firstTag).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val before = compose.onNodeWithTag(secondTag).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("workspace.swipe:${second.id}").performTouchInput {
            swipe(start = center.copy(x = 5f), end = center.copy(x = width * .45f), durationMillis = 400)
        }
        val arrived = entry("Arrived")
        compose.runOnIdle { entries = listOf(arrived, second) }
        compose.onNodeWithTag("feed.row:${arrived.id}").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag(secondTag).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnUiThread { cached() }
        compose.runOnIdle { assertEquals(0, opened) }
        Espresso.pressBack()
        compose.onNodeWithTag("feed.row:${arrived.id}").assertIsDisplayed()
        compose.onNodeWithTag(firstTag).assertDoesNotExist()
        compose.runOnUiThread { cached() }
        compose.runOnIdle { assertEquals(0, opened) }
    }
}
