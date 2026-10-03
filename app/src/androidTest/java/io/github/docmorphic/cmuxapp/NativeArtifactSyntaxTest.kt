package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.Selection
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.io.File

@OptIn(ExperimentalTestApi::class)
class NativeArtifactSyntaxTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val files = mutableListOf<File>()
    @After fun cleanup() { compose.activity.finish(); files.forEach(File::delete) }
    private fun result(text: String, language: String?, dark: Boolean = true): ArtifactSyntaxResult = runBlocking {
        checkNotNull(ArtifactSyntaxHighlighter.highlight(compose.activity, text, language, dark)) { "Highlighter returned no result for $language" }
    }
    private fun color(result: ArtifactSyntaxResult, text: String) = result.runs.single { result.text.indexOf(text) in it.start until it.end }.color
    private fun native(block: (ArtifactTextScrollView) -> Unit) = compose.runOnUiThread {
        block(checkNotNull(findArtifactTextInWindows()))
    }
    private fun hasSyntax(): Boolean {
        var found = false
        compose.runOnUiThread { findArtifactTextInWindows()?.textView?.let { view ->
            found = (view.text as Spannable).getSpans(0, view.length(), ArtifactSyntaxSpan::class.java).isNotEmpty()
        } }
        return found
    }

    @Test fun pinnedEngineSupportsHaskellPureScriptXcodePalettesAndAutomaticDetection() {
        val haskell = "module Main where\r\nmain = putStrLn \"Hi 🙂\"\r\n"
        val dark = result(haskell, ArtifactSyntaxPolicy.language("Main.hs"))
        assertEquals(haskell, dark.text)
        assertEquals(0xFFFC5FA3.toInt(), color(dark, "module"))
        assertEquals(0xFFFC6A5D.toInt(), color(dark, "Hi 🙂"))
        val light = result("module Main where\nimport Prelude\nmain = log \"Hi\"", ArtifactSyntaxPolicy.language("Main.purs"), false)
        assertEquals(0xFFAA0D91.toInt(), color(light, "module"))
        assertEquals(0xFFC41A16.toInt(), color(light, "Hi"))
        val automatic = result("{\"message\": \"hello\", \"count\": 42, \"enabled\": true}", null)
        assertTrue(automatic.runs.any { it.color != -1 })
        assertFalse(automatic.language.isNullOrBlank())
        val emphasis = result("**bold** and *italic*", "markdown")
        assertTrue(emphasis.runs.single { emphasis.text.indexOf("bold") in it.start until it.end }.style and 1 != 0)
        assertTrue(emphasis.runs.single { emphasis.text.indexOf("italic") in it.start until it.end }.style and 2 != 0)
        val paint = android.text.TextPaint().apply { textSize = 18f }
        ArtifactSyntaxSpan(0xFFFC5FA3.toInt(), 3).updateDrawState(paint)
        assertTrue(paint.typeface.isBold && paint.typeface.isItalic)
        assertEquals(18f, paint.textSize, 0f)
    }

    @Test fun activeMarkupQuotesCrLfAndUnicodeRemainLiteralCode() {
        val text = """</script><script>window.cmuxNativeHighlight = null;</script>
            <img src="https://example.invalid/pixel" onerror="window.cmuxNativeHighlight=null">
            "); window.cmuxNativeHighlight = null; //
            const smile = "🙂 & < > ' ";
        """.trimIndent().replace("\n", "\r\n") + "\u2028\u2029"
        val highlighted = result(text, "html")
        assertEquals(text, highlighted.text)
        assertEquals(text.length, highlighted.runs.last().end)
        assertEquals("val stillWorks = 42", result("val stillWorks = 42", "kotlin").text)
    }

    @Test fun nativeLateColoringPreservesSelectionSearchFontViewportAndClipboard() {
        val text = (1..70).joinToString("\n") { "val item$it = \"Hello 🙂\" // $it" }
        val file = File(compose.activity.cacheDir, "syntax-native.kt").apply { writeText(text) }; files.add(file)
        val state = ArtifactViewerState(compose.activity, LocalFilePreview(file, file.length(), "text/plain", ChangesPreviewRoute.TEXT))
        val document = ArtifactTextDocument(text)
        state.document = document
        state.highlightedDocument = document // Hold publication to exercise a genuinely late result.
        state.setFont(18f)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { ArtifactRawTextPreview(state) }
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Raw text preview").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val start = text.indexOf("item45")
        val end = text.indexOf("item47")
        compose.runOnUiThread { state.query = "item45" }
        compose.waitUntil(10_000) { state.matches.size == 1 }
        compose.waitUntil(5_000) { var moved = false; native { moved = it.scrollY > 0 }; moved }
        var beforeScroll = 0; var beforeSize = 0f
        native { view ->
            Selection.setSelection(view.textView.text as Spannable, start, end)
            beforeScroll = view.scrollY; beforeSize = view.textView.textSize
        }
        val highlighted = result(text, "kotlin")
        compose.runOnUiThread { state.syntax = highlighted }
        compose.waitUntil(10_000) { hasSyntax() }
        native { view ->
            val buffer = view.textView.text as Spannable
            assertEquals(text, buffer.toString())
            assertEquals(start, Selection.getSelectionStart(buffer)); assertEquals(end, Selection.getSelectionEnd(buffer))
            assertEquals(beforeSize, view.textView.textSize, 0f)
            assertEquals(beforeScroll, view.scrollY)
            assertEquals(1, buffer.getSpans(0, buffer.length, BackgroundColorSpan::class.java).size)
            assertTrue(view.textView.onTextContextMenuItem(android.R.id.copy))
            assertEquals(text.substring(start, end), (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.runOnUiThread { state.closeSearch(); state.jumpTo(0) }
        compose.waitUntil(5_000) { var atTop = false; native { atTop = it.scrollY == 0 }; atTop }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.openFileOutput("artifact-syntax.png", Context.MODE_PRIVATE).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
    }

    @Test fun realViewerAutomaticallyHighlightsPureScriptWithNativeSpans() {
        val text = "module Main where\nimport Prelude\nmain = log \"Hello Pixel\""
        val file = File(compose.activity.cacheDir, "Main.purs").apply { writeText(text) }; files.add(file)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { FilePreviewContent(LocalFilePreview(file, file.length(), "text/plain", ChangesPreviewRoute.TEXT)) }
        } } }
        compose.waitUntil(25_000) { hasSyntax() }
        native { view ->
            val buffer = view.textView.text as Spannable
            val first = buffer.getSpans(0, 1, ArtifactSyntaxSpan::class.java).single()
            assertEquals(0xFFFC5FA3.toInt(), first.color)
            assertEquals(text, buffer.toString())
        }
    }
    @Test fun oversizedFileExplainsHighlightingLimitAndKeepsRawTextAvailable() {
        val text = "val stillReadable = 42"
        val file = File(compose.activity.cacheDir, "large-policy.kt").apply { writeText(text) }; files.add(file)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { FilePreviewContent(LocalFilePreview(file, ArtifactSyntaxPolicy.MAX_HIGHLIGHT_BYTES + 1, "text/plain", ChangesPreviewRoute.TEXT)) }
        } } }
        compose.onNodeWithText("Highlighting off").performClick()
        compose.onNodeWithText("Syntax highlighting is off above", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("Highlighting off").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Raw text preview").fetchSemanticsNodes().isNotEmpty() }
        native { assertEquals(text, it.textView.text.toString()) }
        assertFalse(hasSyntax())
    }

}
