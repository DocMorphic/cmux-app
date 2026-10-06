package io.github.docmorphic.cmuxapp

import java.text.BreakIterator
import java.util.Locale

internal data class PdfTextBaseline(val startX: Float, val startY: Float, val endX: Float, val endY: Float)
internal data class PdfTextRun(val start: Int, val end: Int, val bounds: List<PdfTextBounds>, val baseline: PdfTextBaseline? = null)

/** UTF-16 offsets refer to extracted reading-order text, including inferred separators. */
internal data class PdfCompatibilityTextPage(val text: String, val runs: List<PdfTextRun>) {
    private val folded by lazy { foldWhitespace(text) }
    fun search(page: Int, query: String): List<PdfTextMatch> {
        if (query.isBlank()) return emptyList()
        val source = folded
        val needle = foldWhitespace(query).first.trim()
        if (needle.isEmpty()) return emptyList()
        val result = mutableListOf<PdfTextMatch>()
        var from = 0
        while (from < source.first.length) {
            val start = source.first.indexOf(needle, from, ignoreCase = true)
            if (start < 0) break
            val originalStart = source.second[start]
            val originalEnd = source.second[start + needle.length - 1] + 1
            var low = 0; var high = runs.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (runs[mid].end <= originalStart) low = mid + 1 else high = mid
            }
            val bounds = runs.subList(low, runs.size).asSequence().takeWhile { it.start < originalEnd }
                .flatMap { it.bounds.asSequence() }.distinct().toList()
            if (bounds.isNotEmpty()) result += PdfTextMatch(page, originalStart, bounds)
            check(result.size <= 10_000) { "Too many PDF matches" }
            from = start + needle.length
        }
        return result
    }

    fun word(x: Float, y: Float): String? {
        if (!x.isFinite() || !y.isFinite()) return null
        val hit = runs.firstOrNull { run -> run.bounds.any { it.contains(x, y) } } ?: return null
        if (hit.start !in text.indices) return null
        val words = BreakIterator.getWordInstance(Locale.ROOT).apply { setText(this@PdfCompatibilityTextPage.text) }
        val end = words.following(hit.start)
        if (end == BreakIterator.DONE) return null
        val start = words.previous().coerceAtLeast(0)
        return text.substring(start, end).takeIf { it.isNotBlank() }
    }

    private fun foldWhitespace(value: String): Pair<String, IntArray> {
        val folded = StringBuilder(); val indices = ArrayList<Int>(value.length)
        value.forEachIndexed { index, char ->
            if (!char.isWhitespace() || folded.lastOrNull() != ' ') {
                folded.append(if (char.isWhitespace()) ' ' else char); indices += index
            }
        }
        return folded.toString() to indices.toIntArray()
    }
}
