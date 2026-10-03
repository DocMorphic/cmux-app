package io.github.docmorphic.cmuxapp

/** The iOS token/soft-wrap hit policy, using the same cell-width function as Android's renderer. */
internal object TerminalArtifactHitTest {
    private val leading = "\"'`([{<"
    private val trailing = "\"'`)]}>,;:!?."
    private val continuation = "/._-+=~@%:\\"
    private data class Segment(val row: Int, val start: Int, val end: Int)
    fun path(text: String, column: Int, row: Int, columns: Int, width: (String) -> Int = TerminalGlyphLayout::estimatedWidth): String? {
        if (column < 0 || row < 0 || columns <= 0) return null
        val lines = text.split('\n')
        if (row >= lines.size) return null
        var bestPath: String? = null
        var bestSegments = 0
        for (head in 0..row) {
            val line = lines[head]
            var cursor = 0
            while (cursor < line.length) {
                while (cursor < line.length && line[cursor].isWhitespace()) cursor++
                val start = cursor
                while (cursor < line.length && !line[cursor].isWhitespace()) cursor++
                if (start == cursor) continue
                val raw = line.substring(start, cursor)
                val token = normalized(raw) ?: continue
                val startColumn = width(line.substring(0, start)) + width(raw.takeWhile { it in leading })
                val segments = mutableListOf(Segment(head, startColumn, startColumn + width(token)))
                var joined = raw
                var lastRow = head
                var rawEnd = width(line.substring(0, cursor))
                while (rawEnd >= columns && width(lines[lastRow]) >= columns && lastRow + 1 < lines.size) {
                    val next = lines[lastRow + 1]
                    val first = next.codePoints().findFirst().orElse(-1)
                    if (first < 0 || !(Character.isLetterOrDigit(first) || first.toChar() in continuation)) break
                    val part = next.takeWhile { !it.isWhitespace() }
                    if (part.isEmpty()) break
                    lastRow++; joined += part; rawEnd = width(part)
                    segments += Segment(lastRow, 0, rawEnd)
                }
                if (segments.size > bestSegments && segments.any { it.row == row && column >= it.start && column < it.end }) {
                    bestPath = normalized(joined) ?: token
                    bestSegments = segments.size
                }
            }
        }
        return bestPath
    }
    private fun normalized(raw: String): String? {
        TerminalArtifactPaths.normalized(raw)?.let { return it }
        val candidate = raw.trim { it in leading }.trimEnd { it in trailing }
        if (candidate.isEmpty() || candidate.any { it in "/@<>\"'\\`" } || "://" in candidate || '\u0000' in candidate) return null
        val dot = candidate.lastIndexOf('.')
        return candidate.takeIf { dot > 0 && dot < candidate.lastIndex && candidate.codePoints().anyMatch(Character::isLetter) }
    }
}
