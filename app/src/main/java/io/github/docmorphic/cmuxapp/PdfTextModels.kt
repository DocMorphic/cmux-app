package io.github.docmorphic.cmuxapp

import java.net.URI

internal data class PdfTextBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left <= right && top <= bottom && x in left..right && y in top..bottom
}
internal data class PdfTextMatch(val page: Int, val start: Int, val bounds: List<PdfTextBounds>)
internal data class PdfDocumentLink(val bounds: List<PdfTextBounds>, val target: PdfLinkTarget)
internal enum class PdfDestinationFit { XYZ, PAGE, WIDTH, HEIGHT, RECTANGLE }
/** Null PDF coordinates retain the current point in unrotated PDF user space. */
internal data class PdfRetainedCoordinates(val geometry: PdfPageCoordinates, val left: Float?, val top: Float?) {
    fun resolve(current: Pair<Float, Float>): Pair<Float, Float>? = geometry.point(left ?: current.first, top ?: current.second)
}
internal sealed interface PdfLinkTarget {
    data class Page(val index: Int, val y: Float, val x: Float = 0f, val zoom: Float = 0f,
        val retainZoom: Boolean = true, val fit: PdfDestinationFit = PdfDestinationFit.XYZ,
        val rectangle: PdfTextBounds? = null, val retained: PdfRetainedCoordinates? = null) : PdfLinkTarget
    data class External(val url: String) : PdfLinkTarget
    companion object {
        fun external(value: String): External? = runCatching {
            val uri = URI(value)
            require(!value.any(Char::isISOControl))
            when (uri.scheme?.lowercase()) {
                "http", "https" -> require(!uri.host.isNullOrEmpty() && uri.rawUserInfo == null)
                "mailto", "tel" -> require(!uri.rawSchemeSpecificPart.isNullOrBlank())
                else -> return null
            }
            External(value)
        }.getOrNull()
    }
}
internal fun pdfMatchStep(current: Int, delta: Int, count: Int): Int =
    if (count <= 0) 0 else Math.floorMod(current.toLong() + delta, count.toLong()).toInt()
