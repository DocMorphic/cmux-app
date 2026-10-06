package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal data class TerminalToolbarScrollSample(val offset: Int, val maximum: Int, val viewport: Int, val interacting: Boolean)

/** Preserve resting edges across geometry changes, without taking ownership from touch/fling. */
internal class TerminalToolbarScrollAnchor {
    private var previous: TerminalToolbarScrollSample? = null
    private var deferred = false

    fun observe(current: TerminalToolbarScrollSample, edgeTolerance: Int = 1): Int? {
        // Compose uses MAX_VALUE before its first measurement. Restored offsets belong to ScrollState.
        if (current.maximum < 0 || current.maximum == Int.MAX_VALUE || current.viewport <= 0) return null
        val before = previous
        previous = current
        if (before == null) return null
        val geometryChanged = before.maximum != current.maximum || before.viewport != current.viewport
        if (geometryChanged && (before.interacting || current.interacting)) deferred = true
        if (current.interacting || (!geometryChanged && !deferred)) return null
        val tolerance = edgeTolerance.coerceAtLeast(0)
        val target = if (deferred) {
            // The gesture's final position wins; do not snap back to an edge it left behind.
            deferred = false
            current.offset.coerceIn(0, current.maximum)
        } else if (current.offset != before.offset.coerceIn(0, current.maximum)) {
            // A focus/reader move and measurement can coalesce into one snapshot. Keep that move.
            current.offset.coerceIn(0, current.maximum)
        } else when {
            before.offset <= tolerance -> 0
            before.maximum.toLong() - before.offset <= tolerance -> current.maximum
            else -> before.offset.coerceIn(0, current.maximum)
        }
        return target.takeIf { abs(it.toLong() - current.offset) > 0 }
    }
}

@Composable
internal fun rememberTerminalToolbarScrollState(contact: State<Boolean>): ScrollState {
    val scroll = rememberScrollState()
    val tolerance by rememberUpdatedState(with(LocalDensity.current) { 1.dp.roundToPx() })
    LaunchedEffect(scroll, contact) {
        val anchor = TerminalToolbarScrollAnchor()
        snapshotFlow { TerminalToolbarScrollSample(scroll.value, scroll.maxValue, scroll.viewportSize,
            contact.value || scroll.isScrollInProgress) }.collect { sample ->
            val target = anchor.observe(sample, tolerance)
            // Check again immediately before acquiring ScrollState's mutation lock.
            if (target != null && !contact.value && !scroll.isScrollInProgress &&
                scroll.maxValue == sample.maximum && scroll.viewportSize == sample.viewport && scroll.value == sample.offset) {
                scroll.scrollTo(target)
            }
        }
    }
    return scroll
}
