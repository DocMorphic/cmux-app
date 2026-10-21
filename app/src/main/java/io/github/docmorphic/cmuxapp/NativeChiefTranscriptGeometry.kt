package io.github.docmorphic.cmuxapp

/** ConversationViewController.updateInsets and ConversationTopEdgeGeometry, plain Chief branch. */
internal object NativeChiefTranscriptGeometry {
    fun topInset(headerBottom: Double, compact: Boolean) = headerBottom + if (compact) 0.0 else 10.0 / 3
    // The source layout adds six points after the last row and subtracts its tail overhang.
    // Combine that with the source bottom inset and the field's four-point container inset.
    fun bottomPadding(composerHeight: Double, trailingOverhang: Double) =
        (composerHeight + 16.71 - 4 - trailingOverhang).coerceAtLeast(0.0)
    data class Stop(val location: Double, val alpha: Double)
    private val lightStops = listOf(-74.0 to .855, -49.0 to .78, -34.0 to .66, -19.0 to .47,
        -4.0 to .26, 6.0 to .17, 16.0 to .08, 26.0 to .04, 41.0 to .01, 46.0 to 0.0)
    fun wash(height: Double, headerBottom: Double, dark: Boolean): List<Stop> {
        if (height <= 0) return emptyList()
        val scale = if (dark) .60 / .855 else 1.0
        val result = mutableListOf(Stop(0.0, lightStops.first().second * scale))
        lightStops.forEach { (offset, alpha) -> result += Stop(((headerBottom + offset).coerceAtLeast(0.0) / height).coerceAtMost(1.0), alpha * scale) }
        if (result.last().location < 1) result += Stop(1.0, 0.0)
        return result
    }
}
