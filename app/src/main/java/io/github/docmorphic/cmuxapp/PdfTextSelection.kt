package io.github.docmorphic.cmuxapp

import java.text.Bidi
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max

internal data class PdfTextPosition(val page: Int, val offset: Int) : Comparable<PdfTextPosition> {
    init { require(page >= 0 && offset >= 0) }
    override fun compareTo(other: PdfTextPosition) = if (page == other.page) offset.compareTo(other.offset) else page.compareTo(other.page)
}
internal data class PdfTextSelection(val anchor: PdfTextPosition, val focus: PdfTextPosition) {
    val start get() = minOf(anchor, focus)
    val end get() = maxOf(anchor, focus)
    fun offsets(page: Int, length: Int): IntRange? {
        if (page !in start.page..end.page) return null
        val from = if (page == start.page) start.offset.coerceAtMost(length) else 0
        val to = if (page == end.page) end.offset.coerceAtMost(length) else length
        return (from until to).takeUnless { it.isEmpty() }
    }
}
internal data class PdfTextCaret(val page: Int, val x: Float, val y: Float)

internal fun PdfCompatibilityTextPage.wordOffsets(x: Float, y: Float): IntRange? {
    if (!x.isFinite() || !y.isFinite()) return null
    val hit = runs.firstOrNull { run -> run.bounds.any { it.contains(x, y) } } ?: return null
    if (hit.start !in text.indices) return null
    val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(this@wordOffsets.text) }
    val end = words.following(hit.start)
    if (end == BreakIterator.DONE) return null
    val start = words.previous().coerceAtLeast(0)
    return (start until end).takeIf { text.substring(start, end).isNotBlank() }
}

/** Snap endpoints to complete extracted runs: never split surrogate pairs or invented ligature positions. */
internal fun PdfCompatibilityTextPage.nearestOffset(x: Float, y: Float): Int? {
    if (!x.isFinite() || !y.isFinite()) return null
    val hit = runs.asSequence().flatMap { run -> run.bounds.asSequence().map { run to it } }
        .minByOrNull { (_, b) ->
            val dx = max(b.left - x, max(0f, x - b.right))
            val dy = max(b.top - y, max(0f, y - b.bottom))
            dx.toDouble() * dx + dy.toDouble() * dy
        } ?: return null
    val (run, bounds) = hit
    val baseline = run.baseline
    val trailing = if (baseline != null) {
        val dx = baseline.endX - baseline.startX; val dy = baseline.endY - baseline.startY
        (x - (baseline.startX + baseline.endX) / 2) * dx + (y - (baseline.startY + baseline.endY) / 2) * dy > 0f
    } else x > (bounds.left + bounds.right) / 2f
    val rtl = text.isNotEmpty() && run.start in text.indices && Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).getLevelAt(run.start) % 2 == 1
    var offset = if (trailing != rtl) run.end else run.start
    offset = offset.coerceIn(0, text.length)
    if (offset in 1 until text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate())
        offset += if (trailing != rtl) 1 else -1
    return offset
}
internal fun PdfCompatibilityTextPage.selectionBounds(selection: PdfTextSelection, page: Int): List<PdfTextBounds> {
    val offsets = selection.offsets(page, text.length) ?: return emptyList()
    return runs.asSequence().filter { it.end > offsets.first && it.start <= offsets.last }
        .flatMap { it.bounds.asSequence() }.distinct().toList()
}
internal fun PdfCompatibilityTextPage.caret(position: PdfTextPosition, leading: Boolean): PdfTextCaret? {
    val candidates = runs.filter { it.bounds.isNotEmpty() }
    val run = if (leading) candidates.firstOrNull { it.end > position.offset } ?: candidates.lastOrNull()
        else candidates.lastOrNull { it.start < position.offset } ?: candidates.firstOrNull()
    run ?: return null
    val bounds = if (leading) run.bounds.first() else run.bounds.last()
    val rtl = text.isNotEmpty() && run.start in text.indices && Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).getLevelAt(run.start) % 2 == 1
    val baseline = run.baseline
    return if (baseline != null) PdfTextCaret(position.page,
        if (leading != rtl) baseline.startX else baseline.endX, if (leading != rtl) baseline.startY else baseline.endY)
        else PdfTextCaret(position.page, if (leading != rtl) bounds.left else bounds.right, bounds.bottom)
}
