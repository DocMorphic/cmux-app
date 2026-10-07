/* Bubble geometry ported from cmux AgentFeedBubbleShape.swift at
 * 186cec79781256867ad4516f0802118738bd2393. GPL-3.0-or-later; see NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One continuous outline for quoted prompts and filled replies, mirrored in RTL. */
internal class AgentFeedBubbleShape(private val trailing: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val unit = with(density) { 1.dp.toPx() }
        val w = size.width / unit; val h = size.height / unit
        val left = 4f.coerceAtMost(w); val right = w
        val radius = minOf(18f, h / 2, (right - left) / 2).coerceAtLeast(0f)
        val handle = radius * .4f
        val mirror = trailing == (layoutDirection == LayoutDirection.Ltr)
        fun x(value: Float) = (if (mirror) w - value else value) * unit
        fun y(value: Float) = value * unit
        val path = Path().apply {
            moveTo(x(left + 21), y(h))
            lineTo(x(right - radius), y(h))
            cubicTo(x(right - handle), y(h), x(right), y(h - handle), x(right), y(h - radius))
            lineTo(x(right), y(radius))
            cubicTo(x(right), y(handle), x(right - handle), 0f, x(right - radius), 0f)
            lineTo(x(left + radius), 0f)
            cubicTo(x(left + handle), 0f, x(left), y(handle), x(left), y(radius))
            lineTo(x(left), y(maxOf(radius, h - 11)))
            cubicTo(x(left), y(h - 2.4f), x(2.1f), y(h - .7f), x(.7f), y(h - .6f))
            quadraticTo(x(.4f), y(h - .1f), x(1.6f), y(h - .2f))
            cubicTo(x(3.4f), y(h + .2f), x(left + 4), y(h - 3.5f), x(left + 7), y(h - 3.5f))
            cubicTo(x(left + 10), y(h - 3.5f), x(left + 16), y(h), x(left + 21), y(h))
            close()
        }
        return Outline.Generic(path)
    }
}
