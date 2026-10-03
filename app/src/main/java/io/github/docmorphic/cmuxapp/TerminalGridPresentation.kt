package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.ui.unit.IntSize

internal data class TerminalGridPresentation(
    val sharedLayout: TerminalSharedGridLayout?, val geometry: TerminalGeometry?,
    val transform: (TerminalGridTransform) -> Unit
)

/** Keep per-surface display state out of the large screen's generated Compose method. */
@Composable
internal fun rememberTerminalGridPresentation(client: MobileRpcClient?, surface: String,
    shared: TerminalSizeState?, grid: TerminalDisplay, pixels: IntSize, cells: TerminalCellMetrics,
    density: Float): TerminalGridPresentation {
    var transform by remember(client, surface) { mutableStateOf(TerminalGridTransform()) }
    val layout = shared?.takeIf { it.grid.columns == grid.columns && it.grid.rows == grid.rows }?.let {
        TerminalSharedGridLayout.resolve(pixels.width.toFloat(), pixels.height.toFloat(), grid.columns,
            grid.rows, cells, density, transform)
    }
    SideEffect { layout?.let { transform = it.transform } }
    val geometry = layout?.geometry ?: TerminalGeometry.fit(pixels.width.toFloat(), pixels.height.toFloat(),
        grid.columns, grid.rows, cells)
    return TerminalGridPresentation(layout, geometry) { transform = it }
}
