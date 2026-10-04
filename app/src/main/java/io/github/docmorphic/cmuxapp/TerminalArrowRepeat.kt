package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

internal enum class TerminalArrowDirection(val button: TerminalToolbarButton) {
    UP(TerminalToolbarButton.UP), DOWN(TerminalToolbarButton.DOWN),
    LEFT(TerminalToolbarButton.LEFT), RIGHT(TerminalToolbarButton.RIGHT);

    companion object {
        // iOS uses an 8-point radial dead zone, with vertical preference on diagonals.
        fun fromDrag(x: Float, y: Float): TerminalArrowDirection? = when {
            !x.isFinite() || !y.isFinite() || hypot(x, y) <= 8f -> null
            abs(x) > abs(y) -> if (x > 0) RIGHT else LEFT
            else -> if (y > 0) DOWN else UP
        }
    }
}

/** Main-thread gesture owner. Each new direction fires now and then every 80ms. */
internal class TerminalArrowRepeat(private val scope: CoroutineScope,
    private val allowed: () -> Boolean, private val emit: (TerminalArrowDirection) -> Unit) {
    private var direction: TerminalArrowDirection? = null
    private var job: Job? = null
    private var closed = false

    fun move(next: TerminalArrowDirection?) {
        if (closed || !allowed()) { stop(); return }
        if (next == direction) return
        stop()
        if (next == null) return
        direction = next
        job = scope.launch {
            while (isActive) {
                delay(80)
                if (!allowed()) { stop(); break }
                emit(next)
            }
        }
        emit(next)
    }

    fun stop() { direction = null; job?.cancel(); job = null }
    fun close() { closed = true; stop() }
}
