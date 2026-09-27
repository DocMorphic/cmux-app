package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

@Stable
internal class BrowserLens {
    var zoom by mutableDoubleStateOf(1.0); private set
    var x by mutableDoubleStateOf(0.0); private set
    var y by mutableDoubleStateOf(0.0); private set
    fun zoomTo(value: Double) {
        if (!value.isFinite()) return
        zoom = value.coerceIn(1.0, 4.0)
        if (zoom <= 1.001) { zoom = 1.0; x = 0.0; y = 0.0 }
    }
    fun pan(delta: Offset) {
        if (zoom > 1 && delta.x.isFinite() && delta.y.isFinite()) { x -= delta.x; y -= delta.y }
    }
    fun transform(size: IntSize, pageWidth: Double, pageHeight: Double) = BrowserPageTransform(
        size.width.toDouble(), size.height.toDouble(), pageWidth, pageHeight, zoom, x, y)
}

@Composable
internal fun BrowserPageSurface(frame: BrowserFrame, queue: BrowserInputQueue, motion: BrowserScrollMotion,
    generation: Long, enabled: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val lens = remember(queue) { BrowserLens() }
    var measured by remember(queue) { mutableStateOf(IntSize.Zero) }
    Canvas(modifier.fillMaxSize().clipToBounds().onSizeChanged { measured = it }
        .semantics { contentDescription = "Mac browser page" }
        .browserScrollGestures(motion, frame.pageWidth, frame.pageHeight, measured, generation, enabled, lens) { point, clicks ->
            onTap(); queue.offer(BrowserInput.Click(point.x, point.y, clicks))
        }) {
        val rect = lens.transform(IntSize(size.width.toInt(), size.height.toInt()), frame.pageWidth, frame.pageHeight).rect
        if (rect != null) withTransform({
            translate(rect.left.toFloat(), rect.top.toFloat())
            scale((rect.width / frame.image.width).toFloat(), (rect.height / frame.image.height).toFloat(), pivot = Offset.Zero)
        }) {
            drawImage(frame.image, dstSize = IntSize(frame.image.width, frame.image.height), filterQuality = FilterQuality.Low)
        }
    }
}

/** One recognizer owns tap, remote drag, pinch and local pan; multi-touch never becomes a click. */
@Composable
internal fun Modifier.browserScrollGestures(motion: BrowserScrollMotion, pageWidth: Double, pageHeight: Double,
    viewportSize: IntSize, generation: Long, enabled: Boolean, lens: BrowserLens? = null,
    onTap: (BrowserPoint, Int) -> Unit = { _, _ -> }): Modifier {
    val currentLens = lens ?: remember(motion) { BrowserLens() }
    val tapCounter = remember(motion) { BrowserTapCounter() }
    val density = LocalDensity.current.density
    val latestTap by rememberUpdatedState(onTap)
    DisposableEffect(motion, pageWidth, pageHeight, viewportSize, generation, enabled) { onDispose { motion.stop() } }
    return pointerInput(motion, pageWidth, pageHeight, viewportSize, generation, enabled, currentLens, density) {
        if (!enabled) return@pointerInput
        fun transform() = currentLens.transform(size, pageWidth, pageHeight)
        fun anchor(position: Offset) = transform().scrollAnchor(position.x.toDouble(), position.y.toDouble())
            .let { Offset(it.x.toFloat(), it.y.toFloat()) }
        fun pageDelta(delta: Offset) = transform().pageDelta(delta.x.toDouble(), delta.y.toDouble())
            .let { Offset(it.x.toFloat(), it.y.toFloat()) }
        try {
            awaitEachGesture {
                val down = awaitFirstDown()
                motion.stop()
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var accumulated = Offset.Zero
                var remoteDrag = false
                var localDrag = false
                var hadMultiple = false
                var transformed = false
                var pinchStartZoom = currentLens.zoom
                var pinchFactor = 1f
                var lastTime = down.uptimeMillis
                var lastPosition = down.position
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) { motion.stop(); break }
                    val primary = event.changes.firstOrNull { it.id == down.id }
                    if (primary != null) {
                        primary.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                        tracker.addPosition(primary.uptimeMillis, primary.position)
                        lastTime = primary.uptimeMillis; lastPosition = primary.position
                    }
                    val pressed = event.changes.count { it.pressed }
                    if (pressed > 1 && !hadMultiple) {
                        hadMultiple = true; motion.stop(); remoteDrag = false
                        pinchStartZoom = currentLens.zoom; accumulated = Offset.Zero
                    }
                    val delta = event.calculatePan()
                    accumulated += delta
                    if (hadMultiple) {
                        pinchFactor *= event.calculateZoom()
                        val zoomDistance = abs(1 - pinchFactor) * event.calculateCentroidSize(useCurrent = false)
                        if (zoomDistance > viewConfiguration.touchSlop || accumulated.getDistance() > viewConfiguration.touchSlop) transformed = true
                        if (transformed) {
                            currentLens.zoomTo(pinchStartZoom * pinchFactor)
                            currentLens.pan(delta)
                            event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                        }
                    } else if (pressed > 0) {
                        if (!remoteDrag && !localDrag && accumulated.getDistance() > viewConfiguration.touchSlop) {
                            if (currentLens.zoom > 1) localDrag = true
                            else { remoteDrag = true; motion.begin(anchor(lastPosition)) }
                            val overSlop = accumulated * (1 - viewConfiguration.touchSlop / accumulated.getDistance())
                            if (localDrag) currentLens.pan(overSlop) else motion.drag(pageDelta(overSlop), anchor(lastPosition))
                        } else if (localDrag) currentLens.pan(delta)
                        else if (remoteDrag) motion.drag(pageDelta(delta), anchor(lastPosition))
                        if (remoteDrag || localDrag) event.changes.filter { it.positionChanged() }.forEach { it.consume() }
                    }
                    if (pressed == 0) {
                        when {
                            remoteDrag -> motion.end(tracker.calculateVelocity().let { Offset(it.x, it.y) }, pageDelta(Offset(1f, 1f)))
                            !hadMultiple && !localDrag -> transform().pagePoint(lastPosition.x.toDouble(), lastPosition.y.toDouble())?.let { pagePoint ->
                                latestTap(pagePoint, tapCounter.register(BrowserPoint(lastPosition.x.toDouble() / density,
                                    lastPosition.y.toDouble() / density), lastTime))
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        } finally { motion.stop() }
    }
}
