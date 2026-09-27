package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.compose.ui.test.ExperimentalTestApi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class NativeLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test fun taskComposerSurvivesBackgroundAndActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer()
        TaskDraftRepository.clearMemory()
        try {
            store.clear(); store.update { it.put("refresh_token", "lifecycle-task-fixture") }
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("New Task") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("New Task").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Task prompt").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Task prompt").performTextInput("Retain this task through rotation 中")
                compose.onNodeWithText("Directory on Mac").performTextReplacement("/rotated-project")
                val repository = TaskDraftRepository.get(context, store.taskSession()!!)
                compose.waitUntil(10_000) { repository.drafts.state.value.values.any { it.directory == "/rotated-project" } }
                val id = repository.drafts.state.value.values.single { it.prompt.isNotEmpty() }.id
                scenario.moveToState(Lifecycle.State.CREATED)
                compose.waitUntil(10_000) {
                    store.load()?.optJSONObject("task_drafts")?.let {
                        TaskDrafts(it).state.value[id]?.directory == "/rotated-project"
                    } == true
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                compose.waitUntil(15_000) { compose.onAllNodes(hasText("Retain this task through rotation 中") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Task prompt").assertTextContains("Retain this task through rotation 中")
                compose.onNodeWithText("Directory on Mac").assertTextContains("/rotated-project")
                assertEquals(setOf(id), repository.drafts.state.value.keys)
                runBlocking { repository.persistNow() }
                assertEquals(setOf(id), TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.keys)
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val directory = java.io.File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                java.io.File(directory, "task-draft-rotation.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            NativeLifecycleTestActivity.connector = null
            peer.close(); TaskDraftRepository.clearMemory(); store.clear()
        }
    }

    @Test fun loadedFeedWindowSurvivesOfflineActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer()
        try {
            store.clear()
            store.update { it.put("refresh_token", "emulator-fixture-only") }
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
            peer.notificationFeed = JSONArray().also { items ->
                repeat(302) { index ->
                    items.put(JSONObject().put("id", "window-$index").put("workspace_id", "workspace-1")
                        .put("surface_id", "terminal-1").put("workspace_title", "Task $index")
                        .put("title", "Agent").put("created_at", System.currentTimeMillis() / 1000.0 - index * 7_201))
                }
            }
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Notifications (302)").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Notifications (302)").performClick()
                val feed = compose.onNode(hasScrollAction() and !hasSetTextAction())
                feed.performScrollToKey("more")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Load more notifications").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Load more notifications").performClick()
                feed.performScrollToKey(store.pairedMacs().single().origin + ":window-301")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Task 301").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Task 301").assertIsDisplayed()
                peer.close()
                scenario.recreate()
                compose.waitForIdle()
                compose.onNode(hasScrollAction() and !hasSetTextAction())
                    .performScrollToKey(store.pairedMacs().single().origin + ":window-301")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Task 301").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Task 301").assertIsDisplayed()
                compose.onNodeWithText("Load more notifications").assertDoesNotExist()
            }
        } finally {
            NativeLifecycleTestActivity.connector = null
            peer.close(); store.clear()
        }
    }

    @Test fun offlineFeedTabQueryAndUnreadFilterSurviveActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer()
        try {
            store.clear()
            store.update { it.put("refresh_token", "emulator-fixture-only") }
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
            peer.notificationFeed = JSONArray().also { items ->
                listOf("Task ready" to false, "Task ready" to false, "Task done" to true).forEachIndexed { index, (title, read) ->
                    val target = if (index < 2) 1 else 2
                    val noon = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
                    items.put(JSONObject().put("id", "n$index").put("workspace_id", "workspace-$target")
                        .put("surface_id", "terminal-$target").put("workspace_title", title)
                        .put("body", if (index == 1) "Earlier progress" else "Latest status")
                        .put("title", "Agent").put("created_at", noon - index * 60)
                        .put("is_read", read))
                }
            }
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Notifications (2)").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Notifications (2)").performClick()
                compose.onNodeWithContentDescription("Computer filter").performClick()
                compose.onNode(hasText("Fixture Mac") and hasAnyAncestor(isPopup())).performClick()
                compose.onNodeWithText("Task done").assertIsDisplayed()
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNode(hasSetTextAction()).performTextInput("Task")
                compose.onNode(hasSetTextAction()).performImeAction()
                compose.onNodeWithContentDescription("Notification filter").performClick()
                compose.onNodeWithText("Unread").performClick()
                compose.onNodeWithText("Task done").assertDoesNotExist()
                compose.onNodeWithContentDescription("Show earlier notifications").performClick()
                compose.onNodeWithText("Earlier progress").assertIsDisplayed()
                peer.close()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Unavailable:", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Task ready").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Task ready").assertIsDisplayed()
                compose.onNodeWithContentDescription("Computer filter").assert(
                    SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Fixture Mac"))
                compose.onNodeWithText("Task done").assertDoesNotExist()
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                compose.onNodeWithContentDescription("Search").assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Search notifications: Task"))
                compose.onNodeWithText("Earlier progress").assertIsDisplayed()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Unavailable:", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodesWithText("127.0.0.1", substring = true).assertCountEquals(0)
                compose.onNodeWithText("Pair a different Mac").assertDoesNotExist()
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val directory = java.io.File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                java.io.File(directory, "notification-rotation-offline.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        } finally {
            NativeLifecycleTestActivity.connector = null
            peer.close(); store.clear()
        }
    }
}
