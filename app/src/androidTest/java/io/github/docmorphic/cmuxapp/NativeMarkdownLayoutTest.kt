package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production leaf with controlled composition/context traits, not real system locale/recreation. */
class NativeMarkdownLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }
    private fun list(): NativeMarkdownList? = WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {
        it.findViewWithTag<NativeMarkdownList>("AgentFeedNativeMarkdown")?.takeIf { v -> v.isAttachedToWindow && v.isShown }
    }
    private fun row(index: Int): NativeMarkdownRow? = list()?.let { reader ->
        (0 until reader.childCount).mapNotNull { reader.getChildAt(it) as? NativeMarkdownRow }
            .firstOrNull { it.ready && it.block?.index == index }
    }
    private fun await(test: () -> Boolean) {
        compose.waitUntil(20_000) { var ready = false; compose.runOnUiThread { ready = test() }; ready }
    }
    private fun settle() {
        compose.waitForIdle()
        val ready = CountDownLatch(1)
        compose.runOnUiThread { list()!!.postOnAnimation { list()?.postOnAnimation { ready.countDown() } } }
        assertTrue(ready.await(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    private fun rect(view: View): JSONArray {
        val xy = IntArray(2); view.getLocationOnScreen(xy)
        return JSONArray(listOf(xy[0], xy[1], view.width, view.height))
    }
    @Test fun sameSourceDocumentReflowsAcrossLocalizedNativeRows() {
        val raw = instrumentation.context.assets.open("native-markdown/reference.json").use { it.readBytes() }
        val input = JSONObject(raw.toString(Charsets.UTF_8)); val markdown = input.getString("markdown")
        val blocks = NativeMarkdownDocument.parse(markdown)
        assertEquals(NativeMarkdownBlock.Kind.entries.toSet(), blocks.map { it.kind }.toSet())
        val stages = listOf("top" to 0, "code" to blocks.single { it.kind == NativeMarkdownBlock.Kind.CODE }.index,
            "end" to blocks.single { it.kind == NativeMarkdownBlock.Kind.HEADING && it.text == "Reader end" }.index)
        data class Profile(val language: String, val scale: Float, val dark: Boolean = true, val narrow: Boolean = false)
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap {
            listOf(Profile(it, 1f), Profile(it, 2f))
        } + Profile("en", 1f, false) + listOf(Profile("en", 2f, narrow = true), Profile("ar", 2f, narrow = true))
        var profile by mutableStateOf(profiles.first())
        compose.setContent { CmuxTheme(darkTheme = profile.dark) {
            val base = LocalContext.current; val density = LocalDensity.current
            val configuration = remember(base, profile) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(profile.language)); fontScale = profile.scale
            } }
            val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, profile.scale),
                LocalLayoutDirection provides if (profile.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize().background(NativeAppColors.page).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    Box(if (profile.narrow) Modifier.width(260.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        key(profile) { NativeMarkdownReader(markdown, remember { NativeMarkdownPosition() }) }
                    }
                }
            }
        } }
        val output = File(compose.activity.getExternalFilesDir(null), "native-markdown-layout").apply { mkdirs() }
        val records = JSONArray()
        for (p in profiles) {
            compose.runOnIdle { profile = p }
            await { row(0) != null && list()!!.resources.configuration.locales[0].toLanguageTag() == p.language &&
                kotlin.math.abs(list()!!.resources.configuration.fontScale - p.scale) < .01f }
            val name = "${p.language}-${p.scale}-${if (p.dark) "dark" else "light"}-${if (p.narrow) "narrow" else "phone"}"
            for ((stage, index) in stages) {
                compose.runOnUiThread { list()!!.setSelectionFromTop(index, 0) }
                await { row(index) != null }; settle()
                val record = JSONObject().put("profile", name).put("stage", stage).put("compositionFontScale", p.scale)
                compose.runOnUiThread {
                    val reader = list()!!; val target = row(index)!!; val text = target.body!!
                    assertEquals("Real native context direction", if (p.language == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, reader.layoutDirection)
                    assertEquals(blocks[index].text, text.text.toString())
                    assertTrue(text.isTextSelectable)
                    val visible = android.graphics.Rect(); assertTrue(target.getGlobalVisibleRect(visible))
                    assertTrue("Native target has visible height", visible.height() > 0)
                    record.put("nativeContextFontScale", reader.resources.configuration.fontScale)
                        .put("nativeDirection", reader.layoutDirection).put("nativeLocale", reader.resources.configuration.locales[0].toLanguageTag())
                        .put("density", reader.resources.displayMetrics.density).put("reader", rect(reader)).put("target", rect(target))
                        .put("text", rect(text)).put("textSizePx", text.textSize).put("lines", text.lineCount)
                        .put("fontMetrics", JSONArray(listOf(text.paint.fontMetrics.top, text.paint.fontMetrics.ascent, text.paint.fontMetrics.descent, text.paint.fontMetrics.bottom)))
                    val rows = JSONArray()
                    for (i in 0 until reader.childCount) (reader.getChildAt(i) as? NativeMarkdownRow)?.let { r ->
                        if (r.ready) rows.put(JSONObject().put("block", r.block!!.index).put("kind", r.block!!.kind.name).put("frame", rect(r))
                            .put("text", r.body?.let { b -> JSONObject().put("frame", rect(b)).put("padding", JSONArray(listOf(b.paddingLeft, b.paddingTop, b.paddingRight, b.paddingBottom)))
                                .put("sizePx", b.textSize).put("lineCount", b.lineCount).put("value", b.text.toString()) } ?: JSONObject.NULL))
                    }
                    record.put("visibleRows", rows)
                    // Verbatim code/table must scroll horizontally. Other rows must
                    // preserve all text without ellipsis or lines outside allocation.
                    if (stage != "code") for (line in 0 until text.lineCount) {
                        assertEquals(0, text.layout.getEllipsisCount(line))
                        assertTrue("Native prose line exceeds its allocation: $name/$stage",
                            text.layout.getLineRight(line) - text.layout.getLineLeft(line) <= text.width - text.totalPaddingLeft - text.totalPaddingRight + 1f)
                    }
                }
                val image = instrumentation.uiAutomation.takeScreenshot()
                File(output, "$name-$stage.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                records.put(record)
            }
        }
        File(output, "observations.json").writeText(JSONObject().put("schema", 1).put("documentSha256",
            java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
            .put("profiles", profiles.size).put("pages", records).put("scope",
                "Actual Android production native leaf; controlled localized context/composition traits, font scales 1/2 and 260dp narrow width. Not actual system locale/font changes, original Feed sheet, transport, physical-device or full iOS parity acceptance.").toString(2))
    }
}
