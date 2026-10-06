package io.github.docmorphic.cmuxapp

/** Scroll and optional leading space are fractions of the unscaled target page height. */
internal data class PdfDestinationViewport(val transform: PreviewZoomTransform, val scrollFraction: Float,
    val topInsetFraction: Float = 0f) {
    companion object {
        fun resolve(target: PdfLinkTarget.Page, width: Int, height: Int, viewportWidth: Float,
            density: Float, currentScale: Float, viewportHeight: Float = viewportWidth,
            documentWidth: Int = width): PdfDestinationViewport {
            require(width > 0 && height > 0 && documentWidth > 0 && viewportWidth.isFinite() && viewportWidth > 0 &&
                viewportHeight.isFinite() && viewportHeight > 0)
            val base = viewportWidth / documentWidth
            val rect = target.rectangle
            val requested = when (target.fit) {
                PdfDestinationFit.PAGE -> minOf(viewportWidth / (width * base), viewportHeight / (height * base))
                PdfDestinationFit.WIDTH -> viewportWidth / (width * base)
                PdfDestinationFit.HEIGHT -> viewportHeight / (height * base)
                PdfDestinationFit.RECTANGLE -> if (rect != null && rect.right > rect.left && rect.bottom > rect.top)
                    minOf(viewportWidth / ((rect.right - rect.left) * base), viewportHeight / ((rect.bottom - rect.top) * base)) else 1f
                PdfDestinationFit.XYZ -> if (target.zoom.isFinite() && target.zoom > 0f)
                    target.zoom * density / base else if (target.retainZoom) currentScale else 1f
            }
            val scale = (requested.takeIf(Float::isFinite) ?: 1f).coerceIn(.125f, 8f)
            val centeredX = target.fit in setOf(PdfDestinationFit.PAGE, PdfDestinationFit.WIDTH, PdfDestinationFit.RECTANGLE)
            val centeredY = target.fit in setOf(PdfDestinationFit.PAGE, PdfDestinationFit.HEIGHT, PdfDestinationFit.RECTANGLE)
            val x = if (target.fit == PdfDestinationFit.RECTANGLE && rect != null) (rect.left + rect.right) / 2f
                else if (centeredX) width / 2f else (target.x.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, width.toFloat())
            val y = if (target.fit == PdfDestinationFit.RECTANGLE && rect != null) (rect.top + rect.bottom) / 2f
                else if (centeredY) height / 2f else (target.y.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, height.toFloat())
            val requestedX = (if (centeredX) 0f else -.5f) - (x - width / 2f) * scale / documentWidth
            val limit = ((width.toFloat() / documentWidth * scale - 1f) / 2f).coerceAtLeast(0f)
            val panX = if (centeredX) requestedX else requestedX.coerceIn(-limit, limit)
            val scrollPixels = y * base * scale - if (centeredY) viewportHeight / 2f else 0f
            return PdfDestinationViewport(PreviewZoomTransform(scale, if (panX == 0f) 0f else panX),
                scrollPixels.coerceAtLeast(0f) / (height * base), (-scrollPixels).coerceAtLeast(0f) / (height * base))
        }
    }
}
