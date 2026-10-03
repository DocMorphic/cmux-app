package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal data class TerminalGridPresentation(
    val sharedLayout: TerminalSharedGridLayout?, val geometry: TerminalGeometry?,
    val pinchOffset: Offset,
    val visibleLines: List<List<RenderGrid.Span>>?,
    val reveal: Float, val maximumReveal: Float,
    val transform: (TerminalGridTransform) -> Unit,
    val scroll: (Double, Double, Int, Boolean) -> TerminalGridScroll
)
internal data class TerminalGridScroll(val rows: Double, val revealed: Boolean)

/** Keep per-surface display state out of the large screen's generated Compose method. */
@Composable
internal fun rememberTerminalGridPresentation(client: MobileRpcClient?, surface: String,
    shared: TerminalSizeState?, grid: TerminalDisplay, pixels: IntSize, cells: TerminalCellMetrics,
    density: Float, visibleHeight: Int = pixels.height, revision: Int = 0,
    scrollViewport: TerminalScrollViewport = TerminalScrollViewport.at(0.0, 0), resetReveal: Int = 0): TerminalGridPresentation {
    var transform by remember(client, surface) { mutableStateOf(TerminalGridTransform()) }
    var reveal by remember(client, surface, resetReveal) { mutableFloatStateOf(0f) }
    val sharedMatches = shared?.grid?.let { it.columns == grid.columns && it.rows == grid.rows } == true
    val layout = TerminalSharedGridLayout.resolve(pixels.width.toFloat(), pixels.height.toFloat(), grid.columns,
        grid.rows, cells, density, if (sharedMatches) transform else TerminalGridTransform())
    val measureContent = pixels.height > visibleHeight && grid.activeScreen == "primary"
    val visibleLines = remember(grid, revision, scrollViewport, measureContent) {
        if (measureContent) scrollViewport.lines(grid) else null
    }
    val contentBottom = remember(grid, revision, scrollViewport, measureContent, cells) {
        if (measureContent) TerminalKeyboardLayout.contentBottomRows(grid, scrollViewport, visibleLines,
            (grid as? TerminalGraphicsDisplay)?.graphicsSnapshot(scrollViewport.rowOffset, cells)) else null
    }
    val keyboard = layout?.let {
        // An unshared TUI can prepare a shorter target before the keyboard has
        // finished moving. Keep that target grid seated above the live dock.
        val base = if (grid.activeScreen != "primary" && shared == null)
            it.geometry.copy(originY = it.geometry.originY + (visibleHeight - pixels.height).coerceAtLeast(0)) else it.geometry
        TerminalKeyboardLayout(base, pixels.height.toFloat(), visibleHeight.toFloat(), contentBottom, reveal)
    }
    SideEffect {
        if (sharedMatches) layout?.let { transform = it.transform }
        if (keyboard != null) reveal = keyboard.reveal
    }
    return TerminalGridPresentation(layout.takeIf { sharedMatches }, keyboard?.geometry,
        Offset(0f, keyboard?.slide ?: 0f), visibleLines, keyboard?.reveal ?: 0f, keyboard?.maximumReveal ?: 0f,
        { transform = it }) { rows, position, history, local ->
        val next = TerminalKeyboardLayout.scroll(position, history, reveal, keyboard?.maximumReveal ?: 0f,
            rows, keyboard?.geometry?.cellHeight ?: cells.heightPx, local)
        val revealed = next.reveal != reveal
        reveal = next.reveal
        TerminalGridScroll(next.remainingRows, revealed)
    }
}
