package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/** Leave ordinary vertical scrolling with LazyColumn; claim pinch and magnified horizontal pan. */
@Composable
internal fun Modifier.pdfPanZoomGestures(scale: Float, onTransform: (Float, Offset, Offset) -> Unit): Modifier {
    val currentScale by rememberUpdatedState(scale)
    val transform by rememberUpdatedState(onTransform)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val nonTouchTransform = rememberTransformableState { zoom, pan, _ ->
        transform(zoom, pan, Offset(size.width / 2f, size.height / 2f))
    }
    return onSizeChanged { size = it }
        .transformable(nonTouchTransform, canPan = { currentScale > 1.01f && abs(it.x) > abs(it.y) })
        .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var panTotal = Offset.Zero
            var zoomTotal = 1f
            var claimed = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.isConsumed }) break
                val pan = event.calculatePan()
                val zoom = event.calculateZoom()
                panTotal += pan; zoomTotal *= zoom
                val multiple = event.changes.count { it.pressed } > 1
                val zoomDistance = abs(zoomTotal - 1f) * event.calculateCentroidSize(useCurrent = false)
                if (!claimed) claimed = (multiple && (zoomDistance > viewConfiguration.touchSlop || panTotal.getDistance() > viewConfiguration.touchSlop)) ||
                    (currentScale > 1.01f && abs(panTotal.x) > viewConfiguration.touchSlop && abs(panTotal.x) > abs(panTotal.y))
                if (claimed) {
                    transform(zoom, pan, event.calculateCentroid(useCurrent = false))
                    event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
