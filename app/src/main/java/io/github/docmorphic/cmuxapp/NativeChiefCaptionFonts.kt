package io.github.docmorphic.cmuxapp

/** Measured Caption 2 metrics from the unmodified UIKit reference, run 39555560226
 * at be5c80c0 / upstream a5911a1f. Interpolate by preferred body descriptor size;
 * UIFontMetrics.scaledValue(17) is a different measurement. Android draws its
 * own glyphs; these inputs reproduce source spacing and baseline geometry. */
internal object NativeChiefCaptionFonts {
    data class Metrics(val pointSize: Double, val lineHeight: Double, val leading: Double, val ascender: Double) {
        val spacing get() = NativeChiefCaptionGeometry.lineSpacing(lineHeight, leading, lineHeight)
    }
    private val bodies = doubleArrayOf(14.0,15.0,16.0,17.0,19.0,21.0,23.0,28.0,33.0,40.0,47.0,53.0)
    private val points = doubleArrayOf(11.0,11.0,11.0,11.0,13.0,15.0,17.0,20.0,24.0,29.0,34.0,40.0)
    private val heights = doubleArrayOf(13.126953125,13.126953125,13.126953125,13.126953125,15.513671875,17.900390625,20.287109375,23.8671875,28.640625,34.607421875,40.57421875,47.734375)
    private val leading = doubleArrayOf(-.126953125,-.126953125,-.126953125,-.126953125,2.486328125,2.099609375,1.712890625,1.1328125,.359375,.392578125,.42578125,.265625)
    private val ascenders = doubleArrayOf(10.4736328125,10.4736328125,10.4736328125,10.4736328125,12.3779296875,14.2822265625,16.1865234375,19.04296875,22.8515625,27.6123046875,32.373046875,38.0859375)
    fun forBody(body: Double): Metrics {
        fun at(i: Int) = Metrics(points[i], heights[i], leading[i], ascenders[i])
        if (body <= bodies.first()) return at(0)
        for (i in 1 until bodies.size) if (body <= bodies[i]) {
            val t = (body - bodies[i - 1]) / (bodies[i] - bodies[i - 1])
            fun value(a: DoubleArray) = a[i - 1] + (a[i] - a[i - 1]) * t
            return Metrics(value(points), value(heights), value(leading), value(ascenders))
        }
        return at(bodies.lastIndex)
    }
}
