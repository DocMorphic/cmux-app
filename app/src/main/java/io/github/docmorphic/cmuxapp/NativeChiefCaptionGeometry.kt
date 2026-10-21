package io.github.docmorphic.cmuxapp

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Exact ConversationTranscriptMetrics/TimestampCell equations, with explicit
 * measured font inputs. UIFont metrics and Android glyph artwork are separate. */
internal object NativeChiefCaptionGeometry {
    data class Insets(val top: Double, val bottom: Double)
    private val points = doubleArrayOf(11.0,13.0,15.0,17.0,20.0,24.0,29.0,34.0,40.0)
    private val tops = doubleArrayOf(7.0/3,3.0,11.0/3,13.0/3,14.0/3,6.0,7.0,25.0/3,29.0/3)
    private val bottoms = doubleArrayOf(3.0,3.0,10.0/3,4.0,5.0,17.0/3,20.0/3,8.0,10.0)
    fun alignmentInsets(pointSize: Double): Insets {
        if (pointSize <= points.first()) return Insets(tops.first() * pointSize / points.first(), bottoms.first() * pointSize / points.first())
        for (i in 1 until points.size) if (pointSize <= points[i]) {
            val t = (pointSize - points[i - 1]) / (points[i] - points[i - 1])
            return Insets(tops[i - 1] + (tops[i] - tops[i - 1]) * t, bottoms[i - 1] + (bottoms[i] - bottoms[i - 1]) * t)
        }
        return Insets(tops.last() * pointSize / points.last(), bottoms.last() * pointSize / points.last())
    }
    fun lineSpacing(lineHeight: Double, leading: Double, neutralLineHeight: Double) = min(lineHeight, neutralLineHeight) + leading
    fun lineGap(spacing: Double, pointSize: Double, scale: Double) =
        NativeChiefBubbleGeometry.space(16.0, spacing, scale) - alignmentInsets(pointSize).top
    fun followingGap(spacing: Double, pointSize: Double, scale: Double) =
        NativeChiefBubbleGeometry.space(10.0, spacing, scale) - alignmentInsets(pointSize).bottom
    fun headerGap(spacing: Double, pointSize: Double, scale: Double): Double {
        val insets = alignmentInsets(pointSize)
        return NativeChiefBubbleGeometry.space(20.0, spacing, scale) - insets.top - insets.bottom
    }
    data class Sender(val height: Double, val bubbleOffset: Double)
    fun sender(lineHeight: Double, spacing: Double, scale: Double): Sender {
        val height = NativeChiefBubbleGeometry.ceilToPixel(lineHeight, scale)
        return Sender(height, height + NativeChiefBubbleGeometry.space(4.0, spacing, scale))
    }
    data class Timestamp(val rowHeight: Double, val labelHeight: Double, val lineTop: Double, val baselineOffset: Double)
    fun timestamp(lineHeight: Double, ascender: Double, spacing: Double, pointSize: Double, scale: Double): Timestamp {
        val labelHeight = max(18.0, ceil(lineHeight))
        val top = 10 + (labelHeight - ceil(lineHeight * 3) / 3) / 2
        val height = top + NativeChiefBubbleGeometry.ceilToPixel(lineHeight, scale) + followingGap(spacing, pointSize, scale) - 4
        return Timestamp(height, labelHeight, top, 10 + (labelHeight - lineHeight) / 2 + ascender)
    }
}
