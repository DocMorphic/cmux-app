package io.github.docmorphic.cmuxapp

import kotlin.math.abs

/** Coordinates are fractions of the aspect-fit view, measured from its center. */
internal data class PreviewZoomTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val atMinimum get() = abs(scale - 1f) <= .01f

    fun transform(zoom: Float, panX: Float, panY: Float, focalX: Float = 0f, focalY: Float = 0f): PreviewZoomTransform {
        if (!listOf(zoom, panX, panY, focalX, focalY).all { it.isFinite() } || zoom <= 0f) return this
        val nextScale = (scale * zoom).coerceIn(1f, 8f)
        val ratio = nextScale / scale
        // Keep the image point under the previous centroid at the moving centroid.
        return bounded(nextScale, focalX - (focalX - x) * ratio + panX,
            focalY - (focalY - y) * ratio + panY)
    }

    fun doubleTap(focalX: Float, focalY: Float, targetScale: Float = 3f): PreviewZoomTransform =
        if (!atMinimum) PreviewZoomTransform()
        else bounded(targetScale, -focalX * targetScale, -focalY * targetScale)

    private fun bounded(scale: Float, x: Float, y: Float): PreviewZoomTransform {
        if (scale == 1f) return PreviewZoomTransform()
        val limit = (scale - 1f) / 2f
        return PreviewZoomTransform(scale, x.coerceIn(-limit, limit), y.coerceIn(-limit, limit))
    }
}
