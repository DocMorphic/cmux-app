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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
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

    private data class RasterFrame(val image: Bitmap, val ink: Int, val matchFraction: Double)
    private fun captureSecondaryLabel(label: SemanticsNodeInteraction): RasterFrame {
        val bounds = label.fetchSemanticsNode().boundsInWindow
        val rendered = label.captureToImage().toPixelMap()
        val expected = ColorUtils.compositeColors(0x99EBEBF5.toInt(), 0xFF1C1C1E.toInt())
        fun close(a: Int, b: Int, tolerance: Int) =
            abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) <= tolerance &&
            abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) <= tolerance && abs((a and 255) - (b and 255)) <= tolerance
        var accepted: RasterFrame? = null
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
            if (count >= 20 && fraction >= .95) { accepted = RasterFrame(image, matches, fraction); true } else { image.recycle(); false }
        }
        return checkNotNull(accepted)
    }

    private fun render(output: String, exact: Boolean) {
        val reference = JSONObject(instrument.context.assets.open("parity/chief-poll-fonts.json").bufferedReader().use { it.readText() })
        val profiles = reference.getJSONArray("metrics")
        val tableProfiles = JSONObject(instrument.context.assets.open("parity/chief-poll-table-metrics.json").bufferedReader().use { it.readText() }).getJSONArray("metrics")
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
            fun tableMetric(field: String): Double {
                for (i in 1 until tableProfiles.length()) {
                    val a = tableProfiles.getJSONObject(i - 1); val b = tableProfiles.getJSONObject(i)
                    if (actualBody <= b.getDouble("bodyPoints")) {
                        val t = ((actualBody - a.getDouble("bodyPoints")) / (b.getDouble("bodyPoints") - a.getDouble("bodyPoints"))).coerceIn(0.0, 1.0)
                        return a.getDouble(field) + (b.getDouble(field) - a.getDouble(field)) * t
                    }
                }
                return tableProfiles.getJSONObject(tableProfiles.length() - 1).getDouble(field)
            }
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
            val frame = captureSecondaryLabel(label)
            compose.runOnIdle { assertSame(dialogRoot, WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }) }
            val name = "chief-poll-footer-" + profile.getString("name")
            File(folder, "$name.png").outputStream().use { frame.image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.image.recycle()
            val geometry = JSONObject()
            for (tag in listOf("ChiefPollSheet", "ChiefPollHeader", "ChiefPollPrompt", "ChiefPollTitle", "ChiefPollDone", "ChiefPollTable",
                "ChiefPollSection:0", "ChiefPollOption:0", "ChiefPollFooter:0", "ChiefPollSection:1", "ChiefPollOption:1", "ChiefPollFooter:1")) {
                val box = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                geometry.put(tag, JSONArray(listOf(box.left.value, box.top.value, (box.right - box.left).value, (box.bottom - box.top).value)))
            }
            val labelBoxes = JSONObject()
            for ((tag, field, inset) in listOf(
                Triple("ChiefPollSection:0", "firstHeaderHeight", "firstHeaderLabelTop"),
                Triple("ChiefPollSection:1", "continuationHeaderHeight", "continuationHeaderLabelTop"),
                Triple("ChiefPollFooter:0", "footerHeight", "footerLabelTop"),
                Triple("ChiefPollFooter:1", "footerHeight", "footerLabelTop"),
                Triple("ChiefPollOption:0", "rowHeight", ""), Triple("ChiefPollOption:1", "rowHeight", ""))) {
                val rect = geometry.getJSONArray(tag)
                // Exact native knots use the same pixel grid; interpolated
                // Android sizes add at most half a pixel of container rounding.
                assertEquals("Native component height at $actualBody: $tag", tableMetric(field), rect.getDouble(3), (if (exact) 1.01 else 1.51) / physicalDensity)
                if (inset.isNotEmpty()) {
                    val textNode = compose.onNode(hasTestTag("ChiefPollLabel") and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNode()
                    val textCoordinates = textNode.layoutInfo.coordinates
                    val box = checkNotNull(textCoordinates.parentLayoutCoordinates)
                    assertEquals("Actual line-box wrapper", textCoordinates.size.width, box.size.width)
                    val top = box.positionInRoot().y / physicalDensity - rect.getDouble(1)
                    assertEquals("Native label inset at $actualBody: $tag", tableMetric(inset), top, 1.01 / physicalDensity)
                    labelBoxes.put(tag, JSONObject().put("topDp", top).put("heightPixels", box.size.height))
                }
            }
            val titleLayouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasTestTag("ChiefPollLabel") and hasAnyAncestor(hasTestTag("ChiefPollTitle")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(titleLayouts)) }
            val title = titleLayouts.single().layoutInput
            assertEquals("Native navigation title font", tableMetric("titlePointSize"), with(title.density) { title.style.fontSize.toDp().value }.toDouble(), .02)

            val table = compose.onNodeWithTag("ChiefPollTable")
            table.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
            val scroll = table.fetchSemanticsNode().config[ChiefPollScrollProbe].invoke()
            assertEquals("Settled table end", scroll.maximum, scroll.offset)
            val nonVoters = compose.onNodeWithTag("ChiefPollSection:2").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertEquals("Native non-voter header height", tableMetric("nonVoterHeaderHeight"), (nonVoters.bottom - nonVoters.top).value.toDouble(), (if (exact) 1.01 else 1.51) / physicalDensity)
            val nonVoterLabel = compose.onNode(hasTestTag("ChiefPollLabel") and hasAnyAncestor(hasTestTag("ChiefPollSection:2")), useUnmergedTree = true).assertIsDisplayed()
            val nonVoterCoordinates = nonVoterLabel.fetchSemanticsNode().layoutInfo.coordinates
            val nonVoterBox = checkNotNull(nonVoterCoordinates.parentLayoutCoordinates)
            val nonVoterInset = nonVoterBox.positionInRoot().y / physicalDensity - nonVoters.top.value
            assertEquals("Native non-voter label inset", tableMetric("nonVoterHeaderLabelTop"), nonVoterInset.toDouble(), 1.01 / physicalDensity)
            for (id in listOf("chief", "human")) {
                val row = compose.onNodeWithTag("ChiefPollParticipant:$id").assertIsDisplayed().getUnclippedBoundsInRoot()
                val text = compose.onNode(hasTestTag("ChiefPollLabel") and hasAnyAncestor(hasTestTag("ChiefPollParticipant:$id")), useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue("Participant ascent fits", text.top.value >= row.top.value)
                assertTrue("Participant descent fits", text.bottom.value <= row.bottom.value)
            }
            val root = compose.onNodeWithTag("ChiefPollDetails").getUnclippedBoundsInRoot()
            val finalRow = compose.onNodeWithTag("ChiefPollParticipant:human").getUnclippedBoundsInRoot()
            val navigation = WindowInsetsCompat.toWindowInsetsCompat(checkNotNull(dialogRoot.rootWindowInsets))
                .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom / physicalDensity
            val actualBottomGap = root.bottom.value - navigation - finalRow.bottom.value
            if (scroll.maximum > 0) assertEquals("Native table tail before platform safe inset", tableMetric("emptyFooterHeight") + tableMetric("tableBottomPadding"), actualBottomGap.toDouble(), 1.01 / physicalDensity)
            val bottom = captureSecondaryLabel(nonVoterLabel)
            File(folder, "$name-bottom.png").outputStream().use { bottom.image.compress(Bitmap.CompressFormat.PNG, 100, it) }; bottom.image.recycle()
            compose.runOnIdle { assertSame(dialogRoot, WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }) }
            records.put(JSONObject().put("name", profile.getString("name")).put("profile", if (exact) profile else JSONObject.NULL).put("fontScale", scale).put("actualBodyPoints", actualBody)
                .put("nativeBodyDeltaPoints", actualBody - body).put("expectedInterpolatedFooterPoints", expectedFooter).put("geometryDp", geometry)
                .put("labelBoxes", labelBoxes).put("actualTitlePoints", with(title.density) { title.style.fontSize.toDp().value })
                .put("actualFooterPoints", with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toDp().value })
                .put("lineCount", layout.lineCount).put("footerHeightPixels", footer.height).put("labelHeightPixels", bounds.height)
                .put("actualInkPixels", frame.ink).put("currentLabelRasterMatchFraction", frame.matchFraction).put("sameWindowDuringCapture", true).put("image", "$name.png"))
            records.getJSONObject(records.length() - 1).put("nonVoterHeaderHeight", (nonVoters.bottom - nonVoters.top).value)
                .put("nonVoterLabelTop", nonVoterInset).put("nonVoterInkPixels", bottom.ink).put("nonVoterRasterMatchFraction", bottom.matchFraction)
                .put("offset", scroll.offset).put("maximumOffset", scroll.maximum).put("navigationBottomDp", navigation)
                .put("actualBottomGapDp", actualBottomGap).put("bottomImage", "$name-bottom.png")
            compose.onNodeWithContentDescription("Done").performClick()
            compose.onNodeWithTag("ChiefPollSheet").assertDoesNotExist()
        }
        File(folder, "chief-poll-footer-$output.json").writeText(JSONObject().put("referenceRun", reference.getLong("run"))
            .put("densityMode", if (exact) "Production sheet layout with exact resolved body-point inputs and normal Android Density; not Android system mapping" else "Production sheet resolves stock Android nonlinear Density at seven controlled local scales")
            .put("scope", "Production sheet, fresh presentations, actual layouts and compositor. No live font-change/system-settings, account, physical phone or full typography parity acceptance.")
            .put("records", records).toString(2))
    }
}
