package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

class TerminalSizingOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before @After fun noAnrDialog() {
        assertFalse(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(By.textContains("isn't responding")))
    }
    private fun display(light: Boolean, rows: Int = 22) = RenderGrid().apply {
        apply(JSONObject().put("format", "cmux.render-grid.v1").put("surface_id", "fixture")
            .put("columns", 60).put("rows", rows).put("full", true)
            .put("terminal_background", if (light) "#ffffff" else "#111316")
            .put("terminal_foreground", if (light) "#111111" else "#e0e5eb")
            .put("row_spans", JSONArray().put(JSONObject().put("row", 0).put("column", 0).put("text", "Shared terminal") )
                .put(JSONObject().put("row", rows - 1).put("column", 0).put("text", "$ echo visible-prompt"))))
    }
    private fun presentation(rows: Int) = TerminalSizingPresentation(TerminalSizeState(1, SharedTerminalGrid(60, rows),
        "smallest", listOf("mac"), TerminalSizePolicy(TerminalSizeMode.SMALLEST), listOf(
            TerminalSizeParticipant("mac", "fixture", "Fixture", "mac", "Mac Studio", null,
                SharedTerminalGrid(60, rows), null, true, "mac"))), "phone")

    @Test fun letterboxDecorationLeavesTerminalTapsAndPromptAvailableAcrossThemes() {
        var light by mutableStateOf(false)
        var opened = 0; var terminalTaps = 0
        compose.setContent { CmuxTheme {
            val cells = with(LocalDensity.current) { TerminalCellMetrics.fromFontSize(9.sp.toPx(), 2.dp.toPx()) }
            val grid = remember(light) { display(light) }
            Box(Modifier.size(360.dp, 520.dp).testTag("fixture").pointerInput(Unit) {
                detectTapGestures { terminalTaps++ }
            }) {
                RenderGridView(grid, cells, 0, Modifier.matchParentSize())
                TerminalSizingOverlay(presentation(22), grid, cells) { opened++ }
            }
        } }
        repeat(2) { theme ->
            compose.waitForIdle()
            val root = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
            val chip = compose.onNodeWithTag("terminal-sizing-chip").fetchSemanticsNode().boundsInRoot
            assertTrue("Chip belongs below the centered grid", chip.top > root.center.y)
            compose.onNodeWithTag("fixture").performTouchInput { click(Offset(width / 2f, height / 2f)) }
            compose.runOnIdle { assertEquals(theme + 1, terminalTaps) }
            compose.onNodeWithTag("terminal-sizing-chip").performTouchInput { click() }
            compose.runOnIdle { assertEquals(theme + 1, opened); assertEquals(theme + 1, terminalTaps) }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap()
            val colors = (0 until bitmap.width).map { bitmap.getPixel(it, bitmap.height / 12) }.distinct()
            assertTrue("Unused terminal space must contain visible hatch strokes", colors.size > 1)
            val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "sizing-chrome-check").apply { mkdirs() }
            File(output, if (theme == 0) "dark.png" else "light.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            compose.runOnIdle { light = true }
        }
    }

    @Test fun fullGridUsesCompactChipAtTopAndExposesAccessibleControl() {
        var opened = false
        compose.setContent { CmuxTheme {
            val cells = with(LocalDensity.current) { TerminalCellMetrics(6.dp.toPx(), 10.dp.toPx(), 8.sp.toPx()) }
            Box(Modifier.size(360.dp, 520.dp).testTag("fixture")) {
                val grid = remember { display(false, 52) }
                RenderGridView(grid, cells, 0, Modifier.matchParentSize())
                TerminalSizingOverlay(presentation(52), grid, cells) { opened = true }
            }
        } }
        val root = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("terminal-sizing-chip").fetchSemanticsNode().boundsInRoot
        assertTrue(chip.bottom < root.top + root.height / 5)
        assertTrue(chip.width < root.width / 3)
        compose.onNodeWithContentDescription("Terminal size, 60×52 · Mac Studio. Open size controls").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
