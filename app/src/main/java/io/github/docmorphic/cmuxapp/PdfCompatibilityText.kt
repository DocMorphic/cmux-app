package io.github.docmorphic.cmuxapp

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.io.Writer
import kotlin.math.hypot

/** Text only: the platform still owns raster rendering. Called under the document owner's lock. */
internal fun pdfCompatibilityText(document: PDDocument, index: Int, geometry: PdfPageCoordinates): PdfCompatibilityTextPage {
    if (!document.currentAccessPermission.canExtractContent()) throw IOException("PDF text extraction is not permitted")
    val text = StringBuilder(); val runs = mutableListOf<PdfTextRun>()
    var glyphs = 0
    fun append(value: String) {
        if (text.length + value.length > 500_000) throw IOException("PDF page text is too large")
        text.append(value)
    }
    fun bounds(position: TextPosition): PdfTextBounds? {
        // PDFBox removes the crop origin from both baseline endpoints. Restore it
        // before applying the renderer's crop/rotation transform. The matrix's Y
        // axis supplies glyph ascent even when text itself is rotated or skewed.
        val matrix = position.textMatrix
        val length = hypot(matrix.shearX, matrix.scaleY)
        if (!length.isFinite() || length <= 0f || !position.height.isFinite()) return null
        val dx = matrix.shearX / length * position.height
        val dy = matrix.scaleY / length * position.height
        val x = matrix.translateX + geometry.left; val y = matrix.translateY + geometry.bottom
        val endX = position.endX + geometry.left; val endY = position.endY + geometry.bottom
        return geometry.bounds(listOf(x to y, endX to endY, (x + dx) to (y + dy), (endX + dx) to (endY + dy)))
    }
    fun baseline(position: TextPosition): PdfTextBaseline? {
        val start = geometry.point(position.textMatrix.translateX + geometry.left, position.textMatrix.translateY + geometry.bottom) ?: return null
        val end = geometry.point(position.endX + geometry.left, position.endY + geometry.bottom) ?: return null
        return PdfTextBaseline(start.first, start.second, end.first, end.second)
    }
    val extractor = object : PDFTextStripper() {
        override fun processTextPosition(position: TextPosition) {
            if (++glyphs > 100_000) throw IOException("PDF page has too much text")
            super.processTextPosition(position)
        }
        override fun writeString(value: String, positions: MutableList<TextPosition>) {
            val start = text.length; append(value)
            if (positions.joinToString("") { it.unicode.orEmpty() } == value) {
                var offset = start
                positions.forEach { position ->
                    val end = offset + position.unicode.orEmpty().length
                    if (end > offset) runs += PdfTextRun(offset, end, listOfNotNull(bounds(position)), baseline(position))
                    offset = end
                }
            } else {
                // Ligature expansion and bidi normalization can change character
                // count/order. Preserve PDFBox's reading text and the complete
                // word's glyph bounds rather than inventing character alignment.
                runs += PdfTextRun(start, text.length, positions.mapNotNull(::bounds))
            }
        }
        override fun writeWordSeparator() = append(" ")
        override fun writeLineSeparator() = append("\n")
        override fun writeParagraphEnd() {
            if (text.isNotEmpty() && text.last() != '\n') append("\n")
            super.writeParagraphEnd()
        }
    }.apply {
        startPage = index + 1; endPage = index + 1
        sortByPosition = true
    }
    // Output is collected only by the bounded callbacks, not duplicated in a StringWriter.
    extractor.writeText(document, object : Writer() {
        override fun write(chars: CharArray, offset: Int, length: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    })
    return PdfCompatibilityTextPage(text.toString(), runs)
}
