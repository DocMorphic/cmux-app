package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalKeyboardFrameUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun grid(background: String, text: String) = RenderGrid().apply { apply(JSONObject()
        .put("format", "cmux.render-grid.v1").put("surface_id", "fixture").put("columns", 20).put("rows", 10)
        .put("active_screen", "alternate").put("terminal_background", background)
        .put("row_spans", JSONArray().put(JSONObject().put("row", 0).put("column", 0).put("text", text)))) }

    private fun color() = compose.onNodeWithTag("terminal").captureToImage().asAndroidBitmap().let {
        it.getPixel(it.width / 2, it.height / 2)
    }
    private fun topPixels() = compose.onNodeWithTag("terminal").captureToImage().asAndroidBitmap().let {
        IntArray(it.width * 80).also { pixels -> it.getPixels(pixels, 0, it.width, 0, 0, it.width, 80) }
    }

    @Test fun heldPixelsAndAccessibleTextSurviveNewGridUntilAcknowledgementRedrawAndAnimationEnd() {
        val presentation = TerminalKeyboardPresentation()
        val full = TerminalViewport(20, 20); val short = TerminalViewport(20, 10)
        var grid by mutableStateOf(grid("#004400", "old frame"))
        var revision by mutableIntStateOf(1)
        var height by mutableStateOf(240.dp)
        presentation.transition(true, false, 0, full)
        presentation.reportPublished(1, full); presentation.reportConfirmed(1); presentation.outputApplied(1, 1)
        compose.setContent { RenderGridView(grid, TerminalCellMetrics(12f, 20f, 16f), revision,
            Modifier.size(240.dp, height).testTag("terminal"), keyboardPresentation = presentation) }
        assertEquals(android.graphics.Color.rgb(0, 68, 0), color())
        val originalTop = topPixels()
        compose.runOnIdle {
            presentation.transition(true, true, 120, short)
            presentation.reportPublished(2, short)
            height = 120.dp
            grid = grid("#440000", "new frame"); revision = 2
            presentation.outputApplied(2, 2)
        }
        assertEquals("Pre-ack pixels must remain frozen", android.graphics.Color.rgb(0, 68, 0), color())
        assertArrayEquals("Held text must not move with the keyboard", originalTop, topPixels())
        compose.onNodeWithText("old frame", substring = true).assertExists()
        compose.onNodeWithText("new frame", substring = true).assertDoesNotExist()
        compose.runOnIdle { presentation.reportConfirmed(2); revision = 3 }
        assertEquals(android.graphics.Color.rgb(0, 68, 0), color())
        compose.runOnIdle { presentation.outputApplied(2, 4); revision = 4 }
        assertEquals("Redraw before animation ends remains hidden", android.graphics.Color.rgb(0, 68, 0), color())
        compose.runOnIdle { presentation.transition(true, false, 120, short) }
        assertEquals(android.graphics.Color.rgb(68, 0, 0), color())
        compose.onNodeWithText("new frame", substring = true).assertExists()
        assertFalse(presentation.frozen)
    }

    @Test fun replacementSurfaceCannotInheritHeldPixelsOrBeReleasedByOldCallbacks() {
        val old = TerminalKeyboardPresentation()
        val full = TerminalViewport(20, 20); val short = TerminalViewport(20, 10)
        old.transition(true, false, 0, full)
        var owner by mutableStateOf(old)
        var grid by mutableStateOf(grid("#004400", "old owner"))
        compose.setContent { RenderGridView(grid, TerminalCellMetrics(12f, 20f, 16f), 1,
            Modifier.size(240.dp).testTag("terminal"), keyboardPresentation = owner) }
        assertEquals(android.graphics.Color.rgb(0, 68, 0), color())
        compose.runOnIdle {
            old.transition(true, true, 120, short); old.reportPublished(2, short)
            owner = TerminalKeyboardPresentation()
            grid = grid("#000044", "new owner")
        }
        assertEquals(android.graphics.Color.rgb(0, 0, 68), color())
        compose.runOnIdle { old.reportConfirmed(2); old.outputApplied(2, 10); old.transition(true, false, 120, short) }
        assertEquals(android.graphics.Color.rgb(0, 0, 68), color())
        compose.onNodeWithText("old owner", substring = true).assertDoesNotExist()
    }

    @Test fun stalledConfirmationReleasesAfterFiveSecondsOfSilence() {
        val presentation = TerminalKeyboardPresentation()
        val full = TerminalViewport(20, 20); val short = TerminalViewport(20, 10)
        presentation.transition(true, false, 0, full)
        var grid by mutableStateOf(grid("#004400", "old frame"))
        compose.setContent { RenderGridView(grid, TerminalCellMetrics(12f, 20f, 16f), 1,
            Modifier.size(240.dp).testTag("terminal"), keyboardPresentation = presentation) }
        assertEquals(android.graphics.Color.rgb(0, 68, 0), color())
        compose.runOnIdle {
            presentation.transition(true, true, 120, short); presentation.reportPublished(2, short)
            grid = grid("#440000", "available frame")
            presentation.transition(true, false, 120, short)
        }
        assertEquals(android.graphics.Color.rgb(0, 68, 0), color())
        compose.waitUntil(6_500) { !presentation.frozen }
        assertEquals(android.graphics.Color.rgb(68, 0, 0), color())
    }
}
