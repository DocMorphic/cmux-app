package io.github.docmorphic.cmuxapp

import androidx.compose.ui.geometry.Offset
import kotlin.math.min

internal data class TerminalGridTransform(val magnification: Float = 1f, val offset: Offset = Offset.Zero)

/** Display-only shared grid transform. The PTY dimensions and font never change during a pinch. */
internal class TerminalSharedGridLayout private constructor(
    val width: Float, val height: Float, val columns: Int, val rows: Int,
    val cells: TerminalCellMetrics, val density: Float, requested: TerminalGridTransform
) {
    private val gridWidth = columns * cells.widthPx
    private val gridHeight = rows * cells.heightPx
    val scaledMode = gridWidth > width || gridHeight > height
    val fitScale = if (scaledMode) min(1f, width / gridWidth) else 1f
    val maximumMagnification = 1f / fitScale
    private val magnification = if (scaledMode && requested.magnification.isFinite())
        requested.magnification.coerceIn(1f, maximumMagnification) else 1f
    val scale = fitScale * magnification
    private val displayWidth = gridWidth * scale
    private val displayHeight = gridHeight * scale
    private fun clamp(value: Float, maximum: Float) = if (value.isFinite()) value.coerceIn(0f, maximum.coerceAtLeast(0f)) else 0f
    val transform = TerminalGridTransform(magnification, Offset(
        if (scaledMode) clamp(requested.offset.x, displayWidth - width) else 0f,
        if (scaledMode) clamp(requested.offset.y, displayHeight - height) else 0f))
    private val slack = height - displayHeight
    val geometry = TerminalGeometry(scale, cells.widthPx * scale, cells.heightPx * scale, 0f - transform.offset.x,
        if (scaledMode) { if (slack > 0f) 0f else slack + transform.offset.y }
        else if (slack >= cells.heightPx - .5f * density) 0f else slack,
        columns, rows)

    fun panned(translation: Offset): TerminalSharedGridLayout = if (!scaledMode || !translation.x.isFinite() || !translation.y.isFinite()) this
        else resolving(transform.copy(offset = Offset(transform.offset.x - translation.x, transform.offset.y + translation.y)))

    fun zoomed(magnification: Float, focus: Offset): TerminalSharedGridLayout {
        if (!scaledMode || !focus.x.isFinite() || !focus.y.isFinite()) return this
        val gridX = (focus.x - geometry.originX) / scale
        val gridY = (focus.y - geometry.originY) / scale
        val next = resolving(TerminalGridTransform(magnification))
        return resolving(next.transform.copy(offset = Offset(
            gridX * next.scale - focus.x,
            focus.y - gridY * next.scale - (height - gridHeight * next.scale))))
    }

    private fun resolving(transform: TerminalGridTransform) = TerminalSharedGridLayout(width, height, columns, rows, cells, density, transform)

    companion object {
        fun resolve(width: Float, height: Float, columns: Int, rows: Int, cells: TerminalCellMetrics,
                    density: Float, transform: TerminalGridTransform = TerminalGridTransform()): TerminalSharedGridLayout? {
            if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f || columns <= 0 || rows <= 0 ||
                !cells.widthPx.isFinite() || !cells.heightPx.isFinite() || cells.widthPx <= 0f || cells.heightPx <= 0f ||
                !density.isFinite() || density <= 0f) return null
            return TerminalSharedGridLayout(width, height, columns, rows, cells, density, transform)
        }
    }
}
