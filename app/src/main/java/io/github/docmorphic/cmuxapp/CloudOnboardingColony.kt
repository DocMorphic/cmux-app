package io.github.docmorphic.cmuxapp

import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Port of the pinned CloudGameOfLifeGrid's toroidal S23/B3 colony and reseed rules. */
internal class CloudOnboardingColony(val columns: Int = 36, val rows: Int = 52,
    private val random: () -> Double = { Random.Default.nextDouble() }, initial: BooleanArray? = null) {
    var cells: BooleanArray = BooleanArray(0); private set
    var stepCount = 0; private set
    var advances = 0L; private set
    var seedCount = 0L; private set
    init {
        require(columns > 0 && rows > 0 && columns <= 256 && rows <= 256)
        require(initial == null || initial.size == columns * rows)
        if (initial == null) seed() else cells = initial.copyOf()
    }
    private fun seed() {
        cells = BooleanArray(columns * rows) { random() < .22 }
        stepCount = 0; seedCount++
    }
    private fun alive(row: Int, col: Int) = cells[((row + rows) % rows) * columns + (col + columns) % columns]
    fun advance() {
        advances++
        val next = BooleanArray(cells.size); var count = 0
        for (row in 0 until rows) for (col in 0 until columns) {
            var neighbors = 0
            for (dr in -1..1) for (dc in -1..1) if ((dr != 0 || dc != 0) && alive(row + dr, col + dc)) neighbors++
            val index = row * columns + col
            val nextAlive = (cells[index] && (neighbors == 2 || neighbors == 3)) || (!cells[index] && neighbors == 3)
            next[index] = nextAlive
            if (nextAlive) count++
        }
        stepCount++
        if (count < max(6, cells.size / 22) || stepCount > 120) seed() else cells = next
    }
    companion object {
        const val TICK_MILLIS = 80L
        fun opacity(dark: Boolean, reducedMotion: Boolean, time: Double, row: Int, col: Int): Float {
            val base = if (dark) .10 else .16
            return (if (reducedMotion) base else max(0.0, base + .10 * sin(time * 2 + (row * 3 + col) * .22))).toFloat()
        }
    }
}
