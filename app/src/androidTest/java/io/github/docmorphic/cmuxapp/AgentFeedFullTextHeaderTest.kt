package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
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

/** Real production header with controlled context/composition traits, not OS locale or Pixel acceptance. */
class AgentFeedFullTextHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun titleStaysCenteredAndCloseRemainsSeparateAcrossLanguagesAndTextSizes() {
        data class Profile(val language: String, val scale: Float, val narrow: Boolean = false)
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap {
            listOf(Profile(it, 1f), Profile(it, 2f))
        } + listOf(Profile("en", 2f, true), Profile("ar", 2f, true), Profile("de", 2f, true))
        var profile by mutableStateOf(profiles.first())
        var clicks = 0
        compose.setContent { CmuxTheme {
            val base = LocalContext.current; val density = LocalDensity.current
            val configuration = remember(base, profile) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(profile.language)); fontScale = profile.scale
            } }
            val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, profile.scale),
                LocalLayoutDirection provides if (profile.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(if (profile.narrow) Modifier.width(260.dp) else Modifier.fillMaxWidth()) {
                        key(profile) { AgentFeedFullTextHeader { clicks++ } }
                    }
                }
            }
        } }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "feed-reader-header").apply { mkdirs() }
        val records = JSONArray()
        for (p in profiles) {
            compose.runOnIdle { profile = p }; compose.waitForIdle()
            val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(p.language)) }
            val context = compose.activity.createConfigurationContext(config)
            val closeText = context.getString(R.string.cmux_ui_mobile_agentfeed_fulltext_close_4053e619)
            val titleText = context.getString(R.string.cmux_ui_mobile_agentfeed_fulltext_title_257807f0)
            val close = compose.onNodeWithText(closeText).assertIsDisplayed().assertHasClickAction()
            val title = compose.onNodeWithTag("AgentFeedFullTextTitle").assertIsDisplayed().assertTextEquals(titleText)
            val root = compose.onNodeWithTag("AgentFeedFullTextHeader").fetchSemanticsNode().boundsInRoot
            val c = close.fetchSemanticsNode().boundsInRoot; val t = title.fetchSemanticsNode().boundsInRoot
            assertEquals("Title remains centered independently of the translated Close width", root.center.x, t.center.x, 1f)
            val stacked = c.bottom <= t.top + 1f
            if (!stacked) {
                if (p.language == "ar") assertTrue(t.right <= c.left + 1f) else assertTrue(c.right <= t.left + 1f)
            }
            for (node in listOf(close, title)) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Translated controls must actually render all their text", layouts.isNotEmpty() && layouts.all { layout ->
                    !layout.didOverflowHeight && layout.getLineEnd(layout.lineCount - 1) == layout.layoutInput.text.length &&
                        (0 until layout.lineCount).none { layout.isLineEllipsized(it) }
                })
            }
            assertTrue(c.left >= root.left && c.right <= root.right && t.left >= root.left && t.right <= root.right)
            if ((p.language == "en" && p.scale == 1f) || (p.language == "ar" && p.scale == 2f && !p.narrow) || (p.language == "de" && p.narrow)) {
                compose.onNodeWithTag("AgentFeedFullTextHeader").captureToImage()
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(output, "${p.language}-${p.scale}-${p.narrow}.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            close.performClick()
            records.put(JSONObject().put("language", p.language).put("compositionFontScale", p.scale).put("narrow260dp", p.narrow)
                .put("stacked", stacked).put("root", root.toString()).put("close", c.toString()).put("title", t.toString()).put("clickCount", clicks))
        }
        assertEquals(profiles.size, clicks)
        File(output, "result.json").writeText(JSONObject().put("profiles", records).put("scope",
            "Controlled production-header context/composition layout and actual semantics clicks. Not real system locale/type changes, full sheet colors/fonts, TalkBack, physical device or full iOS parity acceptance.").toString(2))
    }
}
