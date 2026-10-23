package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
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

/** Actual production leaf with controlled composition traits; no account, provider or VPN is created. */
class CloudOnboardingLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun localizedPagesKeepFooterGeometryReadableCopyAndDistinctAccessibleProgress() {
        var language by mutableStateOf("en"); var scale by mutableFloatStateOf(1f)
        var narrow by mutableStateOf(false); var dark by mutableStateOf(true); var completions = 0
        compose.setContent { CmuxTheme(darkTheme = dark) {
            val base = LocalContext.current; val density = LocalDensity.current
            val configuration = remember(base, language, scale) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language)); fontScale = scale
            } }
            val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize().background(NativeAppColors.page).safeDrawingPadding()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        key(language, scale, narrow, dark) {
                            NativeCloudIntroduction(false, null, { completions++ }, {},
                                if (narrow) Modifier.width(260.dp).fillMaxHeight() else Modifier.fillMaxSize())
                        }
                    }
                }
            }
        } }
        data class Profile(val language: String, val scale: Float, val narrow: Boolean = false, val dark: Boolean = true)
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap {
            listOf(Profile(it, 1f), Profile(it, 2f))
        } + listOf("en", "ar", "de").map { Profile(it, 2f, narrow = true) } + Profile("en", 1f, dark = false)
        val output = File(compose.activity.getExternalFilesDir(null), "cloud-onboarding-layout").apply { mkdirs() }
        val records = JSONArray()
        for (profile in profiles) {
            compose.runOnIdle { language = profile.language; scale = profile.scale; narrow = profile.narrow; dark = profile.dark }
            compose.waitForIdle()
            val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(profile.language)) }
            val resources = compose.activity.createConfigurationContext(config).resources
            val name = "${profile.language}-${profile.scale}-${if (profile.narrow) "sidebar" else "phone"}-${if (profile.dark) "dark" else "light"}"
            val primary = compose.onNodeWithTag("cloud.introduction.next")
            val initialPager = compose.onNodeWithTag("cloud.introduction.pager").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("cloud.introduction.back").assertDoesNotExist()
            val before = completions
            for (page in 0..2) {
                val progress = resources.getString(R.string.cmux_cloud_intro_progress_format, page + 1, 3)
                compose.onNodeWithTag("cloud.introduction.progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, progress))
                primary.assertIsDisplayed().assertIsEnabled().assertTextContains(resources.getString(
                    if (page < 2) R.string.cmux_cloud_intro_continue else R.string.cmux_cloud_intro_done))
                if (page == 1) {
                    val current = compose.onNodeWithTag("cloud.introduction.pager").fetchSemanticsNode().boundsInRoot
                    assertEquals("Pager height changed when Back appeared in $name", initialPager.height, current.height, 1f)
                    compose.onNodeWithTag("cloud.introduction.back").assertIsDisplayed()
                    compose.onNodeWithText("System VPN controls are unavailable in this session.").assertDoesNotExist()
                }
                val title = resources.getString(when (page) {
                    0 -> R.string.cmux_cloud_intro_workspace_title
                    1 -> R.string.cmux_cloud_intro_vpn_title
                    else -> R.string.cmux_cloud_intro_key_title
                })
                val message = resources.getString(when (page) {
                    0 -> R.string.cmux_cloud_intro_workspace_message
                    1 -> R.string.cmux_cloud_intro_android_vpn_message
                    else -> R.string.cmux_cloud_intro_android_key_message
                })
                val lines = JSONArray()
                for (text in listOf(title, message)) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("cloud.introduction.copy.$page")), useUnmergedTree = true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                    val layout = layouts.single()
                    assertEquals(profile.scale, layout.layoutInput.density.fontScale, .0001f)
                    assertFalse("Copy height overflow in $name page $page: $text", layout.didOverflowHeight)
                    for (line in 0 until layout.lineCount) {
                        assertFalse(layout.isLineEllipsized(line))
                        assertTrue("Copy line exceeds available width in $name page $page: $text",
                            layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                    }
                    lines.put(JSONObject().put("text", text).put("lines", layout.lineCount).put("width", layout.size.width))
                }
                val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(output, "$name-page-$page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                compose.onNodeWithTag("cloud.introduction.graphic.$page").performScrollTo().assertIsDisplayed()
                val graphic = compose.onNodeWithTag("cloud.introduction.graphic.$page").fetchSemanticsNode().boundsInRoot
                val pagerBounds = compose.onNodeWithTag("cloud.introduction.pager").fetchSemanticsNode().boundsInRoot
                assertTrue("Graphic still clipped after scrolling in $name page $page", graphic.top >= pagerBounds.top - 1 && graphic.bottom <= pagerBounds.bottom + 1)
                if (profile.scale > 1f) {
                    val scrolled = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    File(output, "$name-page-$page-graphic.png").outputStream().use { scrolled.compress(Bitmap.CompressFormat.PNG, 100, it) }; scrolled.recycle()
                }
                assertEquals(152f * compose.activity.resources.displayMetrics.density, graphic.width, 1f)
                records.put(JSONObject().put("profile", name).put("page", page).put("progress", progress).put("copy", lines)
                    .put("graphicWidth", graphic.width).put("primaryHeight", primary.fetchSemanticsNode().boundsInRoot.height))
                primary.performClick()
            }
            compose.runOnIdle { assertEquals(before + 1, completions) }
        }
        File(output, "profiles.json").writeText(records.toString(2))
    }

    @Test fun optionalVpnControlIsBelowTheGraphicAndRetainsItsAccessibilityAction() {
        var toggles = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().background(NativeAppColors.page).safeDrawingPadding()) {
            NativeCloudIntroduction(false, null, {}, {}, vpnControl = {
                Switch(false, { toggles++ }, Modifier.testTag("FixtureVpnToggle"))
            })
        } } }
        compose.onNodeWithTag("cloud.introduction.next").performClick()
        val graphic = compose.onNodeWithTag("cloud.introduction.graphic.1").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag("FixtureVpnToggle").performScrollTo().assertIsDisplayed().assertHasClickAction()
        assertTrue(toggle.fetchSemanticsNode().boundsInRoot.top >= graphic.bottom)
        toggle.performClick(); compose.runOnIdle { assertEquals(1, toggles) }
    }
}
