package io.github.docmorphic.cmuxapp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.round

/** Window-space points. The arrow is outside the content rectangle; system insets are supplied. */
internal object NativeChiefTranslationPopoverGeometry {
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        val right get() = x + width
        val bottom get() = y + height
        val centerX get() = x + width / 2
        val centerY get() = y + height / 2
    }
    enum class Side { BELOW, ABOVE, LEFT, RIGHT }
    data class Pose(val body: Rect, val side: Side, val arrow: Double)
    fun place(bounds: Rect, source: Rect, width: Double, height: Double, inset: Double = 10.0, arrow: Double = 13.0): Pose {
        require(listOf(bounds.x,bounds.y,bounds.width,bounds.height,source.x,source.y,source.width,source.height,width,height,inset,arrow).all(Double::isFinite))
        require(bounds.width > 0 && bounds.height > 0 && width > 0 && height > 0)
        val left = bounds.x + min(inset, bounds.width / 2)
        val top = bounds.y + min(inset, bounds.height / 2)
        val right = bounds.right - min(inset, bounds.width / 2)
        val bottom = bounds.bottom - min(inset, bounds.height / 2)
        fun clamp(value: Double, low: Double, high: Double) = value.coerceIn(low, max(low, high))
        val x = clamp(floor(source.centerX - width / 2), left, right - width)
        val y = clamp(floor(source.centerY - height / 2), top, bottom - height)
        val candidates = listOf(
            Side.BELOW to Rect(x, round(source.bottom) + arrow, width, height),
            Side.ABOVE to Rect(x, floor(source.y - arrow - height), width, height),
            Side.LEFT to Rect(floor(source.x - arrow - width), y, width, height),
            Side.RIGHT to Rect(round(source.right) + arrow, y, width, height))
        fun visible(rect: Rect) = max(0.0, min(rect.right,right)-max(rect.x,left)) * max(0.0,min(rect.bottom,bottom)-max(rect.y,top))
        val (side, proposed) = candidates.firstOrNull { (_,rect) -> rect.x >= left && rect.y >= top && rect.right <= right && rect.bottom <= bottom }
            ?: candidates.maxBy { (_,rect) -> visible(rect) }
        val body = proposed.copy(x=clamp(proposed.x,left,right-width),y=clamp(proposed.y,top,bottom-height))
        val span = if (side == Side.BELOW || side == Side.ABOVE) width else height
        val padding = min(50.0,span/2)
        val center = if (side == Side.BELOW || side == Side.ABOVE) source.centerX-body.x else source.centerY-body.y
        return Pose(body,side,clamp(center,padding,span-padding))
    }
}
