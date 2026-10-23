package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual compositor colors, production reader and retained native selection during theme updates. */
class NativeMarkdownPaletteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun renderedRolesMatchOriginalPhoneColorsAcrossLiveThemeChanges() {
        var dark by mutableStateOf(true)
        compose.setContent { CmuxTheme(darkTheme = dark) {
            AgentFeedFullText({ "# Native reader\n\nParagraph with [link](https://example.invalid/reference).\n\n12. Ordered body.\n\n> Quoted body with **emphasis**.\n\n---\n\n```swift\nlet source = 1\nprint(source)\n```\n\nRetain this selected passage." }, {}, {})
        } }
        fun current(): NativeMarkdownList? = WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {
            it.findViewWithTag<NativeMarkdownList>("AgentFeedNativeMarkdown")?.takeIf { v -> v.isShown && v.hasWindowFocus() && !v.released }
        }
        var reader: NativeMarkdownList? = null
        fun rows() = (0 until reader!!.childCount).map { reader!!.getChildAt(it) as NativeMarkdownRow }
        compose.waitUntil(20_000) {
            var ready = false
            compose.runOnUiThread { reader = current(); ready = reader != null && rows().size == 7 && rows().all { it.ready } }
            ready
        }
        val original = reader!!
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(compose.activity.getExternalFilesDir(null), "native-markdown-palette").apply { mkdirs() }
        val records = JSONArray()
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)
        for ((index, requestedDark) in listOf(true, false, true).withIndex()) {
            compose.runOnIdle { dark = requestedDark }
            val foreground = if (requestedDark) Color.WHITE else Color.BLACK
            compose.waitUntil(10_000) {
                var ready = false
                compose.runOnUiThread { ready = current() === original && original.colors.foreground == foreground && rows().all { it.ready } }
                ready
            }
            compose.waitForIdle()
            val drawn = CountDownLatch(1)
            compose.runOnUiThread { original.postOnAnimation { original.postOnAnimation { drawn.countDown() } } }
            assertTrue(drawn.await(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
            val areas = mutableMapOf<String, Pair<Rect, Int>>()
            val record = JSONObject().put("dark", requestedDark).put("sourceRun", 40003411274L)
            compose.runOnUiThread {
                assertSame("Theme updates preserve the reader instance", original, current())
                val byIndex = rows().associateBy { it.block!!.index }
                val expectedSecondary = if (requestedDark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
                val link = if (requestedDark) rgb(0, 145, 255) else rgb(0, 136, 255)
                assertEquals(expectedSecondary, byIndex.getValue(3).body!!.currentTextColor)
                assertEquals(link, byIndex.getValue(1).body!!.linkTextColors.defaultColor)
                val caption = descendants(byIndex.getValue(5)).filterIsInstance<TextView>().single { it.text.toString() == "SWIFT" }
                assertEquals(expectedSecondary, caption.currentTextColor)
                fun area(role: String, view: View, expected: Int) {
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    areas[role] = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height) to expected
                }
                area("quoteText", byIndex.getValue(3).body!!, if (requestedDark) rgb(152, 152, 159) else rgb(138, 138, 142))
                area("listMarker", descendants(byIndex.getValue(2)).filterIsInstance<TextView>().single { it.text.toString() == "12." },
                    if (requestedDark) rgb(152, 152, 159) else rgb(138, 138, 142))
                area("link", byIndex.getValue(1).body!!, link)
                area("quoteBar", (byIndex.getValue(3).getChildAt(0) as ViewGroup).getChildAt(0),
                    if (requestedDark) rgb(90, 90, 94) else rgb(197, 197, 199))
                area("divider", byIndex.getValue(4).getChildAt(0), if (requestedDark) rgb(62, 62, 65) else rgb(198, 198, 200))
                area("codeBackground", byIndex.getValue(5).getChildAt(0), if (requestedDark) rgb(40, 40, 43) else rgb(243, 243, 244))
                area("codeCaption", caption, if (requestedDark) rgb(157, 157, 164) else rgb(133, 133, 138))
                val selected = byIndex.getValue(6).body!!
                if (index == 0) Selection.setSelection(selected.text as Spannable, 0, 6)
                assertEquals("Retain", selected.text.subSequence(selected.selectionStart, selected.selectionEnd).toString())
                record.put("selectionStart", selected.selectionStart).put("selectionEnd", selected.selectionEnd)
            }
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(output, "$index-${if (requestedDark) "dark" else "light"}.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val samples = JSONObject()
                for ((role, pair) in areas) {
                    val (rect, expected) = pair
                    assertTrue("$role is fully present in the compositor", rect.left >= 0 && rect.top >= 0 && rect.right <= frame.width && rect.bottom <= frame.height)
                    val counts = mutableMapOf<Int, Int>()
                    for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                        val pixel = frame.getPixel(x, y); counts[pixel] = (counts[pixel] ?: 0) + 1
                    }
                    // One-channel quantization tolerance for translucent native glyphs;
                    // opaque link/fills and bars must match the attributed original RGB.
                    val tolerance = if (role in listOf("quoteText", "listMarker", "codeCaption")) 1 else 0
                    val matched = counts.entries.filter { (color, _) ->
                        kotlin.math.abs(Color.red(color) - Color.red(expected)) <= tolerance &&
                            kotlin.math.abs(Color.green(color) - Color.green(expected)) <= tolerance &&
                            kotlin.math.abs(Color.blue(color) - Color.blue(expected)) <= tolerance
                    }.sumOf { it.value }
                    samples.put(role, JSONObject().put("boundsPx", rect.toString()).put("expectedRgb", JSONArray(listOf(Color.red(expected), Color.green(expected), Color.blue(expected))))
                        .put("channelTolerance", tolerance).put("matchedPixels", matched).put("dominantColors", JSONArray(counts.entries.sortedByDescending { it.value }.take(4).map {
                            JSONObject().put("rgb", JSONArray(listOf(Color.red(it.key), Color.green(it.key), Color.blue(it.key)))).put("count", it.value)
                        })))
                    File(output, "$index-colors.json").writeText(samples.toString(2))
                    assertTrue("$role has actual painted source-colored pixels in ${if (requestedDark) "dark" else "light"}: $samples", matched >= 10)
                }
                records.put(record.put("samples", samples))
            } finally { frame.recycle() }
        }
        File(output, "result.json").writeText(JSONObject().put("profiles", records).put("scope",
            "Owned API36 production reader, exact measured phone link/fill/bar/divider pixels and secondary glyphs within1/255; retained reader/selection across dark-light-dark. Not all iOS materials, dynamic colors, physical transport, TalkBack or full parity.").toString(2))
    }
}
