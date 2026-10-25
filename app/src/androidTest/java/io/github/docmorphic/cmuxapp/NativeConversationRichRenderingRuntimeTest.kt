package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual Android text raster/layout; no claim of identical UIKit font artwork or pixels. */
class NativeConversationRichRenderingRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun allEffectsDrawInkWithHiddenBaseSpansAndRetainGraphemesWordsLinksAndScaledLayout() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        assertEquals(0f, android.provider.Settings.Global.getFloat(compose.activity.contentResolver, "animator_duration_scale"), 0f)
        var source by mutableStateOf("👩🏽‍💻 é 👨‍👩‍👧‍👦")
        var runs by mutableStateOf(listOf(NativeConversationTextRun(0, source.length, 3, NativeConversationTextEffect.BIG)))
        var scale by mutableFloatStateOf(1f)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { CmuxTheme {
            val inherited = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(inherited.density, scale), LocalLayoutDirection provides direction) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                    NativeChiefText(source, Modifier.width(300.dp).testTag("RichRaster"), color = Color.White, textRuns = runs)
                }
            }
        } }
        val out = File(compose.activity.getExternalFilesDir(null), "rich-composer").apply { mkdirs() }
        val records = org.json.JSONArray()
        fun inspect(name: String, expectedUnits: Int? = null) {
            val node = compose.onNodeWithTag("RichRaster")
            compose.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(source, layout.layoutInput.text.text)
            assertTrue(layout.layoutInput.text.spanStyles.any { it.item.color == Color.Transparent })
            assertEquals(scale, layout.layoutInput.density.fontScale, 0f)
            val units = nativeConversationEffectUnits(source, runs, java.util.Locale.US)
            expectedUnits?.let { assertEquals(it, units.size) }
            var pixels = 0
            lateinit var bitmap: Bitmap
            compose.waitUntil(5000) {
                bitmap = node.captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                pixels = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width)
                    if (android.graphics.Color.red(bitmap.getPixel(x, y)) > 30) pixels++
                (pixels > 100).also { if (!it) bitmap.recycle() }
            }
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            records.put(org.json.JSONObject().put("name", name).put("text", source).put("units", units.size)
                .put("fontScale", scale).put("inkPixels", pixels).put("lines", layout.lineCount))
        }
        NativeConversationTextEffect.entries.forEach { effect ->
            compose.runOnIdle { runs = listOf(NativeConversationTextRun(0, source.length, 3, effect)) }
            inspect("raster-${effect.wireName}", 3)
        }
        compose.runOnIdle {
            source = "one ".repeat(22); runs = listOf(NativeConversationTextRun(0, 4, 1, NativeConversationTextEffect.RIPPLE),
                NativeConversationTextRun(4, source.length - 4, 2, NativeConversationTextEffect.RIPPLE))
            scale = 2f
        }
        inspect("raster-wrapped-words-large", 22)
        compose.runOnIdle {
            source = "مرحبا https://example.com/hello عالم"; direction = LayoutDirection.Rtl
            runs = listOf(NativeConversationTextRun(0, source.length, 4, NativeConversationTextEffect.SMALL)); scale = 1f
        }
        inspect("raster-rtl-link")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("RichRaster").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().layoutInput.text.getLinkAnnotations(0, source.length).size)
        File(out, "raster-records.json").writeText(records.toString(2) + "\n")
    }
}
