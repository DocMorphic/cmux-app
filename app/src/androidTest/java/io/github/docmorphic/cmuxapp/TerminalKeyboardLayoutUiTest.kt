package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
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

class TerminalKeyboardLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before @After fun noAnrDialog() {
        assertFalse(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(By.textContains("isn't responding")))
    }
    private fun screenshot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "keyboard-layout-check").apply { mkdirs() }
        compose.onNodeWithTag("fixture").captureToImage().asAndroidBitmap().let { bitmap ->
            File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    @Test fun keyboardKeepsPromptVisibleAndContinuousDragsRevealOldestRowsWithoutRemoteScroll() {
        var keyboard by mutableStateOf(false)
        var presentation: TerminalGridPresentation? = null
        var remoteScrolls = 0
        compose.setContent { CmuxTheme {
            val density = LocalDensity.current
            val cells = with(density) { TerminalCellMetrics(6.dp.toPx(), 10.dp.toPx(), 8.sp.toPx()) }
            val grid = remember { RenderGrid().apply { apply(JSONObject().put("format", "cmux.render-grid.v1")
                .put("surface_id", "fixture").put("columns", 60).put("rows", 52).put("full", true)
                .put("row_spans", JSONArray().apply { for (row in 0 until 52) put(JSONObject()
                    .put("row", row).put("column", 0).put("text", if (row == 51) "$ prompt" else "row $row")) })
                .put("cursor", JSONObject().put("row", 51).put("column", 8).put("visible", true))) } }
            val height = if (keyboard) 260.dp else 520.dp
            val display = rememberTerminalGridPresentation(null, "fixture", null, grid,
                with(density) { IntSize(360.dp.roundToPx(), 520.dp.roundToPx()) }, cells, density.density,
                with(density) { height.roundToPx() })
            val motion = rememberTerminalScrollMotion("fixture", null)
            SideEffect { presentation = display }
            Box(Modifier.size(360.dp, height).testTag("fixture")
                .terminalScrollGestures(motion, display.geometry, 0, "primary", linePath = false, enabled = true,
                    onScroll = { rows, _ ->
                        val move = display.scroll(rows, 0.0, 0, true)
                        if (move.rows != 0.0) remoteScrolls++
                        move.revealed || move.rows != 0.0
                    })) {
                RenderGridView(grid, cells, 0, Modifier.matchParentSize(), displayGeometry = display.geometry)
            }
        } }
        compose.runOnIdle { keyboard = true }
        compose.waitForIdle()
        val height = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle {
            val geometry = presentation!!.geometry!!
            assertEquals(-height, geometry.originY, 1f)
            assertEquals(height, geometry.originY + geometry.rows * geometry.cellHeight, 1f)
        }
        screenshot("keyboard-prompt")
        repeat(2) {
            compose.onNodeWithTag("fixture").performTouchInput { down(Offset(centerX, height * .1f)) }
            for (step in 1..4) {
                compose.onNodeWithTag("fixture").performTouchInput { moveTo(Offset(centerX, height * (.1f + step * .2f)), 100) }
                compose.waitForIdle()
            }
            compose.onNodeWithTag("fixture").performTouchInput { up() }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(0f, presentation!!.geometry!!.originY, 1f); assertEquals(0, remoteScrolls) }
        screenshot("keyboard-oldest-rows")
        compose.runOnIdle { keyboard = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, presentation!!.geometry!!.originY, 1f); assertEquals(52, presentation!!.geometry!!.rows) }
        screenshot("keyboard-dismissed")
    }

    @Test fun pinchKeepsFocusedCellWhenKeyboardHasShiftedSharedGrid() {
        var presentation: TerminalGridPresentation? = null
        compose.setContent { CmuxTheme {
            val density = LocalDensity.current
            val cells = with(density) { TerminalCellMetrics(6.dp.toPx(), 10.dp.toPx(), 8.sp.toPx()) }
            val grid = remember { RenderGrid().apply { apply(JSONObject().put("format", "cmux.render-grid.v1")
                .put("surface_id", "fixture").put("columns", 120).put("rows", 104).put("full", true)
                .put("cursor", JSONObject().put("row", 103).put("column", 0).put("visible", true))) } }
            val state = remember { TerminalSizeState(1, SharedTerminalGrid(120, 104), "fixed", emptyList(),
                TerminalSizePolicy(TerminalSizeMode.FIXED, fixed = SharedTerminalGrid(120, 104)), emptyList()) }
            val display = rememberTerminalGridPresentation(null, "fixture", state, grid,
                with(density) { IntSize(360.dp.roundToPx(), 520.dp.roundToPx()) }, cells, density.density,
                with(density) { 260.dp.roundToPx() })
            val zoom = remember { TerminalZoomState() }
            SideEffect { presentation = display }
            Box(Modifier.size(360.dp, 260.dp).testTag("fixture")
                .terminalPinchZoom(zoom, display.sharedLayout, display.pinchOffset, display.transform)) {
                RenderGridView(grid, cells, 0, Modifier.matchParentSize(), displayGeometry = display.geometry)
            }
        } }
        val bounds = compose.onNodeWithTag("fixture").fetchSemanticsNode().boundsInRoot
        val focus = Offset(bounds.width / 2, bounds.height / 2)
        val before = presentation!!.geometry!!.cell(focus.x, focus.y)
        assertTrue(presentation!!.pinchOffset.y > 0)
        compose.onNodeWithTag("fixture").performTouchInput {
            down(0, Offset(width * .4f, centerY)); down(1, Offset(width * .6f, centerY))
            updatePointerTo(0, Offset(width * .2f, centerY)); updatePointerTo(1, Offset(width * .8f, centerY)); move(100)
            up(0); up(1)
        }
        compose.runOnIdle {
            assertEquals(1f, presentation!!.geometry!!.scale)
            assertEquals(before, presentation!!.geometry!!.cell(focus.x, focus.y))
        }
    }
}
