package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextGeometricTransform

/** Intrinsic UIFont / UIImage measurements from run 39747401479 at b7ca4c87,
 * unmodified upstream a5911a1f. Artwork remains an original Android vector. */
internal object NativeChiefTranslationFonts {
    private data class Slot(val points: Double, val width: Double, val height: Double, val space: Double)
    private val slots = listOf(
        Slot(11.0, 19.666666666666664, 13.666666666666666, 3.158203125),
        Slot(13.0, 23.333333333333332, 16.666666666666664, 3.580078125),
        Slot(15.0, 27.333333333333332, 19.0, 3.984375),
        Slot(17.0, 30.333333333333332, 21.0, 4.349609375),
        Slot(20.0, 35.66666666666667, 25.333333333333332, 4.8157501220703125),
        Slot(24.0, 43.0, 30.333333333333332, 5.324584007263184),
        Slot(29.0, 52.0, 36.666666666666664, 6.32958984375),
        Slot(34.0, 61.0, 42.666666666666664, 7.3544921875),
        Slot(40.0, 71.66666666666667, 50.333333333333336, 8.57421875),
    )
    fun attachment(points: Double): NativeChiefTranslationAttachmentMetrics {
        fun result(s: Slot) = NativeChiefTranslationAttachmentMetrics(s.width, s.height, s.width + s.space)
        if (points <= slots.first().points) return result(slots.first())
        for (i in 1 until slots.size) if (points <= slots[i].points) {
            val a = slots[i - 1]; val b = slots[i]; val t = (points - a.points) / (b.points - a.points)
            val width = a.width + (b.width - a.width) * t
            return NativeChiefTranslationAttachmentMetrics(width, a.height + (b.height - a.height) * t,
                width + a.space + (b.space - a.space) * t)
        }
        return result(slots.last())
    }
    enum class Probe(val text: String) {
        CAPTION("Show Original"),
        PROMPT("Translate this message or automatically translate this conversation?"),
        ACTION("Translate This Message")
    }
    // Full-run advances, not font files or glyph outlines. Interpolation between
    // the two compositor sizes is provisional for other type categories/locales.
    fun advance(points: Double, probe: Probe): Double {
        val (small, large, first, last) = when (probe) {
            Probe.CAPTION -> doubleArrayOf(11.0, 20.0, 72.30029296875, 120.87921142578125)
            Probe.PROMPT -> doubleArrayOf(17.0, 28.0, 512.755859375, 806.99609375)
            Probe.ACTION -> doubleArrayOf(17.0, 28.0, 183.80308258533478, 292.08515310287476)
        }
        if (points < small) return first * points / small
        if (points > large) return last * points / large
        return first + (last - first) * (points - small) / (large - small)
    }
}

/** Preserve Android's selected size and glyphs, while matching the measured
 * source font's advance. Measure the same Latin probe in the actual runtime
 * font; the probe is metrology data and is never presented as localized UI. */
@Composable
internal fun nativeChiefTranslationFont(style: TextStyle, points: Double,
    probe: NativeChiefTranslationFonts.Probe): TextStyle {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val unscaled = style.copy(textGeometricTransform = null)
    val measured = measurer.measure(probe.text, unscaled, maxLines = 1, softWrap = false)
        .multiParagraph.intrinsics.maxIntrinsicWidth / density.density
    return unscaled.copy(textGeometricTransform = TextGeometricTransform(
        scaleX = if (measured > 0) (NativeChiefTranslationFonts.advance(points, probe) / measured).toFloat() else 1f))
}
