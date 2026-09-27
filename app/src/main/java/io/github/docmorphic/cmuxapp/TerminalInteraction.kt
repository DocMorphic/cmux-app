package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

/** Shared painting/hit-test geometry, including transient keyboard letterboxing. */
data class TerminalGeometry(val scale: Float, val cellWidth: Float, val cellHeight: Float,
                            val originX: Float, val originY: Float, val columns: Int, val rows: Int) {
    data class Cell(val column: Int, val row: Int)
    fun cell(x: Float, y: Float) = Cell(((x - originX) / cellWidth).toInt().coerceIn(0, columns - 1),
        ((y - originY) / cellHeight).toInt().coerceIn(0, rows - 1))
    companion object {
        fun fit(width: Float, height: Float, columns: Int, rows: Int, cells: TerminalCellMetrics): TerminalGeometry? {
            if (width <= 0 || height <= 0 || columns <= 0 || rows <= 0) return null
            val scale = min(1f, min(width / (columns * cells.widthPx), height / (rows * cells.heightPx)))
            val cw = cells.widthPx * scale; val ch = cells.heightPx * scale
            return TerminalGeometry(scale, cw, ch, ((width - cw * columns) / 2f).coerceAtLeast(0f),
                ((height - ch * rows) / 2f).coerceAtLeast(0f), columns, rows)
        }
    }
}

data class TerminalScroll(val lines: Double, val column: Int, val row: Int, val prefetchRows: Int? = null) {
    fun merging(next: TerminalScroll) = next.copy(lines = lines + next.lines,
        prefetchRows = listOfNotNull(prefetchRows, next.prefetchRows).maxOrNull())
}

/** UI-dispatcher confined; one in-flight RPC plus one coalesced pending scroll. */
class TerminalScrollQueue(private val scope: CoroutineScope,
                          private val onFailure: (Throwable) -> Unit,
                          private val canSend: () -> Boolean = { true },
                          private val send: suspend (TerminalScroll) -> Unit) {
    private var pending: TerminalScroll? = null
    private var job: Job? = null
    private var closed = false
    private var sincePrefetch = 0.0
    private var primed = false
    fun offer(lines: Double, cell: TerminalGeometry.Cell): Boolean {
        if (closed || !lines.isFinite() || lines == 0.0) return false
        sincePrefetch += kotlin.math.abs(lines)
        val prefetch = if (!primed || sincePrefetch >= 120) {
            primed = true; sincePrefetch = 0.0; 600
        } else null
        val delivery = TerminalScroll(lines, cell.column, cell.row, prefetch)
        pending = pending?.merging(delivery) ?: delivery
        if (job?.isActive != true) job = scope.launch {
            try {
                while (!closed) {
                    val next = pending ?: break
                    pending = null
                    if (!canSend()) break
                    if (next.lines != 0.0) send(next)
                }
            } catch (failure: Exception) {
                pending = null
                if (failure is CancellationException) throw failure
                // Delivery is uncertain. Never replay a gesture after failure.
                onFailure(failure)
            }
        }
        return true
    }
    fun close() { closed = true; pending = null; job?.cancel() }
}
