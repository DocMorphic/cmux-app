package io.github.docmorphic.cmuxapp

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.log10

/** Pinned ConversationReplyMotion geometry, in source points. Physical rightward
 * movement is deliberate: the source rules use positive x even in an RTL locale. */
internal object NativeChiefReplySwipePolicy {
    const val confirmThreshold = 40.0
    const val showIndicatorThreshold = 22.0
    const val indicatorSize = 26.0
    const val initialScale = .4
    const val pulseScale = 1.15
    const val outgoingDrift = 12.0
    data class Area(val x: Double, val y: Double, val width: Double, val height: Double) {
        fun contains(px: Double, py: Double) = px >= x && py >= y && px < x + width && py < y + height
    }
    data class Indicator(val x: Double, val y: Double, val side: Double)
    fun bubbleOffset(translation: Double) = if (translation < confirmThreshold) maxOf(0.0, translation)
        else confirmThreshold * (1 + .7 * log10(translation / confirmThreshold))
    fun progress(offset: Double) = ((offset - showIndicatorThreshold) / (confirmThreshold - showIndicatorThreshold)).coerceIn(0.0, 1.0)
    fun scale(progress: Double) = initialScale + (1 - initialScale) * progress
    fun isSwipeAngle(x: Double, y: Double) = x != 0.0 && abs(atan(y / x)) * 180 / Math.PI <= 18
    fun swipeArea(bubble: Area, outgoing: Boolean): Area {
        var x = bubble.x + if (outgoing) 0 else 28
        var width = bubble.width
        if (width < 156) { val grow = 156 - width; if (outgoing) x -= grow; width += grow }
        return Area(x, bubble.y, width, bubble.height)
    }
    fun indicator(centerX: Double, centerY: Double, outgoing: Boolean, offset: Double): Indicator? {
        if (offset <= showIndicatorThreshold) return null
        val progress = progress(offset); val scale = scale(progress)
        val drift = if (outgoing) -outgoingDrift * progress else 0.0
        val side = indicatorSize * scale
        return Indicator(centerX + drift * scale - side / 2, centerY - side / 2, side)
    }
    fun canBegin(acknowledged: Boolean, translation: Double, velocityX: Double, velocityY: Double,
        pointX: Double, pointY: Double, bubble: Area, outgoing: Boolean) = acknowledged && translation >= 0 &&
        velocityX > 0 && isSwipeAngle(velocityX, velocityY) && swipeArea(bubble, outgoing).contains(pointX, pointY)

    enum class Crossing { NONE, COMMIT, UNCOMMIT }
    /** Exact direction-qualified crossing rules from updateReplySwipe. */
    fun crossing(offset: Double, velocityX: Double, committed: Boolean): Crossing = when {
        !committed && offset > confirmThreshold && velocityX > 0 -> Crossing.COMMIT
        committed && offset < confirmThreshold && velocityX < 0 -> Crossing.UNCOMMIT
        else -> Crossing.NONE
    }
}
