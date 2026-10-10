package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
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
class RtfPreviewRuntimeTest {
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
        compose.runOnUiThread {
            val view = web(compose.activity.window.decorView)
            if (view == null) done.countDown() else view.evaluateJavascript(script) { result.set(it); done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result.get()
    }
    private fun start(): StateRestorationTester {
        file = File(compose.activity.cacheDir, "rich-reader.rtf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("rtf/rich.rtf").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val state = StateRestorationTester(compose)
        state.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val route = filePreviewRoute("binary", "text/rtf", file.name)
                FilePreviewContent(LocalFilePreview(file, file.length(), RtfPreviewPolicy.MIME, route))
            }
        } } }
        ready()
        return state
    }
    private fun ready() {
        compose.waitUntil(45_000) { js("window.__cmuxRtfReady === true") == "true" }
        compose.waitUntil(5_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
    }
    private fun settlePaint() {
        compose.waitForIdle()
        val drawn = CountDownLatch(1)
        compose.runOnUiThread {
            checkNotNull(web(compose.activity.window.decorView)).let { view ->
                view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        view.postOnAnimation { view.postOnAnimation { drawn.countDown() } }
                    }
                })
            }
        }
        assertTrue("WebView did not finish drawing", drawn.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @Test fun authoredRtfRendersOfflineWithSafeFieldsAndPaintedRasterPicture() {
        start()
        assertEquals("true", js("""
            (() => {
              const content=document.getElementById('content');
              return ['RTF preview fixture','Résumé 日本語','Paragraph 80','RTF_FINAL_MARKER'].every(t=>content.textContent.includes(t)) &&
                content.querySelectorAll('a').length===1 && content.querySelector('img').naturalWidth===64 &&
                Array.from(content.querySelectorAll('svg')).filter(n=>!n.parentElement.closest('svg')).length===2 &&
                !content.querySelector('script,iframe,object');
            })()
        """.trimIndent()))
        assertEquals("true", js("!window.__cmuxUnsafeRtf && !Array.from(document.querySelectorAll('[href]')).some(n=>n.getAttribute('href').startsWith('javascript:'))"))
        js("window.__blockedRtfFetch=null;fetch('https://example.invalid/private').then(r=>window.__blockedRtfFetch=r.status).catch(()=>window.__blockedRtfFetch='blocked');")
        compose.waitUntil(5_000) { js("window.__blockedRtfFetch==='blocked' || window.__blockedRtfFetch===403") == "true" }
        js("document.querySelector('img').scrollIntoView({block:'center'});")
        settlePaint()
        compose.waitUntil(15_000) {
            val geometry = JSONObject(js("""
                (()=>{const r=document.querySelector('img').getBoundingClientRect(),v=visualViewport;
                return {width:innerWidth,x:r.x-(v?.offsetLeft||0),y:r.y-(v?.offsetTop||0),w:r.width,h:r.height};})()
            """.trimIndent()) ?: "{}")
            val origin = IntArray(2); var scale = 0.0
            compose.runOnUiThread { web(compose.activity.window.decorView)?.let {
                it.getLocationOnScreen(origin); scale = it.width / geometry.getDouble("width")
            } }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                fun sample(fraction: Double): Int? {
                    val x = (origin[0] + (geometry.getDouble("x") + geometry.getDouble("w") * fraction) * scale).toInt()
                    val y = (origin[1] + (geometry.getDouble("y") + geometry.getDouble("h") * .5) * scale).toInt()
                    return if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else null
                }
                val red = sample(.25); val blue = sample(.75)
                val painted = red != null && blue != null && Color.red(red) > 180 && Color.blue(red) < 80 &&
                    Color.blue(blue) > 180 && Color.red(blue) < 80
                if (painted) compose.activity.openFileOutput("rtf-rich-preview.png", Context.MODE_PRIVATE).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                painted
            } finally { bitmap.recycle() }
        }
    }
    @Test fun savedReaderViewportRestoresItsScrollPositionAfterRemount() {
        val restore = start()
        js("window.scrollTo(0,document.documentElement.scrollHeight);")
        compose.waitUntil(5_000) { js("window.scrollY > 1000") == "true" }
        // Run native capture before saving; restoration owns only reader location.
        compose.waitForIdle()
        val before = (js("window.scrollY") ?: "0").toDouble()
        restore.emulateSavedInstanceStateRestore()
        ready()
        compose.waitUntil(10_000) { js("window.scrollY > 1000") == "true" }
        compose.waitUntil(10_000) { kotlin.math.abs((js("window.scrollY") ?: "0").toDouble() - before) < 80 }
        assertEquals("true", js("""
            (()=>{const content=document.getElementById('content'),r=content.lastElementChild.getBoundingClientRect();
            return content.textContent.includes('RTF_FINAL_MARKER') && r.bottom>0 && r.top<innerHeight;})()
        """.trimIndent()))
        settlePaint()
        // A DOM/scroll match cannot prove the new WebView has painted its content.
        compose.waitUntil(15_000) {
            val geometry = JSONObject(js("""
                (()=>{const range=document.createRange();range.selectNodeContents(document.getElementById('content').lastElementChild);
                const r=range.getBoundingClientRect(),v=visualViewport;
                return {width:v.width,x:r.x-v.offsetLeft,y:r.y-v.offsetTop,w:r.width,h:r.height};})()
            """.trimIndent()) ?: "{}")
            val origin = IntArray(2); var scale = 0.0; var width = 0; var height = 0
            compose.runOnUiThread { checkNotNull(web(compose.activity.window.decorView)).let {
                it.getLocationOnScreen(origin); width = it.width; height = it.height; scale = width / geometry.getDouble("width")
            } }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                val left = (origin[0] + geometry.getDouble("x") * scale).toInt().coerceAtLeast(origin[0])
                val right = (origin[0] + (geometry.getDouble("x") + geometry.getDouble("w")) * scale).toInt().coerceAtMost(minOf(bitmap.width, origin[0] + width))
                val top = (origin[1] + geometry.getDouble("y") * scale).toInt().coerceAtLeast(origin[1])
                val bottom = (origin[1] + (geometry.getDouble("y") + geometry.getDouble("h")) * scale).toInt().coerceAtMost(minOf(bitmap.height, origin[1] + height))
                var ink = 0; var paper = 0
                for (y in top until bottom) for (x in left until right) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) ink++
                    if (Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240) paper++
                }
                compose.activity.openFileOutput("rtf-restored-final.png", Context.MODE_PRIVATE).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                File(compose.activity.filesDir, "rtf-restored-final.json").writeText(geometry.put("ink", ink).put("paper", paper)
                    .put("left", left).put("top", top).put("right", right).put("bottom", bottom).toString())
                ink > 30 && paper > 500
            } finally { bitmap.recycle() }
        }
    }
}
