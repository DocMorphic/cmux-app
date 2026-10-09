package io.github.docmorphic.cmuxapp


/** Coordinates are fractions of the aspect-fit view, measured from its center. */
internal data class PreviewZoomTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val atMinimum get() = scale <= 1.01f

    fun transform(zoom: Float, panX: Float, panY: Float, focalX: Float = 0f, focalY: Float = 0f, minimumScale: Float = 1f): PreviewZoomTransform {
        if (!listOf(zoom, panX, panY, focalX, focalY).all { it.isFinite() } || zoom <= 0f) return this
        val nextScale = (scale * zoom).coerceIn(minimumScale.coerceIn(.125f, 1f), 8f)
        val ratio = nextScale / scale
        // Keep the image point under the previous centroid at the moving centroid.
        return bounded(nextScale, focalX - (focalX - x) * ratio + panX,
            focalY - (focalY - y) * ratio + panY)
    }

    fun doubleTap(focalX: Float, focalY: Float, targetScale: Float = 3f): PreviewZoomTransform =
        if (!atMinimum) PreviewZoomTransform()
        else bounded(targetScale, -focalX * targetScale, -focalY * targetScale)

    /** Preserve the image point at the viewport center when aspect-fit letterboxing changes. */
    fun reframeImage(oldWidth: Int, oldHeight: Int, width: Int, height: Int, imageAspect: Float): PreviewZoomTransform {
        if (oldWidth <= 0 || oldHeight <= 0 || width <= 0 || height <= 0 ||
            !imageAspect.isFinite() || imageAspect <= 0f) return this
        fun fit(w: Int, h: Int): Pair<Float, Float> {
            val viewportAspect = w.toFloat() / h
            return minOf(1f, imageAspect / viewportAspect) to minOf(1f, viewportAspect / imageAspect)
        }
        val before = fit(oldWidth, oldHeight)
        val after = fit(width, height)
        return bounded(scale, x * after.first / before.first, y * after.second / before.second)
    }

    /** Inverse of the centered graphics layer; returns document fractions, not screen pixels. */
    fun contentPoint(px: Float, py: Float, width: Int, height: Int): Pair<Float, Float>? {
        if (width <= 0 || height <= 0 || !px.isFinite() || !py.isFinite() || !scale.isFinite() || scale <= 0) return null
        val localX = (px / width - .5f - x) / scale + .5f
        val localY = (py / height - .5f - y) / scale + .5f
        return (localX to localY).takeIf { localX in 0f..1f && localY in 0f..1f }
    }

    private fun bounded(scale: Float, x: Float, y: Float): PreviewZoomTransform {
        if (scale == 1f) return PreviewZoomTransform()
        val limit = ((scale - 1f) / 2f).coerceAtLeast(0f)
        return PreviewZoomTransform(scale, x.coerceIn(-limit, limit), y.coerceIn(-limit, limit))
    }
}
