package io.github.docmorphic.cmuxapp

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Port of pinned ConversationBubbleGeometry and the balloon-width metrics. Values stay in doubles
 * until drawing, so the actual CoreGraphics command vectors can verify the translation. */
internal object NativeChiefBubbleGeometry {
    const val referenceRadius = 20.1435546875
    data class Point(val x: Double, val y: Double)
    data class Command(val kind: String, val points: List<Point> = emptyList())

    fun tailDrop(radius: Double) = 6.8337 * radius / referenceRadius
    fun maxWidth(betweenMargins: Double, accessibility: Boolean = false) =
        if (accessibility) betweenMargins - 70.0 / 3.0
        else max(0.0, min(betweenMargins - 268.0 / 3.0, betweenMargins * .85))
    fun ceilToPixel(value: Double, scale: Double): Double {
        val s = scale.takeIf { it > 0 } ?: 1.0
        return ceil(value * s - .001) / s
    }
    fun space(base: Double, captionLineSpacing: Double, scale: Double): Double {
        val s = scale.takeIf { it > 0 } ?: 1.0
        // Swift's .rounded() breaks positive half ties away from zero.
        return kotlin.math.floor(base * max(1.0, captionLineSpacing / 13.0) * s + .5) / s
    }

    fun outline(width: Double, height: Double, radius: Double, outgoing: Boolean, tail: Boolean): List<Command> {
        val r = max(0.0, min(radius, min(height / 2, width / 2)))
        fun axis(halfExtent: Double): List<Double> {
            val pill = listOf(1.0, .87327, .74768, .62993, .37476, .17266, .07099, .02408)
            val normal = listOf(1.52866, 1.08849, .86841, .63152, .37282, .16905, .07491, 0.0)
            val t = if (r > 0) ((halfExtent - r) / (.52866 * r)).coerceIn(0.0, 1.0) else 1.0
            return pill.zip(normal) { p, n -> (p + (n - p) * t) * r }
        }
        val v = axis(height / 2); val h = axis(width / 2)
        val corner = listOf(Point(0.0, v[0]), Point(0.0, v[1]), Point(v[7], v[2]), Point(v[6], v[3]),
            Point(v[5], v[4]), Point(h[4], h[5]), Point(h[3], h[6]), Point(h[2], h[7]),
            Point(h[1], 0.0), Point(h[0], 0.0))
        val commands = mutableListOf<Command>()
        fun add(kind: String, vararg points: Point) {
            commands += Command(kind, points.map { if (outgoing) it else Point(width - it.x, it.y) })
        }
        fun addCorner(reverse: Boolean, map: (Point) -> Point) {
            val points = if (reverse) corner.reversed() else corner
            add("line", map(points[0]))
            for (i in 1 until points.size step 3) add("curve", map(points[i]), map(points[i + 1]), map(points[i + 2]))
        }
        add("move", Point(h[0], 0.0))
        addCorner(true) { Point(width - it.x, it.y) }
        if (tail) {
            add("line", Point(width, height - v[0]))
            val scale = r / referenceRadius
            for (i in tailOffsets.indices step 3) {
                fun at(p: Point) = Point(width + p.x * scale, height + p.y * scale)
                add("curve", at(tailOffsets[i]), at(tailOffsets[i + 1]), at(tailOffsets[i + 2]))
            }
        } else addCorner(false) { Point(width - it.x, height - it.y) }
        addCorner(true) { Point(it.x, height - it.y) }
        addCorner(false) { it }
        add("close")
        return commands
    }

    private val tailOffsets = listOf(
        Point(0.0, -15.806), Point(-1.425, -11.584), Point(-4.057, -8.134),
        Point(-5.123, -6.736), Point(-6.355, -5.503), Point(-7.715, -4.455),
        Point(-9.654, -2.943), Point(-10.493, -1.366), Point(-10.493, .407),
        Point(-10.493, 1.598), Point(-10.282, 2.775), Point(-8.571, 5.023),
        Point(-7.750, 6.101), Point(-8.574, 7.201), Point(-9.857, 6.714),
        Point(-12.496, 5.711), Point(-15.502, 3.887), Point(-18.134, 1.941),
        Point(-20.493, .196), Point(-21.122, .020), Point(-22.228, .013))
}
