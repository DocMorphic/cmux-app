package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ArtifactImageGesturesTest {
    @Test fun offCenterZoomRestoresAndLongPressUsesSharedFileActions() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val source = File(context.cacheDir, "image-gestures-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            listOf(Color.GREEN, Color.RED, Color.BLUE).forEachIndexed { index, color ->
                canvas.drawRect(index * 100f, 0f, (index + 1) * 100f, 200f, Paint().apply { this.color = color })
            }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val expected = source.readBytes()
        val output = File(context.getExternalFilesDir(null), "image-gestures").apply { mkdirs() }
        fun find(selector: BySelector): UiObject2 {
            return device.wait(Until.findObject(selector), 15_000) ?: run {
                device.takeScreenshot(File(output, "failure.png"))
                device.dumpWindowHierarchy(File(output, "failure.xml"))
                error("Missing $selector")
            }
        }
        fun await(message: String, predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < until) { if (predicate()) return; Thread.sleep(75) }
            fail(message)
        }
        val image = By.desc("Image preview ${source.name}")
        fun colorAtCenter(color: Int) {
            val until = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < until) {
                val bounds = find(image).visibleBounds
                val screen = instrumentation.uiAutomation.takeScreenshot()
                val matched = try { screen.getPixel(bounds.centerX(), bounds.centerY()) == color } finally { screen.recycle() }
                if (matched) return
                Thread.sleep(75)
            }
            device.takeScreenshot(File(output, "color-failure.png"))
            device.dumpWindowHierarchy(File(output, "color-failure.xml"))
            fail("Expected image color $color at viewport center")
        }
        fun doubleTap(fraction: Float) {
            val bounds = find(image).visibleBounds
            val x = bounds.left + (bounds.width() * fraction).toInt()
            device.click(x, bounds.centerY()); Thread.sleep(80); device.click(x, bounds.centerY())
        }
        fun menu() {
            find(By.clickable(true).enabled(true).hasDescendant(By.desc("Viewer actions")))
            find(image).longClick()
            find(By.text("Share")); find(By.text("Save")); find(By.text("Copy Image"))
            assertFalse(device.hasObject(By.text("Open")))
        }
        val shares = AtomicInteger(); val saves = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                when (intent.action) {
                    Intent.ACTION_CHOOSER -> shares.incrementAndGet()
                    Intent.ACTION_CREATE_DOCUMENT -> saves.incrementAndGet()
                    else -> return null
                }
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", source.absolutePath).putExtra("route", ChangesPreviewRoute.IMAGE.name).putExtra("mime", "image/png")).use { scenario ->
            colorAtCenter(Color.RED)
            doubleTap(.2f); colorAtCenter(Color.GREEN)
            scenario.recreate(); colorAtCenter(Color.GREEN)
            device.takeScreenshot(File(output, "off-center-recreated.png"))
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("Preview did not rotate to landscape") { device.displayWidth > device.displayHeight }
            await("Image viewport did not finish landscape layout") { find(image).visibleBounds.let { it.width() > it.height() } }
            device.waitForIdle()
            colorAtCenter(Color.GREEN)
            device.takeScreenshot(File(output, "off-center-landscape.png"))
            scenario.recreate(); colorAtCenter(Color.GREEN)
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("Preview did not return to portrait") { device.displayHeight > device.displayWidth }
            colorAtCenter(Color.GREEN)
            device.takeScreenshot(File(output, "off-center-portrait-return.png"))
            doubleTap(.5f); colorAtCenter(Color.RED)
            menu(); device.takeScreenshot(File(output, "context-menu.png")); find(By.text("Share")).click()
            await("Share was not dispatched") { shares.get() == 1 }
            find(By.clickable(true).enabled(true).hasDescendant(By.desc("Viewer actions"))); assertEquals(1, shares.get())
            device.takeScreenshot(File(output, "after-share.png"))
            device.dumpWindowHierarchy(File(output, "after-share.xml"))
            menu(); find(By.text("Save")).click()
            await("Save was not dispatched") { saves.get() == 1 }
            find(By.clickable(true).enabled(true).hasDescendant(By.desc("Viewer actions"))); assertEquals(1, saves.get())
            menu(); find(By.text("Copy Image")).click()
            await("Copy Image did not publish its clipboard URI") {
                var matches = false
                instrumentation.runOnMainSync {
                    matches = context.getSystemService(ClipboardManager::class.java).primaryClip?.description?.label?.toString() == source.name
                }
                matches
            }
            find(By.clickable(true).enabled(true).hasDescendant(By.desc("Viewer actions")))
            instrumentation.runOnMainSync {
                val uri = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).uri!!
                assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        } } finally { instrumentation.removeMonitor(monitor); source.delete() }
    }
}
