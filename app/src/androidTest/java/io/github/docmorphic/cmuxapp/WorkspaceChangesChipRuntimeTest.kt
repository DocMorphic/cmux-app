package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WorkspaceChangesChipRuntimeTest {
    @Test fun rowSummaryRetainsSelectedDiffAndScrollAcrossRecreationAndRotationThenRetiresOnSignOut() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val store = NativeCredentialStore(context)
        NativeFixturePeer().use { peer ->
            try {
                store.clear(); store.update { it.put("refresh_token", "changes-chip-fixture")
                    .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
                peer.workspaceChangesSupported = true
                peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Chip workspace","preview":"Latest activity","terminals":[{"id":"terminal-1","title":"Shell"}]}]}""")
                peer.changesResponse = { method, params ->
                    if (method.endsWith(".summary")) {
                        assertEquals("[\"workspace-1\"]", params.getJSONArray("workspace_ids").toString())
                        JSONObject("""{"summaries":[{"workspace_id":"workspace-1","is_repo":true,"files_changed":2,"additions":42,"deletions":7}]}""")
                    } else if (method.endsWith(".file_diff")) {
                        assertEquals("workspace-1", params.getString("workspace_id")); assertEquals("README.md", params.getString("path"))
                        JSONObject().put("path", "README.md").put("unified_diff", "@@ -1,80 +1,80 @@\n" +
                            (1..80).joinToString("") { "-old-$it\n+fresh-$it\n" }).put("truncated", false)
                    } else {
                        assertEquals("workspace-1", params.getString("workspace_id"))
                        JSONObject("""{"workspace_id":"workspace-1","repo_root":"/fixture","files":[{"path":"README.md","status":"modified","additions":42,"deletions":7}]}""")
                    }
                }
                NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
                }
                fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
                ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                    val chip = find(By.desc("Changes: 2 files, +42, −7"))
                    val folder = File(context.getExternalFilesDir(null), "workspace-changes-chip").apply { mkdirs() }
                    device.takeScreenshot(File(folder, "row.png"))
                    chip.click(); find(By.desc("Open diff README.md"))
                    assertTrue(peer.requests.any { it.optString("method") == "mobile.workspace.changes.files" })
                    assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.replay" })
                    find(By.desc("Open diff README.md")).click(); find(By.text("fresh-1"))
                    var original: WorkspaceChangesPresentation? = null
                    scenario.onActivity { original = androidx.lifecycle.ViewModelProvider(it)[NativeFeedSession::class.java].changesSheet }
                    assertNotNull(original)
                    val fileReads = peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" }
                    val diffReads = peer.requests.count { it.optString("method") == "mobile.workspace.changes.file_diff" }
                    device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                        device.displayWidth / 2, device.displayHeight / 3, 25)
                    device.waitForIdle()
                    var visibleLine: String? = null
                    val anchorDeadline = android.os.SystemClock.elapsedRealtime() + 5_000
                    while (visibleLine == null && android.os.SystemClock.elapsedRealtime() < anchorDeadline) {
                        visibleLine = try {
                            device.findObjects(By.text(java.util.regex.Pattern.compile("fresh-[0-9]+")))
                                .firstOrNull { it.visibleBounds.height() > 0 }?.text
                        } catch (_: StaleObjectException) { null }
                        if (visibleLine == null) Thread.sleep(100)
                    }
                    val anchor = checkNotNull(visibleLine) { "Scrolled diff did not expose a stable visible line" }
                    assertNotEquals("fresh-1", anchor)
                    scenario.recreate(); find(By.text(anchor))
                    scenario.onActivity {
                        assertSame(original, androidx.lifecycle.ViewModelProvider(it)[NativeFeedSession::class.java].changesSheet)
                        assertEquals("README.md", original!!.navigation.selected)
                        assertTrue(original!!.store.scrollPositions.getValue("README.md").first > 0)
                        it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                    while (device.displayWidth <= device.displayHeight && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                    assertTrue(device.displayWidth > device.displayHeight)
                    find(By.text(anchor)); scenario.onActivity {
                        assertSame(original, androidx.lifecycle.ViewModelProvider(it)[NativeFeedSession::class.java].changesSheet)
                        assertEquals("README.md", original!!.navigation.selected)
                    }
                    assertEquals(fileReads, peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" })
                    assertEquals(diffReads, peer.requests.count { it.optString("method") == "mobile.workspace.changes.file_diff" })
                    device.takeScreenshot(File(folder, "restored-landscape.png"))
                    device.pressBack(); find(By.desc("Close changes")).click()
                    find(By.desc("Changes: 2 files, +42, −7"))
                    scenario.onActivity { assertNull(androidx.lifecycle.ViewModelProvider(it)[NativeFeedSession::class.java].changesSheet) }
                    find(By.desc("Changes: 2 files, +42, −7")).click(); find(By.desc("Open diff README.md"))
                    val requestsBeforeSignOut = peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" }
                    store.clear(); scenario.recreate()
                    assertTrue(device.wait(Until.gone(By.desc("Changes in Chip workspace")), 10_000))
                    scenario.onActivity { assertNull(androidx.lifecycle.ViewModelProvider(it)[NativeFeedSession::class.java].changesSheet) }
                    assertEquals(requestsBeforeSignOut, peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" })
                    assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.replay" })
                }
            } finally { NativeLifecycleTestActivity.connector = null; store.clear() }
        }
    }
}
