package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real Activity/main dispatch and native RPC framing; local Mac endpoint only. */
class NativeArrowNubRuntimeTest {
    @Test fun heldPadDeliversVtBytesAndStopsOnReleaseAndBackground() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val preferences = context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer()
        val folder = File(context.getExternalFilesDir(null), "arrow-nub").apply { mkdirs() }
        val feedback = CopyOnWriteArrayList<NativeHaptic>()
        fun node(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun input() = peer.requests.filter { it.optString("method") == "terminal.input" }
        fun waitFor(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
            if (!condition()) {
                device.takeScreenshot(File(folder, "failure.png"))
                device.dumpWindowHierarchy(File(folder, "failure.xml"))
                File(folder, "failure.txt").writeText("feedback=${feedback.size}\ninputs=${input().map { it.optJSONObject("params")?.optString("text") }}\nmethods=${peer.requests.map { it.optString("method") }}")
                fail("Condition timed out; feedback=${feedback.size}, inputs=${input().size}")
            }
        }
        var downTime = 0L
        fun touch(action: Int, x: Float, y: Float) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        try {
            preferences.edit().remove(NativeDisplayPreferences.hapticsKey).commit()
            store.clear(); store.update {
                it.put("refresh_token", "emulator-fixture-only")
                it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
            }
            NativeLifecycleTestActivity.haptics = NativeHaptics({ NativeDisplayPreferences.read(preferences).hapticFeedbackEnabled }, feedback::add)
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                node(By.text("Claude Code task")).click()
                node(By.desc("Terminal arrow pad").enabled(true))
                device.waitForIdle()
                val bounds = node(By.desc("Terminal arrow pad").enabled(true)).visibleBounds
                device.takeScreenshot(File(folder, "before-drag.png"))
                device.dumpWindowHierarchy(File(folder, "before-drag.xml"))
                val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
                touch(MotionEvent.ACTION_DOWN, x, y)
                touch(MotionEvent.ACTION_MOVE, x + bounds.width() * .4f, y)
                waitFor { input().size >= 3 }
                touch(MotionEvent.ACTION_UP, x + bounds.width() * .4f, y)
                SystemClock.sleep(200) // Let already admitted RPCs finish, then check no new repeats.
                val released = input().size
                SystemClock.sleep(240); assertEquals(released, input().size)
                assertTrue(input().all { it.getJSONObject("params").getString("text") == "\u001b[C" })
                assertEquals(released, feedback.size)
                preferences.edit().putBoolean(NativeDisplayPreferences.hapticsKey, false).commit()
                touch(MotionEvent.ACTION_DOWN, x, y)
                touch(MotionEvent.ACTION_MOVE, x, y - bounds.height() * .4f)
                waitFor { input().size >= released + 2 }
                scenario.moveToState(Lifecycle.State.STARTED)
                SystemClock.sleep(200)
                val paused = input().size
                SystemClock.sleep(240); assertEquals(paused, input().size)
                touch(MotionEvent.ACTION_UP, x, y - bounds.height() * .4f)
                scenario.moveToState(Lifecycle.State.RESUMED)
                SystemClock.sleep(240); assertEquals(paused, input().size)
                assertEquals(released, feedback.size)
                assertTrue(input().drop(released).all { it.getJSONObject("params").getString("text") == "\u001b[A" })
                device.takeScreenshot(File(folder, "native-toolbar.png"))
                File(folder, "verified.txt").writeText("released=$released; total=$paused; haptics=${feedback.size}")
                // A drag elsewhere on the same edge must retain Android Back navigation.
                assertTrue(device.swipe(1, device.displayHeight / 2, device.displayWidth / 2, device.displayHeight / 2, 20))
                node(By.text("Claude Code task"))
                assertTrue(peer.failures.toString(), peer.failures.isEmpty())
            }
        } finally {
            NativeLifecycleTestActivity.connector = null; NativeLifecycleTestActivity.haptics = null
            peer.close(); store.clear(); preferences.edit().remove(NativeDisplayPreferences.hapticsKey).commit()
        }
    }
}
