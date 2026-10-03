package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.widget.ImageView

internal class LegacySimulatorImageView(context: Context) : ImageView(context) {
    private val gesture = LegacySimulatorGesture(6 * resources.displayMetrics.density)
    private var frame: LegacySimulatorPresentation<Bitmap>? = null
    private var attachment: Any? = null
    private var enabledInput = false
    private var send: (LegacySimulatorInput) -> Boolean = { false }
    init {
        scaleType = ScaleType.FIT_CENTER
        setBackgroundColor(0xFF0E1013.toInt())
        contentDescription = "Simulator display"
    }
    private fun rect() = SimVideoRect.fit(frame?.frame?.width?.toLong() ?: 0, frame?.frame?.height?.toLong() ?: 0, width, height)
    fun update(value: LegacySimulatorPresentation<Bitmap>?, owner: Any?, enabled: Boolean, onInput: (LegacySimulatorInput) -> Boolean) {
        if (attachment !== owner || !enabled) gesture.discard()
        else if (frame?.frame?.width != value?.frame?.width || frame?.frame?.height != value?.frame?.height) {
            if (enabledInput) deliver(gesture.cancel(rect())) else gesture.discard()
        }
        attachment = owner; enabledInput = enabled; send = onInput
        if (frame?.image !== value?.image) setImageBitmap(value?.image)
        frame = value
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (enabledInput) deliver(gesture.cancel(SimVideoRect.fit(frame?.frame?.width?.toLong() ?: 0,
            frame?.frame?.height?.toLong() ?: 0, oldw, oldh))) else gesture.discard()
        super.onSizeChanged(w, h, oldw, oldh)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledInput || frame == null) { gesture.discard(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gesture.begin(event.getPointerId(0), event.x, event.y)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount)
                deliver(gesture.move(event.getPointerId(i), event.getX(i), event.getY(i), rect()))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                deliver(gesture.end(event.getPointerId(i), event.getX(i), event.getY(i), rect()))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> deliver(gesture.cancel(rect()))
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun deliver(values: List<LegacySimulatorInput>) {
        for (value in values) if (!send(value)) { gesture.discard(); break }
    }
    fun release() { gesture.discard(); enabledInput = false; attachment = null; send = { false }; frame = null; setImageDrawable(null) }
}
