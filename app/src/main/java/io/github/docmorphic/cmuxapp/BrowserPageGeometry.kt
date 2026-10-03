package io.github.docmorphic.cmuxapp

import kotlin.math.hypot

internal data class BrowserPoint(val x: Double, val y: Double)
internal data class BrowserRect(val left: Double, val top: Double, val width: Double, val height: Double)

/** Matches the iOS BrowserStreamTransform: width fit, centered local lens, view-pixel pan. */
internal data class BrowserPageTransform(val viewWidth: Double, val viewHeight: Double,
    val pageWidth: Double, val pageHeight: Double, val zoom: Double = 1.0,
    val offsetX: Double = 0.0, val offsetY: Double = 0.0) {
    private val valid get() = listOf(viewWidth, viewHeight, pageWidth, pageHeight, zoom).all { it.isFinite() && it > 0 } &&
        offsetX.isFinite() && offsetY.isFinite()
    val fitScale get() = if (valid) viewWidth / pageWidth else 1.0
    val rect: BrowserRect? get() {
        if (!valid) return null
        val width = viewWidth * zoom.coerceAtLeast(1.0)
        val height = pageHeight * fitScale * zoom.coerceAtLeast(1.0)
        return BrowserRect((viewWidth - width) / 2 - offsetX, (viewHeight - height) / 2 - offsetY, width, height)
    }
    fun pagePoint(x: Double, y: Double): BrowserPoint? {
        val bounds = rect ?: return null
        if (!x.isFinite() || !y.isFinite() || x < bounds.left || y < bounds.top ||
            x >= bounds.left + bounds.width || y >= bounds.top + bounds.height) return null
        return BrowserPoint(((x - bounds.left) / bounds.width * pageWidth).coerceIn(0.0, pageWidth),
            ((y - bounds.top) / bounds.height * pageHeight).coerceIn(0.0, pageHeight))
    }
    fun pageDelta(dx: Double, dy: Double): BrowserPoint = if (valid)
        BrowserPoint(dx / fitScale, dy / fitScale) else BrowserPoint(0.0, 0.0)
    fun scrollAnchor(x: Double, y: Double) = pagePoint(x, y) ?: BrowserPoint(pageWidth / 2, pageHeight / 2)
}

/** Immediate clicks chain using the iOS 450 ms / 28 view-point thresholds. */
internal class BrowserTapCounter {
    private var last: BrowserPoint? = null
    private var lastMillis = 0L
    private var count = 0
    fun register(point: BrowserPoint, millis: Long): Int {
        val previous = last
        count = if (previous != null && millis - lastMillis in 0..450 &&
            hypot(point.x - previous.x, point.y - previous.y) <= 28.0) (count + 1).coerceAtLeast(1) else 1
        last = point; lastMillis = millis
        return count
    }
}
