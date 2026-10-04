package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs the production screen with Android's main dispatcher, frame clock and real IME. */
class NativeTerminalSizingRuntimeTest {
    @Test fun alternateNoticeAndFullHeightPreferenceControlActualKeyboardViewport() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only account fixture" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val store = NativeCredentialStore(context)
        val preferences = context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
        val keys = listOf(NativeDisplayPreferences.altScreenNoticeKey, NativeDisplayPreferences.fullTerminalHeightKey)
        val folder = File(context.getExternalFilesDir(null), "terminal-sizing-preferences").apply { mkdirs() }
        val peer = NativeFixturePeer().apply { alternateScreen = true; gridFooter = "Alternate footer" }
        var scenario: ActivityScenario<NativeLifecycleTestActivity>? = null
        fun node(selector: BySelector): UiObject2 = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue(label, condition())
        }
        fun keyboardVisible(): Boolean {
            var visible = false
            scenario!!.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            return visible
        }
        fun reports() = peer.requests.filter { it.optString("method") == "mobile.terminal.viewport" &&
            !it.getJSONObject("params").optBoolean("clear") }.map { it.getJSONObject("params").getInt("viewport_rows") }
        fun terminal() = node(By.textContains("cmux Android terminal"))
        fun footerPixels(): Int {
            val bounds = terminal().visibleBounds
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            return try {
                var count = 0
                for (y in (bounds.bottom - 80).coerceAtLeast(bounds.top) until bounds.bottom.coerceAtMost(bitmap.height))
                    for (x in bounds.left.coerceAtLeast(0) until minOf(bounds.left + 400, bounds.right, bitmap.width)) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) > 120 && android.graphics.Color.green(pixel) > 120 &&
                            android.graphics.Color.blue(pixel) > 120) count++
                    }
                count
            } finally { bitmap.recycle() }
        }
        fun capture(name: String) {
            device.takeScreenshot(File(folder, "$name.png"))
            device.dumpWindowHierarchy(File(folder, "$name.xml"))
        }
        fun toggle(description: String) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(description))
            node(By.desc(description)).click()
        }
        try {
            keys.forEach { preferences.edit().remove(it).commit() }
            store.clear(); store.update {
                it.put("refresh_token", "emulator-fixture-only")
                it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
            }
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            scenario = ActivityScenario.launch(NativeLifecycleTestActivity::class.java)
            node(By.text("Claude Code task")).click()
            terminal(); node(By.desc("Explain full-screen terminal sizing"))
            device.waitForIdle(1_000)
            val initialRows = reports().last()
            val initialHeight = terminal().visibleBounds.height()
            node(By.clazz("android.widget.EditText")).click()
            waitFor("Default mode shows IME", ::keyboardVisible)
            waitFor("Default alternate grid shrinks to visible height") { reports().last() < initialRows }
            assertTrue(terminal().visibleBounds.height() < initialHeight - 50)
            waitFor("Default TUI footer painted above keyboard") { footerPixels() > 30 }
            capture("visible-height-keyboard")
            device.pressBack()
            waitFor("IME hidden") { !keyboardVisible() }
            waitFor("Default alternate grid restores height") { reports().last() == initialRows }

            node(By.desc("Explain full-screen terminal sizing")).click()
            node(By.text("Full-screen terminal app")); node(By.textContains("codex --no-alt-screen"))
            capture("notice")
            device.pressBack()
            assertTrue(NativeDisplayPreferences.read(preferences).showAltScreenNotice)
            node(By.desc("Explain full-screen terminal sizing")).click()
            node(By.text("Don't Show Again")).click()
            waitFor("Suppression persisted") { !NativeDisplayPreferences.read(preferences).showAltScreenNotice }
            assertTrue(device.wait(Until.gone(By.desc("Explain full-screen terminal sizing")), 5_000))
            val replays = peer.requests.count { it.optString("method") == "mobile.terminal.replay" }
            scenario.recreate()
            waitFor("Recreated terminal replays") { peer.requests.count { it.optString("method") == "mobile.terminal.replay" } > replays }
            terminal()
            assertFalse(device.hasObject(By.desc("Explain full-screen terminal sizing")))

            node(By.desc("Back to workspaces")).click()
            node(By.desc("cmux settings")).click()
            toggle("Full-Screen Sizing Notice")
            toggle("Use Full Terminal Height")
            waitFor("Sizing settings persisted") { NativeDisplayPreferences.read(preferences).let { it.showAltScreenNotice && it.useFullTerminalHeight } }
            device.waitForIdle(1_000); capture("settings")
            UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(30)
            node(By.text("‹  Back")).click()
            node(By.text("Claude Code task")).click()
            terminal(); node(By.desc("Explain full-screen terminal sizing"))
            device.waitForIdle(1_000)
            val legacyRows = reports().last()
            val legacyHeight = terminal().visibleBounds.height()
            val count = reports().size
            node(By.clazz("android.widget.EditText")).click()
            waitFor("Legacy mode shows IME", ::keyboardVisible)
            waitFor("Keyboard occludes the visible pane") { terminal().visibleBounds.height() < legacyHeight - 50 }
            device.waitForIdle(1_000)
            // Let the three-frame geometry fence settle; no keyboard-sized report is allowed.
            SystemClock.sleep(300)
            assertEquals(legacyRows, reports().last())
            assertTrue(reports().drop(count).all { it == legacyRows })
            waitFor("Full-height TUI footer painted above keyboard") { footerPixels() > 30 }
            capture("full-height-keyboard")
            device.pressBack(); waitFor("Legacy IME hidden") { !keyboardVisible() }
            node(By.desc("Back to workspaces")).click()
            peer.alternateScreen = false
            node(By.text("Claude Code task")).click()
            terminal()
            assertFalse("Primary screen has no sizing warning", device.hasObject(By.desc("Explain full-screen terminal sizing")))
            assertTrue(NativeDisplayPreferences.read(preferences).useFullTerminalHeight)
            assertTrue(peer.failures.toString(), peer.failures.isEmpty())
            File(folder, "verified.txt").writeText("default_rows=$initialRows\nfull_height_rows=$legacyRows\n" +
                "notice dismissed, suppressed, restored by Settings; recreation and primary-mode hiding verified\n")
        } catch (failure: Throwable) {
            capture("failure")
            File(folder, "failure.txt").writeText("Methods: " + peer.requests.map { it.optString("method") } +
                "\nViewport rows: " + reports() + "\nPeer failures: " + peer.failures + "\n" + MobileDebugLog.snapshot())
            throw failure
        } finally {
            scenario?.close(); NativeLifecycleTestActivity.connector = null
            peer.close(); store.clear()
            keys.forEach { preferences.edit().remove(it).commit() }
        }
    }
}
