package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production screen/coordinator and real Android dispatch, with an isolated framed Mac peer. */
class NativeWorkspaceCustomizationRuntimeTest {
    @Test fun rowEditorSavesMacMetadataAndPanePickerCanClearIt() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer()
        val folder = File(context.getExternalFilesDir(null), "workspace-customization").apply { mkdirs() }
        fun node(selector: BySelector): UiObject2 {
            val found = device.wait(Until.findObject(selector), 15_000)
            if (found == null) { device.takeScreenshot(File(folder, "failure.png")); device.dumpWindowHierarchy(File(folder, "failure.xml")) }
            return checkNotNull(found) { "Missing $selector" }
        }
        fun actions() = peer.requests.filter { it.optString("method") == "workspace.action" }.map { it.getJSONObject("params").getString("action") }
        try {
            store.clear(); store.update { it.put("refresh_token", "customization-emulator-fixture")
                .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
            peer.workspaceMetadataSupported = true
            peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","window_id":"fixture-window","title":"Original workspace","description":"Baseline","terminals":[{"id":"terminal-1","title":"Shell"}]}]}""")
            peer.workspaceActionResponse = { params ->
                val listing = JSONObject(peer.customWorkspaceListing.toString())
                val row = listing.getJSONArray("workspaces").getJSONObject(0)
                assertEquals("workspace-1", params.getString("workspace_id")); assertEquals("fixture-window", params.getString("window_id"))
                when (params.getString("action")) {
                    "rename" -> row.put("title", params.getString("title"))
                    "set_description" -> row.put("description", params.getString("description"))
                    "clear_description" -> row.remove("description")
                    "set_color" -> row.put("custom_color", params.getString("color"))
                    "clear_color" -> row.remove("custom_color")
                    "pin" -> row.put("is_pinned", true)
                    "unpin" -> row.put("is_pinned", false)
                    else -> error("Unexpected metadata action")
                }
                peer.customWorkspaceListing = listing; JSONObject()
            }
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use {
                node(By.desc("Actions for Original workspace")).click(); node(By.text("Customize Workspace")).click()
                node(By.text("Original workspace").clazz("android.widget.EditText")).text = "Customized workspace"
                node(By.text("Baseline").clazz("android.widget.EditText")).text = "Description from Android"
                node(By.desc("Pinned")).click()
                node(By.desc("Use Workspace Color")).click()
                node(By.text("#007AFF").clazz("android.widget.EditText")).text = "#12ABEF"
                node(By.text("#12ABEF").clazz("android.widget.EditText")); device.waitForIdle()
                device.takeScreenshot(File(folder, "editor.png"))
                node(By.text("Save")).click()
                node(By.desc("Actions for Customized workspace"))
                assertEquals(listOf("rename", "set_description", "set_color", "pin"), actions())
                val saved = peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0)
                assertEquals("#12ABEF", saved.getString("custom_color")); assertTrue(saved.getBoolean("is_pinned"))
                node(By.text("Description from Android"))
                device.takeScreenshot(File(folder, "saved-row.png"))
                node(By.text("Customized workspace")).click(); node(By.text("Shell ▾")).click()
                node(By.text("Customize Workspace")).click()
                node(By.text("Description from Android").clazz("android.widget.EditText")).text = ""
                node(By.desc("Use Workspace Color")).click()
                node(By.text("Save")).click()
                node(By.text("Shell ▾"))
                assertEquals(listOf("clear_description", "clear_color"), actions().takeLast(2))
                assertTrue(peer.failures.toString(), peer.failures.isEmpty())
                File(folder, "verified.txt").writeText(actions().joinToString("\n"))
            }
        } finally { NativeLifecycleTestActivity.connector = null; peer.close(); store.clear() }
    }
}
