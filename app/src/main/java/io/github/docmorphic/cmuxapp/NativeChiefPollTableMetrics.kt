package io.github.docmorphic.cmuxapp

/** Settled visible UIKit table geometry at all twelve system categories,
 * run 39838940985 / upstream a5911a1f. Values are thirds of a point on the
 * reference screen. Interpolate resolved body points for Android's scales. */
internal object NativeChiefPollTableMetrics {
    data class Metrics(val referenceBodyPoints: Double, val rowHeight: Double,
        val firstHeaderHeight: Double, val firstHeaderLabelTop: Double,
        val footerHeight: Double, val footerLabelTop: Double, val titlePointSize: Double,
        val nonVoterHeaderLabelTop: Double) {
        val continuationHeaderHeight get() = firstHeaderHeight - 10.0
        val continuationHeaderLabelTop get() = firstHeaderLabelTop - 10.0
        val nonVoterHeaderHeight get() = firstHeaderHeight - 52.0 / 3.0
        val emptyFooterHeight get() = 52.0 / 3.0
        val tableBottomPadding get() = 20.0
    }
    private val bodies = doubleArrayOf(14.0, 15.0, 16.0, 17.0, 19.0, 21.0, 23.0, 28.0, 33.0, 40.0, 47.0, 53.0)
    private val rows = intArrayOf(150, 153, 156, 159, 165, 171, 180, 194, 212, 237, 262, 283)
    private val headers = intArrayOf(144, 151, 159, 166, 181, 196, 219, 256, 301, 361, 421, 466)
    private val headerTops = intArrayOf(78, 80, 84, 86, 94, 100, 113, 129, 152, 181, 210, 230)
    private val footers = intArrayOf(79, 79, 79, 90, 102, 113, 124, 152, 175, 214, 248, 282)
    private val footerTops = intArrayOf(18, 18, 18, 23, 25, 26, 28, 35, 39, 48, 56, 62)
    // The largest inset is from visible bottom run 39844879900, not an offscreen estimate.
    private val nonVoterTops = intArrayOf(26, 28, 32, 35, 42, 48, 61, 77, 100, 129, 158, 178)

    fun forBody(body: Double): Metrics {
        require(body.isFinite())
        val reference = body.coerceIn(bodies.first(), bodies.last())
        val upper = (1..bodies.lastIndex).first { reference <= bodies[it] }
        val t = (reference - bodies[upper - 1]) / (bodies[upper] - bodies[upper - 1])
        fun value(values: IntArray) = (values[upper - 1] + (values[upper] - values[upper - 1]) * t) / 3.0
        return Metrics(reference, value(rows), value(headers), value(headerTops), value(footers), value(footerTops), reference.coerceIn(17.0, 21.0), value(nonVoterTops))
    }
}
