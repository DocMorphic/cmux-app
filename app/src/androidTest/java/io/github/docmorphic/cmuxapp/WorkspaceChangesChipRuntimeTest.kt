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
    @Test fun rowSummaryOpensItsMacChangesWithoutOpeningATerminal() {
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
                    } else {
                        assertEquals("workspace-1", params.getString("workspace_id"))
                        JSONObject("""{"workspace_id":"workspace-1","repo_root":"/fixture","files":[{"path":"README.md","status":"modified","additions":42,"deletions":7}]}""")
                    }
                }
                NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
                }
                fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
                ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use {
                    val chip = find(By.desc("Changes: 2 files, +42, −7"))
                    val folder = File(context.getExternalFilesDir(null), "workspace-changes-chip").apply { mkdirs() }
                    device.takeScreenshot(File(folder, "row.png"))
                    chip.click(); find(By.desc("Open diff README.md"))
                    assertTrue(peer.requests.any { it.optString("method") == "mobile.workspace.changes.files" })
                    assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.replay" })
                    find(By.desc("Close changes")).click()
                    find(By.desc("Changes: 2 files, +42, −7"))
                }
            } finally { NativeLifecycleTestActivity.connector = null; store.clear() }
        }
    }
}
