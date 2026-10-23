package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real long-press, native selection menu and OS clipboard on an owned emulator. */
class NativeMarkdownSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }
    private fun body() = WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {
        it.findViewWithTag<android.widget.TextView>("NativeMarkdownBody.0")?.takeIf { text -> text.isAttachedToWindow && text.isShown }
    }
    @Test fun nativeLongPressCopyUsesSelectedRenderedText() {
        assertEquals("English system toolbar test", "en", compose.activity.resources.configuration.locales[0].language)
        compose.setContent { CmuxTheme {
            Box(Modifier.fillMaxSize().background(NativeAppColors.page).safeDrawingPadding()) {
                NativeMarkdownReader("Select this **copyword** from native text.", remember { NativeMarkdownPosition() })
            }
        } }
        compose.waitUntil(15_000) { var ready = false; compose.runOnUiThread { ready = body()?.text?.toString() == "Select this copyword from native text." }; ready }
        compose.waitForIdle()
        val point = IntArray(2)
        compose.runOnUiThread {
            val text = body()!!; val offset = text.text.indexOf("copyword") + 3; val line = text.layout.getLineForOffset(offset)
            text.getLocationOnScreen(point)
            point[0] += text.totalPaddingLeft + text.layout.getPrimaryHorizontal(offset).toInt()
            point[1] += text.totalPaddingTop + (text.layout.getLineTop(line) + text.layout.getLineBottom(line)) / 2
        }
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.swipe(point[0], point[1], point[0], point[1], 120))
        val action = device.wait(Until.findObject(By.text("Copy")), 7_000)
            ?: device.wait(Until.findObject(By.desc("Copy")), 2_000)
        val output = File(compose.activity.getExternalFilesDir(null), "native-markdown-selection").apply { mkdirs() }
        val screen = instrumentation.uiAutomation.takeScreenshot()
        File(output, "native-copy-menu.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }; screen.recycle()
        assertNotNull("Actual native Copy action after long press", action)
        var selected = ""
        compose.runOnUiThread {
            val text = body()!!; assertTrue(text.hasSelection())
            selected = text.text.subSequence(text.selectionStart, text.selectionEnd).toString()
            assertEquals("copyword", selected)
        }
        action!!.click()
        var clipboard = ""
        compose.runOnUiThread {
            clipboard = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(compose.activity).toString()
        }
        assertEquals(selected, clipboard)
        File(output, "result.json").writeText(JSONObject().put("gesture", "UiDevice stationary swipe, 120 steps")
            .put("selection", selected).put("clipboard", clipboard).put("scope", "Actual Android native long press/menu/clipboard on synthetic rendered text in a production leaf; not TalkBack, physical-device or full iOS parity acceptance.").toString(2))
    }
}
