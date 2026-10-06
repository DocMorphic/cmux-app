package io.github.docmorphic.cmuxapp

/** Pinch anchors do not depend on a LazyColumn remeasure between pointer events. */
internal data class PdfGestureAnchor(val page: Int, val unscaledY: Float, val focalY: Float) {
    fun move(panY: Float) = copy(focalY = focalY + panY)
    fun scrollOffset(scale: Float) = unscaledY * scale - focalY
}

internal class PdfGestureAnchorState { var anchor: PdfGestureAnchor? = null }
