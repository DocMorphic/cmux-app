package io.github.docmorphic.cmuxapp

/** Normal iOS 26 reply-header timings from the pinned source. A transition owns
 * its entire timeline: superseded delayed fades cannot mutate the next reply.
 * This deliberately repairs the stale UIKit completions observed in run39668896472. */
internal class NativeChiefHeaderMotion(initialReply: Long? = null) {
    data class Pose(val backAlpha: Float, val trailingAlpha: Float, val closeFraction: Float,
        val closeLabel: Boolean, val running: Boolean, val backAcceptsPointer: Boolean = true)
    private var reply = initialReply
    private var began = 0.0
    private var scale = 0.0
    private var from = rest(initialReply != null)

    fun transition(next: Long?, nowMillis: Double, durationScale: Float) {
        val nextScale = durationScale.toDouble().takeIf { it.isFinite() && it > 0 } ?: 0.0
        if (next == reply) {
            // Settings changes preserve the animation's phase; enabling motion
            // on an already settled header must not start a fresh glyph swap.
            if (nextScale == 0.0) scale = 0.0
            else if (scale > 0.0) {
                began = nowMillis - (nowMillis - began) / scale * nextScale
                scale = nextScale
            }
            return
        }
        val current = frame(nowMillis)
        // An outgoing cross-dissolve is a disposable snapshot, not the new
        // entry's image. A fresh entry starts from the current logical symbol.
        from = if (next != null && reply == null) current.copy(closeFraction = 0f, closeLabel = false) else current
        reply = next
        began = nowMillis
        scale = nextScale
    }

    fun frame(nowMillis: Double): Pose {
        if (scale == 0.0) return rest(reply != null)
        val t = ((nowMillis - began) / scale).coerceAtLeast(0.0)
        fun fade(a: Float, b: Float, start: Double, duration: Double): Float =
            (a + (b - a) * NativeChiefReplyMotion.easeInOut(((t - start) / duration).coerceIn(0.0, 1.0))).toFloat()
        return if (reply != null) {
            val swapped = t >= 370
            Pose(fade(from.backAlpha, 0f, 0.0, 60.0),
                if (swapped) fade(0f, 1f, 370.0, 200.0) else fade(from.trailingAlpha, 0f, 320.0, 50.0),
                if (swapped) 1f else from.closeFraction,
                swapped || from.closeLabel, t < 570, false)
        } else Pose(fade(from.backAlpha, 1f, 300.0, 200.0),
            fade(from.trailingAlpha, 1f, 0.0, 60.0), fade(from.closeFraction, 0f, 0.0, 60.0), false, t < 500,
            t < 300 || t >= 500)
    }

    private fun rest(open: Boolean) = Pose(if (open) 0f else 1f, 1f, if (open) 1f else 0f, open, false, !open)
}
