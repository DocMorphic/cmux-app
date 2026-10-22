package io.github.docmorphic.cmuxapp

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
        compose.setContent { CmuxTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { FilePreviewContent(LocalFilePreview(file, size, "text/markdown", ChangesPreviewRoute.TEXT)) }
        } } }
    }
    @Test fun appearanceChangesKeepTheDocumentAlive() {
        // The upstream JS offers consent for ::2; the Android address policy rejects this
        // unspecified-prefix literal before DNS/socket access. This exercises the real consent
        // closure without fetching an external resource or relaxing the production policy.
        // Raw HTML keeps the IPv6 brackets intact; pinned marked percent-encodes them in image syntax.
        assertNull(MarkdownImagePolicy.url("https://[::2]/approved.png"))
        val dark = androidx.compose.runtime.mutableStateOf(false)
        val viewport = MarkdownViewportState()
        compose.setContent { CmuxTheme(darkTheme = dark.value) { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                MarkdownWebPreview("# Appearance\n\n<img src=\"https://[::2]/approved.png\" alt=\"Approved\">\n\n<img src=\"https://[::2]/blocked.png\" alt=\"Blocked\">\n\n" + (1..50).joinToString("\n\n") { "## Section $it\n\nVisible document text." }, viewport) { error(it) }
            }
        } } }
        waitJs("document.querySelector('h1')?.textContent === 'Appearance'")
        waitJs("!matchMedia('(prefers-color-scheme: dark)').matches")
        var original: WebView? = null
        var baseScale = 0f
        val initialFrame = CountDownLatch(1)
        compose.runOnUiThread {
            original = findWeb(compose.activity.window.decorView)
            original!!.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { initialFrame.countDown() }
            })
        }
        assertTrue("Initial Markdown frame did not commit", initialFrame.await(5, TimeUnit.SECONDS))
        var zoomRequested = false
        try {
            compose.runOnUiThread {
                baseScale = original!!.scale
                assertTrue("Committed Markdown viewport must have positive dimensions and scale", baseScale > 0 && original!!.width > 0 && original!!.height > 0)
                original!!.zoomBy(1.3f); zoomRequested = true
            }
            compose.waitUntil(5_000) { var zoomed = false; compose.runOnUiThread { zoomed = original!!.scale > baseScale * 1.2f }; zoomed }
        } finally {
            var zoom = JSONObject()
            compose.runOnUiThread { zoom = JSONObject().put("beforeScale", baseScale.toDouble()).put("afterScale", original!!.scale.toDouble())
                .put("zoomRequested", zoomRequested).put("width", original!!.width).put("height", original!!.height) }
            File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                .resolve("markdown-initial-zoom.json").writeText(zoom.toString(2))
        }
        var readingScale = 0f
        compose.runOnUiThread { readingScale = original!!.scale }
        assertEquals("true", js("""
            (() => { try {
            const load = document.querySelector('[data-cmux-remote-action="load"]');
            const heading = document.getElementById('section-8');
            if (!load || !heading) return JSON.stringify({missingLoad:!load,missingHeading:!heading,html:document.getElementById('content').innerHTML.slice(0,2500)});
            window.cmuxAppearanceDocument = {};
            window.approvedAttempts = 0; window.unapprovedAttempts = 0;
            new MutationObserver(records => records.forEach(record => {
                if (record.attributeName !== 'src') return;
                const value = record.target.getAttribute('src') || record.oldValue || '';
                if (!value.startsWith('cmux-remote-image:')) return;
                if (value.includes('approved.png')) window.approvedAttempts++;
                if (value.includes('blocked.png')) window.unapprovedAttempts++;
            })).observe(document.getElementById('content'), {subtree:true, attributes:true, attributeOldValue:true});
            load.click(); return true;
            } catch (e) { return String(e.stack || e); } })()
        """.trimIndent()))
        waitJs("window.approvedAttempts > 0 && window.unapprovedAttempts === 0 && !document.querySelector('img[data-cmux-remote-loading]')")
        // The shell enables smooth scrolling. Measure only after the explicit setup jump and
        // image-error layout have settled, rather than capturing an in-flight animation frame.
        assertEquals("true", js("""
            window.cmuxFixtureScrolled = false;
            document.getElementById('section-8').scrollIntoView({behavior:'instant',block:'start'});
            requestAnimationFrame(() => requestAnimationFrame(() => window.cmuxFixtureScrolled = true)); true
        """.trimIndent()))
        waitJs("window.cmuxFixtureScrolled && (window.visualViewport?.pageTop || scrollY) > 500")
        val before = js("(document.getElementById('section-8').getBoundingClientRect().top - (window.visualViewport?.offsetTop || 0))")!!.toDouble()
        var attempts = js("window.approvedAttempts")!!.toInt()
        val retention = JSONArray().put(JSONObject().put("dark", false).put("anchorTop", before).put("approvedAttempts", attempts))
        for (next in listOf(true, false)) {
            compose.runOnIdle { dark.value = next }
            waitJs("matchMedia('(prefers-color-scheme: dark)').matches === $next")
            waitJs("!!window.cmuxAppearanceDocument && document.querySelector('h1')?.textContent === 'Appearance'")
            compose.runOnUiThread {
                assertSame(original, findWeb(compose.activity.window.decorView))
                assertEquals("Theme change reset reader zoom", readingScale, original!!.scale, .02f)
            }
            waitJs("window.approvedAttempts > $attempts && window.unapprovedAttempts === 0")
            attempts = js("window.approvedAttempts")!!.toInt()
            waitJs("Math.abs((document.getElementById('section-8').getBoundingClientRect().top - (window.visualViewport?.offsetTop || 0)) - $before) < 2")
            retention.put(JSONObject().put("dark", next).put("anchorTop", js("(document.getElementById('section-8').getBoundingClientRect().top - (window.visualViewport?.offsetTop || 0))")!!.toDouble())
                .put("approvedAttempts", attempts).put("unapprovedAttempts", js("window.unapprovedAttempts")!!.toInt()))
        }
        File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }.resolve("markdown-appearance-retention.json")
            .writeText(JSONObject().put("sameWebViewAndDocument", true).put("readingScale", readingScale.toDouble()).put("states", retention).toString(2))
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
    private fun waitJs(expression: String) {
        try { compose.waitUntil(45_000) { js(expression) == "true" } }
        catch (error: Throwable) {
            val state = runCatching { js("""
                ({approved:window.approvedAttempts,unapproved:window.unapprovedAttempts,scrollY,
                anchor:document.getElementById('section-8')?.getBoundingClientRect().top,
                viewport:window.visualViewport?{pageTop:visualViewport.pageTop,offsetTop:visualViewport.offsetTop,scale:visualViewport.scale}:null,
                images:Array.from(document.querySelectorAll('#content img')).map(e=>e.outerHTML)})
            """.trimIndent()) }.getOrNull()
            throw AssertionError("Markdown condition: $expression; state=$state", error)
        }
    }

    /** DOM completion alone does not prove Chromium has painted the diagram. */
    private fun assertPaintedDiagrams(dark: Boolean = true, name: String = "markdown") {
        var lastScreenshot: Bitmap? = null
        var lastGeometry: String? = null
        var lastCounts = emptyList<Int>()
        try {
            compose.waitUntil(15_000) {
                lastGeometry = js("""
                    (() => { const rect = e => { const r=e.getBoundingClientRect(); return [r.left,r.top,r.right,r.bottom]; };
                    return {width: innerWidth, heading:rect(document.querySelector('h1')), keyword:rect(document.querySelector('.hljs-keyword')),
                    vega:rect(document.querySelector('.cmux-vega svg')), labels: Array.from(document.querySelectorAll('.cmux-mermaid .node .nodeLabel')).map(e => {
                        const r=e.getBoundingClientRect(); return [r.left,r.top,r.right,r.bottom];
                    }), bars: Array.from(document.querySelectorAll('.cmux-vega .mark-rect.role-mark path')).map(e => {
                        const r=e.getBoundingClientRect(); return [r.left,r.top,r.right,r.bottom];
                    })};})()
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
                    val label = if (dark) 204 else 51
                    pixels(labels.getJSONArray(index)) { r, g, b -> kotlin.math.abs(r-label) <= 8 && kotlin.math.abs(g-label) <= 8 && kotlin.math.abs(b-label) <= 8 }
                } + (0 until labels.length()).map { index ->
                    val background = if (dark) intArrayOf(31,32,32) else intArrayOf(236,236,255)
                    pixels(labels.getJSONArray(index)) { r, g, b -> kotlin.math.abs(r-background[0]) <= 8 && kotlin.math.abs(g-background[1]) <= 8 && kotlin.math.abs(b-background[2]) <= 8 }
                } + (0 until bars.length()).map { index ->
                    pixels(bars.getJSONArray(index)) { r, g, b -> b > r + 30 && g > r + 15 }
                }
                fun expectedPixels(key: String, color: IntArray): Int = pixels(geometry.getJSONArray(key)) { r,g,b ->
                    kotlin.math.abs(r-color[0]) <= 8 && kotlin.math.abs(g-color[1]) <= 8 && kotlin.math.abs(b-color[2]) <= 8
                }
                lastCounts = lastCounts + listOf(
                    expectedPixels("heading", if (dark) intArrayOf(230,237,243) else intArrayOf(31,35,40)),
                    expectedPixels("keyword", if (dark) intArrayOf(255,123,114) else intArrayOf(215,58,73)),
                    expectedPixels("vega", if (dark) intArrayOf(51,51,51) else intArrayOf(255,255,255)))
                val vega = geometry.getJSONArray("vega")
                val backgroundArea = (vega.getDouble(2)-vega.getDouble(0)) * (vega.getDouble(3)-vega.getDouble(1)) * scale * scale
                focused && labels.length() == 2 && bars.length() == 2 && lastCounts.all { it > 30 } && lastCounts.last() > backgroundArea * .25
            }
        } finally {
            // Retain the actual last frame even on failure, before Activity teardown.
            lastScreenshot?.let { screenshot ->
                File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }.resolve("$name-rendered.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }.resolve("$name-paint-evidence.json").outputStream().use {
                it.write(JSONObject().put("dark", dark).put("geometry", lastGeometry).put("countOrder", "two Mermaid text, two Mermaid backgrounds, two Vega bars, heading, keyword, Vega background").put("inkPixels", JSONArray(lastCounts)).toString(2).toByteArray())
            }
        }
    }

    @Test fun diagramsCodeAndSurfaceFollowLightDarkLight() {
        val dark = androidx.compose.runtime.mutableStateOf(false)
        val viewport = MarkdownViewportState()
        val text = """
            # Appearance

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
            {"height":100,"data":{"values":[{"label":"A","value":2},{"label":"B","value":4}]},"mark":"bar","encoding":{"x":{"field":"label","type":"nominal"},"y":{"field":"value","type":"quantitative"}}}
            ```
        """.trimIndent()
        compose.setContent { CmuxTheme(darkTheme = dark.value) { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { MarkdownWebPreview(text, viewport) { error(it) } }
        } } }
        for ((index, next) in listOf(false, true, false).withIndex()) {
            compose.runOnIdle { dark.value = next }
            waitJs("matchMedia('(prefers-color-scheme: dark)').matches === $next && !window.__cmuxAndroidPendingTheme")
            waitJs("document.querySelectorAll('.cmux-mermaid svg .node').length === 2 && document.querySelectorAll('.cmux-vega svg').length > 0")
            val fg = if (next) "rgb(230, 237, 243)" else "rgb(31, 35, 40)"
            val keyword = if (next) "rgb(255, 123, 114)" else "rgb(215, 58, 73)"
            waitJs("getComputedStyle(document.querySelector('h1')).color === '$fg' && getComputedStyle(document.querySelector('.hljs-keyword')).color === '$keyword'")
            waitJs("document.getElementById('hljs-dark').disabled === ${!next} && document.getElementById('hljs-light').disabled === $next")
            assertEquals("true", js("getComputedStyle(document.getElementById('content')).backgroundColor === 'rgba(0, 0, 0, 0)' && document.getElementById('content').style.getPropertyValue('--bgColor-muted').startsWith('rgba(')"))
            assertPaintedDiagrams(next, "markdown-appearance-$index")
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
