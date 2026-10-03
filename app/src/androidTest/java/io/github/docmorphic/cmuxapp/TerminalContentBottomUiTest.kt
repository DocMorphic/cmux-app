package io.github.docmorphic.cmuxapp

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Base64

class TerminalContentBottomUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun primaryImageBelowCursorStaysAboveKeyboard() {
        var terminal: GhosttyVtTerminal? = null
        var geometry: TerminalGeometry? = null
        var pixel: Pair<Int, Int>? = null
        try {
            compose.setContent {
                val density = LocalDensity.current
                val cells = with(density) { TerminalCellMetrics(10.dp.toPx(), 20.dp.toPx(), 14.dp.toPx()) }
                val grid = remember { (ghosttyTerminalFactory(cells)(20, 20) as GhosttyVtTerminal).also {
                    terminal = it
                    val pixels = Base64.getEncoder().encodeToString(byteArrayOf(-1, 0, 0))
                    it.append(("\u001b]11;#000000\u0007\u001b[?25l\u001b[15;1H" +
                        "\u001b_Ga=T,f=24,s=1,v=1,i=1,p=1,c=2,r=3,C=1;$pixels\u001b\\\u001b[H").toByteArray())
                } }
                val display = rememberTerminalGridPresentation(null, "image", null, grid,
                    with(density) { IntSize(200.dp.roundToPx(), 400.dp.roundToPx()) }, cells, density.density,
                    with(density) { 200.dp.roundToPx() })
                SideEffect {
                    geometry = display.geometry
                    pixel = with(density) { 10.dp.roundToPx() to 190.dp.roundToPx() }
                }
                RenderGridView(grid, cells, 0, Modifier.size(200.dp).testTag("image"),
                    displayGeometry = display.geometry, displayLines = display.visibleLines)
            }
            compose.waitForIdle()
            val bitmap = compose.onNodeWithTag("image").captureToImage().asAndroidBitmap()
            assertTrue("Image below the cursor must slide the render", geometry!!.originY < 0)
            assertEquals(Color.RED, bitmap.getPixel(pixel!!.first, pixel!!.second))
        } finally { compose.runOnIdle { terminal?.close() } }
    }

    @Test fun measurementSkipsClosedKeyboardAndSharesRowsWithRendererWhenOpen() {
        var reads = 0
        val backing = RenderGrid().apply { apply(JSONObject().put("format", "cmux.render-grid.v1")
            .put("surface_id", "fixture").put("columns", 20).put("rows", 20)
            .put("row_spans", JSONArray().put(JSONObject().put("row", 0).put("column", 0).put("text", "prompt")))) }
        val grid = object : TerminalDisplay by backing {
            override fun visibleLines(scrollOffset: Int): List<List<RenderGrid.Span>> {
                reads++; return backing.visibleLines(scrollOffset)
            }
        }
        var keyboard by mutableStateOf(false)
        var revision by mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            val cells = with(density) { TerminalCellMetrics(10.dp.toPx(), 20.dp.toPx(), 14.dp.toPx()) }
            val height = if (keyboard) 200.dp else 400.dp
            val display = rememberTerminalGridPresentation(null, "fixture", null, grid,
                with(density) { IntSize(200.dp.roundToPx(), 400.dp.roundToPx()) }, cells, density.density,
                with(density) { height.roundToPx() }, revision)
            RenderGridView(grid, cells, revision, Modifier.size(200.dp, height),
                displayGeometry = display.geometry, displayLines = display.visibleLines)
        }
        compose.runOnIdle { assertEquals(1, reads); revision++ }
        compose.runOnIdle { assertEquals(2, reads); keyboard = true }
        compose.runOnIdle { assertEquals(3, reads); revision++ }
        compose.runOnIdle { assertEquals(4, reads) }
    }
}
