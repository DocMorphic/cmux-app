package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale

/** Production status component under controlled locale/font/viewport traits, not physical acceptance. */
class AgentFeedFullTextStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }
    private data class Profile(val language: String, val scale: Float = 1f, val dark: Boolean = true, val narrow: Boolean = false)
    private val output get() = File(compose.activity.getExternalFilesDir(null), "feed-reader-status").apply { mkdirs() }

    @Test fun translatedStatusRetainsNaturalAlignmentReadableTextAndScrollableRetry() {
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap {
            listOf(Profile(it), Profile(it, 28f / 17f))
        } + listOf(Profile("en", dark = false), Profile("de", 3f, narrow = true), Profile("ar", 3f, narrow = true))
        var profile by mutableStateOf(profiles.first())
        var failed by mutableStateOf(false)
        var retries = 0
        compose.setContent { CmuxTheme(darkTheme = profile.dark) {
            val base = LocalContext.current; val density = LocalDensity.current
            val config = remember(base, profile) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(profile.language)); fontScale = profile.scale
            } }
            val context = remember(base, config) { base.createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, profile.scale),
                LocalLayoutDirection provides if (profile.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                AgentFeedFullTextTheme {
                    Surface(Modifier.fillMaxSize(), color = NativeAppColors.page) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.width(if (profile.narrow) 260.dp else 402.dp).height(if (profile.narrow) 160.dp else 260.dp)) {
                                key(profile) { AgentFeedFullTextStatus(failed) { retries++ } }
                            }
                        }
                    }
                }
            }
        } }
        val records = JSONArray()
        fun layout(tag: String, text: String): TextLayoutResult {
            val values = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertTextEquals(text)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
            val result = values.single()
            assertFalse(result.didOverflowHeight)
            assertFalse(result.didOverflowWidth)
            assertEquals("Every translated character is laid out", text.length, result.getLineEnd(result.lineCount - 1))
            assertEquals("Source ordinary body role remains17sp before native font scaling", 17.sp, result.layoutInput.style.fontSize)
            assertEquals("Use the requested Android font scaler, including nonlinear accessibility growth", profile.scale,
                result.layoutInput.density.fontScale, .01f)
            return result
        }
        fun screenshot(name: String) {
            val frame = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(output, name).outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; frame.recycle()
        }
        for ((index, p) in profiles.withIndex()) {
            File(output, "active-profile.json").writeText(JSONObject().put("index", index).put("language", p.language)
                .put("compositionFontScale", p.scale).put("narrow260dp", p.narrow).put("dark", p.dark).toString(2))
            compose.runOnIdle { profile = p; failed = false }
            compose.waitForIdle()
            val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(p.language)) }
            val context = compose.activity.createConfigurationContext(config)
            val loadingText = context.getString(R.string.cmux_ui_mobile_agentfeed_fulltext_loading_fa5e4486)
            val unavailable = context.getString(R.string.cmux_ui_mobile_agentfeed_fulltext_unavailable_b62af026)
            val retry = context.getString(R.string.cmux_ui_mobile_agentfeed_fulltext_retry_6fd0587a)
            val viewport = compose.onNodeWithTag("AgentFeedFullTextStatusViewport").fetchSemanticsNode().boundsInRoot
            val spinner = compose.onNodeWithTag("AgentFeedFullTextProgress", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("AgentFeedFullTextStatusContent").fetchSemanticsNode().boundsInRoot
            val d = compose.density.density
            assertEquals("Status starts16pt below the source toolbar", 16f, (spinner.top - viewport.top) / d, .5f)
            assertEquals("Loading content is centered in the viewport", viewport.center.x, content.center.x, 1f)
            if (p.scale == 1f) assertEquals("Source ordinary activity bounds are20pt", 20f, spinner.height / d, .5f)
            else assertTrue("Activity remains larger and visible under enlarged body text", spinner.height / d > 20f)
            compose.onNodeWithTag("AgentFeedFullTextLoading").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            compose.onNodeWithTag("AgentFeedFullTextRetry").assertDoesNotExist()
            val loadingLayout = layout("AgentFeedFullTextLoadingLabel", loadingText)
            compose.onNodeWithTag("AgentFeedFullTextLoadingLabel", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            if (p.language in listOf("en", "ar", "de")) screenshot("$index-loading.png")
            compose.runOnIdle { failed = true }
            val errorLayout = layout("AgentFeedFullTextUnavailable", unavailable)
            val errorGroup = compose.onNodeWithTag("AgentFeedFullTextStatusContent").fetchSemanticsNode().boundsInRoot
            val retryNode = compose.onNodeWithTag("AgentFeedFullTextRetry").assertTextEquals(retry).assertHasClickAction()

            // A translated error may extend below the short viewport. Source
            // spacing is a layout contract; clipped offscreen semantics can be
            // empty, so inspect un-clipped allocation before scrolling to act.
            val initialRetry = retryNode.getUnclippedBoundsInRoot()
            val fullError = compose.onNodeWithTag("AgentFeedFullTextUnavailable").getUnclippedBoundsInRoot()
            assertEquals("Natural error group remains horizontally centered", viewport.center.x, errorGroup.center.x, 1f)
            assertEquals("Retry follows the error with the source16pt gap", 16f, (initialRetry.top - fullError.bottom).value, .5f)
            if (p.language == "en" && p.scale == 1f) {
                assertEquals("Original phone error wraps to two lines", 2, errorLayout.lineCount)
                assertTrue("Short wrapped error hugs its content instead of filling the viewport", errorGroup.width < viewport.width - 40 * d)
            }
            if (p.narrow) assertTrue("Large translated errors must be scrollable in short windows",
                compose.onNodeWithTag("AgentFeedFullTextStatusViewport").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0)
            retryNode.performScrollTo().assertIsDisplayed()
            if (p.narrow) {
                // An oversized label can exceed this deliberately short viewport.
                // Reach the final line as an accessibility scroll action would,
                // then activate the visible button portion with actual input.
                compose.onNodeWithTag("AgentFeedFullTextStatusViewport").performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, 10_000f)
                }
                val lastLineBottom = retryNode.getUnclippedBoundsInRoot().bottom
                assertTrue("Final retry line remains scroll-reachable", lastLineBottom.value <= viewport.bottom / d + .5f)
                retryNode.assertIsDisplayed()
            }
            if (p.language in listOf("en", "ar", "de")) screenshot("$index-error.png")
            val touch = retryNode.fetchSemanticsNode().boundsInRoot
            assertTrue("Retry retains at least48dp touch height", retryNode.fetchSemanticsNode().touchBoundsInRoot.height / d >= 47.5f)
            val edgeOffset = if (touch.height / d < 48) 1f else -1f
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(touch.center.x, touch.bottom + edgeOffset.dp.toPx())) }
            compose.runOnIdle { assertEquals("Retry pointer edge activates once", index + 1, retries) }
            records.put(JSONObject().put("language", p.language).put("compositionFontScale", p.scale).put("dark", p.dark).put("narrow260dp", p.narrow)
                .put("loadingLines", loadingLayout.lineCount).put("errorLines", errorLayout.lineCount)
                .put("actualBodySizeDp", with(errorLayout.layoutInput.density) { errorLayout.layoutInput.style.fontSize.toDp().value })
                .put("actualSpinnerSizeDp", spinner.height / d)
                .put("viewport", viewport.toString()).put("loadingContent", content.toString()).put("errorContent", errorGroup.toString())
                .put("retryCount", retries))
            File(output, "result-progress.json").writeText(records.toString(2))
        }
        File(output, "result.json").writeText(JSONObject().put("profiles", records).put("actualEdgeTaps", retries).put("scope",
            "Production status in controlled9-language/body-type/theme/short-window profiles. Natural alignment, text completeness, merged indeterminate label, scroll reachability and actual retry edge taps, including final-line scroll reachability. Not real OS locales, all source metrics/materials, physical devices or TalkBack/full parity.").toString(2))
    }

    @Test fun activitySpokesAdvanceWithMotionEnabled() {
        assertEquals("Runner enables actual platform animator duration for this check", 1f,
            android.provider.Settings.Global.getFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), .01f)
        // Infinite animations are cancelled by the test policy while automatic
        // clock advancement is enabled. Configure it before starting the effect.
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent { CmuxTheme { AgentFeedFullTextTheme {
                Column(Modifier.fillMaxSize()) { AgentFeedFullTextStatus(false) {} }
            } } }
            compose.mainClock.advanceTimeBy(32)
            val spinner = compose.onNodeWithTag("AgentFeedFullTextProgress", useUnmergedTree = true)
            val before = spinner.captureToImage().toPixelMap()
            compose.mainClock.advanceTimeBy(200)
            val after = spinner.captureToImage().toPixelMap()
            var changed = 0
            for (y in 0 until before.height) for (x in 0 until before.width) if (before[x, y] != after[x, y]) changed++
            File(output, "motion.json").writeText(JSONObject().put("clockAdvanceMs", 200).put("changedPixels", changed)
                .put("scope", "Owned API36 enabled-motion production Canvas under Compose clock; not exact UIKit phase/timing or physical acceptance.").toString(2))
            assertTrue("Activity spokes visibly advance rather than showing a frozen glyph", changed > 10)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
