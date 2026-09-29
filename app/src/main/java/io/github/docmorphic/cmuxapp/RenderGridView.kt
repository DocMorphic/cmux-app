package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay

/** Draws the authoritative cmux cell positions, including wide and combining graphemes. */
@Composable
fun RenderGridView(
    grid: TerminalDisplay, cells: TerminalCellMetrics, revision: Int,
    modifier: Modifier = Modifier, scrollOffset: Int = 0, scrollPosition: Double = scrollOffset.toDouble()
) {
    var blinkVisible by remember(grid) { mutableStateOf(true) }
    LaunchedEffect(grid) {
        while (true) { delay(600); blinkVisible = !blinkVisible }
    }
    val viewport = TerminalScrollViewport.at(scrollPosition, grid.historyLineCount, grid.activeScreen)
    val lines = remember(grid, revision, viewport.rowOffset, viewport.topClipFraction > 0) { viewport.lines(grid) }
    val plan = remember(lines) { TerminalGridPainter.plan(lines) }
    val accessibleText = remember(lines) { RenderGrid.plainText(lines) }
    val painter = remember { TerminalGridPainter() }
    Box(modifier.background(Color(0xFF111316))) {
        Canvas(Modifier.fillMaxSize().clipToBounds().semantics { text = AnnotatedString(accessibleText) }) {
            drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, grid, plan, cells, viewport.rowOffset, blinkVisible, viewport.topClipFraction) }
        }
    }
}
