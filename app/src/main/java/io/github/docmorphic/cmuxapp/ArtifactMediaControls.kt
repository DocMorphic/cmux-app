package io.github.docmorphic.cmuxapp

internal object ArtifactMediaControls {
    val speeds = listOf(.5f, 1f, 1.25f, 1.5f, 2f)
    fun speed(value: Float) = value.takeIf { it in speeds } ?: 1f
    fun seek(position: Int, delta: Int, duration: Int): Int =
        (position.toLong() + delta).coerceIn(0L, duration.coerceAtLeast(0).toLong()).toInt()
    fun time(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
        else "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
