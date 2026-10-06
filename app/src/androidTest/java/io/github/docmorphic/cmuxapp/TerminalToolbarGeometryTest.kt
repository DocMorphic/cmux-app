package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class TerminalToolbarGeometryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun row() = compose.onNodeWithTag("terminal-toolbar-scroll")
    private fun range(): Pair<Float, Float> = row().fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        .let { it.value() to it.maxValue() }
    private fun end() {
        row().performSemanticsAction(SemanticsActions.ScrollBy) { it(100_000f, 0f) }
        compose.waitUntil(5000) { range().let { it.second > 0 && abs(it.first - it.second) < 2 } }
    }
    @Test fun restingTrailingEdgeSurvivesViewportShrinkAndAddingAShortcut() {
        var width by mutableStateOf(360.dp)
        var layout by mutableStateOf(TerminalToolbarLayout.defaults())
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column { Box(Modifier.width(width)) {
                TerminalToolbarView(layout, TerminalInputModifiers(), true, true, {}, {}, {}, {}, inputOwner = "fixture")
            } }
        } } }
        end(); val before = range().second
        compose.runOnIdle { width = 260.dp }
        compose.waitUntil(5000) { range().let { it.second > before + 10 && abs(it.first - it.second) < 2 } }
        val resized = range().second
        compose.runOnIdle { layout = layout.save(TerminalToolbarAction(
            "da4cd09c-9af8-4e71-b876-061c5c31a6b1", "Inspect current workspace", "pwd")) }
        compose.waitUntil(5000) { range().let { it.second > resized + 10 && abs(it.first - it.second) < 2 } }
        compose.onNodeWithContentDescription("Inspect current workspace").assertIsDisplayed()
        row().performSemanticsAction(SemanticsActions.ScrollBy) { it(-100_000f, 0f) }
        compose.waitUntil(5000) { range().first < 2 }
        compose.runOnIdle { width = 320.dp }
        compose.waitForIdle(); assertEquals(0f, range().first, 2f)
    }
    @Test fun heldContactDefersGeometryCorrectionAndReleasePreservesItsPosition() {
        var width by mutableStateOf(360.dp)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column { Box(Modifier.width(width)) {
                TerminalToolbarView(TerminalToolbarLayout.defaults(), TerminalInputModifiers(), true, true,
                    {}, {}, {}, {}, inputOwner = "fixture")
            } }
        } } }
        end(); val before = range()
        row().performTouchInput { down(center) }
        compose.runOnIdle { width = 260.dp }
        compose.waitUntil(5000) { range().second > before.second + 10 }
        assertEquals(before.first, range().first, 2f)
        row().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(before.first, range().first, 2f)
        assertTrue(range().second - range().first > 10)
    }
}
