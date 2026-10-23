package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Runtime settings, draw output and lifecycle cancellation on the owned emulator only. */
class CloudOnboardingMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    @Test fun realReducedMotionSettingFreezesPixelsAndBackgroundOrDisposalStopsAdvancing() {
        val prior = shell("settings get global animator_duration_scale")
        var visible by mutableStateOf(true)
        val folder = File(compose.activity.getExternalFilesDir(null), "cloud-onboarding-motion").apply { mkdirs() }
        try {
            shell("settings put global animator_duration_scale 1")
            compose.setContent { CmuxTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("MotionViewport")) {
                    if (visible) NativeCloudOnboardingBackdrop(cloudOnboardingReducedMotion(), Modifier.fillMaxSize())
                }
            } }
            val probe = compose.onNode(SemanticsMatcher.keyIsDefined(CloudOnboardingMotionProbe), useUnmergedTree = true)
                .fetchSemanticsNode().config[CloudOnboardingMotionProbe]
            fun ticks(): Long { var n = -1L; instrumentation.runOnMainSync { n = probe() }; return n }
            compose.waitUntil(5_000) { ticks() >= 3 }
            val viewport = compose.onNodeWithTag("MotionViewport").fetchSemanticsNode().boundsInRoot
            fun capture(name: String): Bitmap {
                val full = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(folder, "$name-screen.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val crop = Bitmap.createBitmap(full, viewport.left.toInt(), viewport.top.toInt(), viewport.width.toInt(), viewport.height.toInt())
                File(folder, "$name-viewport.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (crop !== full) full.recycle()
                return crop
            }
            val activeBefore = ticks(); val activeA = capture("animated-a")
            compose.mainClock.advanceTimeBy(320); compose.waitForIdle()
            val activeB = capture("animated-b"); val activeAfter = ticks()
            assertTrue(activeAfter > activeBefore); assertFalse("Animated source grid did not change actual pixels", activeA.sameAs(activeB))
            activeA.recycle(); activeB.recycle()
            shell("settings put global animator_duration_scale 0")
            Thread.sleep(200); compose.waitForIdle()
            val staticBefore = ticks(); val staticA = capture("reduced-a")
            compose.mainClock.advanceTimeBy(320); compose.waitForIdle()
            val staticB = capture("reduced-b"); val staticAfter = ticks()
            assertEquals(staticBefore, staticAfter)
            assertTrue("Reduced Motion changed actual viewport pixels", staticA.sameAs(staticB))
            staticA.recycle(); staticB.recycle()
            shell("settings put global animator_duration_scale 1")
            compose.waitUntil(5_000) { ticks() > staticAfter + 1 }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(150); val stoppedBefore = ticks(); compose.mainClock.advanceTimeBy(320); compose.waitForIdle(); val stoppedAfter = ticks()
            assertEquals(stoppedBefore, stoppedAfter)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5_000) { ticks() > stoppedAfter + 1 }
            compose.runOnIdle { visible = false }; compose.waitForIdle()
            val disposedBefore = ticks(); compose.mainClock.advanceTimeBy(320); compose.waitForIdle(); val disposedAfter = ticks()
            assertEquals(disposedBefore, disposedAfter)
            File(folder, "lifecycle.json").writeText(JSONObject().put("activeBefore", activeBefore).put("activeAfter", activeAfter)
                .put("reducedBefore", staticBefore).put("reducedAfter", staticAfter).put("reducedPixelsUnchanged", true)
                .put("animatedPixelsChanged", true).put("stoppedBefore", stoppedBefore).put("stoppedAfter", stoppedAfter)
                .put("disposedBefore", disposedBefore).put("disposedAfter", disposedAfter).put("capture", "UiAutomation screen and exact viewport crop").toString(2))
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            shell(if (prior == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $prior")
        }
    }
}
