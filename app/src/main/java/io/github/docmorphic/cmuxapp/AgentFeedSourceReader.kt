package io.github.docmorphic.cmuxapp

import android.content.Context
import android.text.Selection
import android.text.Spannable
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView

/** Save coordinates and selection only; the authenticated message is reloaded. */
internal class AgentFeedSourcePosition {
    var anchor = 0
    var fraction = 0f
    var selectionStart = -1
    var selectionEnd = -1
    var capture: (() -> Unit)? = null

    companion object {
        val Saver = listSaver<AgentFeedSourcePosition, Any>(save = {
            it.capture?.invoke()
            listOf(it.anchor, it.fraction, it.selectionStart, it.selectionEnd)
        }, restore = { values -> AgentFeedSourcePosition().apply {
            anchor = values[0] as Int; fraction = values[1] as Float
            selectionStart = values[2] as Int; selectionEnd = values[3] as Int
        } })
    }
}

/** Native measurement supports messages taller than Compose's packed constraints. */
internal class AgentFeedSourceScroll(context: Context, private val position: AgentFeedSourcePosition,
    source: String) : ScrollView(context) {
    val body: TextView = agentFeedQuoteTextView(context).apply {
        tag = "AgentFeedSourceBody"
        textSize = 16f
        setTextColor(0xFFE7E9ED.toInt())
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        movementMethod = null
        setTextIsSelectable(true)
        // TextView normally freezes selected text into the Activity's Bundle.
        // Multi-megabyte messages belong only in the authenticated load result.
        isSaveEnabled = false
        text = source
        NativeViewHaptics(this)
    }
    private var restoring = true
    private var released = false
    private val capture: () -> Unit = { capturePosition() }

    init {
        tag = "AgentFeedSourceScroll"
        isSaveEnabled = false
        isSmoothScrollingEnabled = false
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        position.capture = capture
    }

    private fun capturePosition() {
        if (restoring || released) return
        val layout = body.layout ?: return
        val y = (scrollY - body.paddingTop).coerceAtLeast(0)
        val line = layout.getLineForVertical(y)
        position.anchor = layout.getLineStart(line)
        position.fraction = if (scrollY == 0) 0f else
            (y - layout.getLineTop(line)).toFloat() / (layout.getLineBottom(line) - layout.getLineTop(line)).coerceAtLeast(1)
        position.selectionStart = body.selectionStart
        position.selectionEnd = body.selectionEnd
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!restoring) return
        val layout = body.layout ?: return
        val line = layout.getLineForOffset(position.anchor.coerceIn(0, body.text.length))
        if (position.selectionStart >= 0 && position.selectionEnd >= 0) {
            Selection.setSelection(body.text as Spannable, position.selectionStart.coerceAtMost(body.text.length),
                position.selectionEnd.coerceAtMost(body.text.length))
        }
        val top = layout.getLineTop(line)
        val y = if (position.anchor == 0 && position.fraction == 0f) 0 else body.paddingTop + top +
            ((layout.getLineBottom(line) - top) * position.fraction).toInt()
        scrollTo(0, y)
        restoring = false
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        capturePosition()
    }

    fun release() {
        capturePosition()
        released = true
        if (position.capture === capture) position.capture = null
    }
}

@Composable
internal fun AgentFeedSourceReader(source: String, position: AgentFeedSourcePosition) {
    AndroidView(factory = { AgentFeedSourceScroll(it, position, source) },
        modifier = Modifier.fillMaxSize().clipToBounds(), onRelease = { it.release() })
}
