package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
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

/** Controlled context/density component evidence, not OS locale/TalkBack or matched UIKit material. */
class NativeBrowserDialogCardRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun allNineLocalizedAuthCardsRemainScrollableAndUsableAtEnlargedText() {
        val locales = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant")
        var locale by mutableStateOf("en")
        var scale by mutableFloatStateOf(1f)
        val value = JSONObject().put("dialog_id", "localized").put("kind", "http_basic_authentication").put("host", "site.example")
            .put("text_field", JSONObject().put("initial", "fixture-user"))
            .put("buttons", JSONArray().put(JSONObject().put("id", "cancel").put("role", "cancel").put("label", "Cancel"))
                .put(JSONObject().put("id", "ok").put("role", "default").put("label", "Sign in")))
        compose.setContent { CmuxTheme {
            val base = LocalContext.current
            val density = LocalDensity.current
            val configuration = remember(base, locale, scale) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale)); fontScale = scale
            } }
            val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (locale == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { Box(Modifier.align(androidx.compose.ui.Alignment.Center).width(260.dp).height(500.dp).testTag("CardViewport")) {
                    NativeBrowserDialogCard(value) { _, _ -> error("Rendering probe must not send") }
                } } }
            }
        } }
        val records = JSONArray()
        val folder = File(compose.activity.getExternalFilesDir(null), "browser-dialog-cards").apply { mkdirs() }
        for (language in locales) for (size in listOf(1f, 2f)) {
            compose.runOnIdle { locale = language; scale = size }
            compose.waitForIdle()
            val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val resources = compose.activity.createConfigurationContext(config).resources
            val title = compose.onNodeWithText(resources.getString(R.string.cmux_browser_dialog_requesttitle))
            title.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(size, layouts.single().layoutInput.density.fontScale, .0001f)
            val layout = layouts.single()
            File(folder, "diagnostic-$language-$size.json").writeText(JSONObject()
                .put("locale", language).put("scale", size).put("size", layout.size.toString())
                .put("constraints", layout.layoutInput.constraints.toString()).put("lineCount", layout.lineCount)
                .put("paragraphWidth", layout.multiParagraph.width).put("overflowWidth", layout.didOverflowWidth).put("overflowHeight", layout.didOverflowHeight)
                .put("viewport", compose.onNodeWithTag("CardViewport").fetchSemanticsNode().boundsInRoot.toString()).toString(2))
            val diagnosticImage = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(folder, "diagnostic-$language-$size.png").outputStream().use { diagnosticImage.compress(Bitmap.CompressFormat.PNG, 100, it) }; diagnosticImage.recycle()
            // A paragraph may retain the available width while Text wraps its measured
            // box to content. RTL lines also retain that paragraph alignment offset.
            // Check actual line extents and height, not the unused paragraph width.
            assertFalse("Title height overflow in $language at $size", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("Title line exceeds measured width in $language at $size", layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
            }
            val viewport = compose.onNodeWithTag("CardViewport").fetchSemanticsNode().boundsInRoot
            val expectedDensity = compose.activity.resources.displayMetrics.density
            assertEquals(260f * expectedDensity, viewport.width, 1f)
            assertEquals(500f * expectedDensity, viewport.height, 1f)
            compose.onNodeWithContentDescription(resources.getString(R.string.cmux_browser_dialog_username)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(resources.getString(R.string.cmux_browser_dialog_password)).performScrollTo().assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("BrowserDialogPassword").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
            val placeholderLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(resources.getString(R.string.cmux_browser_dialog_password), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(placeholderLayouts)) }
            assertEquals("Single-line password hint in $language at $size", 1, placeholderLayouts.single().lineCount)
            for (id in listOf("cancel", "ok")) compose.onNodeWithTag("BrowserDialogButton:$id").performScrollTo().assertIsDisplayed()
            val cancel = compose.onNodeWithTag("BrowserDialogButton:cancel").fetchSemanticsNode().boundsInRoot
            val accept = compose.onNodeWithTag("BrowserDialogButton:ok").fetchSemanticsNode().boundsInRoot
            assertEquals(cancel.center.y, accept.center.y, 1f)
            assertTrue(if (language == "ar") cancel.center.x > accept.center.x else cancel.center.x < accept.center.x)
            records.put(JSONObject().put("locale", language).put("fontScale", layouts.single().layoutInput.density.fontScale)
                .put("titleLines", layouts.single().lineCount).put("usernameAndPasswordVisibleByScroll", true)
                .put("passwordSemantic", true).put("buttonOrderCorrect", true))
            compose.waitForIdle()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(folder, "$language-$size.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        File(folder, "profiles.json").writeText(records.toString(2))
    }

    @Test fun buttonLayoutUsesSourceCountAndGraphemeThreshold() {
        var labels by mutableStateOf(listOf("Cancel", "Continue"))
        val value = fixtureValue()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            val current = JSONObject(value.toString()).put("buttons", JSONArray().apply {
                labels.forEachIndexed { i, label -> put(JSONObject().put("id", "$i").put("label", label)) }
            })
            NativeBrowserDialogCard(current) { _, _ -> error("No response in layout probe") }
        } } }
        for ((list, horizontal) in listOf(listOf("Cancel", "Continue") to true,
            listOf("Cancel", "👨‍👩‍👧‍👦 Continue") to true,
            listOf("Cancel", "Continue without HTTPS") to false,
            listOf("One", "Two", "Three") to false)) {
            compose.runOnIdle { labels = list }; compose.waitForIdle()
            val first = compose.onNodeWithTag("BrowserDialogButton:0").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("BrowserDialogButton:1").fetchSemanticsNode().boundsInRoot
            if (horizontal) assertEquals(first.center.y, second.center.y, 1f)
            else assertTrue(second.top >= first.bottom)
        }
    }

    private fun fixtureValue() = JSONObject().put("dialog_id", "button-layout").put("title", "Synthetic layout")
}
