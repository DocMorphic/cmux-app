package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil

/** Real production text raster and link annotations on an owned emulator.
 * Does not establish native font geometry, physical accessibility or date/address detection parity. */
class NativeChiefUnderlineRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Profile(val name: String, val ink: Color, val background: Color,
        val source: String = "🙂 https://example.com/hello", val width: Int = 300,
        val scale: Float = 1f, val lines: Int = Int.MAX_VALUE)
    @Before fun emulatorOnly() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
    }
    @After fun close() { compose.activity.finish() }

    @Test fun sourceOpacityPreservesGlyphsWrappingBidiEllipsisScaleAndClosingPixels() {
        val blue = Color(0xFF007AFF)
        val profiles = listOf(
            Profile("white", Color.White, blue),
            Profile("black", Color.Black, Color.White),
            Profile("colored", blue, Color.White),
            Profile("translucent", Color.White.copy(alpha = .6f), Color.Black),
            Profile("rtl", Color.White, blue, "مرحبا https://example.com/hello عالم"),
            Profile("wrapped", Color.Black, Color.White, width = 120),
            Profile("ellipsis", Color.Black, Color.White, width = 160, lines = 1),
            Profile("large", Color.White, blue, scale = 2f))
        var profile by mutableStateOf(profiles.first())
        var interactive by mutableStateOf(true)
        compose.setContent { CmuxTheme(darkTheme = false) {
            val inherited = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(inherited.density, profile.scale)) {
                Box(Modifier.fillMaxSize().background(profile.background).padding(top = 80.dp, start = 16.dp)) {
                    NativeChiefText(profile.source, Modifier.width(profile.width.dp).testTag("detected"),
                        color = profile.ink, lineLimit = profile.lines, interactive = interactive)
                }
            }
        } }
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "chief-underlines").apply { mkdirs() }
        val records = JSONArray()
        for (item in profiles) {
            compose.runOnIdle { profile = item; interactive = true }
            val node = compose.onNodeWithTag("detected")
            compose.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(item.source, layout.layoutInput.text.text)
            assertEquals(item.scale, layout.layoutInput.density.fontScale, .0001f)
            val links = layout.layoutInput.text.getLinkAnnotations(0, item.source.length)
            assertEquals(1, links.size)
            assertEquals("https://example.com/hello", item.source.substring(links.single().start, links.single().end))
            if (item.name == "wrapped") assertTrue(layout.lineCount > 1)
            if (item.name == "ellipsis") assertTrue(layout.isLineEllipsized(0))
            // Values come from the pinned native detector: white 46%, others 26%.
            val alpha = if (item.name in setOf("white", "translucent", "rtl", "large")) .46f else .26f
            val expected = ColorUtils.compositeColors(item.ink.copy(alpha = alpha).toArgb(), item.background.toArgb())
            val glyph = ColorUtils.compositeColors(item.ink.toArgb(), item.background.toArgb())
            val metrics = android.graphics.Paint().apply {
                typeface = layout.layoutInput.fontFamilyResolver.resolve(layout.layoutInput.style.fontFamily).value as android.graphics.Typeface
                textSize = with(layout.layoutInput.density) { 17.sp.toPx() }
            }
            val thickness = metrics.underlineThickness.coerceAtLeast(1f)
            val opaque = item.ink.copy(alpha = 1f).toArgb()
            val background = item.background.toArgb()
            val channel = listOf(0, 8, 16).maxBy { abs(((opaque ushr it) and 255) - ((background ushr it) and 255)) }
            val backgroundComponent = (background ushr channel) and 255
            val contrast = ((opaque ushr channel) and 255) - backgroundComponent
            var accepted: Bitmap? = null
            var linePixels = 0; var glyphPixels = 0; var longestLineRun = 0
            var capturedDiagnostic = false
            fun close(a: Int, b: Int) = listOf(0, 8, 16).all { abs(((a ushr it) and 255) - ((b ushr it) and 255)) <= 2 }
            compose.waitUntil(5_000) {
                val image = node.captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                if (!capturedDiagnostic) {
                    File(out, "${item.name}-diagnostic.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val palette = mutableMapOf<Int, Int>()
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        val pixel = image.getPixel(x, y); palette[pixel] = (palette[pixel] ?: 0) + 1
                    }
                    File(out, "${item.name}-diagnostic.json").writeText(JSONObject()
                        .put("expected", expected).put("glyph", glyph)
                        .put("layoutAnnotationDescription", layout.layoutInput.text.toString())
                        .put("detectedRangeCount", layout.layoutInput.text.getStringAnnotations(ChiefDetectedTextRange, 0, item.source.length).size)
                        .put("baseline", layout.getLineBaseline(0)).put("lineBottom", layout.getLineBottom(0))
                        .put("paintUnderlinePosition", metrics.underlinePosition).put("paintUnderlineThickness", thickness)
                        .put("palette", JSONArray(palette.entries.sortedByDescending { it.value }.take(20).map { JSONObject().put("argb", it.key).put("count", it.value) }))
                        .toString(2))
                    capturedDiagnostic = true
                }
                var lines = 0; var glyphs = 0; var longest = 0
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        val pixel = image.getPixel(x, y)
                        if (close(pixel, glyph)) glyphs++
                    }
                }
                // A subpixel 1 px line can cover two raster rows. Integrate
                // their coverage; requiring a fully covered pixel is incorrect.
                for (line in 0 until layout.lineCount) {
                    val top = layout.getLineBaseline(line) + metrics.underlinePosition
                    val first = floor(top).toInt().coerceAtLeast(0)
                    val last = ceil(top + thickness).toInt().coerceAtMost(image.height)
                    var run = 0
                    for (x in 0 until image.width) {
                        var coverage = 0f
                        for (y in first until last) coverage += (((image.getPixel(x, y) ushr channel) and 255) - backgroundComponent).toFloat() / contrast
                        if (abs(coverage - alpha * thickness) <= .025f) { lines++; run++; longest = maxOf(longest, run) } else run = 0
                    }
                }
                if (longest >= 25 && glyphs >= 20) {
                    accepted = image; linePixels = lines; glyphPixels = glyphs; longestLineRun = longest; true
                } else { image.recycle(); false }
            }
            val image = checkNotNull(accepted)
            File(out, "${item.name}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.runOnIdle { interactive = false }
            compose.waitForIdle()
            val closing = node.captureToImage().asAndroidBitmap()
            assertEquals(image.width, closing.width); assertEquals(image.height, closing.height)
            val before = IntArray(image.width * image.height); val after = before.copyOf()
            image.getPixels(before, 0, image.width, 0, 0, image.width, image.height)
            closing.getPixels(after, 0, image.width, 0, 0, image.width, image.height)
            assertArrayEquals("Closing keeps the exact pixels for ${item.name}", before, after)
            val closedLayouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(closedLayouts) }
            assertTrue(closedLayouts.single().layoutInput.text.getLinkAnnotations(0, item.source.length).isEmpty())
            records.put(JSONObject().put("name", item.name).put("linePixels", linePixels).put("glyphPixels", glyphPixels)
                .put("longestIntegratedOpacityRunPixels", longestLineRun).put("underlineThicknessPixels", thickness)
                .put("expectedUnderlineArgb", expected).put("lineCount", layout.lineCount)
                .put("fontScale", layout.layoutInput.density.fontScale).put("closingPixelsIdentical", true))
            image.recycle()
        }
        File(out, "raster.json").writeText(records.toString(2))
    }
}
