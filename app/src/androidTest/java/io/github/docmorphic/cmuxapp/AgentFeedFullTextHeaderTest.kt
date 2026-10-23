package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale

/** Real production header with controlled context/composition traits, not OS locale or Pixel acceptance. */
@OptIn(ExperimentalTestApi::class)
class AgentFeedFullTextHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun titleCentersOrShiftsAwayFromCloseWithoutLosingTranslatedText() {
        data class Profile(val language: String, val scale: Float, val narrow: Boolean = false)
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap {
            listOf(Profile(it, 1f), Profile(it, 2f))
        } + listOf(Profile("en", 2f, true), Profile("ar", 2f, true), Profile("de", 2f, true))
        var profile by mutableStateOf(profiles.first())
        var clicks = 0
        var shiftedProfiles = 0
        lateinit var inputMode: InputModeManager
        compose.setContent { CmuxTheme(darkTheme = true) {
            val base = LocalContext.current; val density = LocalDensity.current
            val configuration = remember(base, profile) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(profile.language)); fontScale = profile.scale
            } }
            val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, profile.scale),
                LocalLayoutDirection provides if (profile.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                val manager = LocalInputModeManager.current
                SideEffect { inputMode = manager }
                AgentFeedFullTextTheme {
                    Surface(Modifier.fillMaxSize(), color = NativeAppColors.page, contentColor = NativeAppColors.text) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Box(if (profile.narrow) Modifier.width(260.dp) else Modifier.fillMaxWidth()) {
                                key(profile) { AgentFeedFullTextHeader { clicks++ } }
                            }
                        }
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
            close.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Button))
            close.assertTouchHeightIsEqualTo(48.dp)
            val title = compose.onNodeWithTag("AgentFeedFullTextTitle").assertIsDisplayed().assertTextEquals(titleText)
            val root = compose.onNodeWithTag("AgentFeedFullTextHeader").fetchSemanticsNode().boundsInRoot
            val c = close.fetchSemanticsNode().boundsInRoot; val t = title.fetchSemanticsNode().boundsInRoot
            val stacked = c.bottom <= t.top + 1f
            assertEquals("Source capsule paints44pt; its AX frame is inset4pt", 44f, c.height / compose.density.density, .5f)
            if (!p.narrow) assertFalse("Supported source-sized profiles keep both controls in the measured single row", stacked)
            if (stacked) {
                assertEquals("Fallback second row remains centered", root.center.x, t.center.x, 1f)
            } else {
                assertEquals("Source bar retains 54 points under enlarged text", 54f, root.height / compose.density.density, .5f)
                val sourceCenter = root.top + with(compose.density) { 22.dp.toPx() }
                assertEquals("Close centers in the 44pt painted control row", sourceCenter, c.center.y, 1f)
                assertEquals("Title shares the measured 22pt control center", sourceCenter, t.center.y, 1f)
                val gap = with(compose.density) { 12.dp.toPx() }
                if (p.language == "ar") {
                    assertEquals("RTL title shifts away from trailing-screen Close", minOf(root.center.x + t.width / 2, c.left - gap), t.right, 1f)
                    assertTrue(t.right <= c.left - gap + 1f)
                } else {
                    assertEquals("Title centers until it would collide with Close", maxOf(root.center.x - t.width / 2, c.right + gap), t.left, 1f)
                    assertTrue(t.left >= c.right + gap - 1f)
                }
                if (kotlin.math.abs(root.center.x - t.center.x) > 1f) shiftedProfiles++
            }
            for (node in listOf(close, title)) {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Translated controls must actually render all their text", layouts.isNotEmpty() && layouts.all { layout ->
                    !layout.didOverflowHeight && layout.getLineEnd(layout.lineCount - 1) == layout.layoutInput.text.length &&
                        (0 until layout.lineCount).none { layout.isLineEllipsized(it) }
                })
            }
            val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            title.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
            for (layout in titleLayouts) with(layout.layoutInput.density) {
                val expected = minOf(17 * p.scale, 21f)
                assertEquals("Title role follows the measured source cap without shrinking the body scale", expected,
                    layout.layoutInput.style.fontSize.toDp().value, .05f)
            }
            val closeLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            close.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(closeLayouts) }
            for (layout in closeLayouts) with(layout.layoutInput.density) {
                assertEquals("Optical Android Close role remains bounded while body text scales", minOf(17 * p.scale, 21f),
                    layout.layoutInput.style.fontSize.toDp().value, .05f)
            }
            assertTrue(c.left >= root.left && c.right <= root.right && t.left >= root.left && t.right <= root.right)
            if ((p.language == "en" && p.scale == 1f) || (p.language == "ar" && !p.narrow) || p.language == "de") {
                compose.onNodeWithTag("AgentFeedFullTextHeader").captureToImage()
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(output, "${p.language}-${p.scale}-${p.narrow}.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
            // Actual pointer input one dp below the painted capsule exercises
            // Android's 48dp touch expansion, rather than a semantics-only click.
            compose.onRoot().performTouchInput { click(Offset(c.center.x, c.bottom + with(compose.density) { 1.dp.toPx() })) }
            compose.runOnIdle { assertEquals(profiles.indexOf(p) + 1, clicks) }
            records.put(JSONObject().put("language", p.language).put("compositionFontScale", p.scale).put("narrow260dp", p.narrow)
                .put("stacked", stacked).put("root", root.toString()).put("close", c.toString()).put("title", t.toString())
                .put("closeLabel", compose.onNodeWithTag("AgentFeedFullTextCloseLabel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.toString())
                .put("clickCount", clicks))
        }
        // Capture the default pointer appearance before introducing keyboard
        // focus. Activation is then exercised on the final narrow fallback row.
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        val keyboardClose = compose.onNodeWithTag("AgentFeedFullTextCloseCapsule")
        keyboardClose.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        keyboardClose.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(profiles.size + 1, clicks) }
        assertEquals(profiles.size + 1, clicks)
        assertTrue("Exercise actual collision allocation, not just already-centered titles", shiftedProfiles > 0)
        File(output, "result.json").writeText(JSONObject().put("profiles", records).put("pointerEdgeClicks", profiles.size)
            .put("keyboardEnterClicks", 1).put("scope",
            "Controlled production-header context/composition dark layout, actual expanded-edge pointer input and keyboard Enter. Close role follows measured glyphs; actual iOS UIFont remains unobserved. Not real system locale/type changes, full materials, TalkBack, physical device or full iOS parity acceptance.").toString(2))
    }
}
