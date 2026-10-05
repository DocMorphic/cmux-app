package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class MarkdownViewportRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var anchorTop: Double? = null
    private fun View.web(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).web() }
        else -> null
    }
    private fun <T> ActivityScenario<ArtifactPreviewTestActivity>.read(block: (WebView?) -> T): T {
        var value: T? = null
        onActivity { value = block(it.window.decorView.web()) }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(message: String, block: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < end) { if (block()) return; Thread.sleep(100) }
        screenshot("failure"); fail(message)
    }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "markdown-lifecycle").apply { mkdirs() }
        // UiAutomator can capture before Chromium paints, even after JS/native scroll agree.
        awaitPaint@ for (attempt in 0..40) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            var ink = 0
            for (y in bitmap.height / 6 until bitmap.height * 4 / 5 step 3)
                for (x in bitmap.width / 8 until bitmap.width * 7 / 8 step 3) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) > 150 && android.graphics.Color.blue(pixel) > 150) ink++
                }
            if (ink > 1_000 || attempt == 40) {
                File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle(); assertTrue("Rendered viewport did not paint text ($ink sampled pixels)", ink > 1_000)
                break@awaitPaint
            }
            bitmap.recycle(); Thread.sleep(100)
        }
    }
    private fun fixture(block: (ActivityScenario<ArtifactPreviewTestActivity>) -> Unit) {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val file = File(context.cacheDir, "markdown-lifecycle.md").apply {
            writeText((1..100).joinToString("\n\n") { "## Section $it\n\nReading section $it. " + "A paragraph of visible text. ".repeat(8) })
        }
        try {
            ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
                .putExtra("path", file.absolutePath).putExtra("route", "TEXT").putExtra("mime", "text/markdown")).use { scenario ->
                await("Markdown did not render") { scenario.read { it != null && it.contentHeight > 4_000 } }
                block(scenario)
            }
        } finally { file.delete() }
    }
    private fun gesture(scenario: ActivityScenario<ArtifactPreviewTestActivity>): Pair<Float, Int> {
        val before = scenario.read { it!!.scale }
        assertTrue(device.findObject(UiSelector().className(WebView::class.java.name)).pinchOut(60, 30))
        await("Real pinch did not zoom") { scenario.read { it!!.scale > before * 1.2f } }
        val bounds = device.findObject(By.clazz(WebView::class.java.name)).visibleBounds
        device.swipe(bounds.centerX(), bounds.bottom - 150, bounds.centerX(), bounds.top + 150, 45)
        device.waitForIdle()
        await("Reader did not scroll") { scenario.read { it!!.scrollY > 500 } }
        Thread.sleep(350) // Let the final compositor scroll/anchor notification arrive.
        anchorTop = headingTop(scenario)
        screenshot("before")
        return scenario.read { it!!.scale to it.scrollY }
    }
    private fun headingTop(scenario: ActivityScenario<ArtifactPreviewTestActivity>): Double? {
        val latch = CountDownLatch(1); var answer: Double? = null
        scenario.read { web ->
            if (web == null) latch.countDown()
            else web.evaluateJavascript("(() => { const e=document.getElementById('section-4'); return e ? e.getBoundingClientRect().top - (window.visualViewport?.offsetTop || 0) : null; })()") {
                answer = it.toDoubleOrNull(); latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); return answer
    }
    private fun checkPosition(scenario: ActivityScenario<ArtifactPreviewTestActivity>, expected: Pair<Float, Int>) {
        try { await("Markdown viewport lost: wanted $expected") { scenario.read {
            it != null && kotlin.math.abs(it.scale - expected.first) < .04f && kotlin.math.abs(it.scrollY - expected.second) < 12
        } } } catch (error: AssertionError) {
            throw AssertionError("Wanted $expected, actual ${scenario.read { it?.let { web -> web.scale to web.scrollY } }}", error)
        }
        await("Visible paragraph shifted despite native scroll coordinates") {
            val top = headingTop(scenario)
            top != null && anchorTop != null && kotlin.math.abs(top - anchorTop!!) < 2
        }
        val painted = CountDownLatch(1)
        scenario.read { it!!.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { painted.countDown() }
        }) }
        assertTrue("Visual state did not commit", painted.await(5, TimeUnit.SECONDS))
        Thread.sleep(350) // Allow the committed frame to reach the screenshot surface.
        // Real document text must still be present after recovery, not only a scrollable blank view.
        val latch = CountDownLatch(1); var valid = false
        scenario.read { it!!.evaluateJavascript("document.querySelectorAll('#content h2').length === 100") { result -> valid = result == "true"; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertTrue(valid)
    }
    private fun action(name: String) {
        checkNotNull(device.wait(Until.findObject(By.desc("Viewer actions")), 10_000)).click()
        checkNotNull(device.wait(Until.findObject(By.text(name)), 10_000)).click()
    }
    @Test fun pinchAndReadingPositionSurviveRecreationAndRawToggle() = fixture { scenario ->
        val expected = gesture(scenario)
        scenario.recreate(); checkPosition(scenario, expected); screenshot("recreated")
        // Add exactly 180 CSS pixels, preserving the heading's existing stylesheet padding.
        scenario.read { it!!.evaluateJavascript("(() => { const e = document.querySelector('#content').children[0]; e.style.paddingBottom = (parseFloat(getComputedStyle(e).paddingBottom) + 180) + 'px'; })()", null) }
        val shifted = expected.first to (expected.second + (180 * expected.first).toInt())
        checkPosition(scenario, shifted); screenshot("late-layout")
        // Resume reading without another scroll event, then reopen the original document.
        // Its original layout must preserve the same paragraph-relative bookmark.
        val bounds = device.findObject(By.clazz(WebView::class.java.name)).visibleBounds
        device.click(bounds.centerX(), bounds.centerY()); device.waitForIdle()
        action("Raw"); await("Raw view absent") { scenario.read { it == null } }
        action("Rendered"); checkPosition(scenario, expected); screenshot("rendered-again")
    }
    @Test fun rendererTerminationRecoversViewportTwiceThenShowsRawSource() = fixture { scenario ->
        val expected = gesture(scenario)
        repeat(2) { attempt ->
            val old = scenario.read { it!! }
            scenario.read { assertTrue("Renderer not terminable", it!!.webViewRenderProcess!!.terminate()) }
            await("Renderer not replaced") { scenario.read { it != null && it !== old && it.contentHeight > 4_000 } }
            checkPosition(scenario, expected); screenshot("recovered-$attempt")
        }
        scenario.read { assertTrue(it!!.webViewRenderProcess!!.terminate()) }
        assertNotNull(device.wait(Until.findObject(By.text("Markdown renderer stopped. Showing raw source.")), 15_000))
        await("Failed renderer still mounted") { scenario.read { it == null } }
        await("Raw source missing after bounded recovery") {
            var text: String? = null
            scenario.onActivity { text = findArtifactText(it.window.decorView)?.textView?.text?.toString() }
            text?.startsWith("## Section 1") == true && text?.contains("## Section 100") == true
        }
        screenshot("bounded-fallback")
    }
}
