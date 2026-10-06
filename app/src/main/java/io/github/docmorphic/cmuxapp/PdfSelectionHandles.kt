package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private data class PdfSelectionDrag(val anchor: Boolean, val fixed: PdfTextPosition, val point: Offset)

/** Overlay siblings keep handle drags out of the page's pinch/scroll recognizers. */
@Composable
internal fun PdfSelectionHandles(state: PdfSelectionState, pdf: ChangesPdfDocument, scroll: LazyListState,
    zoom: PreviewZoomTransform, width: Float, height: Float, documentWidth: Int, inset: (Int) -> Float) {
    var drag by remember(state) { mutableStateOf<PdfSelectionDrag?>(null) }
    val density = LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }
    val factor = width / documentWidth * zoom.scale
    fun caretPoint(caret: PdfTextCaret?): Offset? {
        caret ?: return null
        val item = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.index == caret.page } ?: return null
        return Offset(width / 2 + (caret.x - pdf.pageSizes[caret.page].first / 2f) * factor + zoom.x * width,
            item.offset + inset(caret.page) * width + caret.y * factor)
    }
    fun move(value: PdfSelectionDrag) {
        if (factor <= 0f || !factor.isFinite()) return
        val items = scroll.layoutInfo.visibleItemsInfo
        val y = value.point.y.coerceIn(0f, height)
        val item = items.minByOrNull { when {
            y < it.offset -> it.offset - y
            y > it.offset + it.size -> y - it.offset - it.size
            else -> 0f
        } } ?: return
        val size = pdf.pageSizes[item.index]
        val x = size.first / 2f + (value.point.x - width / 2 - zoom.x * width) / factor
        val pageY = (y - item.offset - inset(item.index) * width) / factor
        state.move(value.anchor, value.fixed, item.index, x.coerceIn(0f, size.first.toFloat()), pageY.coerceIn(0f, size.second.toFloat()))
    }
    val latestMove by rememberUpdatedState<(PdfSelectionDrag) -> Unit>(::move)
    LaunchedEffect(state.range == null) { if (state.range == null) drag = null }
    LaunchedEffect(drag != null, height, edge) {
        if (drag == null || height <= 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f); previous = now
            val moving = drag ?: break
            val fraction = when {
                moving.point.y < edge -> -((edge - moving.point.y) / edge).coerceIn(0f, 1f)
                moving.point.y > height - edge -> ((moving.point.y - height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (fraction != 0f && scroll.scrollBy(fraction * edge * 9 * seconds) != 0f) latestMove(moving)
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        for (anchor in listOf(true, false)) key(anchor) {
            val caret = if (anchor) state.anchorCaret else state.focusCaret
            val moving = drag?.takeIf { it.anchor == anchor }
            val point = moving?.point ?: caretPoint(caret)
            if (point != null && (moving != null || point.x in 0f..width && point.y in 0f..height)) {
                val shown = Offset(point.x.coerceIn(0f, width), point.y.coerceIn(0f, (height - edge).coerceAtLeast(0f)))
                val leading = state.range?.let { if (anchor) it.anchor <= it.focus else it.focus < it.anchor } ?: anchor
                PdfSelectionHandle(if (leading) "PDF selection start" else "PDF selection end", shown,
                    onStart = {
                        val range = state.range
                        if (range != null) drag = PdfSelectionDrag(anchor, if (anchor) range.focus else range.anchor, point)
                    }, onDrag = { delta -> drag?.let { old ->
                        val next = old.copy(point = old.point + delta); drag = next; move(next)
                    } }, onEnd = { drag = null },
                    onAdjust = { forward -> state.adjust(anchor, forward) })
            }
        }
    }
}

@Composable
private fun PdfSelectionHandle(label: String, point: Offset, onStart: () -> Unit,
    onDrag: (Offset) -> Unit, onEnd: () -> Unit, onAdjust: (Boolean) -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val halfWidth = with(LocalDensity.current) { 24.dp.toPx() }
    val start by rememberUpdatedState(onStart); val move by rememberUpdatedState(onDrag); val end by rememberUpdatedState(onEnd)
    Canvas(Modifier.offset { IntOffset((point.x - halfWidth).roundToInt(), point.y.roundToInt()) }.size(48.dp)
        .semantics {
            contentDescription = label
            customActions = listOf(CustomAccessibilityAction("Move backward") { onAdjust(false); true },
                CustomAccessibilityAction("Move forward") { onAdjust(true); true })
        }.pointerInput(Unit) {
            detectDragGestures(onDragStart = { start() }, onDragEnd = { end() }, onDragCancel = { end() }) { change, delta ->
                change.consume(); move(delta)
            }
        }) {
        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, 16.dp.toPx()), 2.dp.toPx())
        drawCircle(color, 7.dp.toPx(), Offset(size.width / 2, 18.dp.toPx()))
    }
}
