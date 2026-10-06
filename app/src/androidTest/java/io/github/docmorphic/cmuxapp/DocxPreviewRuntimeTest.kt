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
class DocxPreviewRuntimeTest {
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
    @Test fun wordDocumentRendersRichContentAndBlocksActiveContentAndNetwork() {
        file = File(compose.activity.cacheDir, "rich.docx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("docx/rich.docx").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                FilePreviewContent(LocalFilePreview(file, file.length(), DocxPreviewPolicy.MIME, ChangesPreviewRoute.DOCX))
            }
        } } }
        compose.waitUntil(45_000) { js("window.__cmuxDocxReady === true") == "true" }
        assertEquals("true", js("""
            (() => {
              const text = document.getElementById('content').textContent;
              return ['Word preview fixture','Résumé 日本語','Embedded table','CMUX DOCX HEADER',
                'CMUX DOCX FOOTER','Offline footnote','Second page content'].every(s => text.includes(s)) &&
                document.querySelectorAll('section.docx').length === 2 &&
                document.querySelectorAll('table').length === 1 &&
                document.querySelector('img').naturalWidth === 120 &&
                document.querySelectorAll('iframe').length === 0 && !text.includes('ACTIVE HTML MUST NOT RENDER');
            })()
        """.trimIndent()))
        // Programmatic activation must not execute an active hyperlink from the document.
        js("Array.from(document.querySelectorAll('a')).find(a => a.textContent === 'Blocked active link').click();")
        assertEquals("true", js("!window.__cmuxUnsafeLink && !window.__cmuxUnsafeChunk"))
        js("window.__blockedFetch = null; fetch('https://example.invalid/untrusted-image').then(r => window.__blockedFetch = r.status).catch(() => window.__blockedFetch = 'blocked');")
        compose.waitUntil(5000) { js("window.__blockedFetch === 'blocked' || window.__blockedFetch === 403") == "true" }
        // A resolved DOM/image promise alone does not prove the image was painted.
        compose.waitUntil(10_000) {
            val geometry = JSONObject(js("(() => { const r=document.querySelector('img').getBoundingClientRect(); return {width:innerWidth,x:r.x,y:r.y,w:r.width,h:r.height}; })()") ?: "{}")
            val origin = IntArray(2); var scale = 0.0
            compose.runOnUiThread { web(compose.activity.window.decorView)?.let {
                it.getLocationOnScreen(origin); scale = it.width / geometry.getDouble("width")
            } }
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try {
                val x = (origin[0] + (geometry.getDouble("x") + geometry.getDouble("w") / 2) * scale).toInt()
                val y = (origin[1] + (geometry.getDouble("y") + geometry.getDouble("h") / 2) * scale).toInt()
                val painted = x in 0 until image.width && y in 0 until image.height && image.getPixel(x, y).let {
                    Color.red(it) > 180 && Color.green(it) < 80 && Color.blue(it) < 80
                }
                if (painted) compose.activity.openFileOutput("docx-rich-preview.png", Context.MODE_PRIVATE).use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                painted
            } finally { image.recycle() }
        }
    }
}
