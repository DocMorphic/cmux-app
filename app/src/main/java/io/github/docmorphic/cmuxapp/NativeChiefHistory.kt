package io.github.docmorphic.cmuxapp

/** ConversationHistoryPrefetch's pixel thresholds and ConversationStore's retry cadence. */
internal object NativeChiefHistory {
    enum class Decision { LOAD, STOP, NONE }
    fun decision(distanceFromTop: Float, viewport: Float) = when {
        distanceFromTop < viewport * 4 -> Decision.LOAD
        distanceFromTop > viewport * 6 -> Decision.STOP
        else -> Decision.NONE
    }
    fun backoff(attempt: Int): Long = minOf(8000L, 500L * (1L shl minOf(attempt.coerceAtLeast(0), 4)))
}

/** Exact offsets for the measured top region; unknown later rows are known to be beyond six screens. */
internal data class NativeChiefHistoryGeometry(val offsets: List<Int>, val measuredHeight: Int, val width: Int, val viewport: Int) {
    fun distance(index: Int, scrollOffset: Int): Float? = when {
        index in offsets.indices -> (offsets[index] + scrollOffset).toFloat()
        index >= offsets.size && measuredHeight > viewport * 6 -> measuredHeight.toFloat()
        else -> null
    }
}
