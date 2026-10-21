package io.github.docmorphic.cmuxapp

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Chief's direct-conversation branch of the pinned conversation header geometry. */
internal object NativeChiefHeaderPolicy {
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)
    fun margin(layoutMargin: Double) = max(16.0, layoutMargin)
    fun nameSize(scaled: Double) = min(max(scaled, 17.0), 21.0)
    // Body/Title 1/Caption 2 anchors: Apple's published iOS Dynamic Type table.
    // Android supports continuous font scaling, so interpolate between source categories.
    private val bodies = doubleArrayOf(14.0,15.0,16.0,17.0,19.0,21.0,23.0,28.0,33.0,40.0,47.0,53.0)
    private fun scaled(body: Double, sizes: DoubleArray): Double {
        if (body <= bodies.first()) return sizes.first()
        for (i in 1 until bodies.size) if (body <= bodies[i])
            return sizes[i-1] + (sizes[i] - sizes[i-1]) * (body - bodies[i-1]) / (bodies[i] - bodies[i-1])
        return sizes.last()
    }
    fun titleSize(body: Double) = scaled(body, doubleArrayOf(25.0,26.0,27.0,28.0,30.0,32.0,34.0,38.0,43.0,48.0,53.0,58.0))
    fun captionSize(body: Double) = scaled(body, doubleArrayOf(11.0,11.0,11.0,11.0,13.0,15.0,17.0,20.0,24.0,29.0,34.0,40.0))
    fun actionSize(body: Double) = scaled(body, doubleArrayOf(133.0/3,136.0/3,140.0/3,48.0,151.0/3,158.0/3,55.0,179.0/3,66.0,73.0,80.0,265.0/3))
    fun unreadWidth(textWidth: Double) = max(19.0, textWidth + 35.0 / 3)
    fun backWidth(textWidth: Double?) = textWidth?.let { 103.0 / 3 + unreadWidth(it) + 17 } ?: 44.0
    fun unreadFrame(textWidth: Double) = Rect(103.0 / 3, (44.0 - 19) / 2 + 1.0 / 6, unreadWidth(textWidth), 19.0)
    data class Header(val height: Double, val back: Rect, val stop: Rect, val avatar: Rect, val name: Rect)
    data class Compact(val avatar: Rect, val label: Rect, val chevron: Rect, val height: Double)
    fun compact(width: Double, nameWidth: Double, nameHeight: Double, chevronWidth: Double = 4.7, chevronHeight: Double = 9.0, safeTop: Double = 0.0): Compact {
        val top = max(safeTop, 24.0)
        val cluster = 44 + 12 + nameWidth + 4 + chevronWidth + 8
        val avatar = Rect((width - cluster) / 2, top, 44.0, 44.0)
        val name = Rect(avatar.x + 44 + 12, top + 22 - nameHeight / 2, nameWidth, nameHeight)
        return Compact(avatar, name, Rect(name.x + name.width + 4, top + 22 - chevronHeight / 2, chevronWidth, chevronHeight), top + 54)
    }
    fun header(width: Double, layoutMargin: Double, nameWidth: Double, nameHeight: Double,
        normalLine: Double, unreadTextWidth: Double?, compact: Boolean, chevronWidth: Double = 4.7, safeTop: Double = 0.0): Header {
        val margin = margin(layoutMargin)
        if (compact) {
            val fitted = min(nameWidth, max(0.0, width - 2 * (margin + 44 + 8) - 44 - 24 - chevronWidth))
            val raw = compact(width, fitted, nameHeight, chevronWidth, safeTop = safeTop)
            val avatar = raw.avatar.copy(y = raw.avatar.y - safeTop)
            return Header(raw.height - safeTop, Rect(margin, avatar.y, backWidth(unreadTextWidth), 44.0),
                Rect(width - margin - 44, avatar.y, 44.0, 44.0), avatar,
                Rect(avatar.x + 44, avatar.y, 12 + fitted + 4 + chevronWidth + 8, 44.0))
        }
        val pill = min(width - 2 * (margin + 60), 13 + nameWidth + 5.9 + 4.7 + 10.9).coerceAtLeast(0.0)
        return Header(92.0, Rect(margin, 0.0, backWidth(unreadTextWidth), 44.0),
            Rect(width - margin - 44, 0.0, 44.0, 44.0), Rect(width / 2 - 30, 0.0, 60.0, 60.0),
            Rect((width - pill) / 2, 55.0, pill, 97.0 / 3 + max(0.0, ceil(nameHeight) - ceil(normalLine))))
    }
    data class Details(val avatar: Rect, val titleTop: Double, val titleScale: Double,
        val actions: Rect, val actionsAlpha: Double, val height: Double)
    fun progress(offset: Double) = if (offset >= 0) min(1.0, offset / 97.23) else offset / (-offset + 97.23)
    fun details(width: Double, offset: Double, titleHeight: Double = 101.0 / 3, actionSize: Double = 48.0): Details {
        val p = progress(offset)
        val size = 80 - 20 * p
        val titleTop = 84 - 23 * p
        val actionsFromTitle = (titleHeight + 16) - (titleHeight + 16 - 5.0 / 3) * p
        val actionsWidth = 3 * actionSize + 40
        return Details(Rect((width - size) / 2, 0.0, size, size), titleTop, 1 - .37 * p,
            Rect((width - actionsWidth) / 2, titleTop + actionsFromTitle, actionsWidth, actionSize),
            (1 - p / .6).coerceIn(0.0, 1.0), 84 + titleHeight + 16 + actionSize + 20 - 97.23 * max(0.0, p))
    }
}
