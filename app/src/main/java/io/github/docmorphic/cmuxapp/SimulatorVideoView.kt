package io.github.docmorphic.cmuxapp

import android.content.Context
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

internal data class SimVideoRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun point(x: Float, y: Float, clamp: Boolean): Pair<Float, Float>? {
        if (!x.isFinite() || !y.isFinite() || width <= 0 || height <= 0) return null
        val nx = (x - left) / width; val ny = (y - top) / height
        if (!clamp && (nx < 0 || nx >= 1 || ny < 0 || ny >= 1)) return null
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }
    companion object {
        fun fit(pixelsWide: Long, pixelsHigh: Long, viewWidth: Int, viewHeight: Int): SimVideoRect {
            if (pixelsWide <= 0 || pixelsHigh <= 0 || viewWidth <= 0 || viewHeight <= 0) return SimVideoRect(0f, 0f, 0f, 0f)
            val scale = min(viewWidth.toDouble() / pixelsWide, viewHeight.toDouble() / pixelsHigh)
            val width = (pixelsWide * scale).toFloat(); val height = (pixelsHigh * scale).toFloat()
            return SimVideoRect((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
        }
    }
}

/** One host pointer (ID 0), matching iOS. Secondary fingers never become surprise replacement gestures. */
internal class SimTouchTracker {
    private var pointer: Int? = null
    private var last = 0f to 0f
    fun begin(id: Int, x: Float, y: Float, rect: SimVideoRect, micros: ULong): SimInput.Touch? {
        if (pointer != null) return null
        val point = rect.point(x, y, false) ?: return null
        pointer = id; last = point
        return event(SimTouchPhase.BEGAN, point, micros)
    }
    fun move(id: Int, x: Float, y: Float, rect: SimVideoRect, micros: ULong): SimInput.Touch? {
        if (pointer != id) return null
        val point = rect.point(x, y, true) ?: return cancel(micros)
        last = point
        return event(SimTouchPhase.MOVED, point, micros)
    }
    fun end(id: Int, x: Float, y: Float, rect: SimVideoRect, micros: ULong): SimInput.Touch? {
        if (pointer != id) return null
        val point = rect.point(x, y, true) ?: last
        pointer = null
        return event(SimTouchPhase.ENDED, point, micros)
    }
    fun cancel(micros: ULong): SimInput.Touch? {
        if (pointer == null) return null
        pointer = null
        return event(SimTouchPhase.CANCELLED, last, micros)
    }
    fun discard() { pointer = null }
    private fun event(phase: SimTouchPhase, point: Pair<Float, Float>, micros: ULong) =
        SimInput.Touch(phase, 0, point.first, point.second, micros)
}

/** Surface lifetime belongs to this view, decoder lifetime to its per-Surface binding. */
internal class SimulatorVideoView(context: Context,
    private val surfaceCreated: (Surface) -> Unit, private val surfaceDestroyed: () -> Unit) : FrameLayout(context) {
    private val video = SurfaceView(context)
    private val touch = SimTouchTracker()
    private var pixelsWide = 0L
    private var pixelsHigh = 0L
    private var orientation = SimOrientation.PORTRAIT
    private var attachment = 0uL
    private var inputEnabled = false
    private var send: (SimInput) -> Boolean = { false }
    private var released = false

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        contentDescription = "Simulator display"
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { if (!released) surfaceCreated(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { touch.discard(); surfaceDestroyed() }
        })
    }

    fun update(state: SimViewerState, enabled: Boolean, send: (SimInput) -> Boolean) {
        if (attachment != state.attachment || !enabled) touch.discard()
        if (pixelsWide != state.width || pixelsHigh != state.height || orientation != state.orientation) {
            // Rotation/config changes must not carry a half gesture into another coordinate space.
            touch.cancel((android.os.SystemClock.uptimeMillis() * 1000).toULong())?.let { if (inputEnabled) this.send(it) }
            pixelsWide = state.width; pixelsHigh = state.height; orientation = state.orientation
            requestLayout()
        }
        attachment = state.attachment; inputEnabled = enabled; this.send = send
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rect = SimVideoRect.fit(pixelsWide, pixelsHigh, width, height)
        if (rect.width <= 0 || rect.height <= 0) video.layout(0, 0, width, height)
        else video.layout(rect.left.roundToInt(), rect.top.roundToInt(),
            (rect.left + rect.width).roundToInt(), (rect.top + rect.height).roundToInt())
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) { touch.discard(); return true }
        val rect = SimVideoRect.fit(pixelsWide, pixelsHigh, width, height)
        val micros = (event.eventTime * 1000).toULong()
        fun deliver(value: SimInput.Touch?) { if (value != null && !send(value)) touch.discard() }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> deliver(touch.begin(event.getPointerId(0), event.x, event.y, rect, micros))
            MotionEvent.ACTION_MOVE -> for (index in 0 until event.pointerCount)
                deliver(touch.move(event.getPointerId(index), event.getX(index), event.getY(index), rect, micros))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.actionIndex.let { index ->
                deliver(touch.end(event.getPointerId(index), event.getX(index), event.getY(index), rect, micros))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> deliver(touch.cancel(micros))
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    fun release() {
        if (released) return
        released = true; touch.discard(); inputEnabled = false; send = { false }
        surfaceDestroyed()
    }
}
