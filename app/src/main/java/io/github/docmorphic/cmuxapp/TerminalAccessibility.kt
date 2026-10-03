package io.github.docmorphic.cmuxapp

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*

/** Accessibility uses a top-origin pixel axis; terminal history counts from live bottom. */
internal data class TerminalAccessibilityScroll(val local: Boolean, val historyRows: Int, val position: Double,
    val reveal: Float, val maximumReveal: Float, val cellHeight: Float, val visibleHeight: Int) {
    private val valid get() = cellHeight.isFinite() && cellHeight > 0 && position.isFinite() &&
        reveal.isFinite() && maximumReveal.isFinite()
    val maximum get() = if (valid) historyRows.coerceAtLeast(0) * cellHeight + maximumReveal.coerceAtLeast(0f) else 0f
    val value get() = if (valid) (maximum - position.coerceIn(0.0, historyRows.coerceAtLeast(0).toDouble()) * cellHeight -
        reveal.coerceIn(0f, maximumReveal.coerceAtLeast(0f))).toFloat().coerceIn(0f, maximum) else 0f
    val older get() = valid && (!local || value > 0f)
    val newer get() = valid && (!local || value < maximum)
    val pageRows get() = if (valid) (visibleHeight / cellHeight - 1).coerceIn(1f, 1000f).toDouble() else 0.0
    fun rowsForPixels(y: Float) = if (valid && y.isFinite()) -y.toDouble() / cellHeight else 0.0
    val latestRows get() = if (valid && local) -(maximum - value).toDouble() / cellHeight else 0.0
}

internal fun Modifier.nativeTerminalAccessibility(fontSize: Float, scroll: TerminalAccessibilityScroll,
    enabled: Boolean, openKeyboard: () -> Unit, openText: () -> Unit,
    latest: (() -> Boolean)? = null, move: (Double) -> Boolean): Modifier =
    semantics(mergeDescendants = true) {
        stateDescription = "Terminal font size $fontSize"
        onClick("Open keyboard") { openKeyboard(); true }
        customActions = buildList {
            add(CustomAccessibilityAction("View as Text") { openText(); true })
            if (enabled) {
                if (scroll.older) add(CustomAccessibilityAction(if (scroll.local) "Older output" else "Scroll up") { move(scroll.pageRows) })
                if (scroll.newer) add(CustomAccessibilityAction(if (scroll.local) "Newer output" else "Scroll down") { move(-scroll.pageRows) })
                if (scroll.local && scroll.newer) add(CustomAccessibilityAction("Latest output") { latest?.invoke() ?: move(scroll.latestRows) })
            }
        }
        if (enabled && scroll.local && scroll.maximum > 0) {
            verticalScrollAxisRange = ScrollAxisRange({ scroll.value }, { scroll.maximum }, reverseScrolling = false)
            scrollBy { _, y -> scroll.rowsForPixels(y).let { if (it == 0.0) false else move(it) } }
        }
    }
