package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Kills only a dedicated emulator app process. Android owns and restores its actual task saved state. */
class WorkspaceProcessRestorationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val manager get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    private val activity = NativeProcessRestoreTestActivity::class.java
    private fun marker(name: String) = File(context.filesDir, "pane-process-$name")
    private fun task() = manager.appTasks.single { it.taskInfo?.baseIntent?.component?.className == activity.name }
    private fun childPid() = manager.runningAppProcesses?.singleOrNull { it.processName == context.packageName + ":restore_test" }?.pid
    private fun waitUntil(test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!test() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Condition timed out", test())
    }
    private fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 20_000)) { "Missing text: $value" }
    private fun description(value: String) = checkNotNull(device.wait(Until.findObject(By.desc(value)), 20_000)) { "Missing description: $value" }
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        retireChild()
        listOf("status", "stopped", "saved").forEach { marker(it).delete() }
        NativeNotificationService.setEnabled(context, false)
        NativeCredentialStore(context, "native_notification_state").clear()
        store = NativeCredentialStore(context); store.clear()
        store.update { it.put("refresh_token", "process-pane-fixture").put("pairing_code", code) }
        store.rememberMac(code, "fixture-mac", "Fixture Mac"); store.taskSession()
        peer = NativeFixturePeer(); listing()
    }
    @After fun cleanup() {
        retireChild()
        if (::peer.isInitialized) { peer.close(); store.clear() }
    }
    private fun retireChild() {
        manager.appTasks.filter { it.taskInfo?.baseIntent?.component?.className == activity.name }.forEach { it.finishAndRemoveTask() }
        childPid()?.let { check(it != Process.myPid()); Process.killProcess(it); waitUntil { childPid() == null } }
    }
    private fun listing(terminals: String = """[{"id":"terminal-1","title":"First shell"},{"id":"terminal-2","title":"Focused shell","is_focused":true}]""", surfaces: String = "[]") {
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Process workspace","terminals":$terminals,"surfaces":$surfaces}]}""")
    }
    private fun start(link: String? = null) {
        context.startActivity(Intent(context, activity).putExtra("fixturePort", peer.port)
            .setData(link?.let(android.net.Uri::parse))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun launch(open: Boolean = true, link: String? = null) {
        start(link)
        val row = text("Process workspace"); if (open) row.click()
    }
    private fun calls(method: String) = peer.requests.filter { it.optString("method") == method }
    private fun killAndRestore(whileDead: () -> Unit = {}) {
        val parent = Process.myPid()
        val pid = checkNotNull(childPid()); assertNotEquals(parent, pid)
        val appTask = task()
        val taskId = checkNotNull(appTask.taskInfo) { "Fixture task disappeared before capture" }.taskId
        device.pressHome()
        waitUntil { marker("stopped").takeIf(File::exists)?.readText() == pid.toString() &&
            marker("saved").takeIf(File::exists)?.readText() == pid.toString() }
        // The callback marker precedes ActivityThread's stop transaction. Wait until
        // system_server actually retains this task's bundle before killing the UI.
        // A fixed delay can race a busy emulator and exercise a cold launch instead.
        val recordHeader = Regex("(?m)^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{[^\\n]+")
        waitUntil {
            val dump = device.executeShellCommand("dumpsys activity activities")
            val header = recordHeader.findAll(dump).singleOrNull {
                it.value.contains(activity.name) && it.value.contains(" t$taskId")
            }
            val record = header?.let { dump.substring(it.range.last + 1).lineSequence()
                .takeWhile { line -> !line.contains("* Hist") }.map(String::trim).toList() }.orEmpty()
            record.firstOrNull { it.startsWith("mHaveState=") }?.startsWith("mHaveState=true ") == true &&
                record.firstOrNull { it.startsWith("state=") }?.startsWith("state=STOPPED ") == true
        }
        Process.killProcess(pid); waitUntil { childPid() == null }
        whileDead()
        assertEquals(parent, Process.myPid())
        appTask.moveToFront()
        waitUntil { marker("status").takeIf(File::exists)?.readLines()?.let { it.size >= 2 && it[0] != pid.toString() && it[1] == "true" } == true }
        assertNotEquals(pid, childPid())
        val restoredTaskId = checkNotNull(appTask.taskInfo) { "Restored fixture task disappeared" }.taskId
        assertEquals(taskId, restoredTaskId)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putInt("parent_process", parent); putInt("killed_ui_process", pid)
            putInt("restored_ui_process", checkNotNull(childPid())); putBoolean("android_restored_bundle", true)
            putInt("restored_task", restoredTaskId)
        })
    }
    @Test fun fullFeedReaderRestoresSamePaintedPassageAfterRealProcessDeath() {
        val preferences = context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
        val key = NativeDisplayPreferences.feedReplacesNotificationsKey
        val had = preferences.contains(key); val previous = preferences.getBoolean(key, true)
        val output = File(context.getExternalFilesDir(null), "feed-reader-process").apply { mkdirs() }
        val body = "# Process reader\n\n" + (1..4000).joinToString("\n") { "Process row $it: body reloaded after process death with **bold** content." }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val rows = JSONArray().put(JSONObject().put("id", "process-reader").put("workstream_id", "reader-turn")
            .put("source", "Claude").put("kind", "stop").put("status", "telemetry").put("created_at", 198).put("updated_at", 198)
            .put("title", "Process report").put("reason", "Read this report").put("full_text_truncated", true)
            .put("workspace_id", "workspace-1").put("surface_id", "terminal-1"))
        peer.agentFeedResponse = { method, params -> when (method) {
            "feed.list" -> JSONObject().put("revision", 1).put("items", rows)
            "feed.text" -> {
                assertEquals("process-reader", params.getString("item_id"))
                val start = params.optInt("offset"); val end = (start + 16_384).coerceAtMost(bytes.size)
                JSONObject().put("text", bytes.copyOfRange(start, end).toString(Charsets.UTF_8)).put("version", 1).apply {
                    if (end < bytes.size) put("next_offset", end)
                }
            }
            else -> JSONObject()
        } }
        fun reader(clazz: String): androidx.test.uiautomator.UiObject2 {
            // The list exists before its asynchronous paragraph binding completes.
            checkNotNull(device.wait(Until.findObject(By.textContains("Process row 1:")), 20_000)) { "Native body not ready" }
            return checkNotNull(device.wait(Until.findObject(By.clazz(clazz)), 20_000)) { "Reader not restored: $clazz" }
        }
        fun capture(name: String, clazz: String): Pair<android.graphics.Bitmap, android.graphics.Rect> {
            val view = reader(clazz); device.waitForIdle()
            // Native scrollbar fade is transient paint, independent of reading state.
            // Wait beyond its default delay/fade before comparing the entire viewport.
            SystemClock.sleep(1_000)
            val nativeBounds = view.visibleBounds
            // Compare the text viewport, excluding the native right padding/
            // scrollbar strip. Full unmodified screenshots remain the evidence.
            val bounds = android.graphics.Rect(nativeBounds).apply { right -= (20 * context.resources.displayMetrics.density).toInt() }
            File(output, "$name-bounds.json").writeText(JSONObject().put("nativeBounds", nativeBounds.toShortString())
                .put("textComparisonBounds", bounds.toShortString()).put("excludedRightDp", 20).toString(2))
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            device.dumpWindowHierarchy(File(output, "$name.xml"))
            return image to bounds
        }
        val measurements = JSONArray()
        fun restorePassage(mode: String, clazz: String) {
            val (initial, initialBounds) = capture("$mode-at-start", clazz)
            repeat(2) {
                val bounds = reader(clazz).visibleBounds
                assertTrue("Actual native swipe injection", device.swipe(bounds.centerX(), bounds.bottom - bounds.height() / 8,
                    bounds.centerX(), bounds.top + bounds.height() / 4, 40))
                device.waitForIdle()
            }
            val (before, bounds) = capture("$mode-before", clazz)
            try {
                assertEquals(initialBounds, bounds)
                val original = android.graphics.Bitmap.createBitmap(initial, bounds.left, bounds.top, bounds.width(), bounds.height())
                val scrolled = android.graphics.Bitmap.createBitmap(before, bounds.left, bounds.top, bounds.width(), bounds.height())
                try { assertFalse("The real gestures must change the painted passage", original.sameAs(scrolled)) }
                finally { original.recycle(); scrolled.recycle() }
            } finally { initial.recycle() }
            val reads = calls("feed.text").size
            val oldPid = checkNotNull(childPid())
            try {
                killAndRestore()
                text("Full text")
                val (after, restoredBounds) = capture("$mode-restored", clazz)
                try {
                    assertEquals("Restored native reader uses the same text viewport", bounds, restoredBounds)
                    assertTrue("New UI process reloads the message through fresh RPCs", calls("feed.text").size > reads)
                    val original = android.graphics.Bitmap.createBitmap(before, bounds.left, bounds.top, bounds.width(), bounds.height())
                    val restored = android.graphics.Bitmap.createBitmap(after, bounds.left, bounds.top, bounds.width(), bounds.height())
                    try { assertTrue("Same actually painted $mode text viewport after process death", original.sameAs(restored)) }
                    finally { original.recycle(); restored.recycle() }
                    measurements.put(JSONObject().put("mode", mode).put("killedPid", oldPid).put("restoredPid", childPid())
                        .put("bounds", bounds.toShortString()).put("textViewportPixelsIdentical", true).put("textReads", calls("feed.text").size))
                } finally { after.recycle() }
            } finally { before.recycle() }
        }
        try {
            preferences.edit().putBoolean(key, true).commit()
            launch(open = false)
            checkNotNull(device.wait(Until.findObject(By.textStartsWith("Feed")), 20_000)).click()
            description("See more").click(); text("Full text")
            restorePassage("formatted", "android.widget.ListView")
            assertFalse(device.hasObject(By.text("Source"))); assertFalse(device.hasObject(By.text("Formatted")))
            restorePassage("native-second-position", "android.widget.ListView")
            File(output, "result.json").writeText(JSONObject().put("bodyCharacters", body.length).put("processRestores", measurements)
                .put("scope", "Actual MainActivity subclass task restored by Android after isolated emulator UI process death, local socket and synthetic credentials. Pixel-identical native text comparison viewports at two successive reading positions excluding the right 20-dp native padding/scrollbar strip; full screenshots retained. No selection, real account/Iroh/Mac/Pixel, TalkBack or full parity acceptance.").toString(2))
        } catch (error: Throwable) {
            device.takeScreenshot(File(output, "failure.png")); device.dumpWindowHierarchy(File(output, "failure.xml")); throw error
        } finally {
            val edit = preferences.edit(); if (had) edit.putBoolean(key, previous) else edit.remove(key); edit.commit()
        }
    }

    private val uncertainCreation = "Creation may have completed. Check your workspaces before trying again."

    private fun pendingWorkspaceCreate(gate: java.util.concurrent.CountDownLatch) {
        peer.workspaceCreationResponse = {
            val created = JSONObject("""{"id":"interrupted-workspace","title":"Created after interruption",
                "terminals":[{"id":"interrupted-terminal","title":"Recovered shell"}]}""")
            val updated = JSONObject(peer.customWorkspaceListing.toString())
            updated.getJSONArray("workspaces").put(created); peer.customWorkspaceListing = updated
            check(gate.await(60, java.util.concurrent.TimeUnit.SECONDS)) { "Creation response was never released" }
            JSONObject().put("created_workspace_id", "interrupted-workspace")
                .put("created_terminal_id", "interrupted-terminal").put("workspaces", JSONArray().put(created))
        }
        launch(open = false)
        val create = checkNotNull(device.wait(Until.findObject(By.desc("New Workspace").enabled(true)), 20_000))
        create.click()
        waitUntil { calls("workspace.create").size == 1 && peer.customWorkspaceListing!!.getJSONArray("workspaces").length() == 2 }
    }

    @Test fun unacknowledgedWorkspaceCreateSurvivesProcessLossWithoutAnotherMutation() {
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            pendingWorkspaceCreate(gate)
            killAndRestore { gate.countDown() }
            text(uncertainCreation); text("Created after interruption")
            assertFalse("An unacknowledged result must not invent a destination", device.hasObject(By.text("Recovered shell ▾")))
            assertTrue(calls("mobile.terminal.replay").isEmpty())
            text("Created after interruption").click(); text("Recovered shell ▾")
            waitUntil { calls("mobile.terminal.replay").isNotEmpty() }
            text(uncertainCreation) // Successful replay must not erase creation uncertainty.
            val captures = File(context.getExternalFilesDir(null), "native-creation-process").apply { mkdirs() }
            assertTrue(device.takeScreenshot(File(captures, "recovered-creation-notice.png")))
            text("Dismiss").click(); assertTrue(device.wait(Until.gone(By.text(uncertainCreation)), 5000))
            killAndRestore(); text("Recovered shell ▾")
            assertFalse("Dismissal must survive another process restoration", device.hasObject(By.text(uncertainCreation)))
            assertEquals(1, calls("workspace.create").size)
            assertTrue(calls("terminal.create").isEmpty())
        } finally { gate.countDown() }
    }

    @Test fun unacknowledgedTerminalCreateRestoresItsWorkspaceWithoutResending() {
        val gate = java.util.concurrent.CountDownLatch(1)
        peer.terminalCreationResponse = { params ->
            assertEquals("workspace-1", params.getString("workspace_id"))
            listing(terminals = """[{"id":"interrupted-terminal","title":"Recovered shell"}]""")
            check(gate.await(60, java.util.concurrent.TimeUnit.SECONDS)) { "Creation response was never released" }
            JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "interrupted-terminal")
        }
        try {
            listing(terminals = "[]"); launch(); text("Waiting for workspace panes…")
            text("New terminal").click()
            waitUntil { calls("terminal.create").size == 1 && peer.customWorkspaceListing!!.getJSONArray("workspaces")
                .getJSONObject(0).getJSONArray("terminals").length() == 1 }
            killAndRestore { gate.countDown() }
            text(uncertainCreation); text("Recovered shell ▾")
            assertEquals(1, calls("terminal.create").size)
            assertTrue(calls("workspace.create").isEmpty())
            description("Back to workspaces").click(); text("Process workspace")
        } finally { gate.countDown() }
    }

    @Test fun changedLoginDiscardsAnInterruptedCreationWaiterAfterProcessDeath() {
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            pendingWorkspaceCreate(gate)
            killAndRestore {
                gate.countDown()
                store.clear(); store.update { it.put("refresh_token", "replacement-creation-login").put("pairing_code", code) }
                store.rememberMac(code, "fixture-mac", "Fixture Mac"); store.taskSession()
            }
            text("Process workspace"); text("Created after interruption")
            assertFalse(device.hasObject(By.text(uncertainCreation)))
            assertFalse(device.hasObject(By.text("Recovered shell ▾")))
            assertEquals(1, calls("workspace.create").size)
            assertTrue(calls("terminal.create").isEmpty())
        } finally { gate.countDown() }
    }

    private fun openCustomizationDraft() {
        peer.workspaceMetadataSupported = true
        peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0)
            .put("description", "Original description").put("window_id", "fixture-window")
        launch(open = false)
        text("Process workspace").longClick(); text("Customize").click()
        text("Process workspace").text = "Restored custom name"
        text("Original description").text = "Unsent description"
        description("Pinned").click(); description("Use Workspace Color").click()
        text("#007AFF").text = "#12ABEF"
        device.waitForIdle()
    }
    @Test fun customizationDraftRestoresInANewProcessWithoutAutomaticWrites() {
        peer.workspaceActionResponse = { params ->
            val listing = JSONObject(peer.customWorkspaceListing.toString())
            val row = listing.getJSONArray("workspaces").getJSONObject(0)
            assertEquals("workspace-1", params.getString("workspace_id"))
            when (params.getString("action")) {
                "rename" -> row.put("title", params.getString("title"))
                "set_description" -> row.put("description", params.getString("description"))
                "set_color" -> row.put("custom_color", params.getString("color"))
                "pin" -> row.put("is_pinned", true)
                else -> error("Unexpected action")
            }
            peer.customWorkspaceListing = listing; JSONObject()
        }
        openCustomizationDraft()
        killAndRestore()
        text("Restored custom name"); text("Unsent description"); text("#12ABEF")
        assertTrue("Restoration must not submit unfinished edits", calls("workspace.action").isEmpty())
        text("Save").click()
        assertTrue(device.wait(Until.gone(By.text("Customize Workspace")), 10_000))
        text("Restored custom name")
        assertEquals(listOf("rename", "set_description", "set_color", "pin"),
            calls("workspace.action").map { it.getJSONObject("params").getString("action") })
    }
    @Test fun restoredCustomizationDetectsChangesMadeOnMacWhileProcessWasDead() {
        openCustomizationDraft()
        killAndRestore {
            val listing = JSONObject(peer.customWorkspaceListing.toString())
            listing.getJSONArray("workspaces").getJSONObject(0).put("title", "Mac renamed")
            peer.customWorkspaceListing = listing
        }
        text("Restored custom name"); text("Unsent description")
        assertTrue(calls("workspace.action").isEmpty())
        text("Save").click(); text("Couldn't save workspace")
        text("This workspace changed on your Mac. Review the latest values and save again.")
        assertTrue("Saved baseline must prevent overwriting the Mac's newer name", calls("workspace.action").isEmpty())
        text("OK").click(); text("Mac renamed"); text("Original description"); text("Cancel").click()
    }
    @Test fun replacementLoginDiscardsRestoredCustomizationDraft() {
        openCustomizationDraft()
        killAndRestore {
            store.clear(); store.update { it.put("refresh_token", "replacement-process-login").put("pairing_code", code) }
            store.rememberMac(code, "fixture-mac", "Fixture Mac"); store.taskSession()
        }
        text("Process workspace")
        assertFalse("Previous login's editor must not be restored", device.hasObject(By.text("Customize Workspace")))
        assertFalse(device.hasObject(By.text("Unsent description")))
        assertTrue(calls("workspace.action").isEmpty())
        text("Process workspace").longClick(); text("Customize").click()
        text("Original description")
        assertFalse("Opening a new editor must not consume the old saved draft", device.hasObject(By.text("Unsent description")))
        text("Cancel").click()
    }
    @Test fun explicitTerminalRestoresFromAndroidTaskStateInANewProcess() {
        launch(); text("Focused shell ▾"); text("First shell").click(); text("First shell ▾")
        val before = calls("mobile.terminal.replay").size
        killAndRestore(); text("First shell ▾")
        waitUntil { calls("mobile.terminal.replay").size > before }
        assertEquals("terminal-1", calls("mobile.terminal.replay").last().getJSONObject("params").getString("surface_id"))
        assertTrue(calls("terminal.create").isEmpty()); assertTrue(calls("workspace.create").isEmpty())
    }
    @Test fun macPanelRestoresWithoutDefaultTerminalTraffic() {
        listing(surfaces = """[{"surface_id":"project","kind":"project","title":"Process panel","is_focused":true}]""")
        launch(); text("Process panel ▾"); killAndRestore(); text("Process panel ▾")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun browserRestoresThroughFreshDiscovery() {
        listing(surfaces = """[{"surface_id":"browser","kind":"browser","title":"Process browser","is_focused":true}]""")
        val panel = JSONObject().put("panel_id", "browser").put("workspace_id", "workspace-1").put("title", "Process browser")
            .put("page_width", 800).put("page_height", 600).put("can_go_back", false).put("can_go_forward", false).put("is_loading", false)
        peer.browserCreationSupported = true
        peer.browserResponse = { method, _ -> if (method == "mobile.browser.list") JSONObject().put("panels", JSONArray().put(panel)) else panel }
        launch(); waitUntil { calls("mobile.browser.stream.start").isNotEmpty() }
        val before = calls("mobile.browser.stream.start").size
        val discoveries = calls("mobile.browser.list").size
        // The Mac now focuses a terminal and omits the browser from wire surfaces.
        // Only the saved browser intent and separate discovery can restore this panel.
        killAndRestore { listing() }; waitUntil { calls("mobile.browser.stream.start").size > before }
        assertTrue(calls("mobile.browser.list").size > discoveries)
        assertEquals(setOf("browser"), calls("mobile.browser.stream.start").map { it.getJSONObject("params").getString("panel_id") }.toSet())
        assertTrue(calls("mobile.browser.create").isEmpty())
    }
    private fun openChanges() {
        peer.workspaceChangesSupported = true
        val previous = peer.changesResponse
        peer.changesResponse = { method, params ->
            if (method.endsWith(".summary")) JSONObject("""{"summaries":[{"workspace_id":"workspace-1","is_repo":true,"files_changed":2,"additions":42,"deletions":7}]}""")
            else previous?.invoke(method, params) ?: JSONObject()
        }
        launch(open = false); description("Changes: 2 files, +42, −7").click()
    }
    @Test fun changesRestoresAndClosesToWorkspaceList() {
        openChanges()
        description("Close changes"); killAndRestore(); description("Close changes").click(); text("Process workspace")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    private fun changesFiles(vararg paths: String) {
        peer.changesResponse = { method, params ->
            if (method.endsWith(".files")) JSONObject().put("workspace_id", "workspace-1").put("repo_root", "/fixture")
                .put("files", JSONArray(paths.map { JSONObject().put("path", it).put("status", "modified") }))
            else JSONObject().put("path", params.getString("path"))
                .put("unified_diff", "@@ -1 +1 @@\n-old\n+Restored diff ${params.getString("path")}\n")
        }
    }
    @Test fun changesDetailAndCollapsedFoldersRestoreFromRealTaskState() {
        changesFiles("README.md", "src/App.kt")
        openChanges()
        description("Collapse folder src").click(); description("Open diff README.md").click()
        text("Restored diff README.md")
        val before = calls("mobile.workspace.changes.file_diff").size
        killAndRestore { changesFiles("A.md", "README.md", "src/App.kt") }
        text("Restored diff README.md"); text("2 of 3")
        assertTrue(calls("mobile.workspace.changes.file_diff").size > before)
        text("‹ Changes").click(); description("Expand folder src")
        description("Close changes").click(); text("Process workspace")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun vanishedChangedFileDoesNotRestoreANeighborOrFetchItsOldPath() {
        changesFiles("README.md", "src/App.kt")
        openChanges()
        description("Open diff README.md").click(); text("Restored diff README.md")
        val before = calls("mobile.workspace.changes.file_diff").count { it.getJSONObject("params").getString("path") == "README.md" }
        killAndRestore { changesFiles("src/App.kt") }
        text("File no longer changed")
        assertEquals(before, calls("mobile.workspace.changes.file_diff").count { it.getJSONObject("params").getString("path") == "README.md" })
        text("‹ Changes").click(); description("Open diff src/App.kt").click(); text("Restored diff src/App.kt")
    }
    @Test fun filesOverlayAndCurrentPreviewRestoreThroughFreshSessionScan() {
        peer.artifactsSupported = true
        peer.artifactResponse = { method, params ->
            val body = "Process file ${params.optString("path")}"
            when {
                method.endsWith("scan") -> JSONObject().put("session_id", "files-session").put("gallery_row_total", 2)
                method.endsWith("gallery") -> JSONObject().put("session_id", "files-session").put("referenced", JSONArray(
                    listOf("/a.txt", "/b.txt").map { JSONObject().put("path", it).put("kind", "text") }))
                method.endsWith("stat") -> JSONObject().put("exists", true).put("is_directory", false).put("kind", "text")
                    .put("size", body.length).put("mime_type", "text/plain")
                else -> JSONObject().put("offset", 0).put("total_size", body.length).put("eof", true)
                    .put("data_b64", java.util.Base64.getEncoder().encodeToString(body.toByteArray()))
            }
        }
        launch(); description("Open files in view").click(); description("Open file /a.txt").click()
        text("Process file /a.txt"); text("Next").click(); text("Process file /b.txt")
        val before = calls("mobile.chat.artifact.fetch").size
        killAndRestore()
        text("Process file /b.txt"); text("2 of 2")
        assertTrue(calls("mobile.chat.artifact.fetch").size > before)
        assertTrue(calls("mobile.chat.artifact.fetch").all { it.getJSONObject("params").getString("session_id") == "files-session" })
        text("‹ Back").click(); description("Open file /a.txt"); text("Done").click(); text("Focused shell ▾")
    }
    @Test fun localBrowserRestoresWithoutAttemptingRemoteCreation() {
        launch(); description("Choose terminal or pane").click(); text("New Browser").click()
        description("Close Browser"); killAndRestore(); description("Close Browser")
        assertTrue(calls("mobile.browser.create").isEmpty())
    }
    @Test fun emptyWorkspaceRestoresThenAcceptsItsFirstTerminal() {
        listing(terminals = "[]"); launch(); text("Waiting for workspace panes…")
        killAndRestore(); text("Waiting for workspace panes…")
        listing(); peer.pushTerminalEvent("workspace.updated", JSONObject()); text("Focused shell ▾")
    }
    @Test fun simulatorRestoresItsSavedPanelIdentity() {
        peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0).put("simulators", JSONArray().put(
            JSONObject().put("panel_id", "11111111-1111-4111-8111-111111111111").put("workspace_id", "workspace-1")
                .put("title", "Process Simulator").put("status", "ready").put("is_ready", true)
                .put("supports_touch", true).put("supports_keyboard", true).put("supports_hardware_buttons", true).put("supports_rotation", true)))
        launch(); text("Process Simulator ▾"); killAndRestore(); text("Process Simulator ▾")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun replacementLoginRejectsTheSavedDestination() {
        launch(); text("Focused shell ▾")
        killAndRestore {
            store.clear(); store.update { it.put("refresh_token", "replacement-process-login").put("pairing_code", code) }
            store.rememberMac(code, "fixture-mac", "Fixture Mac"); store.taskSession()
        }
        text("Process workspace"); assertFalse(device.hasObject(By.text("Focused shell ▾")))
    }
    @Test fun backCancelsRestoreBeforeTheReconnectInventoryArrives() {
        launch(); text("Focused shell ▾")
        val before = calls("mobile.terminal.replay").size
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            killAndRestore {
                peer.workspaceListingResponse = {
                    gate.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    JSONObject(peer.customWorkspaceListing.toString())
                }
            }
            text("Restoring workspace…")
            val screenshot = File(context.getExternalFilesDir(null), "screenshots/process-restoring.png")
            screenshot.parentFile!!.mkdirs(); assertTrue(device.takeScreenshot(screenshot))
            description("Back to workspaces").click()
            gate.countDown(); text("Process workspace")
            assertFalse(device.hasObject(By.text("Focused shell ▾")))
            assertEquals(before, calls("mobile.terminal.replay").size)
        } finally { gate.countDown() }
    }
    @Test fun confirmedRemovedWorkspaceDoesNotAttachItsOldTerminal() {
        launch(); text("Focused shell ▾")
        val before = calls("mobile.terminal.replay").size
        killAndRestore { peer.customWorkspaceListing = JSONObject().put("workspaces", JSONArray()) }
        text("This workspace is no longer available on Fixture Mac.")
        assertEquals(before, calls("mobile.terminal.replay").size)
    }
    @Test fun startupDeadlineExpiresWhileTheProcessIsDeadWithoutRepeatingCreate() {
        listing(terminals = "[]")
        peer.terminalCreationResponse = {
            listing(terminals = """[{"id":"new-terminal","title":"New shell","is_ready":false},{"id":"terminal-1","title":"Ready shell"}]""")
            JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal")
        }
        launch(); text("Waiting for workspace panes…"); text("New terminal").click(); text("Starting terminal…")
        killAndRestore { SystemClock.sleep(31_000) }
        text(NativeTerminalStartup.TIMEOUT_MESSAGE); text("Ready shell ▾")
        assertEquals(1, calls("terminal.create").size)
        assertTrue(calls("mobile.terminal.replay").none { it.getJSONObject("params").optString("surface_id") == "new-terminal" })
    }
    @Test fun createdTerminalRestoresBeforeItsDeadlineThenBecomesReady() {
        listing(terminals = "[]")
        peer.terminalCreationResponse = {
            listing(terminals = """[{"id":"new-terminal","title":"New shell","is_ready":false},{"id":"terminal-1","title":"Ready shell"}]""")
            JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal")
        }
        launch(); text("Waiting for workspace panes…"); text("New terminal").click(); text("Starting terminal…")
        killAndRestore(); text("Starting terminal…"); text("New shell ▾")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
        listing(terminals = """[{"id":"new-terminal","title":"New shell","is_ready":true},{"id":"terminal-1","title":"Ready shell"}]""")
        peer.pushTerminalEvent("workspace.updated", JSONObject())
        waitUntil { calls("mobile.terminal.replay").any { it.getJSONObject("params").optString("surface_id") == "new-terminal" } }
        assertEquals(1, calls("terminal.create").size)
    }

    @Test fun consumedOriginalPairingLinkDoesNotCancelRestoredTerminal() {
        launch(link = code); text("Focused shell ▾"); text("First shell").click(); text("First shell ▾")
        killAndRestore(); text("First shell ▾")
        assertFalse(device.hasObject(By.text("Connect to this Mac?")))
        assertTrue(calls("terminal.create").isEmpty())
    }
    @Test fun pendingPairingConfirmationSurvivesDeathAndDismissalStaysConsumed() {
        start(code.replace(".1:", ".2:")); text("Connect to this Mac?")
        killAndRestore(); text("Connect to this Mac?"); text("Cancel").click()
        text("Process workspace").click(); text("Focused shell ▾")
        killAndRestore(); text("Focused shell ▾")
        assertFalse(device.hasObject(By.text("Connect to this Mac?")))
    }
    @Test fun newLinkReplacesOldConfirmationAndSameLinkCanBeOpenedAgain() {
        start(code.replace(".1:", ".2:")); text("Connect to this Mac?"); text("100.64.0.2:58465")
        val before = childPid()
        start(code.replace(".1:", ".3:")); text("100.64.0.3:58465"); assertEquals(before, childPid())
        text("Cancel").click(); text("Process workspace")
        start(code.replace(".1:", ".3:")); text("100.64.0.3:58465"); text("Cancel").click()
        assertEquals(before, childPid())
        start(code.replace(".1:", ".2:")); text("100.64.0.2:58465")
        start(code); waitUntil { !device.hasObject(By.text("Connect to this Mac?")) }
        assertEquals(before, childPid())
    }
    @Test fun signedOutPairingSurvivesProcessDeathAndLauncherReentry() {
        store.clear(); start(code.replace(".1:", ".2:")); text("Sign in to cmux")
        start(); text("Sign in to cmux")
        killAndRestore {
            store.update { it.put("refresh_token", "entry-fixture").put("pairing_code", code) }
            store.rememberMac(code, "fixture-mac", "Fixture Mac"); store.taskSession()
        }
        text("Connect to this Mac?"); text("100.64.0.2:58465"); text("Cancel").click()
    }
    @Test fun newNotificationSupersedesPairingThenStaysConsumedAfterDeath() {
        lateinit var route: NotificationDestination
        NativeCredentialStore(context, "native_notification_state").update {
            route = NativeNotificationLedger(it).stage(store.pairedMacs().single().origin,
                NativeNotification("entry-notification", "workspace-1", "terminal-1", "Ready", "Fixture", false))
        }
        start(code.replace(".1:", ".2:")); text("Connect to this Mac?")
        start(NativeNotificationDelivery.launchIntent(context, route.routeId).dataString)
        text("First shell ▾"); assertFalse(device.hasObject(By.text("Connect to this Mac?")))
        text("Focused shell").click(); text("Focused shell ▾")
        killAndRestore(); text("Focused shell ▾")
        assertFalse(device.hasObject(By.text("Connect to this Mac?")))
    }
}
