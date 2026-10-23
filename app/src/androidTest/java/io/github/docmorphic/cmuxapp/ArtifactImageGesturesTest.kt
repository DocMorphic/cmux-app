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
    @Test fun tallImageKeepsVerticalFocalPointAcrossRotationAndRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val source = File(context.cacheDir, "tall-image-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.RED)
            // A small, off-center patch identifies the same image point on both axes.
            canvas.drawRect(105f, 60f, 165f, 140f, Paint().apply { color = Color.GREEN })
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val output = File(context.getExternalFilesDir(null), "image-gestures-tall").apply { mkdirs() }
        val records = org.json.JSONArray()
        val image = By.desc("Image preview ${source.name}")
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                try { if (predicate()) return } catch (_: StaleObjectException) { }
                Thread.sleep(75)
            }
            device.takeScreenshot(File(output, "failure.png"))
            device.dumpWindowHierarchy(File(output, "failure.xml"))
            fail(message)
        }
        fun bounds() = checkNotNull(device.wait(Until.findObject(image), 10_000)).visibleBounds
        fun centerIs(color: Int): Boolean {
            val rect = bounds()
            val screen = instrumentation.uiAutomation.takeScreenshot()
            return try {
                // Check a neighborhood, not one potentially stale/interpolated pixel.
                listOf(-8, 0, 8).all { dx -> listOf(-8, 0, 8).all { dy ->
                    screen.getPixel(rect.centerX() + dx, rect.centerY() + dy) == color
                } }
            } finally { screen.recycle() }
        }
        fun capture(name: String) {
            await("Focal patch moved in $name") { centerIs(Color.GREEN) }
            val rect = bounds()
            val screen = instrumentation.uiAutomation.takeScreenshot()
            val pixels = IntArray(rect.width() * rect.height())
            screen.getPixels(pixels, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
            var left = rect.width(); var right = -1; var top = rect.height(); var bottom = -1
            // Bilinear magnification blends the edge pixels. The half-color
            // boundary measures the patch, rather than only its smaller pure-green interior.
            pixels.forEachIndexed { index, color -> if (Color.green(color) > Color.red(color) && Color.green(color) > Color.blue(color)) {
                val x = index % rect.width(); val y = index / rect.width()
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            } }
            val fit = minOf(rect.width() / 300f, rect.height() / 400f)
            try {
                File(output, "$name.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertEquals("Horizontal focal point in $name", rect.width() / 2f, (left + right) / 2f, 6f)
                assertEquals("Vertical focal point in $name", rect.height() / 2f, (top + bottom) / 2f, 6f)
                assertEquals("Retained horizontal scale in $name", 60 * fit * 3, (right - left + 1).toFloat(), 6f)
                assertEquals("Retained vertical scale in $name", 80 * fit * 3, (bottom - top + 1).toFloat(), 6f)
            } finally { screen.recycle() }
            records.put(org.json.JSONObject().put("state", name)
                .put("viewportWidthPx", rect.width()).put("viewportHeightPx", rect.height())
                .put("centerPatchColor", Color.GREEN)
                .put("patchBoundsPx", org.json.JSONArray(listOf(left, top, right, bottom)))
                .put("expectedPatchWidthPx", 60 * fit * 3).put("expectedPatchHeightPx", 80 * fit * 3))
        }
        try {
            ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
                .putExtra("path", source.absolutePath).putExtra("route", ChangesPreviewRoute.IMAGE.name)
                .putExtra("mime", "image/png")).use { scenario ->
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await("Initial portrait image did not settle") { device.displayHeight > device.displayWidth && centerIs(Color.RED) }
                val rect = bounds()
                val fit = minOf(rect.width() / 300f, rect.height() / 400f)
                val x = (rect.centerX() + (135f - 150f) * fit).toInt()
                val y = (rect.centerY() + (100f - 200f) * fit).toInt()
                device.click(x, y); Thread.sleep(80); device.click(x, y)
                capture("portrait-zoomed")
                scenario.recreate(); capture("portrait-recreated")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("Tall image did not reach landscape geometry") { device.displayWidth > device.displayHeight && bounds().width() > bounds().height() }
                capture("landscape-zoomed")
                scenario.recreate(); capture("landscape-recreated")
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await("Tall image did not return to portrait geometry") { device.displayHeight > device.displayWidth && bounds().height() > bounds().width() }
                capture("portrait-return")
                val final = bounds()
                device.click(final.centerX(), final.centerY()); Thread.sleep(80); device.click(final.centerX(), final.centerY())
                await("Double-tap did not reset the tall image") { centerIs(Color.RED) }
                device.takeScreenshot(File(output, "reset.png"))
                File(output, "profiles.json").writeText(records.toString(2))
            }
        } finally { source.delete() }
    }

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
