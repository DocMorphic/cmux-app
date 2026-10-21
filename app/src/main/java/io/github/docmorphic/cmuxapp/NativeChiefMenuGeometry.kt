package io.github.docmorphic.cmuxapp

import kotlin.math.max
import kotlin.math.min

/** Pinned MessagePressTiming; the mounted gesture driver must use both clocks,
 * arbitrate with scrolling and apply the held curve before opening the menu. */
internal object NativeChiefMessagePressTiming {
    const val liftBegins = .15
    const val clickDown = .4
    const val clickTimeout = .725
    const val replyHandoffDelay = .12
    val pressCurve = listOf(.32, .85, .95, 1.0)
    enum class Gesture { PRESS, TAP, PAN, OTHER }
    fun opensOnRelease(held: Double) = held >= clickDown
    fun tapOpensItem(held: Double) = held < clickDown
    fun recognizeTogether(a: Gesture, b: Gesture, growing: Boolean): Boolean = when {
        a == Gesture.PRESS && b == Gesture.PRESS -> true
        a == Gesture.PRESS && b == Gesture.PAN || a == Gesture.PAN && b == Gesture.PRESS -> growing
        a == Gesture.TAP || b == Gesture.TAP -> true
        else -> false
    }
    fun pressScale(width: Double, height: Double) = scale(width, height, 14.0)
    fun liftScale(width: Double, height: Double) = min(scale(width, height, 26.0), 1.15)
    private fun scale(width: Double, height: Double, growth: Double): Double {
        val side = max(width, height)
        return if (side <= 0) 1.0 else 1 + growth / side
    }
}

/** Chief's menu branch (no reactions/custom-emoji strip). Direct layoutFinal /
 * liftedFrame port; the preview uses its original scale even when clipped. */
internal object NativeChiefMenuGeometry {
    const val rowHeight = 42.0
    const val menuWidth = 250.0
    const val menuGap = 18.0
    const val verticalInset = 10.0
    const val iconCenter = 241.0 / 6
    const val labelLeading = 64.0
    const val labelTrailing = 28.0
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        val right get() = x + width
        val bottom get() = y + height
    }
    data class Layout(val preview: Rect, val menu: Rect, val scale: Double, val clipped: Boolean)
    fun menuHeight(rows: Int): Double {
        require(rows >= 0)
        return rows * rowHeight + (if (rows > 3) 14 else 0) + 2 * verticalInset
    }
    fun menuX(preview: Rect, outgoing: Boolean, width: Double, inset: Double) =
        min(max(inset, if (outgoing) preview.right - menuWidth else preview.x), width - inset - menuWidth)
    fun layout(width: Double, height: Double, safeTop: Double, safeBottom: Double, headerBottom: Double,
        inset: Double, source: Rect, outgoing: Boolean, rows: Int): Layout {
        val scale = NativeChiefMessagePressTiming.liftScale(source.width, source.height)
        val lift = (scale - 1) / 2
        val topLimit = max(safeTop, headerBottom) + 8
        val menuHeight = menuHeight(rows)
        val bottomLimit = height - safeBottom - 8 - menuHeight - menuGap
        var y = source.y
        var h = source.height
        if (y + h + h * lift > bottomLimit) y = bottomLimit - h * (1 + lift)
        if (y - h * lift < topLimit) y = topLimit + h * lift
        val clipped = h * scale > bottomLimit - topLimit
        if (clipped) {
            h = max(60.0, (bottomLimit - topLimit) / scale)
            y = topLimit + h * lift
        }
        val scaledWidth = source.width * scale
        val scaledHeight = h * scale
        val low = inset + scaledWidth / 2
        val high = width - inset - scaledWidth / 2
        val originalCenter = source.x + source.width / 2
        val centerX = if (low <= high) min(max(originalCenter, low), high) else originalCenter
        val preview = Rect(centerX - scaledWidth / 2, y + h / 2 - scaledHeight / 2, scaledWidth, scaledHeight)
        return Layout(preview, Rect(menuX(preview, outgoing, width, inset),
            min(preview.bottom + menuGap, height - safeBottom - menuHeight - 8), menuWidth, menuHeight), scale, clipped)
    }
}
