package io.github.docmorphic.cmuxapp

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real MainActivity checklist controls against only an owned physical Mac workspace. */
@OptIn(ExperimentalTestApi::class)
class LiveNativeTodoUiCheck {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test fun checklistControlsAndReopenMatchAuthoritativeMac() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("cmux_live_todo_ui") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk"))
        val context = instrumentation.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the physical device" }
        val receipt = File(context.filesDir, "live-todo-ui-fixture.json")
        check(!receipt.exists()) { "Inspect the previous Todo UI fixture receipt before rerunning" }
        val handle = NativeAppConnections.acquire(context)
        val connections = handle.connections
        val probe = Any()
        connections.setProbeActive(probe, true)
        val polling = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var client: MobileRpcClient? = null
        var owned: NativeWorkspace? = null
        var activity: MainActivity? = null
        var closed = false
        var stage = "existing account and saved nightly host"
        var failure: Throwable? = null
        try {
            val login = checkNotNull(connections.store.taskSession())
            val previous = connections.store.pairedMacs()
            val (mac, fixture, todoTitle) = runBlocking { withTimeout(60_000) {
                check(connections.account.isSignedIn())
                val team = checkNotNull(connections.teams.refresh().scope)
                val mac = previous.single { it.instanceTag == "nightly" && connections.connector.allowsSaved(it) &&
                    PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh }
                val active = connections.connector.connectSaved(mac, connections.account)
                client = active
                val status = active.hostStatus()
                mac.requireMatchingHost(status)
                val caps = status.getJSONArray("capabilities")
                check((0 until caps.length()).any { caps.getString(it) == "todo.v1" })
                val existing = parseAuthoritativeWorkspaces(active.workspaces()).map { it.id }.toSet()
                stage = "create owned workspace and checklist"
                val title = "Android Todo UI check " + UUID.randomUUID().toString().take(8)
                val created = TaskCreationResult.parse(active.request("workspace.create",
                    JSONObject().put("title", title), timeoutMillis = 30_000)).created
                check(created.id !in existing)
                owned = created
                receipt.writeText(JSONObject().put("id", created.id).put("windowId", created.windowId)
                    .put("title", title).put("build", "nightly").put("deviceId", mac.deviceId)
                    .put("accountUserId", team.userId).put("accountTeamId", team.teamId).toString())
                check(created.title == title)
                active.request("mobile.todo.open", JSONObject().put("workspace_id", created.id).put("focus", false))
                val workspace = parseAuthoritativeWorkspaces(active.workspaces()).single { it.id == created.id }
                val surface = workspace.macSurfaces.single { it.kind == "todo" }
                check(checkNotNull(TodoSnapshot.decode(surface.todoJson)).items.isEmpty())
                Triple(mac, created, surface.displayTitle)
            } }
            suspend fun snapshot(): TodoSnapshot {
                check(connections.store.taskSession() == login && connections.connector.allowsSaved(mac))
                val workspace = parseAuthoritativeWorkspaces(checkNotNull(client).workspaces()).single { it.id == fixture.id }
                return checkNotNull(TodoSnapshot.decode(workspace.macSurfaces.single { it.kind == "todo" }.todoJson))
            }
            fun awaitHost(matches: (TodoSnapshot) -> Boolean): TodoSnapshot {
                val result = polling.async { withTimeout(15_000) {
                    var value = snapshot()
                    while (!matches(value)) { delay(150); value = snapshot() }
                    value
                } }
                try {
                    compose.waitUntil(20_000) { result.isCompleted }
                    return runBlocking { result.await() }
                } finally { result.cancel() }
            }
            fun ready(description: String) = compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes()
                    .any { !it.config.contains(SemanticsProperties.Disabled) }
            }
            fun showChecklist() {
                compose.waitUntil(25_000) { compose.onAllNodes(hasText(" ▾", substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
                if (compose.onAllNodesWithTag("todo-new-item").fetchSemanticsNodes().isEmpty()) {
                    compose.onNode(hasText(" ▾", substring = true) and hasClickAction()).performClick()
                    compose.onNodeWithText(todoTitle).performClick()
                }
                ready("Choose status")
            }
            fun screenshot(name: String) {
                val dir = File(context.getExternalFilesDir(null), "live-todo-ui-check").apply { mkdirs() }
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(dir, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            stage = "open checklist through production MainActivity"
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(mac.code)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            compose.waitUntil(25_000) { compose.onAllNodesWithText(fixture.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(fixture.title).performClick()
            showChecklist()
            stage = "add two items through checklist composer"
            fun add(text: String) {
                compose.onNodeWithTag("todo-new-item").performTextInput(text)
                compose.onNodeWithContentDescription("Add checklist item").assertIsEnabled().performClick()
                ready("Mark $text as in progress")
            }
            add("First Pixel item")
            val a = awaitHost { it.items.size == 1 }.items.single()
            add("Second Pixel item")
            val b = awaitHost { it.items.size == 2 }.items.single { it.id != a.id }
            stage = "edit Unicode and complete through controls"
            compose.onNodeWithText(a.text).performClick()
            val edited = "Edited 你好 👩‍💻"
            compose.onNodeWithTag("todo-edit-${a.id}").performTextReplacement(edited)
            compose.onNodeWithTag("todo-edit-${a.id}").performImeAction()
            ready("Mark $edited as in progress")
            awaitHost { it.items.single { row -> row.id == a.id }.text == edited }
            compose.onNodeWithContentDescription("Mark $edited as in progress").performClick()
            ready("Mark $edited as completed")
            awaitHost { it.items.single { row -> row.id == a.id }.state == TodoItemState.WORKING }
            compose.onNodeWithContentDescription("Mark $edited as completed").performClick()
            ready("Mark $edited as pending")
            awaitHost { it.completed == 1 && it.items.last().id == a.id }
            compose.onNodeWithContentDescription("Mark $edited as pending").performClick()
            ready("Mark $edited as in progress")
            awaitHost { it.completed == 0 && it.items.last().id == a.id }
            stage = "physical touch drag reorders authoritative checklist"
            instrumentation.runOnMainSync {
                (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val bounds = compose.onNodeWithTag("todo-list").fetchSemanticsNode().boundsInRoot
            val from = compose.onNodeWithTag("todo-row-${a.id}").fetchSemanticsNode().boundsInRoot
            val to = compose.onNodeWithTag("todo-row-${b.id}").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("todo-list").performTouchInput {
                val x = width - 12f
                down(Offset(x, from.center.y - bounds.top)); advanceEventTime(700)
                moveTo(Offset(x, to.center.y - bounds.top), delayMillis = 150); up()
            }
            awaitHost { it.items.map { row -> row.id } == listOf(a.id, b.id) }
            ready("Choose status")
            stage = "manual status and workspace reopen"
            compose.onNodeWithContentDescription("Choose status").performClick()
            compose.onNodeWithText("Review").performClick()
            val expected = awaitHost { it.status == TodoStatus.REVIEW && !it.statusHidden }
            ready("Choose status")
            screenshot("todo-checklist.png")
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText(fixture.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(fixture.title).performClick()
            showChecklist()
            compose.onNodeWithText(edited).assertIsDisplayed()
            compose.onNodeWithText(b.text).assertIsDisplayed()
            awaitHost { it == expected }
            screenshot("todo-reopened.png")
            stage = "swipe removes only owned item"
            compose.onNodeWithTag("todo-row-${b.id}").performTouchInput { swipeLeft() }
            awaitHost { it.items.map { row -> row.id } == listOf(a.id) }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(b.text).fetchSemanticsNodes().isEmpty() }
            check(connections.store.taskSession() == login && connections.store.pairedMacs().containsAll(previous))
            println("CMUX_LIVE_TODO_UI_REPORT " + JSONObject().put("addEditStateVerified", true)
                .put("touchReorderVerified", true).put("manualStatusVerified", true)
                .put("reopenVerified", true).put("swipeDeleteVerified", true).put("loginPreserved", true))
        } catch (error: Throwable) {
            failure = AssertionError("Live Todo UI failed at $stage (${error.javaClass.simpleName})")
        } finally {
            try {
                instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
                if (activity != null) compose.waitUntil(20_000) { activity.lifecycle.currentState == Lifecycle.State.DESTROYED }
            } catch (error: Throwable) { if (failure == null) failure = AssertionError("Todo Activity cleanup failed (${error.javaClass.simpleName})") }
            polling.cancel()
            val fixture = owned
            if (fixture != null && client != null) runBlocking { withContext(NonCancellable) {
                try { withTimeout(15_000) {
                    receipt.writeText(JSONObject(receipt.readText()).put("cleanupAttempted", true).toString())
                    checkNotNull(client).closeWorkspace(fixture.id, fixture.windowId)
                    while (parseAuthoritativeWorkspaces(checkNotNull(client).workspaces()).any { it.id == fixture.id }) delay(250)
                    closed = true
                    check(receipt.delete())
                } } catch (_: Exception) { /* Retain exact receipt; never repeat an uncertain close. */ }
            } }
            client?.close()
            connections.setProbeActive(probe, false)
            handle.close()
        }
        println("CMUX_LIVE_TODO_UI_CLEANUP " + JSONObject().put("fixtureClosed", closed))
        failure?.let { throw it }
        check(closed) { "Todo UI fixture cleanup was not verified" }
    }
}
