package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android main dispatch and RPC framing; only the Mac endpoint and actuator are fixtures. */
class NativeTerminalBellRuntimeTest {
    @Test fun liveNativeOutputRespectsSettingsForegroundReplayAndDuplicateDelivery() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val preferences = context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer().apply { rawTerminal = true; rawReplayText = "BELL-BASE\u0007\r\n"; rawReplaySequence = 100 }
        val feedback = CopyOnWriteArrayList<NativeHaptic>()
        fun node(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun waitFor(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 15_000
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            assertTrue("Condition timed out", condition())
        }
        var sequence = 100L
        fun output(text: String) { peer.pushBytes(text.toByteArray(), sequence); sequence += text.toByteArray().size }
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
                node(By.textContains("BELL-BASE"))
                assertTrue(feedback.isEmpty())
                output("\u0007LIVE-ONE")
                node(By.textContains("LIVE-ONE")); waitFor { feedback.size == 1 }
                peer.pushBytes("\u0007LIVE-ONE".toByteArray(), 100)
                output("\u001b]0;title\u0007\r\nOSC-DONE")
                node(By.textContains("OSC-DONE")); assertEquals(1, feedback.size)
                preferences.edit().putBoolean(NativeDisplayPreferences.hapticsKey, false).commit()
                output("\u0007\r\nDISABLED")
                node(By.textContains("DISABLED")); assertEquals(1, feedback.size)
                preferences.edit().putBoolean(NativeDisplayPreferences.hapticsKey, true).commit()
                scenario.moveToState(Lifecycle.State.STARTED)
                output("\u0007\r\nBACKGROUND")
                // Barrier on a local observer: background data has been rendered before resuming.
                waitFor { device.hasObject(By.textContains("BACKGROUND")) }
                assertEquals(1, feedback.size)
                scenario.moveToState(Lifecycle.State.RESUMED)
                output("\u0007\r\nRESUMED")
                node(By.textContains("RESUMED")); waitFor { feedback.size == 2 }
                assertEquals(listOf(NativeHaptic.WARNING, NativeHaptic.WARNING), feedback.toList())
                peer.rawReplayText = "RESTORED\u0007\r\n"; peer.rawReplaySequence = sequence
                scenario.recreate()
                node(By.textContains("RESTORED")); assertEquals(2, feedback.size)
                assertTrue(peer.failures.toString(), peer.failures.isEmpty())
            }
        } finally {
            NativeLifecycleTestActivity.connector = null; NativeLifecycleTestActivity.haptics = null
            peer.close(); store.clear(); preferences.edit().remove(NativeDisplayPreferences.hapticsKey).commit()
        }
    }
}
