package io.github.docmorphic.cmuxapp

/** Bounded scan of already-owned visible rows and image placement metadata. */
internal object TerminalContentBottom {
    // Upstream caps viewport text at 128 KiB. Charge row/span/placement visits as
    // well, so millions of empty spans cannot evade the bound. Unknown is safe:
    // it disables blank-space absorption instead of hiding unmeasured content.
    const val MAX_WORK = 131_072
    data class Result(val rows: Float?, val inspectedUnits: Int)

    fun measure(lines: List<List<RenderGrid.Span>>, cursorRow: Int?, columns: Int, rows: Int,
        topClipFraction: Float, graphics: TerminalGraphicsDisplay.Snapshot? = null): Result {
        var work = 0
        fun charge(units: Int = 1): Boolean {
            if (units > MAX_WORK - work) return false
            work += units; return true
        }
        fun unknown() = Result(null, work)
        var bottom = cursorRow?.plus(1)?.toFloat() ?: 0f
        text@ for (row in lines.indices.reversed()) {
            // Text above the known cursor/content bottom cannot extend it.
            if (row + 1f - topClipFraction <= bottom) break
            if (!charge()) return unknown()
            for (span in lines[row]) {
                if (!charge()) return unknown()
                for (index in span.text.indices) {
                    val char = span.text[index]
                    val bytes = when {
                        char.code < 0x80 -> 1
                        char.code < 0x800 -> 2
                        char.isHighSurrogate() && span.text.getOrNull(index + 1)?.isLowSurrogate() == true -> 4
                        else -> 3
                    }
                    if (!charge(bytes)) return unknown()
                    if (!char.isWhitespace()) {
                        bottom = maxOf(bottom, row + 1f - topClipFraction)
                        break@text
                    }
                }
            }
        }
        if (bottom >= rows) return Result(rows.toFloat(), work)
        if (graphics != null) {
            if (graphics.cellWidth <= 0 || graphics.cellHeight <= 0) return unknown()
            for (placement in graphics.frame.placements) {
                if (!charge()) return unknown()
                if (placement.virtual || (!placement.visible && !(topClipFraction > 0 && placement.row == rows)) ||
                    (!placement.placeholder && (placement.sourceWidth == 0 || placement.sourceHeight == 0)) ||
                    placement.pixelWidth <= 0 || placement.pixelHeight <= 0 || placement.imageId !in graphics.frame.images) continue
                val left = placement.column + placement.xOffset.toDouble() / graphics.cellWidth
                val right = left + placement.pixelWidth.toDouble() / graphics.cellWidth
                val top = placement.row + placement.yOffset.toDouble() / graphics.cellHeight - topClipFraction
                val end = top + placement.pixelHeight.toDouble() / graphics.cellHeight
                if (left >= columns || right <= 0 || top >= rows || end <= 0) continue
                bottom = maxOf(bottom, end.toFloat().coerceAtMost(rows.toFloat()))
                if (bottom >= rows) break
            }
        }
        return Result(bottom.coerceIn(0f, rows.coerceAtLeast(0).toFloat()), work)
    }
}
