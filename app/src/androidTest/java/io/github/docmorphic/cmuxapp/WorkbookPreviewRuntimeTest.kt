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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
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
    private fun ready() {
        compose.waitUntil(45_000) { js("window.__cmuxWorkbookReady === true") == "true" }
        compose.waitUntil(5000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
    }
    @Test fun formattedWorkbookNavigatesAndRestoresSelectedSheetAndRange() {
        file = File(compose.activity.cacheDir, "rich-workbook.xlsx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("workbook/rich-runs.xlsx").use { input ->
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
        val cellLayout = JSONObject(js("""
            (() => {
              const cell=document.querySelector('[data-cell="B2"]'), range=document.createRange();
              range.selectNodeContents(cell);
              return {width:cell.getBoundingClientRect().width,
                lines:new Set([...range.getClientRects()].map(rect => Math.round(rect.top))).size,
                whitespace:getComputedStyle(cell).whiteSpace};
            })()
        """.trimIndent()) ?: "{}")
        // The fixture's authored 18-character column is 108 CSS px at its saved font metrics.
        assertEquals(cellLayout.toString(), 108.0, cellLayout.getDouble("width"), 1.0)
        assertEquals(cellLayout.toString(), 1, cellLayout.getInt("lines"))
        assertEquals(cellLayout.toString(), "pre", cellLayout.getString("whitespace"))
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
        assertEquals("true", js("""
            (() => {
              const runs = address => [...document.querySelectorAll('[data-cell="'+address+'"] .rich-run')];
              const inline = runs('A11'), shared = runs('A12'), overrides = runs('A13');
              return inline.length === 2 && getComputedStyle(inline[0]).color === 'rgb(255, 0, 0)' &&
                getComputedStyle(inline[0]).fontWeight === '700' && getComputedStyle(inline[1]).fontStyle === 'italic' &&
                shared.length === 5 && getComputedStyle(shared[1]).verticalAlign === 'sub' &&
                getComputedStyle(shared[3]).verticalAlign === 'super' &&
                document.querySelector('[data-cell="A12"]').textContent === 'H2O + x2 <script>unsafe</script>' &&
                !document.querySelector('#content script') &&
                getComputedStyle(overrides[0]).fontWeight === '400' &&
                getComputedStyle(overrides[0]).textDecorationLine === 'none' &&
                getComputedStyle(overrides[1]).textDecorationLine.includes('line-through') &&
                getComputedStyle(overrides[1]).textDecorationStyle === 'double' &&
                runs('A14').length === 2 && document.querySelector('[data-cell="A14"]').textContent === '_x0041_ literal';
            })()
        """.trimIndent()))
        js("document.querySelector('[data-cell=\"A11\"]').scrollIntoView({block:'center'});")
        compose.waitUntil(10_000) {
            val geometry = JSONObject(js("(() => { const r=document.querySelector('[data-cell=\"A11\"]').getBoundingClientRect(); return {width:visualViewport.width,x:r.x,y:r.y,w:r.width,h:r.height}; })()") ?: "{}")
            val origin = IntArray(2); var scale = 0.0; var width = 0; var height = 0
            compose.runOnUiThread { web(compose.activity.window.decorView)?.let {
                it.getLocationOnScreen(origin); width = it.width; height = it.height; scale = width / geometry.getDouble("width")
            } }
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                val left = (origin[0] + geometry.getDouble("x") * scale).toInt().coerceAtLeast(origin[0])
                val right = (origin[0] + (geometry.getDouble("x") + geometry.getDouble("w")) * scale).toInt().coerceAtMost(minOf(image.width, origin[0] + width))
                val top = (origin[1] + geometry.getDouble("y") * scale).toInt().coerceAtLeast(origin[1])
                val bottom = (origin[1] + (geometry.getDouble("y") + geometry.getDouble("h")) * scale).toInt().coerceAtMost(minOf(image.height, origin[1] + height))
                var red = 0; var green = 0
                for (y in top until bottom) for (x in left until right) {
                    val color = image.getPixel(x, y)
                    if (Color.red(color) > 160 && Color.green(color) < 90 && Color.blue(color) < 90) red++
                    if (Color.green(color) > 70 && Color.green(color) > Color.red(color) * 1.4 && Color.blue(color) < 90) green++
                }
                val painted = red > 30 && green > 30
                if (painted) compose.activity.openFileOutput("workbook-rich-runs.png", Context.MODE_PRIVATE).use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                painted
            } finally { image.recycle() }
        }
        js("document.querySelector('[data-cell=\"A12\"] a').click();")
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
