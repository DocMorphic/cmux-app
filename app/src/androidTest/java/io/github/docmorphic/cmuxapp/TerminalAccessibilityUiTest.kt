package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalAccessibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun accessibilityRevealsHiddenTopRowsReturnsToLatestAndRemovesDisabledScrollActions() {
        var enabled by mutableStateOf(true)
        var geometry: TerminalGeometry? = null
        var visibleHeight = 0f
        var remoteRows = 0.0
        var textOpened = 0
        compose.setContent {
            val density = LocalDensity.current
            val cells = with(density) { TerminalCellMetrics(10.dp.toPx(), 20.dp.toPx(), 14.dp.toPx()) }
            val grid = remember { RenderGrid().apply { apply(JSONObject().put("format", "cmux.render-grid.v1")
                .put("surface_id", "fixture").put("columns", 20).put("rows", 20)
                .put("cursor", JSONObject().put("row", 19).put("column", 0).put("visible", true))) } }
            val pixels = with(density) { IntSize(200.dp.roundToPx(), 400.dp.roundToPx()) }
            val height = with(density) { 200.dp.roundToPx() }
            val display = rememberTerminalGridPresentation(null, "fixture", null, grid, pixels, cells, density.density, height)
            val model = TerminalAccessibilityScroll(true, 0, 0.0, display.reveal, display.maximumReveal,
                display.geometry!!.cellHeight, height)
            SideEffect { geometry = display.geometry; visibleHeight = height.toFloat() }
            RenderGridView(grid, cells, 0, Modifier.size(200.dp).testTag("terminal")
                .nativeTerminalAccessibility(12f, model, enabled, {}, { textOpened++ }) { rows ->
                    if (!enabled) false else display.scroll(rows, 0.0, 0, true).let {
                        remoteRows += it.rows; it.revealed || it.rows != 0.0
                    }
                }, displayGeometry = display.geometry, displayLines = display.visibleLines)
        }
        compose.runOnIdle { assertEquals(-visibleHeight, geometry!!.originY, 1f) }
        compose.onNodeWithTag("terminal").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            assertTrue(scroll(0f, -visibleHeight))
        }
        compose.runOnIdle { assertEquals(0f, geometry!!.originY, 1f); assertEquals(0.0, remoteRows, 0.0) }
        val latestAction = compose.onNodeWithTag("terminal").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Latest output" }
        compose.runOnIdle { assertTrue(latestAction.action()) }
        compose.runOnIdle { assertEquals(-visibleHeight, geometry!!.originY, 1f); enabled = false }
        assertNull(compose.onNodeWithTag("terminal").fetchSemanticsNode().config.getOrNull(SemanticsActions.ScrollBy))
        val actions = compose.onNodeWithTag("terminal").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(listOf("View as Text"), actions.map { it.label })
            assertTrue(actions.single().action())
        }
        compose.runOnIdle { assertEquals(1, textOpened); assertEquals(0.0, remoteRows, 0.0) }
    }
}
