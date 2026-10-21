package io.github.docmorphic.cmuxapp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Pinned ConversationReplyMotion and ReplyTrip dynamics, in source points.
 * These are frame equations, not a fixed-duration tween or a spring. */
internal object NativeChiefReplyMotion {
    const val backgroundDuration = .3
    const val firstStepFrames = 1.15

    fun offsetCurve(progress: Double): Double {
        val t = progress.coerceIn(0.0, 1.0)
        val u = 1 - t
        return u * u * u + 4.5 * t * u * u + 3 * t * t * u
    }

    fun easing(centerY: Double, itemHeight: Double, referenceY: Double, viewHeight: Double, animatingOut: Boolean): Double {
        val near = if (animatingOut) .84 else .89
        val far = if (animatingOut) .775 else .825
        if (viewHeight <= 0) return near
        var total = 0.0
        for (k in -1..1) {
            val p = ((abs(centerY - referenceY) + k * .5 * itemHeight) / viewHeight).coerceIn(0.0, 1.0)
            val eased = 1 - (1 - p) * (1 - p) * (1 - p)
            total += near + (far - near) * eased
        }
        return total / 3
    }

    fun threadEasing(startCenterY: Double, itemHeight: Double, viewHeight: Double, animatingOut: Boolean) =
        easing(startCenterY, itemHeight, viewHeight / 2, viewHeight, animatingOut)

    fun step(easing: Double, frameDuration: Double): Double {
        val frames = max(frameDuration, 0.0) * 60
        if (easing <= 0) return 1.0
        return minOf(1.0, 1 - minOf(easing, 1.0).pow(frames))
    }

    fun snapDistance(scale: Double, animatingOut: Boolean) = (if (animatingOut) 2.0 else .25) / max(scale, 1.0)

    fun easeInOut(x: Double) = cubicBezierY(x, .42, 0.0, .58, 1.0)
    fun cubicBezierY(x: Double, firstX: Double, firstY: Double, secondX: Double, secondY: Double): Double {
        val bounded = x.coerceIn(0.0, 1.0)
        fun coordinate(t: Double, a: Double, b: Double): Double {
            val u = 1 - t
            return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
        }
        var low = 0.0
        var high = 1.0
        var t = bounded
        for (index in 0 until 40) {
            val value = coordinate(t, firstX, secondX)
            if (abs(value - bounded) < 1e-6) break
            if (value < bounded) low = t else high = t
            t = (low + high) / 2
        }
        return coordinate(t, firstY, secondY)
    }

    data class Slot(val key: String, val messageID: String?, val y: Double, val height: Double)
    data class Plan(val slot: Slot, val startY: Double, val targetY: Double, val initialOffsetX: Double)

    /** Exact threadRows fallback: unseen copies and the heading follow the
     * anchor's vertical displacement; only the anchor carries its swipe. */
    fun plan(slots: List<Slot>, starts: Map<String, Double>, anchorID: String, anchorOffset: Double): List<Plan> {
        val anchor = slots.firstOrNull { it.messageID == anchorID }
        val shift = anchor?.let { starts[it.key]?.minus(it.y) } ?: 0.0
        return slots.map { slot ->
            Plan(slot, starts[slot.key] ?: slot.y + shift, slot.y, if (slot === anchor) anchorOffset else 0.0)
        }
    }
}

/** One row's source dynamics. Horizontal swing reads the previous vertical
 * frame; travelling rows keep their screen position when the container moves,
 * while rows whose y has landed ride it even if their short swing is unwinding. */
internal class NativeChiefReplyTrip(startY: Double, val targetY: Double, val initialOffsetX: Double,
    val easing: Double, val snapDistance: Double) {
    var startY = startY
        private set
    var y = startY
        private set
    var offsetX = initialOffsetX
        private set
    var stepCount = 0
        private set
    val isResting get() = y == targetY && offsetX == 0.0

    fun containerMoved(delta: Double) {
        if (y == targetY) return
        startY -= delta
        y -= delta
    }

    fun step(frameDuration: Double) {
        val frames = max(frameDuration, 0.0) * 60 * if (stepCount == 0) NativeChiefReplyMotion.firstStepFrames else 1.0
        stepCount++
        if (initialOffsetX > 0) {
            val travel = max(abs(targetY - startY), 1.0)
            offsetX = when {
                travel < 2 -> max(0.0, offsetX - 6 * frames)
                y == targetY -> 0.0
                else -> max(0.0, initialOffsetX * NativeChiefReplyMotion.offsetCurve(abs(y - startY) / travel))
            }
        }
        val k = NativeChiefReplyMotion.step(easing, frames / 60)
        y += (targetY - y) * k
        if (abs(targetY - y) < snapDistance) y = targetY
    }
}
