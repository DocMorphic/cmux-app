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
class WorkbookPreviewRuntimeTest {
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
    private fun ready() = compose.waitUntil(45_000) { js("window.__cmuxWorkbookReady === true") == "true" }
    @Test fun formattedWorkbookNavigatesAndRestoresSelectedSheetAndRange() {
        file = File(compose.activity.cacheDir, "rich-workbook.xlsx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("workbook/rich.xlsx").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                FilePreviewContent(LocalFilePreview(file, file.length(), WorkbookPreviewPolicy.MIME, ChangesPreviewRoute.WORKBOOK))
            }
        } } }
        ready()
        assertEquals("true", js("""
            (() => {
              const cell = document.querySelector('[data-cell="A1"]'), style = getComputedStyle(cell);
              return document.getElementById('sheets').options.length === 2 && cell.colSpan === 2 &&
                style.backgroundColor === 'rgb(23, 54, 93)' && style.fontWeight === '700' &&
                document.querySelector('[data-cell="B2"]').textContent === '${'$'}1,234.50' &&
                !document.querySelector('[data-cell="A3"]') && !document.querySelector('[data-cell="A8"] a') &&
                document.querySelector('[data-cell="A10"]').textContent === '<script>unsafe</script>' &&
                !window.__cmuxUnsafeWorkbook;
            })()
        """.trimIndent()))
        compose.waitUntil(10_000) {
            val geometry = JSONObject(js("(() => { const r=document.querySelector('[data-cell=\"A1\"]').getBoundingClientRect(); return {width:visualViewport.width,x:r.x,y:r.y,w:r.width,h:r.height}; })()") ?: "{}")
            val origin = IntArray(2); var scale = 0.0; var width = 0
            compose.runOnUiThread { web(compose.activity.window.decorView)?.let {
                it.getLocationOnScreen(origin); width = it.width; scale = width / geometry.getDouble("width")
            } }
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                val left = (origin[0] + geometry.getDouble("x") * scale).toInt().coerceAtLeast(0)
                val right = (origin[0] + (geometry.getDouble("x") + geometry.getDouble("w")) * scale).toInt().coerceAtMost(minOf(image.width, origin[0] + width))
                val top = (origin[1] + geometry.getDouble("y") * scale).toInt().coerceAtLeast(0)
                val bottom = (origin[1] + (geometry.getDouble("y") + geometry.getDouble("h")) * scale).toInt().coerceAtMost(image.height)
                var blue = 0; var ink = 0
                for (y in top until bottom) for (x in left until right) {
                    val color = image.getPixel(x, y)
                    if (Color.red(color) in 15..35 && Color.green(color) in 45..65 && Color.blue(color) in 83..103) blue++
                    if (Color.red(color) > 220 && Color.green(color) > 220 && Color.blue(color) > 220) ink++
                }
                val painted = blue > 500 && ink > 20
                if (painted) compose.activity.openFileOutput("workbook-formatted.png", Context.MODE_PRIVATE).use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                painted
            } finally { image.recycle() }
        }
        js("document.querySelector('[data-cell=\"A9\"] a').click();")
        compose.waitUntil(5000) { js("document.getElementById('sheets').value === '1' && document.querySelector('[data-cell=\"B2\"]').textContent === 'Destination 日本語'") == "true" }
        // Let the native location bridge settle before exercising composition state restoration.
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore(); ready()
        assertEquals("true", js("document.getElementById('sheets').value === '1' && document.querySelector('[data-cell=\"B2\"]').textContent === 'Destination 日本語'"))
        js("document.getElementById('sheets').value='0'; document.getElementById('sheets').dispatchEvent(new Event('change')); document.getElementById('address').value='AJ205'; document.getElementById('go').click();")
        compose.waitUntil(5000) { js("document.querySelector('[data-cell=\"AJ205\"]').textContent === 'Last visible cell'") == "true" }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore(); ready()
        assertEquals("true", js("document.querySelector('[data-cell=\"AJ205\"]').textContent === 'Last visible cell'"))
    }
}
