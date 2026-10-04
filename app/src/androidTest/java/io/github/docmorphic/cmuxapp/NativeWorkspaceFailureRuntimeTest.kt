package io.github.docmorphic.cmuxapp

import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Activity/main dispatcher and framed local RPC; emulator-only credentials. */
class NativeWorkspaceFailureRuntimeTest {
    @Test fun rejectedWorkspaceActionsKeepListUsableAndTerminalFailuresStillSurface() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer()
        val folder = File(context.getExternalFilesDir(null), "workspace-action-failures").apply { mkdirs() }
        fun node(selector: BySelector): UiObject2 {
            val found = device.wait(Until.findObject(selector), 15_000)
            if (found == null) {
                device.takeScreenshot(File(folder, "failure-before-close.png"))
                device.dumpWindowHierarchy(File(folder, "failure-before-close.xml"))
            }
            return checkNotNull(found) { "Missing $selector" }
        }
        fun waitFor(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
            check(condition()) { "Timed out waiting for workspace action" }
        }
        fun failures() = MobileDebugLog.snapshot().lineSequence().count { it.contains("RPC_WORKSPACE REMOTE_ERROR") }
        fun reject(method: String, action: () -> Unit) {
            peer.rejectedMethods = setOf(method)
            val before = failures()
            action()
            // The wire logs first; the shell logs after reconciliation and consumes the failure.
            waitFor { failures() >= before + 2 }
            device.waitForIdle()
            assertFalse(device.hasObject(By.text("Fixture terminal temporarily unavailable")))
            assertNotNull(node(By.text("Claude Code task")))
        }
        try {
            store.clear(); store.update {
                it.put("refresh_token", "workspace-action-emulator-fixture")
                it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
            }
            peer.groupActionsSupported = true
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use {
                node(By.text("Claude Code task"))
                reject("workspace.action") {
                    node(By.text("Claude Code task")).longClick(); node(By.text("Pin")).click()
                }
                assertEquals("pin", peer.requests.last { it.optString("method") == "workspace.action" }.getJSONObject("params").getString("action"))
                reject("workspace.close") {
                    node(By.text("Claude Code task")).longClick(); node(By.text("Close workspace")).click()
                    node(By.text("Delete")).click()
                }
                assertNull(peer.hiddenWorkspaceId)
                reject("workspace.group.action") {
                    node(By.text("Completed group")).longClick(); node(By.text("Pin group")).click()
                }
                reject("workspace.move") {
                    node(By.text("Claude Code task")).longClick(); node(By.text("Move to Group")).click(); node(By.text("Completed group")).click()
                }
                assertNull(peer.customWorkspaceListing)
                device.takeScreenshot(File(folder, "after-rejections.png"))
                device.dumpWindowHierarchy(File(folder, "after-rejections.xml"))
                peer.rejectedMethods = emptySet()
                node(By.text("Claude Code task")).click()
                node(By.text("Shell ▾"))
                // A terminal error must still reach the existing recovery UI.
                peer.rejectNextInput.set(true)
                node(By.desc("Terminal arrow pad").enabled(true)).let { pad ->
                    val bounds = pad.visibleBounds
                    device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX() + bounds.width() / 3, bounds.centerY(), 8)
                }
                val deliveryError = "Typing paused. Delivery was not confirmed. Check the terminal before resuming."
                node(By.text(deliveryError))
                node(By.text("Resume typing"))
                device.takeScreenshot(File(folder, "terminal-error-preserved.png"))
                node(By.text("Resume typing")).click()
                assertTrue(device.wait(Until.gone(By.text(deliveryError)), 5_000))
                node(By.desc("Terminal arrow pad").enabled(true))
                assertTrue(peer.failures.toString(), peer.failures.isEmpty())
                File(folder, "verified.txt").writeText("Workspace pin, close, group pin and move rejected without a global banner; terminal input rejection remains visible.\n")
            }
        } catch (failure: Throwable) {
            device.takeScreenshot(File(folder, "failure.png")); device.dumpWindowHierarchy(File(folder, "failure.xml"))
            File(folder, "failure.txt").writeText("${failure}\n${peer.requests.map { it.optString("method") }}\n${MobileDebugLog.snapshot()}")
            throw failure
        } finally {
            NativeLifecycleTestActivity.connector = null; peer.close(); store.clear()
        }
    }
}
