package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class PresentationPreviewRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var file: File
    @After fun cleanup() { compose.activity.finish(); if (::file.isInitialized) file.delete() }
    private fun web(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) }
        else -> null
    }
    private fun js(script: String): String? {
        val result = AtomicReference<String?>(); val done = CountDownLatch(1)
        compose.runOnUiThread { val view = web(compose.activity.window.decorView)
            if (view == null) done.countDown() else view.evaluateJavascript(script) { result.set(it); done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result.get()
    }
    private fun paintedFrame() {
        compose.waitForIdle()
        val done = CountDownLatch(1)
        compose.runOnUiThread {
            checkNotNull(web(compose.activity.window.decorView)).let { view ->
                view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        // The callback makes DOM changes eligible for the next draw. Wait for
                        // two actual frame callbacks before sampling the screen compositor.
                        view.postOnAnimation { view.postOnAnimation { done.countDown() } }
                    }
                })
            }
        }
        assertTrue("WebView did not paint the requested frame", done.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun screenshot(name: String) {
        paintedFrame()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { compose.activity.openFileOutput(name, Context.MODE_PRIVATE).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
    private fun ready() = compose.waitUntil(45_000) { js("window.__cmuxPresentationReady === true") == "true" }
    private fun go(index: Int) {
        js("document.getElementById('slide').value='$index'; document.getElementById('go').click();")
        compose.waitUntil(10_000) { js("document.getElementById('slide').value === '$index' && !document.getElementById('go').disabled") == "true" }
    }
    @Test fun slidesRenderImageTableChartAndRetainSelectedSlideWithoutRemoteRequests() {
        file = File(compose.activity.cacheDir, "rich.pptx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("presentation/rich.pptx").use { input -> file.outputStream().use { input.copyTo(it) } }
        val restore = StateRestorationTester(compose)
        restore.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            FilePreviewContent(LocalFilePreview(file, file.length(), PresentationPreviewPolicy.MIME, ChangesPreviewRoute.PRESENTATION))
        } } } }
        ready()
        assertEquals("true", js("document.getElementById('content').textContent.includes('CMUX POWERPOINT') && document.getElementById('count').textContent === 'of 3'"))
        compose.waitUntil(10_000) {
            val raw = js("(() => { const image=document.querySelector('#content img'); if(!image || !image.complete)return null; const r=image.getBoundingClientRect(); return {width:innerWidth,x:r.x,y:r.y,w:r.width,h:r.height}; })()")
            if (raw == null || raw == "null") false else {
                val rect = JSONObject(raw); val origin = IntArray(2); var scale = 0.0
                compose.runOnUiThread { web(compose.activity.window.decorView)?.let { it.getLocationOnScreen(origin); scale = it.width / rect.getDouble("width") } }
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    val x = (origin[0] + (rect.getDouble("x") + rect.getDouble("w") / 2) * scale).toInt()
                    val y = (origin[1] + (rect.getDouble("y") + rect.getDouble("h") / 2) * scale).toInt()
                    x in 0 until image.width && y in 0 until image.height && image.getPixel(x,y).let { Color.red(it) > 180 && Color.green(it) < 80 && Color.blue(it) < 80 }
                } finally { image.recycle() }
            }
        }
        screenshot("presentation-cover.png")
        js("window.__blockedFetch=null; fetch('https://example.invalid/untrusted').then(r=>window.__blockedFetch=r.status).catch(()=>window.__blockedFetch='blocked');")
        compose.waitUntil(5000) { js("window.__blockedFetch === 'blocked' || window.__blockedFetch === 403") == "true" }
        assertEquals("true", js("(() => { const n=Array.from(document.querySelectorAll('#content *')).find(n=>!n.children.length && n.textContent === 'Blocked active link'); if(!n)return false; n.click(); return true; })()"))
        assertEquals("true", js("!window.__cmuxUnsafeLink"))
        assertEquals("true", js("(() => { const n=Array.from(document.querySelectorAll('#content *')).find(n=>!n.children.length && n.textContent === 'Jump to final slide'); if(!n)return false; n.click(); return true; })()"))
        compose.waitUntil(10_000) { js("document.getElementById('slide').value === '3' && document.getElementById('content').textContent.includes('Final slide 你好')") == "true" }
        go(2)
        compose.waitUntil(10_000) { js("document.getElementById('content').textContent.includes('Revenue and plan') && document.getElementById('content').textContent.includes('$175') && document.querySelectorAll('#content svg, #content canvas').length > 0") == "true" }
        paintedFrame()
        compose.waitUntil(10_000) {
            val raw = js("(() => { const c=document.querySelector('#content canvas'); if(!c)return null; const r=c.getBoundingClientRect(); return {width:innerWidth,x:r.x,y:r.y,w:r.width,h:r.height}; })()")
            if (raw == null || raw == "null") false else {
                val rect = JSONObject(raw); val origin = IntArray(2); var scale = 0.0
                compose.runOnUiThread { web(compose.activity.window.decorView)?.let { it.getLocationOnScreen(origin); scale = it.width / rect.getDouble("width") } }
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    val left = (origin[0] + rect.getDouble("x") * scale).toInt().coerceIn(0, image.width)
                    val top = (origin[1] + rect.getDouble("y") * scale).toInt().coerceIn(0, image.height)
                    val right = (left + rect.getDouble("w") * scale).toInt().coerceIn(left, image.width)
                    val bottom = (top + rect.getDouble("h") * scale).toInt().coerceIn(top, image.height)
                    var colored = 0
                    for (y in top until bottom step 3) for (x in left until right step 3) {
                        val pixel = image.getPixel(x,y)
                        if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) -
                            minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 60) colored++
                    }
                    colored > 100
                } finally { image.recycle() }
            }
        }
        screenshot("presentation-chart.png")
        go(3)
        compose.waitUntil(10_000) { js("document.getElementById('content').textContent.includes('Final slide 你好')") == "true" }
        restore.emulateSavedInstanceStateRestore(); ready()
        assertEquals("true", js("document.getElementById('slide').value === '3' && document.getElementById('content').textContent.includes('Final slide 你好') && document.getElementById('next').disabled"))
        screenshot("presentation-restored.png")
        js("document.getElementById('previous').click();")
        compose.waitUntil(10_000) { js("document.getElementById('slide').value === '2' && !document.getElementById('go').disabled") == "true" }
        go(0)
        assertEquals("true", js("document.getElementById('status').textContent.includes('Enter a slide number') && document.getElementById('content').textContent.includes('Revenue and plan')"))
    }
}
