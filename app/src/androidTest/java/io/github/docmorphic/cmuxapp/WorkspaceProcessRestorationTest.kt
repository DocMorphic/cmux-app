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
    @Test fun changesRestoresAndClosesToWorkspaceList() {
        launch(open = false); description("Actions for Process workspace").click(); text("View changes").click()
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
        launch(open = false); description("Actions for Process workspace").click(); text("View changes").click()
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
        launch(open = false); description("Actions for Process workspace").click(); text("View changes").click()
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
        launch(open = false); description("Actions for Process workspace").click(); text("New browser").click()
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
