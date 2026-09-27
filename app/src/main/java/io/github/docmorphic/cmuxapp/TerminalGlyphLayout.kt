package io.github.docmorphic.cmuxapp

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import java.util.Locale

/** Pin grapheme clusters to producer cells rather than stretching a whole mixed-width span. */
object TerminalGlyphLayout {
    data class Glyph(val text: String, val column: Int, val width: Int)
    private data class Cluster(val text: String, val width: Int, val expandable: Boolean)

    fun estimatedWidth(text: String): Int = clusters(text).sumOf { it.width }

    fun layout(text: String, column: Int, cellWidth: Int): List<Glyph> {
        if (text.isEmpty() || cellWidth <= 0) return emptyList()
        val clusters = clusters(text)
        if (clusters.size == 1) return listOf(Glyph(text, column, cellWidth))
        val widths = clusters.map { it.width }.toMutableList()
        var difference = cellWidth - widths.sum()
        if (difference > 0) {
            for (index in widths.indices) {
                if (difference == 0) break
                if (widths[index] == 1 && clusters[index].expandable) { widths[index]++; difference-- }
            }
        } else if (difference < 0) {
            for (index in widths.indices.reversed()) {
                if (difference == 0) break
                if (widths[index] > 1) { widths[index]--; difference++ }
            }
        }
        var offset = 0
        return buildList {
            clusters.forEachIndexed { index, cluster ->
                val width = widths[index]
                if (width == 0) {
                    if (isNotEmpty()) {
                        val previous = removeAt(lastIndex)
                        add(previous.copy(text = previous.text + cluster.text))
                    }
                } else if (offset + width <= cellWidth) {
                    add(Glyph(cluster.text, column + offset, width))
                }
                offset += width
            }
        }
    }

    private fun clusters(text: String): List<Cluster> {
        // The common path is allocation-light and also usable in plain JVM protocol tests.
        if (text.all { it in ' '..'~' }) return text.map { Cluster(it.toString(), 1, false) }
        val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val result = mutableListOf<Cluster>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            val value = text.substring(start, end)
            val scalars = value.codePoints().toArray()
            val visible = scalars.any {
                UCharacter.getType(it) !in setOf(UCharacter.NON_SPACING_MARK.toInt(),
                    UCharacter.ENCLOSING_MARK.toInt(), UCharacter.FORMAT.toInt())
            }
            val wide = scalars.any {
                UCharacter.getIntPropertyValue(it, UProperty.EAST_ASIAN_WIDTH) in
                    setOf(UCharacter.EastAsianWidth.WIDE, UCharacter.EastAsianWidth.FULLWIDTH) ||
                    UCharacter.hasBinaryProperty(it, UProperty.EMOJI_PRESENTATION) || it == 0xfe0f
            }
            val ambiguous = scalars.any { UCharacter.getIntPropertyValue(it, UProperty.EAST_ASIAN_WIDTH) == UCharacter.EastAsianWidth.AMBIGUOUS }
            result += Cluster(value, if (!visible) 0 else if (wide) 2 else 1, ambiguous)
            start = end; end = breaks.next()
        }
        return result
    }
}
