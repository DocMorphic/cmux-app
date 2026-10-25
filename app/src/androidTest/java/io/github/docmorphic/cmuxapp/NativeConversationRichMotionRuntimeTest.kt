package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Real mounted renderer pixels, controlled Compose frame clock and actual OS motion setting.
 * This is Android animation/loop evidence; UIKit pixels, physical smoothness and GPU budgets
 * require their own matched-device observations. */
class NativeConversationRichMotionRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var original: String? = null
    @Before fun start() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        original = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 1")
        compose.mainClock.autoAdvance = false
    }
    @After fun restore() {
        original?.let { device.executeShellCommand(if (it == "null") "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $it") }
        compose.mainClock.autoAdvance = true
    }

    @Test fun actualEffectPixelsMoveRestRepeatAndStopWhenSystemMotionIsDisabled() {
        var effect by mutableStateOf(NativeConversationTextEffect.BIG)
        compose.setContent { CmuxTheme {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.size(300.dp).background(Color.Black).testTag("EffectMotionCanvas"), contentAlignment = Alignment.Center) {
                    NativeChiefText("MOVE", Modifier.testTag("MovingText"), color = Color.White, fontSize = 51,
                        textRuns = listOf(NativeConversationTextRun(0, 4, effect = effect)), effectSeed = 0uL)
                }
            }
        } }
        val dir = File(compose.activity.getExternalFilesDir(null), "rich-composer").apply { mkdirs() }
        val records = org.json.JSONArray()
        fun frame(name: String): String {
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("EffectMotionCanvas").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            val buffer = ByteBuffer.allocate(bitmap.byteCount); bitmap.copyPixelsToBuffer(buffer)
            val hash = MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            records.put(org.json.JSONObject().put("name", name).put("clockMillis", compose.mainClock.currentTime).put("pixelSha256", hash))
            return hash
        }
        var rest = ""
        NativeConversationTextEffect.entries.forEach { value ->
            compose.runOnUiThread { effect = value }
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
            compose.mainClock.advanceTimeBy(160)
            val active = frame("motion-${value.wireName}-active")
            compose.mainClock.advanceTimeBy(1500)
            rest = frame("motion-${value.wireName}-rest")
            assertNotEquals("${value.wireName}: motion must change actual pixels", rest, active)
            compose.mainClock.advanceTimeBy(400)
            assertEquals("${value.wireName}: stable rest interval", rest, frame("motion-${value.wireName}-rest-again"))
            compose.mainClock.advanceTimeBy(1100)
            assertNotEquals("${value.wireName}: next loop moves again", rest, frame("motion-${value.wireName}-next-loop"))
        }
        device.executeShellCommand("settings put global animator_duration_scale 0")
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeBy(64)
            frame("motion-disabled") == rest
        }
        compose.mainClock.advanceTimeBy(4000)
        assertEquals("Disabled motion stays at static ink across a later cycle", rest, frame("motion-disabled-later"))
        device.executeShellCommand("settings put global animator_duration_scale 1")
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeBy(64)
            frame("motion-reenabled") != rest
        }
        File(dir, "motion-records.json").writeText(records.toString(2) + "\n")
    }
}
