package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.ghostty.GhosttyGraphicsFrame

/** Optional byte-stream graphics. Authoritative text grids have no image payload. */
interface TerminalGraphicsDisplay {
    data class Snapshot(val frame: GhosttyGraphicsFrame, val cellWidth: Int, val cellHeight: Int)
    fun graphicsSnapshot(scrollOffset: Int, cells: TerminalCellMetrics): Snapshot?
}

/** Initialize pixel metrics before replay parsing can advance an image cursor. */
fun ghosttyTerminalFactory(cells: TerminalCellMetrics): (Int, Int) -> ByteTerminal = { columns, rows ->
    GhosttyVtTerminal(columns, rows).also { terminal ->
        try { terminal.setCellMetrics(cells) }
        catch (failure: Throwable) { terminal.close(); throw failure }
    }
}
