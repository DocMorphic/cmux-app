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

class TerminalSharedGridUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before @After fun noAnrDialog() {
        assertFalse(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(By.textContains("isn't responding")))
    }
    private fun grid(cols: Int, rows: Int) = RenderGrid().apply {
        apply(JSONObject().put("format", "cmux.render-grid.v1").put("surface_id", "fixture")
            .put("columns", cols).put("rows", rows).put("full", true)
            .put("row_spans", JSONArray().apply { for (row in 0 until rows step 5) for (col in 0 until cols step 30)
                put(JSONObject().put("row", row).put("column", col).put("text", "row $row col $col")) }
                .put(JSONObject().put("row", rows - 1).put("column", 0).put("text", "$ prompt at last row"))))
    }
    private fun screenshot(name: String) {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "shared-grid-check").apply { mkdirs() }
        compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap().let { bitmap ->
            File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @Test fun smallerGridPinsTopLeftAndKeepsChipBelowPrompt() {
        var observed: TerminalSharedGridLayout? = null
        compose.setContent { CmuxTheme {
            val density = LocalDensity.current
            val cells = with(density) { TerminalCellMetrics(6.dp.toPx(), 10.dp.toPx(), 8.sp.toPx()) }
            val layout = with(density) { TerminalSharedGridLayout.resolve(360.dp.toPx(), 520.dp.toPx(), 60, 30, cells, this.density)!! }
            SideEffect { observed = layout }
            val grid = remember { grid(60, 30) }
            val sizing = TerminalSizingPresentation(TerminalSizeState(1, SharedTerminalGrid(60, 30), "smallest", emptyList(),
                TerminalSizePolicy(TerminalSizeMode.SMALLEST), emptyList()), null)
            Box(Modifier.size(360.dp, 520.dp).testTag("fixture")) {
                RenderGridView(grid, cells, 0, Modifier.matchParentSize(), displayGeometry = layout.geometry)
                TerminalSizingOverlay(sizing, grid, cells, layout.geometry) {}
            }
        } }
        compose.runOnIdle {
            assertEquals(0f, observed!!.geometry.originX); assertEquals(0f, observed!!.geometry.originY)
            assertEquals(1f, observed!!.scale)
        }
        val root = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("terminal-sizing-chip").fetchSemanticsNode().boundsInRoot
        assertTrue(chip.top - root.top >= observed!!.geometry.cellHeight * 30)
        screenshot("small-grid")
    }

    @Test fun realPinchAndPanKeepFontAndGridFixedAndUpdateTapCoordinates() {
        var observed: TerminalSharedGridLayout? = null
        var font: TerminalZoomState? = null
        val taps = mutableListOf<TerminalGeometry.Cell>()
        compose.setContent { CmuxTheme {
            val density = LocalDensity.current
            val zoom = remember { TerminalZoomState() }
            var transform by remember { mutableStateOf(TerminalGridTransform()) }
            val cells = with(density) { TerminalCellMetrics(6.dp.toPx(), 10.dp.toPx(), 8.sp.toPx()) }
            val layout = with(density) { TerminalSharedGridLayout.resolve(360.dp.toPx(), 520.dp.toPx(), 120, 100, cells, this.density, transform)!! }
            val latest by rememberUpdatedState(layout.geometry)
            SideEffect { observed = layout; font = zoom }
            val grid = remember { grid(120, 100) }
            Box(Modifier.size(360.dp, 520.dp).testTag("fixture")
                .terminalPinchZoom(zoom, layout) { transform = it }
                .pointerInput(Unit) { detectTapGestures { taps += latest.cell(it.x, it.y) } }) {
                RenderGridView(grid, cells, 0, Modifier.matchParentSize(), displayGeometry = layout.geometry)
            }
        } }
        var initialFont = 0f
        compose.runOnIdle { initialFont = font!!.size; assertEquals(.5f, observed!!.scale) }
        compose.onNodeWithTag("fixture").performTouchInput {
            down(0, Offset(width * .4f, height * .5f)); down(1, Offset(width * .6f, height * .5f))
            updatePointerTo(0, Offset(width * .2f, height * .5f)); updatePointerTo(1, Offset(width * .8f, height * .5f)); move(100)
            up(0); up(1)
        }
        compose.runOnIdle {
            assertEquals(1f, observed!!.scale); assertEquals(initialFont, font!!.size)
            assertEquals(120, observed!!.columns); assertEquals(100, observed!!.rows)
            assertTrue(taps.isEmpty()); assertFalse(font!!.overlayVisible)
        }
        var beforeY = 0f
        compose.runOnIdle { beforeY = observed!!.geometry.originY }
        compose.onNodeWithTag("fixture").performTouchInput {
            down(0, Offset(width * .3f, height * .4f)); down(1, Offset(width * .7f, height * .4f))
            updatePointerTo(0, Offset(width * .3f, height * .6f)); updatePointerTo(1, Offset(width * .7f, height * .6f)); move(100)
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue(observed!!.geometry.originY > beforeY); assertEquals(initialFont, font!!.size); assertTrue(taps.isEmpty()) }
        val root = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
        val expected = observed!!.geometry.cell(root.width / 2f, root.height / 2f)
        compose.onNodeWithTag("fixture").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(expected), taps) }
        screenshot("zoomed-panned-grid")
    }
}
