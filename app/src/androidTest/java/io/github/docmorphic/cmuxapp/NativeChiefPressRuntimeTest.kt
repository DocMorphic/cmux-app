package io.github.docmorphic.cmuxapp

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Real Android frame/uptime dispatch, deliberately outside Compose's test clock. */
class NativeChiefPressRuntimeTest {
    @Test fun actualUptimePressReleaseTimeoutAndTravelFollowTheSourceClocks() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val writes = AtomicInteger()
        val date = Instant.parse("2026-10-20T12:00:00Z")
        val message = NativeChiefMessage("press", 1uL, "chief", "Chief", "chief", "A message for actual held-touch dispatch",
            date, emptyList(), emptyList(), null, null)
        val conversation = NativeChiefConversation("chief-main", "Chief", "Ready on your Mac", 0, true, false, date)
        val api = object : NativeChiefApi {
            override suspend fun status() = NativeChiefStatus(true, null, null, "chief", "Chief", "", "", 1, 1, emptyList(), "chief-main")
            override suspend fun conversations(afterCursor: String?, waitForChange: Boolean): NativeChiefSnapshot =
                if (waitForChange) awaitCancellation() else NativeChiefSnapshot(listOf(conversation), "home")
            override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead =
                if (waitForChange) awaitCancellation() else NativeChiefRead("chief-main", false, listOf(message), false, 1uL, 0, 1uL, "transcript")
            override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent {
                writes.incrementAndGet(); error("No send expected")
            }
            override suspend fun markRead(conversation: String?, upToSeq: ULong) = NativeChiefMarked("chief-main", upToSeq, true)
            override suspend fun stop(conversation: String?): NativeChiefStopped { writes.incrementAndGet(); error("No stop expected") }
            override suspend fun approve(conversation: String?, request: String?, choice: String): NativeChiefApproved { writes.incrementAndGet(); error("No approval expected") }
            override suspend fun answer(conversation: String?, message: String?, choices: List<String>, text: String?): NativeChiefAnswered { writes.incrementAndGet(); error("No answer expected") }
            override suspend fun open(windowId: String?, workspaceId: String?) = NativeChiefOpened(windowId, true)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var computer: NativeChiefComputerSession? = null
        lateinit var directory: File
        val events = JSONArray()
        var heldObservedAfterMs = 0L
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            try {
                activity.onActivity { host ->
                    directory = File(host.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                    val owner = NativeChiefComputerSession(scope, api) { true }
                    computer = owner
                    host.setContent { CmuxTheme {
                        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                            .semantics { testTagsAsResourceId = true }) {
                            NativeChiefView(owner, true, Modifier.fillMaxSize())
                        }
                    } }
                }
                val pinned = device.wait(Until.findObject(By.res("ChiefPinnedConversation")), 10_000)
                assertNotNull(pinned); pinned.click()
                assertNotNull(device.wait(Until.findObject(By.res("ChiefMessage:press")), 10_000))
                fun inject(action: Int, down: Long, x: Float, y: Float) {
                    val now = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(down, now, action, x, y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    events.put(JSONObject().put("action", action).put("afterDownMs", now - down).put("uptimeMs", now).put("downUptimeMs", down))
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                fun hold(duration: Long, travel: Float = 0f, beforeUp: (Long) -> Unit = {}) {
                    val bounds = device.findObject(By.res("ChiefMessage:press")).visibleBounds
                    val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
                    val down = SystemClock.uptimeMillis()
                    inject(MotionEvent.ACTION_DOWN, down, x, y)
                    try {
                        if (travel != 0f) {
                            SystemClock.sleep(200)
                            inject(MotionEvent.ACTION_MOVE, down, x + travel, y)
                            SystemClock.sleep((duration - 200).coerceAtLeast(0))
                        } else SystemClock.sleep(duration)
                        beforeUp(down)
                    } finally { inject(MotionEvent.ACTION_UP, down, x + travel, y) }
                }
                fun closeMenu() {
                    device.pressBack()
                    assertTrue(device.wait(Until.gone(By.res("ChiefActionMenu")), 5_000))
                }
                hold(250)
                assertFalse(device.hasObject(By.res("ChiefActionMenu")))
                hold(450)
                assertNotNull(device.wait(Until.findObject(By.res("ChiefActionMenu")), 5_000))
                SystemClock.sleep(150) // Allow the OS compositor to present the observed menu.
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(directory, "chief-menu-release-real-clock.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                closeMenu()
                hold(850) { down ->
                    // AX event delivery can lag the actual committed frame.
                    // Keep the finger down throughout this bounded observation.
                    assertTrue("Held timeout must open before the finger lifts", device.wait(Until.hasObject(By.res("ChiefActionMenu")), 1_000))
                    heldObservedAfterMs = SystemClock.uptimeMillis() - down
                }
                closeMenu()
                hold(850, -80f)
                assertFalse(device.hasObject(By.res("ChiefActionMenu")))
                assertEquals(0, writes.get())
                File(directory, "chief-menu-real-clock.json").writeText(JSONObject().put("realUptime", true)
                    .put("composeTestClockInstalled", false).put("releaseAndHeldBeforeUpPassed", true)
                    .put("heldObservedAfterMs", heldObservedAfterMs).put("axObservationWaitMs", 1_000)
                    .put("backendWrites", writes.get()).put("events", events).toString(2))
            } finally {
                activity.onActivity { computer?.close(); scope.cancel() }
            }
        }
    }
}
