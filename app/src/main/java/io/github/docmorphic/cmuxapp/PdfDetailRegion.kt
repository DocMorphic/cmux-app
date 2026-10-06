package io.github.docmorphic.cmuxapp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Pixel rectangle in a scaled page, not a full-page bitmap allocation. */
internal data class PdfDetailRegion(val scale: Float, val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object { const val MAX_PIXELS = 4_000_000; const val MAX_EDGE = 4096 }
}

internal fun pdfDetailRegion(pageWidth: Int, pageHeight: Int, viewportWidth: Float, viewportHeight: Float,
    pageLeft: Float, pageTop: Float, pixelsPerPoint: Float, previewPixelsPerPoint: Float): PdfDetailRegion? {
    if (pageWidth <= 0 || pageHeight <= 0 || listOf(viewportWidth, viewportHeight, pageLeft, pageTop,
            pixelsPerPoint, previewPixelsPerPoint).any { !it.isFinite() } || viewportWidth <= 0 || viewportHeight <= 0 ||
        pixelsPerPoint <= 0 || previewPixelsPerPoint <= 0 || pixelsPerPoint <= previewPixelsPerPoint * 1.05f) return null
    val x0 = maxOf(0.0, -pageLeft.toDouble() / pixelsPerPoint)
    val y0 = maxOf(0.0, -pageTop.toDouble() / pixelsPerPoint)
    val x1 = minOf(pageWidth.toDouble(), (viewportWidth.toDouble() - pageLeft) / pixelsPerPoint)
    val y1 = minOf(pageHeight.toDouble(), (viewportHeight.toDouble() - pageTop) / pixelsPerPoint)
    if (x0 >= x1 || y0 >= y1) return null
    // Quarter-octave scales and an overscanned 128px grid avoid rerendering on every tiny pan/zoom.
    val density = minOf(1.0, sqrt(2_000_000.0 / (viewportWidth.toDouble() * viewportHeight)))
    var scale = 2.0.pow(ceil(ln(pixelsPerPoint * density) / ln(2.0) * 4) / 4)
    repeat(12) {
        if (!scale.isFinite() || scale <= previewPixelsPerPoint * 1.05 ||
            maxOf(pageWidth, pageHeight) * scale > Int.MAX_VALUE - 256.0) return null
        val left = maxOf(0.0, floor((x0 * scale - 64) / 128) * 128).toInt()
        val top = maxOf(0.0, floor((y0 * scale - 64) / 128) * 128).toInt()
        val right = minOf(ceil(pageWidth * scale), ceil((x1 * scale + 64) / 128) * 128).toInt()
        val bottom = minOf(ceil(pageHeight * scale), ceil((y1 * scale + 64) / 128) * 128).toInt()
        val width = right - left; val height = bottom - top
        if (width in 1..PdfDetailRegion.MAX_EDGE && height in 1..PdfDetailRegion.MAX_EDGE && width.toLong() * height <= PdfDetailRegion.MAX_PIXELS)
            return PdfDetailRegion(scale.toFloat(), left, top, width, height)
        scale /= 2.0.pow(.25)
    }
    return null
}
