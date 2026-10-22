package io.github.docmorphic.cmuxapp

/** Actual preferred body/footnote metrics from UIKit run 39806263693 at
 * d493d24e / upstream a5911a1f. Android permits continuous text scales, so
 * interpolate between measured categories and clamp outside their range.
 * Android supplies its own glyphs; these metrics specify the native line box. */
internal object NativeChiefPollFooterFonts {
    data class Metrics(val pointSize: Double, val lineHeight: Double, val ascender: Double, val descender: Double)
    private val bodies = doubleArrayOf(14.0, 15.0, 16.0, 17.0, 19.0, 21.0, 23.0, 28.0, 33.0, 40.0, 47.0, 53.0)
    private val points = doubleArrayOf(12.0, 12.0, 12.0, 13.0, 15.0, 17.0, 19.0, 23.0, 27.0, 33.0, 38.0, 44.0)

    fun forBody(body: Double): Metrics {
        require(body.isFinite())
        val clamped = body.coerceIn(bodies.first(), bodies.last())
        val upper = (1..bodies.lastIndex).first { clamped <= bodies[it] }
        val t = (clamped - bodies[upper - 1]) / (bodies[upper] - bodies[upper - 1])
        val point = points[upper - 1] + (points[upper] - points[upper - 1]) * t
        return Metrics(point, point * 1.193359375, point * .9521484375, -point * .2412109375)
    }
}
