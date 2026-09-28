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
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.openFileOutput("markdown-rendered.png", Context.MODE_PRIVATE).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithText("Raw", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("# Parity document").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("# Parity document").assertIsDisplayed()
        compose.onNodeWithText("Rendered", useUnmergedTree = true).performClick()
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
        compose.onNodeWithText("Rendered").assertIsNotEnabled()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("# Large source").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("# Large source").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rendered Markdown").assertDoesNotExist()
    }
}
