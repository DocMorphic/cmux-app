package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class NativeMarkdownReaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val modal = AgentFeedModal("account", "event", "mac", "build", "turn", null,
        AgentFeedKind.STOP, "workspace", "terminal", "read", raw = false)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val output get() = File(compose.activity.getExternalFilesDir(null), "native-markdown").apply { mkdirs() }
    @Before fun emulatorOnly() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        InstrumentationRegistry.getArguments().getString("cmux_font_scale")?.toFloat()?.let {
            assertEquals("Actual Activity font profile", it, compose.activity.resources.configuration.fontScale, .01f)
        }
    }
    @After fun finish() { compose.activity.finish() }

    private fun list(): NativeMarkdownList? = WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {
        it.findViewWithTag<NativeMarkdownList>("AgentFeedNativeMarkdown")?.takeIf { v -> v.isAttachedToWindow && v.isShown && v.hasWindowFocus() }
    }
    private fun row(index: Int): NativeMarkdownRow? = list()?.let { reader ->
        (0 until reader.childCount).mapNotNull { reader.getChildAt(it) as? NativeMarkdownRow }.firstOrNull { it.block?.index == index && it.ready }
    }
    private fun await(check: () -> Boolean) {
        compose.waitUntil(30_000) { var okay = false; compose.runOnUiThread { okay = check() }; okay }
    }
    private fun settle() {
        compose.waitForIdle()
        val drawn = CountDownLatch(1)
        compose.runOnUiThread { list()!!.postOnAnimation { list()?.postOnAnimation { drawn.countDown() } } }
        assertTrue(drawn.await(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    private fun show(body: String) {
        compose.setContent { CmuxTheme { var raw by rememberSaveable { mutableStateOf(false) }
            AgentFeedFullText(modal.copy(raw = raw), { raw = it.raw }, { body }, {}, {})
        } }
        await { row(0) != null }; settle()
    }
    private fun go(index: Int) {
        compose.runOnUiThread { list()!!.setSelectionFromTop(index, 0) }
        await { row(index) != null }; settle()
    }
    private fun screenshot(name: String) {
        settle()
        val frame = instrumentation.uiAutomation.takeScreenshot()
        File(output, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }; frame.recycle()
    }
    private fun header(label: String) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        File(output, "header-$label.json").writeText(org.json.JSONArray(layouts.map {
            JSONObject().put("fontScale", compose.activity.resources.configuration.fontScale)
                .put("lines", it.lineCount).put("overflowWidth", it.didOverflowWidth).put("overflowHeight", it.didOverflowHeight)
                .put("intrinsicWidth", it.multiParagraph.maxIntrinsicWidth)
                .put("length", it.layoutInput.text.length).put("lineEnd", it.getLineEnd(0)).put("width", it.size.width).put("height", it.size.height)
        }).toString(2))
        // Compose rounds the natural width to integer pixels. Retain a one-physical-pixel
        // quantization fence while rejecting wrapping, missing text and genuine overflow.
        val fits = layouts.isNotEmpty() && layouts.all { it.lineCount == 1 && !it.didOverflowHeight &&
            it.getLineEnd(0) == it.layoutInput.text.length && it.multiParagraph.maxIntrinsicWidth - it.size.width <= 1f }
        if (!fits) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            File(output, "header-$label.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }; frame.recycle()
        }
        assertTrue("Header label must fit intact: $label", fits)
    }

    @Test fun sourceBlocksUseNativeSelectableRowsWithLinksAndRetainedHorizontalCode() {
        // Exercise Android's actual ICU adapter, not the JVM reference backend.
        val unicode = NativeMarkdownDocument.parse("١. Arabic\n五. Chinese\n1️⃣. Keycap")
        assertEquals(listOf("١.", "五.", "1️⃣."), unicode.map { it.marker })
        assertTrue(unicode.all { it.kind == NativeMarkdownBlock.Kind.ORDERED })
        val code = "val message = \"" + "native_code ".repeat(70) + "\""
        show("# Guide\n\nIntro **bold** [site](https://cmux.com).\n\n- one\n  2. nested\n> quoted *text*\n---\n```kotlin\n$code\n```\n| kind | file |\n| --- | --- |\n| log | build.log |")
        header("Close"); header("Source")
        fun hasWeb(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { hasWeb(view.getChildAt(it)) }
        compose.runOnUiThread {
            assertFalse(WindowInspector.getGlobalWindowViews().any(::hasWeb))
            assertEquals("Guide", row(0)!!.body!!.text.toString())
            assertTrue(row(0)!!.body!!.isTextSelectable)
            assertTrue(row(0)!!.body!!.typeface.isBold)
            assertEquals(9, list()!!.blocks.size)
        }
        go(1)
        val opened = CopyOnWriteArrayList<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened.add(intent.dataString.orEmpty()); return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val point = IntArray(2)
            compose.runOnUiThread {
                val body = row(1)!!.body!!; val text = body.text as Spanned
                assertEquals("Intro bold site.", text.toString())
                val link = text.getSpans(0, text.length, URLSpan::class.java).single()
                val offset = text.getSpanStart(link) + 1; val line = body.layout.getLineForOffset(offset)
                body.getLocationOnScreen(point)
                point[0] += body.totalPaddingLeft + body.layout.getPrimaryHorizontal(offset).toInt()
                point[1] += body.totalPaddingTop + (body.layout.getLineTop(line) + body.layout.getLineBottom(line)) / 2
            }
            UiDevice.getInstance(instrumentation).click(point[0], point[1])
            compose.waitUntil(5_000) { opened.isNotEmpty() }; assertEquals(listOf("https://cmux.com"), opened.toList())
        } finally { instrumentation.removeMonitor(monitor) }
        go(6)
        var horizontal = 0
        compose.runOnUiThread {
            val r = row(6)!!; assertEquals(code, r.body!!.text.toString())
            assertEquals("KOTLIN", (0 until (r.getChildAt(0) as ViewGroup).childCount)
                .map { (r.getChildAt(0) as ViewGroup).getChildAt(it) }.filterIsInstance<TextView>().single().text.toString())
            val scroll = (r.getChildAt(0) as ViewGroup).getChildAt(1) as android.widget.HorizontalScrollView
            assertTrue(scroll.getChildAt(0).width > scroll.width)
            scroll.scrollTo(900, 0); horizontal = scroll.scrollX; assertTrue(horizontal > 0)
        }
        screenshot("native-code")
        compose.onNodeWithText("Source").performClick(); header("Formatted"); compose.onNodeWithText("Formatted").performClick()
        await { row(6)?.let { r -> ((r.getChildAt(0) as ViewGroup).getChildAt(1) as android.widget.HorizontalScrollView).scrollX == horizontal } == true }
        go(7)
        compose.runOnUiThread {
            assertEquals("kind │ file", row(7)!!.body!!.text.toString()); assertTrue(list()!!.blocks[7].header)
            assertEquals("log │ build.log", list()!!.blocks[8].text)
        }
        screenshot("native-table")
        File(output, "blocks.json").writeText(JSONObject().put("blockCount", 9).put("webViews", 0)
            .put("opened", org.json.JSONArray(opened)).put("codeHorizontalRestoredPx", horizontal).toString(2))
    }

    @Test fun hugeFormattedParagraphKeepsAnchorSelectionAndPaintedTailWithoutFreezingMessage() {
        val tail = "FORMATTED_TAIL_MARKER"
        val text = "# Start\n\n" + (1..14_000).joinToString("\n") { "Formatted line $it with **bold** content." } + "\n$tail"
        val restoration = StateRestorationTester(compose); var loads = 0
        restoration.setContent { CmuxTheme { var raw by rememberSaveable { mutableStateOf(false) }
            AgentFeedFullText(modal.copy(raw = raw), { raw = it.raw }, { loads++; text }, {}, {})
        } }
        await { row(1)?.body?.height?.let { it > 262143 } == true }; settle()
        var selection = 0; var top = 0
        compose.runOnUiThread {
            val reader = list()!!; val body = row(1)!!.body!!
            assertTrue(body.text.endsWith(tail)); assertFalse(body.text.contains("**"))
            reader.setSelectionFromTop(1, -12_000)
        }
        settle()
        compose.runOnUiThread {
            val r = row(1)!!; val body = r.body!!
            val line = body.layout.getLineForVertical((-r.top).coerceAtLeast(0))
            selection = body.layout.getLineStart(line)
            Selection.setSelection(body.text as Spannable, selection, selection + 12)
            top = r.top
            val state = android.util.SparseArray<android.os.Parcelable>()
            list()!!.id = View.generateViewId(); body.id = View.generateViewId()
            list()!!.saveHierarchyState(state)
            assertEquals("Message text must not enter native hierarchy state", 0, state.size())
        }
        restoration.emulateSavedInstanceStateRestore()
        await { row(1)?.let { it.body!!.selectionStart == selection && kotlin.math.abs(it.top - top) < 3 } == true }
        compose.runOnIdle { assertEquals(2, loads) }
        compose.onNodeWithText("Source").performClick(); compose.onNodeWithText("Formatted").performClick()
        await { row(1)?.let { it.body!!.selectionStart == selection && kotlin.math.abs(it.top - top) < 3 } == true }
        compose.runOnIdle { assertEquals(2, loads) }
        compose.runOnUiThread {
            val r = row(1)!!; val reader = list()!!
            reader.setSelectionFromTop(1, reader.height - r.height - reader.paddingTop - reader.paddingBottom)
        }
        settle()
        val frame = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(output, "formatted-tail.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var checked = 0; var painted = 0
            compose.runOnUiThread {
                val body = row(1)!!.body!!; val layout = body.layout
                val line = layout.getLineForOffset(body.text.length - 1)
                val start = body.text.length - tail.length
                assertEquals(tail, body.text.subSequence(start, body.text.length).toString())
                val paint = android.text.TextPaint(body.paint)
                val topFont = kotlin.math.floor(paint.fontMetrics.top.toDouble()).toInt()
                val location = IntArray(2); body.getLocationOnScreen(location)
                val first = layout.getLineForOffset(start)
                for (tailLine in first..line) {
                    val part = body.text.subSequence(layout.getLineStart(tailLine), layout.getLineEnd(tailLine)).toString()
                    val reference = Bitmap.createBitmap(kotlin.math.ceil(paint.measureText(part).toDouble()).toInt() + 4,
                        kotlin.math.ceil((paint.fontMetrics.bottom - topFont).toDouble()).toInt() + 4, Bitmap.Config.ARGB_8888)
                    Canvas(reference).drawText(part, 2f, (2 - topFont).toFloat(), paint)
                    val left = location[0] + body.totalPaddingLeft + layout.getLineLeft(tailLine).toInt() - 2
                    val screenTop = location[1] + body.totalPaddingTop + layout.getLineBaseline(tailLine) + topFont - 2
                    for (y in 0 until reference.height) for (x in 0 until reference.width) {
                        if (Color.alpha(reference.getPixel(x, y)) < 200) continue
                        checked++
                        if (left+x !in 0 until frame.width || screenTop+y !in 0 until frame.height) continue
                        val pixel = frame.getPixel(left+x, screenTop+y)
                        if (kotlin.math.abs(Color.red(pixel)-Color.red(paint.color)) < 45 && kotlin.math.abs(Color.green(pixel)-Color.green(paint.color)) < 45 && kotlin.math.abs(Color.blue(pixel)-Color.blue(paint.color)) < 45) painted++
                    }
                    reference.recycle()
                }
                File(output, "formatted-tail.json").writeText(JSONObject().put("bodyHeight", body.height).put("renderedLength", body.text.length)
                    .put("fontScale", body.resources.configuration.fontScale).put("bodyTextSizePx", body.textSize)
                    .put("tailLayoutLines", line-first+1).put("loads", loads).put("selectionStart", selection)
                    .put("restoredRowTop", top).put("checkedInkPixels", checked).put("paintedInkPixels", painted).toString(2))
            }
            assertTrue("Tail ink must be painted ($painted/$checked)", checked > 100 && painted.toDouble()/checked > .85)
        } finally { frame.recycle() }
    }
}
