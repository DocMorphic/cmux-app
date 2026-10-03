package io.github.docmorphic.cmuxapp

internal enum class ChangesExpandDirection { UP, DOWN }
internal data class ChangesLineRange(val start: Int, val end: Int) {
    val count get() = (end - start).coerceAtLeast(0)
    operator fun contains(line: Int) = line >= start && line < end
}
internal data class ChangesGap(val id: Int, val range: ChangesLineRange?, val oldOffset: Int,
    val directions: List<ChangesExpandDirection>) {
    companion object {
        fun gaps(document: ChangesDiffDocument, lineCount: Int?): List<ChangesGap> = buildList {
            if (document.hunks.isEmpty()) return@buildList
            fun end(start: Int, count: Int) = (start.toLong() + count).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val first = document.hunks.first()
            val upper = end(first.newStart, if (first.newCount == 0) 1 else 0)
            if (upper > 1) add(ChangesGap(0, ChangesLineRange(1, upper), first.oldStart - first.newStart, listOf(ChangesExpandDirection.UP)))
            document.hunks.zipWithNext().forEachIndexed { index, (previous, next) ->
                val lower = end(previous.newStart, maxOf(previous.newCount, 1))
                val nextUpper = end(next.newStart, if (next.newCount == 0) 1 else 0)
                if (lower < nextUpper) add(ChangesGap(index + 1, ChangesLineRange(lower, nextUpper), next.oldStart - next.newStart,
                    listOf(ChangesExpandDirection.DOWN, ChangesExpandDirection.UP)))
            }
            if (!document.truncated) {
                val last = document.hunks.last()
                val lower = end(last.newStart, maxOf(last.newCount, 1))
                val range = lineCount?.let { ChangesLineRange(lower, maxOf(lower, it + 1)) }
                if (range == null || range.count > 0) add(ChangesGap(document.hunks.size, range,
                    end(last.oldStart, maxOf(last.oldCount, 1)) - lower, listOf(ChangesExpandDirection.DOWN)))
            }
        }
    }
}
internal data class ChangesExpansion(val current: ChangesCurrentFile? = null,
    val revealed: Map<Int, List<ChangesLineRange>> = emptyMap(), val pending: Int? = null,
    val failed: Int? = null, val tooLarge: Boolean = false) {
    fun hidden(gap: ChangesGap): List<ChangesLineRange> = buildList {
        val range = gap.range ?: return@buildList
        var cursor = range.start
        revealed[gap.id].orEmpty().forEach {
            val low = maxOf(range.start, it.start); val high = minOf(range.end, it.end)
            if (low < high) { if (cursor < low) add(ChangesLineRange(cursor, low)); cursor = maxOf(cursor, high) }
        }
        if (cursor < range.end) add(ChangesLineRange(cursor, range.end))
    }
    fun reveal(gap: ChangesGap, direction: ChangesExpandDirection, preferred: ChangesLineRange?): ChangesExpansion {
        val hidden = hidden(gap)
        val target = preferred?.takeIf { it in hidden } ?: (if (direction == ChangesExpandDirection.DOWN) hidden.firstOrNull() else hidden.lastOrNull()) ?: return this
        val count = if (target.count <= 120) target.count else 100
        val range = if (direction == ChangesExpandDirection.DOWN) ChangesLineRange(target.start, target.start + count) else ChangesLineRange(target.end - count, target.end)
        val sorted = (revealed[gap.id].orEmpty() + range).sortedBy { it.start }
        val merged = mutableListOf<ChangesLineRange>()
        sorted.forEach { next ->
            val last = merged.lastOrNull()
            if (last != null && next.start <= last.end) merged[merged.lastIndex] = ChangesLineRange(last.start, maxOf(last.end, next.end))
            else merged += next
        }
        return copy(revealed = revealed + (gap.id to merged))
    }
}
internal sealed interface ChangesDiffRowContent {
    val id: String
    data class Code(override val id: String, val line: ChangesDiffLine, val hunk: String) : ChangesDiffRowContent
    data class Expand(val gap: ChangesGap, val hidden: ChangesLineRange?) : ChangesDiffRowContent {
        override val id = "gap:${gap.id}:${hidden?.start}:${hidden?.end}"
    }
}
internal fun projectChanges(document: ChangesDiffDocument, kind: ChangeKind, expansion: ChangesExpansion = ChangesExpansion()): List<ChangesDiffRowContent> = buildList {
    val gaps = if (kind == ChangeKind.DELETED || document.binary) emptyMap() else ChangesGap.gaps(document, expansion.current?.lines?.size).associateBy { it.id }
    fun appendGap(id: Int) {
        val gap = gaps[id] ?: return
        val range = gap.range
        if (range == null) { add(ChangesDiffRowContent.Expand(gap, null)); return }
        val hidden = expansion.hidden(gap).associateBy { it.start }
        var line = range.start
        while (line < range.end) {
            val omitted = hidden[line]
            if (omitted != null) { add(ChangesDiffRowContent.Expand(gap, omitted)); line = omitted.end }
            else {
                val text = expansion.current?.lines?.getOrNull(line - 1)
                // Never fabricate context if the file did not supply this line.
                if (text != null) add(ChangesDiffRowContent.Code("context:$id:$line", ChangesDiffLine(DiffKind.CONTEXT, text,
                    (line.toLong() + gap.oldOffset).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(), line), ""))
                line++
            }
        }
    }
    document.hunks.forEachIndexed { index, hunk ->
        appendGap(index)
        add(ChangesDiffRowContent.Code("hunk:$index", ChangesDiffLine(DiffKind.HEADER, hunk.header), hunk.copyText))
        hunk.lines.forEachIndexed { line, value -> add(ChangesDiffRowContent.Code("line:$index:$line", value, hunk.copyText)) }
    }
    appendGap(document.hunks.size)
}
