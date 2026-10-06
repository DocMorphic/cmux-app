package io.github.docmorphic.cmuxapp

/** Destination in renderer coordinates; scroll is measured in fitted page heights. */
internal data class PdfDestinationViewport(val transform: PreviewZoomTransform, val scrollFraction: Float) {
    companion object {
        fun resolve(target: PdfLinkTarget.Page, width: Int, height: Int, viewportWidth: Float,
            density: Float, currentScale: Float): PdfDestinationViewport {
            require(width > 0 && height > 0 && viewportWidth.isFinite() && viewportWidth > 0)
            val requested = if (target.zoom.isFinite() && target.zoom > 0f)
                target.zoom * density / (viewportWidth / width) else if (target.retainZoom) currentScale else 1f
            val scale = (requested.takeIf(Float::isFinite) ?: 1f).coerceIn(.125f, 8f)
            val limit = ((scale - 1f) / 2f).coerceAtLeast(0f)
            val x = (target.x.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, width.toFloat()) / width
            val y = (target.y.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, height.toFloat()) / height
            // Align X with the leading edge as far as page bounds allow. Keep the
            // destination's fitted Y inside the clipped page, then scroll to it.
            val panX = (-.5f - (x - .5f) * scale).coerceIn(-limit, limit)
            val panY = ((y - .5f) * (1f - scale)).coerceIn(-limit, limit)
            val transform = if (scale == 1f) PreviewZoomTransform() else PreviewZoomTransform(scale, panX, panY)
            return PdfDestinationViewport(transform, ((y - .5f) * scale + panY + .5f).coerceIn(0f, 1f))
        }
    }
}
