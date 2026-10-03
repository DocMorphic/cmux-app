package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import org.json.JSONObject
import kotlin.math.abs

/** Pinned iOS sizes, expressed in Android's accessibility-scaled sp at the view boundary. */
internal object TerminalFontSize {
    const val DEFAULT = 10f
    const val MIN = 8f
    const val MAX = 28f
    const val SAVED_KEY = "terminal_zoom_default_v1"
    fun clamp(value: Float): Float? = value.takeIf { it.isFinite() }?.coerceIn(MIN, MAX)
}

/** One mounted terminal owns live sizing; reconnect and viewport changes retain this owner. */
internal class TerminalZoomState {
    var size by mutableFloatStateOf(TerminalFontSize.DEFAULT)
        private set
    var overlayVisible by mutableStateOf(false)
        private set
    var interaction by mutableIntStateOf(0)
        private set
    fun apply(value: Float): Boolean {
        val next = TerminalFontSize.clamp(value) ?: return false
        if (next == size) return false
        size = next
        return true
    }
    fun step(direction: Int): Boolean {
        val next = size + direction.coerceIn(-1, 1)
        if (next !in TerminalFontSize.MIN..TerminalFontSize.MAX || !apply(next)) return false
        show()
        return true
    }
    fun show() { overlayVisible = true; interaction++ }
    fun hide() { overlayVisible = false }
    fun reset(saved: Float?) { apply(saved ?: TerminalFontSize.DEFAULT); show() }
}

/** iOS's cumulative gesture-scale threshold: at most one point per accepted sample. */
internal class TerminalPinchSteps {
    private var scale = 1f
    private var accepted = 1f
    fun update(factor: Float, step: (Int) -> Boolean) {
        if (!factor.isFinite() || factor <= 0) return
        val next = scale * factor
        if (!next.isFinite()) return
        scale = next
        if (abs(scale - accepted) >= .15f && step(if (scale > accepted) 1 else -1)) accepted = scale
    }
}

/** Surface scope takes precedence over workspace; missing scope reaches the mounted view. */
internal data class TerminalSetFont(val size: Float, val surface: String?, val workspace: String?) {
    fun matches(workspaceId: String, surfaceId: String): Boolean = when {
        surface != null -> surface == surfaceId
        workspace != null -> workspace == workspaceId
        else -> true
    }
    companion object {
        fun decode(payload: JSONObject): TerminalSetFont? {
            val raw = payload.opt("font_size") as? Number ?: return null
            val size = raw.toDouble().takeIf { it.isFinite() } ?: return null
            // Match the typed Swift decoder; a number/bool scope is not an absent scope.
            for (key in listOf("surface_id", "workspace_id")) {
                if (payload.has(key) && !payload.isNull(key) && payload.opt(key) !is String) return null
            }
            return TerminalSetFont(size.coerceIn(TerminalFontSize.MIN.toDouble(), TerminalFontSize.MAX.toDouble()).toFloat(),
                payload.opt("surface_id") as? String, payload.opt("workspace_id") as? String)
        }
    }
}
