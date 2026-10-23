package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale

/** The runner changes only its owned emulator's real window size and restores it. */
class CloudOnboardingWideLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun realWideViewportCentersCopyAndKeepsTheCorrectGraphicAndFooterArrangement() {
        val kind = InstrumentationRegistry.getArguments().getString("onboardingViewport")
        require(kind in listOf("landscape", "tablet")) { "Owned runner must explicitly select the viewport" }
        var language by mutableStateOf("en"); var scale by mutableFloatStateOf(1f)
        var completions = 0
        compose.setContent { CmuxTheme(darkTheme = true) {
            val base = LocalContext.current; val density = LocalDensity.current
            val configuration = remember(base, language, scale) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language)); fontScale = scale
            } }
            val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize().background(NativeAppColors.page).safeDrawingPadding()) {
                    key(language, scale) { NativeCloudIntroduction(false, null, { completions++ }, {}) }
                }
            }
        } }
        val output = File(compose.activity.getExternalFilesDir(null), "cloud-onboarding-wide-$kind").apply { mkdirs() }
        val records = JSONArray(); val density = compose.activity.resources.displayMetrics.density
        for (locale in listOf("en", "ar", "de")) for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { language = locale; scale = fontScale }; compose.waitForIdle()
            val root = compose.onNodeWithTag("cloud.introduction.inline").fetchSemanticsNode().boundsInRoot
            assertTrue(root.width / density >= 800)
            assertEquals(kind == "landscape", root.height / density < 480)
            val before = completions
            for (page in 0..2) {
                val primary = compose.onNodeWithTag("cloud.introduction.next").assertIsDisplayed()
                val copy = compose.onNodeWithTag("cloud.introduction.copy.$page").fetchSemanticsNode().boundsInRoot
                val graphicNode = compose.onNodeWithTag("cloud.introduction.graphic.$page").performScrollTo().assertIsDisplayed()
                val graphic = graphicNode.fetchSemanticsNode().boundsInRoot
                assertEquals((if (kind == "landscape") 88 else 152) * density, graphic.width, 1f)
                val wide = kind == "landscape" || fontScale == 1f
                if (wide) {
                    if (locale == "ar") assertTrue(graphic.right <= copy.left + 1) else assertTrue(copy.right <= graphic.left + 1)
                } else assertTrue(graphic.top >= copy.bottom)
                if (page == 0) compose.onNodeWithTag("cloud.introduction.back").assertDoesNotExist()
                else {
                    val back = compose.onNodeWithTag("cloud.introduction.back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val next = primary.fetchSemanticsNode().boundsInRoot
                    if (kind == "landscape") {
                        assertTrue(back.right <= next.left + 1 || next.right <= back.left + 1)
                        assertTrue(kotlin.math.abs(back.center.y - next.center.y) <= 1)
                    } else assertTrue(back.top >= next.bottom)
                }
                val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                File(output, "$locale-$fontScale-page-$page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                records.put(JSONObject().put("viewport", kind).put("locale", locale).put("scale", fontScale).put("page", page)
                    .put("wide", wide).put("widthDp", root.width / density).put("heightDp", root.height / density)
                    .put("graphicWidthDp", graphic.width / density).put("copyLeft", copy.left).put("graphicLeft", graphic.left))
                primary.performClick()
            }
            compose.runOnIdle { assertEquals(before + 1, completions) }
        }
        File(output, "profiles.json").writeText(records.toString(2))
    }
}
