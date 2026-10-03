package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.text.Selection
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.io.File

internal fun findArtifactText(view: View): ArtifactTextScrollView? = when (view) {
    is ArtifactTextScrollView -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findArtifactText(view.getChildAt(it)) }
    else -> null
}

internal fun findArtifactTextInWindows(): ArtifactTextScrollView? =
    android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findArtifactText)

@OptIn(ExperimentalTestApi::class)
class NativeArtifactTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val files = mutableListOf<File>()
    private val current = mutableStateOf<LocalFilePreview?>(null)
    @Before fun setup() {
        compose.activity.getSharedPreferences("cmux-artifact-text", Context.MODE_PRIVATE).edit().clear().commit()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { current.value?.let { key(it.file) { FilePreviewContent(it) } } }
        } } }
    }
    @After fun cleanup() { compose.activity.finish(); files.forEach(File::delete) }
    private fun show(name: String, text: String) {
        val file = File(compose.activity.cacheDir, name).apply { writeText(text) }; files.add(file)
        val oldView = native { it }
        compose.runOnUiThread { current.value = LocalFilePreview(file, file.length(), "text/plain", ChangesPreviewRoute.TEXT) }
        compose.waitUntil(10_000) { native { it != null && it !== oldView && it.textView.text.toString() == text && it.textView.layout != null } }
    }
    private fun find(view: View): ArtifactTextScrollView? = when (view) {
        is ArtifactTextScrollView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }
    private fun <T> native(read: (ArtifactTextScrollView?) -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = read(find(compose.activity.window.decorView)) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun action(name: String) {
        compose.onNodeWithContentDescription("Viewer actions").performClick()
        compose.onNodeWithText(name).performScrollTo().performClick()
    }
    private fun assertVisibleGutter() {
        compose.waitUntil(5_000) {
            val bounds = native { scroll ->
                val view = scroll!!.textView
                val origin = IntArray(2); view.getLocationOnScreen(origin)
                intArrayOf(origin[0], origin[1], origin[0] + view.paddingLeft, origin[1] + minOf(view.height, scroll.height))
            }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            var pixels = 0
            for (x in bounds[0].coerceAtLeast(0) until bounds[2].coerceAtMost(bitmap.width))
                for (y in bounds[1].coerceAtLeast(0) until bounds[3].coerceAtMost(bitmap.height))
                    if (bitmap.getPixel(x, y) == 0xFF92979F.toInt()) pixels++
            bitmap.recycle()
            pixels > 5
        }
    }
    private fun clipboard() = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()

    @Test fun searchWrapsAndJumpsToMatchesAndLineControlsMoveActualViewport() {
        val text = (1..120).joinToString("\n") { when (it) { 3 -> "Needle first"; 110 -> "NEEDLE second"; else -> "Line $it" } }
        show("search-fixture.txt", text)
        action("Search")
        compose.onNodeWithText("Find in file").performTextInput("needle")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1/2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithText("2/2").assertExists()
        compose.waitUntil(5_000) { native { it!!.scrollY > it.height } }
        native { scroll ->
            val spans = (scroll!!.textView.text as Spannable).getSpans(0, text.length, BackgroundColorSpan::class.java)
            assertEquals(2, spans.size)
            val active = spans.single { it.backgroundColor == 0xFF8A6320.toInt() }
            assertEquals(text.indexOf("NEEDLE"), (scroll.textView.text as Spannable).getSpanStart(active))
        }
        compose.onNodeWithContentDescription("Next match").performClick(); compose.onNodeWithText("1/2").assertExists()
        compose.onNodeWithContentDescription("Previous match").performClick(); compose.onNodeWithText("2/2").assertExists()
        compose.onNodeWithContentDescription("Close search").performClick()
        action("Top"); compose.waitUntil(5_000) { native { it!!.scrollY == 0 } }
        action("Go to line"); compose.onNodeWithText("Line number").performTextInput("110"); compose.onNodeWithText("Go").performClick()
        compose.waitUntil(5_000) { native { it!!.scrollY > it.height } }
        action("End"); compose.waitUntil(5_000) { native { !it!!.canScrollVertically(1) } }
        action("Top"); compose.waitUntil(5_000) { native { it!!.scrollY == 0 } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Word wrap").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertVisibleGutter()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.openFileOutput("artifact-text-controls.png", Context.MODE_PRIVATE).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
    }

    @Test fun selectionAndCopyContentsPreserveNewlinesUnicodeAndExcludeLineNumbers() {
        val text = "🙂 Header\nSecond line\r\nThird λ line\n"
        show("selection-fixture.txt", text)
        native { scroll ->
            val view = scroll!!.textView
            assertTrue(view.isTextSelectable)
            val buffer = view.text as Spannable
            Selection.setSelection(buffer, 3, text.indexOf("Third"))
            assertTrue(view.onTextContextMenuItem(android.R.id.copy))
            assertEquals(text.substring(3, text.indexOf("Third")), clipboard())
        }
        action("Copy Contents")
        compose.waitUntil(5_000) { native { clipboard() == text } }
        val numberedPadding = native { scroll ->
            val view = scroll!!.textView
            val bitmap = Bitmap.createBitmap(view.width, minOf(view.height, 400), Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            var gutterPixels = 0
            for (x in 0 until view.paddingLeft) for (y in 0 until bitmap.height) if (bitmap.getPixel(x, y) == 0xFF92979F.toInt()) gutterPixels++
            bitmap.recycle()
            assertTrue("Line-number gutter must visibly draw numbers", gutterPixels > 5)
            view.paddingLeft
        }
        action("Line numbers")
        compose.waitUntil(5_000) { native { it!!.textView.paddingLeft < numberedPadding } }
        assertEquals(text, native { it!!.textView.text.toString() })
    }

    @Test fun wrappingFontAndPinchPersistPerTextKindAcrossFiles() {
        val long = "word ".repeat(90)
        show("layout-fixture.log", long)
        compose.waitUntil(5_000) { native { it!!.textView.width > it.width } }
        action("Word wrap")
        try { compose.waitUntil(5_000) { native { it != null && (it.textView.layout?.lineCount ?: 0) > 1 && it.textView.width <= it.width } } }
        catch (error: Exception) { throw AssertionError(native { "wrap=${it?.textView?.wrapWidth}, viewport=${it?.width}, text=${it?.textView?.width}, lines=${it?.textView?.layout?.lineCount}" }, error) }
        action("Text size")
        compose.onNodeWithContentDescription("Text size").performSemanticsAction(SemanticsActions.SetProgress) { it(22f) }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { native { kotlin.math.abs(it!!.textView.textSize / it.resources.displayMetrics.scaledDensity - 22f) < .1f } }
        show("layout-second.out", long)
        assertEquals(22f, native { it!!.textView.textSize / it.resources.displayMetrics.scaledDensity }, .1f)
        assertTrue(compose.activity.getSharedPreferences("cmux-artifact-text", Context.MODE_PRIVATE).getBoolean("wrap.LOG", false))
        try { compose.waitUntil(5_000) { native { (it!!.textView.layout?.lineCount ?: 0) > 1 } } }
        catch (error: Exception) { throw AssertionError(native { "reopened wrap=${it?.textView?.wrapWidth}, viewport=${it?.width}, text=${it?.textView?.width}, lines=${it?.textView?.layout?.lineCount}, requested=${it?.textView?.isLayoutRequested}" }, error) }
        show("layout-plain.txt", long)
        assertEquals(15f, native { it!!.textView.textSize / it.resources.displayMetrics.scaledDensity }, .1f)
        compose.onNodeWithContentDescription("Raw text preview").performTouchInput {
            pinch(start0 = Offset(centerX - width * .15f, centerY), end0 = Offset(centerX - width * .4f, centerY),
                start1 = Offset(centerX + width * .15f, centerY), end1 = Offset(centerX + width * .4f, centerY))
        }
        try { compose.waitUntil(5_000) { native { it!!.textView.textSize / it.resources.displayMetrics.scaledDensity > 15.5f } } }
        catch (error: Exception) { throw AssertionError(native { "pinch font=${it!!.textView.textSize / it.resources.displayMetrics.scaledDensity}, minimum span=${android.view.ViewConfiguration.get(it.context).scaledMinimumScalingSpan}, width=${it.width}" }, error) }
        val zoomed = native { it!!.textView.textSize / it.resources.displayMetrics.scaledDensity }
        show("layout-next.txt", long)
        assertEquals(zoomed, native { it!!.textView.textSize / it.resources.displayMetrics.scaledDensity }, .1f)
    }
}
