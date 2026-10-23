package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
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

/** Controlled composition contexts, not OS locale, actual TalkBack or native iOS appearance. */
class CloudMachineRestartLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun narrowLocalizedRestartActionsFitAndRetainTheirFullAccessibilityLabels() {
        var language by mutableStateOf("en")
        var scale by mutableFloatStateOf(1f)
        var failed by mutableStateOf(false)
        val swipes = CloudMachineSwipeCoordinator()
        val sent = mutableListOf<CloudMachineAction>()
        val machine = CloudMachine("fixture", "fixture", "running", "Fixture machine", null,
            CloudMachineResources(4, 8192), supportsRestart = true)
        compose.setContent { CmuxTheme {
            val base = LocalContext.current; val density = LocalDensity.current
            val configuration = remember(base, language, scale) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language)); fontScale = scale
            } }
            val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Box(Modifier.width(260.dp).testTag("RestartViewport")) {
                        NativeCloudMachineRow(machine, false,
                            if (failed) CloudMachineActionFailure(machine.id, CloudMachineAction.RESTART,
                                CloudSessionFailure("private diagnostic", 504, "provider recovery")) else null,
                            null, swipes, {}, { sent += it })
                    }
                } }
            }
        } }
        val output = File(compose.activity.getExternalFilesDir(null), "cloud-restart-layout").apply { mkdirs() }
        val records = JSONArray()
        for (locale in listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant")) for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { language = locale; scale = fontScale; failed = false; swipes.opened = null }
            compose.waitForIdle()
            val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            val resources = compose.activity.createConfigurationContext(config).resources
            val restart = resources.getString(R.string.cmux_cloud_restart)
            val row = compose.onNodeWithTag("cloud.machine.fixture")
            val actionNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
            assertTrue(actionNodes.any { node -> node.config[SemanticsActions.CustomActions].any { it.label == restart } })
            val before = sent.size
            row.performTouchInput { if (locale == "ar") swipeRight() else swipeLeft() }
            assertEquals(before, sent.size) // A full swipe only reveals; it never submits.
            val tags = listOf("RESTART", "PAUSE", "DELETE").map { "cloud.swipe.fixture.$it" }
            val bounds = tags.map { tag -> compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            val viewport = compose.onNodeWithTag("RestartViewport").fetchSemanticsNode().boundsInRoot
            assertEquals(260f * compose.activity.resources.displayMetrics.density, viewport.width, 1f)
            bounds.forEach { assertTrue(it.left >= viewport.left - 1 && it.right <= viewport.right + 1) }
            if (locale == "ar") assertTrue(bounds[0].left >= bounds[1].right - 1 && bounds[1].left >= bounds[2].right - 1)
            else assertTrue(bounds[0].right <= bounds[1].left + 1 && bounds[1].right <= bounds[2].left + 1)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(restart) and hasAnyAncestor(hasTestTag(tags.first())), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(fontScale, layouts.single().layoutInput.density.fontScale, .0001f)
            val diagnostic = layouts.single()
            File(output, "diagnostic-$locale-$fontScale.json").writeText(JSONObject()
                .put("locale", locale).put("scale", fontScale).put("text", diagnostic.layoutInput.text.text)
                .put("size", diagnostic.size.toString()).put("paragraphWidth", diagnostic.multiParagraph.width)
                .put("constraints", diagnostic.layoutInput.constraints.toString())
                .put("lines", JSONArray().apply { for (line in 0 until diagnostic.lineCount) put(JSONObject()
                    .put("left", diagnostic.getLineLeft(line)).put("right", diagnostic.getLineRight(line))) }).toString(2))
            val diagnosticImage = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, "diagnostic-$locale-$fontScale.png").outputStream().use { diagnosticImage.compress(Bitmap.CompressFormat.PNG, 100, it) }; diagnosticImage.recycle()
            assertFalse("Restart label clipped in $locale at $fontScale", layouts.single().didOverflowHeight)
            for (line in 0 until layouts.single().lineCount) {
                val layout = layouts.single()
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("Line $line width ${layout.getLineRight(line) - layout.getLineLeft(line)} exceeds ${layout.size.width} in $locale at $fontScale", layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
            }
            compose.waitForIdle()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(output, "$locale-$fontScale.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            compose.onNodeWithTag(tags.first()).performClick()
            assertEquals(before + 1, sent.size); assertEquals(CloudMachineAction.RESTART, sent.last())
            compose.runOnIdle { failed = true }; compose.waitForIdle()
            compose.onNodeWithText(resources.getString(R.string.cmux_cloud_restart_failed, "provider recovery"), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("private diagnostic", substring = true).assertDoesNotExist()
            records.put(JSONObject().put("locale", locale).put("scale", fontScale).put("lines", layouts.single().lineCount)
                .put("allThreeActionsWithinViewport", true).put("fullAccessibilityLabel", restart).put("failureFormatted", true))
        }
        File(output, "profiles.json").writeText(records.toString(2))
    }

}
