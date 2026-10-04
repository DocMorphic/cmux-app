package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.NumberFormat

/** No Compose test rule: the Activity uses Android's real main dispatcher and frame clock. */
class NativeDisplayRuntimeTest {
    @Test fun settingsSelectionReachesTerminalAndSurvivesActivityRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) {
            "Synthetic account tests require the disposable emulator"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val preferences = context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
        val keys = listOf(NativeDisplayPreferences.wrapKey, NativeDisplayPreferences.previewKey, NativeDisplayPreferences.scrollbackKey)
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer()
        val folder = File(context.getExternalFilesDir(null), "display-runtime").apply { mkdirs() }
        fun node(selector: BySelector): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun visibleSetting(description: String): UiObject2 {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(description))
            return node(By.desc(description))
        }
        fun waitFor(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("Condition timed out", condition())
        }
        fun capture(name: String) {
            device.takeScreenshot(File(folder, "$name.png"))
            device.dumpWindowHierarchy(File(folder, "$name.xml"))
        }
        try {
            keys.forEach { preferences.edit().remove(it).commit() }
            store.clear()
            store.update {
                it.put("refresh_token", "emulator-fixture-only")
                it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
            }
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                node(By.text("Claude Code task"))
                node(By.desc("cmux settings")).click()
                visibleSetting("Wrap Workspace Titles").click()
                visibleSetting("Preview Lines").click()
                node(By.text("1 Line")).click()
                visibleSetting("Terminal Scrollback").click()
                node(By.text(NumberFormat.getIntegerInstance().format(20_000) + " Rows")).click()
                waitFor { NativeDisplayPreferences.read(preferences) == NativeDisplayPreferences(true, 1, 20_000) }
                capture("settings")
                UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(30)
                node(By.text("‹  Back")).click()
                node(By.text("Claude Code task")).click()
                node(By.textContains("cmux Android terminal"))
                assertEquals(20_000, peer.requests.first { it.optString("method") == "mobile.terminal.replay" }
                    .getJSONObject("params").getInt("max_scrollback_rows"))
                capture("terminal")
                val replays = peer.requests.count { it.optString("method") == "mobile.terminal.replay" }
                scenario.recreate()
                // The retained pane selection must reconnect automatically after recreation.
                node(By.textContains("cmux Android terminal"))
                waitFor { peer.requests.count { it.optString("method") == "mobile.terminal.replay" } > replays }
                assertEquals(20_000, peer.requests.last { it.optString("method") == "mobile.terminal.replay" }
                    .getJSONObject("params").getInt("max_scrollback_rows"))
                assertEquals(NativeDisplayPreferences(true, 1, 20_000), NativeDisplayPreferences.read(preferences))
                capture("recreated-terminal")
                node(By.desc("Back to workspaces")).click()
                node(By.text("Claude Code task")).click()
                node(By.textContains("cmux Android terminal"))
                // Exercise real input after the recreated binding, not just retained pixels.
                node(By.clazz("android.widget.EditText")).text = "display-runtime-input"
                node(By.text("Send")).click()
                waitFor { peer.requests.any { it.optString("method") == "terminal.paste" &&
                    it.optJSONObject("params")?.optString("text")?.contains("display-runtime-input") == true } }
                assertTrue(peer.failures.toString(), peer.failures.isEmpty())
            }
        } catch (failure: Throwable) {
            capture("failure")
            File(folder, "failure.txt").writeText("Methods: " + peer.requests.map { it.optString("method") } +
                "\nPeer failures: " + peer.failures + "\n" + MobileDebugLog.snapshot())
            throw failure
        } finally {
            NativeLifecycleTestActivity.connector = null
            peer.close(); store.clear()
            keys.forEach { preferences.edit().remove(it).commit() }
        }
    }
}
