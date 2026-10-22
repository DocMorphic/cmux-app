package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Production sheet, actual text layouts and compositor frames. Controlled local
 * density, not a system settings or physical accessibility acceptance test. */
@OptIn(ExperimentalTestApi::class)
class NativeChiefPollTypographyRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private var scale by mutableFloatStateOf(1f)
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    @Before fun emulatorOnly() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
    }
    @After fun cleanup() { compose.activity.finish() }

    @Test fun allTwelveExactNativeDescriptorsRenderFreshSheetsWithoutClipping() = render("profiles", exact = true)
    @Test fun stockAndroidNonlinearScalesRenderTheMeasuredFooterInterpolation() = render("android-scales", exact = false)

    private fun render(output: String, exact: Boolean) {
        val reference = JSONObject(instrument.context.assets.open("parity/chief-poll-fonts.json").bufferedReader().use { it.readText() })
        val profiles = reference.getJSONArray("metrics")
        assertEquals(12, profiles.length())
        var showing by mutableStateOf(false)
        var resolvedBodyPoints by mutableFloatStateOf(17f)
        val contents = NativeChiefPollContents("Run deploy?", listOf("allow", "deny"),
            listOf(NativeChiefPollParticipant("chief", "Chief"), NativeChiefPollParticipant("human", "You")))
        compose.setContent { CmuxTheme(darkTheme = true) {
            val host = LocalDensity.current
            Surface(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDensity provides Density(host.density, scale)) {
                    if (showing) {
                        if (exact) NativeChiefPollSheetLayout(contents, resolvedBodyPoints) { showing = false }
                        else NativeChiefPollSheet(contents) { showing = false }
                    }
                }
            }
        } }
        val folder = File(instrument.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val records = JSONArray()
        val physicalDensity = compose.activity.resources.displayMetrics.density
        // Exact component descriptors and stock Android nonlinear scaling are
        // distinct evidence. Stock conversion cannot represent every iOS size.
        val plans = if (exact) List(profiles.length()) { 1f }
            else listOf(.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)
        repeat(plans.size) { index ->
            val selected = plans[index]
            compose.runOnIdle { scale = selected; if (exact) resolvedBodyPoints = profiles.getJSONObject(index).getDouble("bodyPointSize").toFloat(); showing = true }
            compose.waitForIdle()
            val density = Density(physicalDensity, scale)
            val actualBody = if (exact) resolvedBodyPoints.toDouble() else with(density) { 17.sp.toDp().value }.toDouble()
            val profile = if (exact) profiles.getJSONObject(index) else JSONObject().put("name", "android-$selected").put("bodyPointSize", actualBody)
            val body = profile.getDouble("bodyPointSize")
            assertEquals(body, actualBody, .0001)
            var expectedFooter = profiles.getJSONObject(0).getDouble("footerPointSize")
            for (i in 1 until profiles.length()) {
                val a = profiles.getJSONObject(i - 1); val b = profiles.getJSONObject(i)
                if (actualBody <= b.getDouble("bodyPointSize")) {
                    val t = ((actualBody - a.getDouble("bodyPointSize")) / (b.getDouble("bodyPointSize") - a.getDouble("bodyPointSize"))).coerceIn(0.0, 1.0)
                    expectedFooter = a.getDouble("footerPointSize") + (b.getDouble("footerPointSize") - a.getDouble("footerPointSize")) * t
                    break
                }
                expectedFooter = b.getDouble("footerPointSize")
            }
            val label = compose.onNode(hasTestTag("ChiefPollLabel") and hasAnyAncestor(hasTestTag("ChiefPollFooter:0")), useUnmergedTree = true)
            val bodyLabel = compose.onNode(hasTestTag("ChiefPollLabel") and hasAnyAncestor(hasTestTag("ChiefPollOption:0")), useUnmergedTree = true)
            // Match the native reference's fresh presentation at each size.
            // Live system font changes are a separate lifecycle acceptance gate.
            var diagnostic = "No layout read"
            try { compose.waitUntil(10_000) { runCatching {
                val results = mutableListOf<TextLayoutResult>()
                bodyLabel.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val current = results.single().layoutInput
                diagnostic = "target scale=$scale body=$body actual scale=${current.density.fontScale} body=${with(current.density) { current.style.fontSize.toDp().value }}"
                abs(current.density.fontScale - scale) < .0001 &&
                    abs(with(current.density) { current.style.fontSize.toDp().value }.toDouble() - actualBody) < .02
            }.onFailure { diagnostic = it.toString() }.getOrDefault(false) } }
            catch (failure: Throwable) {
                val labels = compose.onAllNodesWithTag("ChiefPollLabel", useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { node ->
                    val parents = generateSequence(node.parent) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toList()
                    "${node.config} parents=$parents"
                }
                File(folder, "chief-poll-font-wait-failure.txt").writeText(diagnostic + "\n" + labels)
                throw AssertionError(diagnostic, failure)
            }
            compose.waitUntil(10_000) { WindowInspector.getGlobalWindowViews().any { it !== compose.activity.window.decorView && it.hasWindowFocus() } }
            val dialogRoot = compose.runOnIdle { WindowInspector.getGlobalWindowViews().single { it !== compose.activity.window.decorView && it.hasWindowFocus() } }
            label.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals("0 Votes", layout.layoutInput.text.text)
            assertEquals(scale, layout.layoutInput.density.fontScale, .0001f)
            assertEquals(expectedFooter, with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toDp().value }.toDouble(), .02)
            assertEquals(1, layout.lineCount)
            assertFalse(layout.hasVisualOverflow)
            val node = label.fetchSemanticsNode()
            val bounds = node.boundsInWindow
            val footer = compose.onNodeWithTag("ChiefPollFooter:0").fetchSemanticsNode().boundsInWindow
            assertTrue(footer.contains(bounds.topLeft) && footer.contains(bounds.bottomRight - androidx.compose.ui.geometry.Offset(.1f, .1f)))
            assertTrue("Native line box fits actual footer", footer.height >= ceil(expectedFooter * 1.193359375 * physicalDensity))
            compose.runOnIdle { assertSame(dialogRoot, WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }) }
            // Bind the screen frame to the current label's actual window raster,
            // so an old differently sized footer cannot satisfy a color check.
            val rendered = label.captureToImage().toPixelMap()
            var accepted: Bitmap? = null
            var ink = 0
            var matchFraction = 0.0
            val expected = ColorUtils.compositeColors(0x99EBEBF5.toInt(), 0xFF1C1C1E.toInt())
            fun close(a: Int, b: Int, tolerance: Int) = listOf(16, 8, 0).all { abs(((a ushr it) and 255) - ((b ushr it) and 255)) <= tolerance }
            compose.waitUntil(10_000) {
                val image = instrument.uiAutomation.takeScreenshot()
                var count = 0; var matches = 0
                for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                    val pixel = rendered[x, y].toArgb()
                    if (close(pixel, expected, 2)) {
                        count++
                        val sx = bounds.left.roundToInt() + x; val sy = bounds.top.roundToInt() + y
                        if (sx in 0 until image.width && sy in 0 until image.height && close(image.getPixel(sx, sy), pixel, 3)) matches++
                    }
                }
                val fraction = if (count == 0) 0.0 else matches.toDouble() / count
                if (count >= 20 && fraction >= .95) { accepted = image; ink = matches; matchFraction = fraction; true } else { image.recycle(); false }
            }
            compose.runOnIdle { assertSame(dialogRoot, WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }) }
            val name = "chief-poll-footer-" + profile.getString("name")
            checkNotNull(accepted).let { image ->
                File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            val geometry = JSONObject()
            for (tag in listOf("ChiefPollSheet", "ChiefPollHeader", "ChiefPollPrompt", "ChiefPollTitle", "ChiefPollDone", "ChiefPollTable",
                "ChiefPollSection:0", "ChiefPollOption:0", "ChiefPollFooter:0", "ChiefPollSection:1", "ChiefPollOption:1", "ChiefPollFooter:1")) {
                val box = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                geometry.put(tag, JSONArray(listOf(box.left.value, box.top.value, (box.right - box.left).value, (box.bottom - box.top).value)))
            }
            records.put(JSONObject().put("name", profile.getString("name")).put("profile", if (exact) profile else JSONObject.NULL).put("fontScale", scale).put("actualBodyPoints", actualBody)
                .put("nativeBodyDeltaPoints", actualBody - body).put("expectedInterpolatedFooterPoints", expectedFooter).put("geometryDp", geometry)
                .put("actualFooterPoints", with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toDp().value })
                .put("lineCount", layout.lineCount).put("footerHeightPixels", footer.height).put("labelHeightPixels", bounds.height)
                .put("actualInkPixels", ink).put("currentLabelRasterMatchFraction", matchFraction).put("sameWindowDuringCapture", true).put("image", "$name.png"))
            compose.onNodeWithContentDescription("Done").performClick()
            compose.onNodeWithTag("ChiefPollSheet").assertDoesNotExist()
        }
        File(folder, "chief-poll-footer-$output.json").writeText(JSONObject().put("referenceRun", reference.getLong("run"))
            .put("densityMode", if (exact) "Production sheet layout with exact resolved body-point inputs and normal Android Density; not Android system mapping" else "Production sheet resolves stock Android nonlinear Density at seven controlled local scales")
            .put("scope", "Production sheet, fresh presentations, actual layouts and compositor. No live font-change/system-settings, account, physical phone or full typography parity acceptance.")
            .put("records", records).toString(2))
    }
}
