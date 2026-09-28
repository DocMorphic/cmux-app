package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class NativeMarkdownPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var file: File
    @After fun cleanup() { compose.activity.finish(); if (::file.isInitialized) file.delete() }
    private fun show(text: String, size: Long = text.toByteArray().size.toLong()) {
        file = File(compose.activity.cacheDir, "markdown-fixture.md").apply { writeText(text) }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { FilePreviewContent(LocalFilePreview(file, size, "text/markdown", ChangesPreviewRoute.TEXT)) }
        } } }
    }
    private fun findWeb(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
        else -> null
    }
    private fun js(script: String): String? {
        val answer = AtomicReference<String?>(); val done = CountDownLatch(1)
        compose.runOnUiThread {
            val web = findWeb(compose.activity.window.decorView)
            if (web == null) done.countDown() else web.evaluateJavascript(script) { answer.set(it); done.countDown() }
        }
        assertTrue("WebView evaluation timed out", done.await(5, TimeUnit.SECONDS))
        return answer.get()
    }
    private fun waitJs(expression: String) = compose.waitUntil(45_000) { js(expression) == "true" }

    /** DOM completion alone does not prove Chromium has painted the diagram. */
    private fun assertPaintedDiagrams() {
        var lastScreenshot: Bitmap? = null
        var lastGeometry: String? = null
        var lastCounts = emptyList<Int>()
        try {
            compose.waitUntil(15_000) {
                lastGeometry = js("""
                    (() => ({width: innerWidth, labels: Array.from(document.querySelectorAll('.cmux-mermaid .node .nodeLabel')).map(e => {
                        const r=e.getBoundingClientRect(); return [r.left,r.top,r.right,r.bottom];
                    }), bars: Array.from(document.querySelectorAll('.cmux-vega .mark-rect.role-mark path')).map(e => {
                        const r=e.getBoundingClientRect(); return [r.left,r.top,r.right,r.bottom];
                    })}))()
                """.trimIndent())
                val geometry = JSONObject(lastGeometry ?: "{}")
                val origin = IntArray(2)
                var scale = 0.0
                var focused = false
                compose.runOnUiThread {
                    findWeb(compose.activity.window.decorView)?.let { web ->
                        web.getLocationOnScreen(origin)
                        scale = web.width / geometry.getDouble("width")
                        focused = web.hasWindowFocus()
                    }
                }
                lastScreenshot?.recycle()
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                lastScreenshot = bitmap
                fun pixels(rect: JSONArray, isInk: (Int, Int, Int) -> Boolean): Int {
                    val left = origin[0] + rect.getDouble(0) * scale
                    val top = origin[1] + rect.getDouble(1) * scale
                    val right = origin[0] + rect.getDouble(2) * scale
                    val bottom = origin[1] + rect.getDouble(3) * scale
                    // A clipped/offscreen diagram is not visual acceptance.
                    if (left < 0 || top < 0 || right > bitmap.width || bottom > bitmap.height ||
                        right - left < 4 || bottom - top < 4) return 0
                    var count = 0
                    for (y in kotlin.math.ceil(top).toInt() until bottom.toInt())
                        for (x in kotlin.math.ceil(left).toInt() until right.toInt()) {
                            val color = bitmap.getPixel(x, y)
                            if (isInk(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))) count++
                        }
                    return count
                }
                val labels = geometry.getJSONArray("labels")
                val bars = geometry.getJSONArray("bars")
                lastCounts = (0 until labels.length()).map { index ->
                    pixels(labels.getJSONArray(index)) { r, g, b -> minOf(r, g, b) > 130 && maxOf(r, g, b) - minOf(r, g, b) < 25 }
                } + (0 until bars.length()).map { index ->
                    pixels(bars.getJSONArray(index)) { r, g, b -> b > r + 30 && g > r + 15 }
                }
                focused && labels.length() == 2 && bars.length() == 2 && lastCounts.all { it > 30 }
            }
        } finally {
            // Retain the actual last frame even on failure, before Activity teardown.
            lastScreenshot?.let { screenshot ->
                compose.activity.openFileOutput("markdown-rendered.png", Context.MODE_PRIVATE).use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            compose.activity.openFileOutput("markdown-paint-evidence.json", Context.MODE_PRIVATE).use {
                it.write(JSONObject().put("geometry", lastGeometry).put("inkPixels", JSONArray(lastCounts)).toString(2).toByteArray())
            }
        }
    }

    @Test fun sharedRendererDisplaysTablesCodeMermaidAndVegaAndSwitchesToRaw() {
        show("""
            # Parity document

            A **formatted** paragraph and [cmux](https://cmux.com).

            | Feature | Status |
            | --- | --- |
            | Markdown | Ready |

            ```kotlin
            val answer = 42
            ```

            ```mermaid
            graph LR
              Phone --> Mac
            ```

            ```vega-lite
            {"data":{"values":[{"label":"A","value":2},{"label":"B","value":4}]},"mark":"bar","encoding":{"x":{"field":"label","type":"nominal"},"y":{"field":"value","type":"quantitative"}}}
            ```
        """.trimIndent())
        waitJs("document.querySelector('h1')?.textContent === 'Parity document' && document.querySelectorAll('table tbody tr').length === 1 && document.querySelectorAll('code .hljs-keyword').length > 0")
        waitJs("document.querySelectorAll('.cmux-mermaid svg .node').length === 2 && document.querySelector('.cmux-mermaid svg').getBoundingClientRect().height > 40 && document.querySelectorAll('.cmux-vega svg').length > 0")
        assertEquals("true", js("window.matchMedia('(prefers-color-scheme: dark)').matches"))
        assertEquals("true", js("getComputedStyle(document.querySelector('h1')).color.match(/\\d+/g).slice(0,3).map(Number).reduce((a,b)=>a+b,0) > 500"))
        assertEquals("0", js("document.querySelectorAll('.cmux-render-error').length"))
        assertPaintedDiagrams()
        compose.onNodeWithContentDescription("Viewer actions").performClick()
        compose.onNodeWithText("Raw").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Raw text preview").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { assertTrue(findArtifactText(compose.activity.window.decorView)!!.textView.text.startsWith("# Parity document")) }
        compose.onNodeWithContentDescription("Viewer actions").performClick()
        compose.onNodeWithText("Rendered").performScrollTo().performClick()
        waitJs("document.querySelector('h1')?.textContent === 'Parity document'")
    }

    @Test fun renderedMarkupCannotExecuteScriptsOrLoadRemoteImagesBeforeConsent() {
        show("""
            # Safe document
            <script>window.markdownInjected = true;</script>
            <iframe src="https://example.com/hidden"></iframe>
            <img src="https://example.com/pixel.png" onerror="window.markdownInjected=true">
            <img src="cmux-remote-image://image?url=https%3A%2F%2Fexample.com%2Fhidden.png">
            [Unsafe](javascript:alert(1))
            [Relative](next.md)
        """.trimIndent())
        waitJs("document.querySelector('h1')?.textContent === 'Safe document'")
        assertEquals("true", js("window.markdownInjected !== true && document.querySelectorAll('#content script,#content iframe,[onerror]').length === 0"))
        assertEquals("true", js("Array.from(document.querySelectorAll('#content img')).every(i => !(i.getAttribute('src') || '').startsWith('http') && !(i.getAttribute('src') || '').startsWith('cmux-remote-image:'))"))
        assertEquals("true", js("document.querySelectorAll('.cmux-remote-image-placeholder').length > 0"))
    }

    @Test fun largeMarkdownKeepsRawContentAndDisablesRenderedMode() {
        show("# Large source", MarkdownPreviewPolicy.MAX_RENDERED_BYTES + 1)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Raw text preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Viewer actions").performClick()
        compose.onNodeWithText("Rendered").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Rendered Markdown").assertDoesNotExist()
    }
}
