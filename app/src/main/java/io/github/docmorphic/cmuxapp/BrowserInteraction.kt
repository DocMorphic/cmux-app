package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** Fields defined by CMUXMobileCore's panel descriptor and browser.state event. */
internal data class BrowserPageState(
    val url: String = "", val title: String = "", val canGoBack: Boolean = false,
    val canGoForward: Boolean = false, val loading: Boolean = false, val progress: Float = 1f,
    val editableFocused: Boolean = false
) {
    companion object {
        fun read(value: JSONObject) = BrowserPageState(
            value.opt("url") as? String ?: "", value.opt("title") as? String ?: "",
            value.optBoolean("can_go_back"), value.optBoolean("can_go_forward"), value.optBoolean("is_loading"),
            value.optDouble("progress", if (value.optBoolean("is_loading")) 0.0 else 1.0)
                .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f,
            value.optBoolean("editable_focused"))
    }
}

/** Mirrors the iOS manual-hide override until the page changes editable focus. */
internal data class BrowserKeyboardPolicy(val editable: Boolean = false, val manual: Boolean = false, val dismissed: Boolean = false) {
    val focus get() = !dismissed && (editable || manual)
    fun pageFocus(value: Boolean) = if (value == editable) this else copy(editable = value, dismissed = false, manual = manual && !value)
    fun toggle() = if (focus) copy(manual = false, dismissed = true) else copy(manual = true, dismissed = false)
    fun show() = copy(manual = true, dismissed = false)
    fun hide() = if (focus) copy(manual = false, dismissed = true) else this
}

internal sealed interface BrowserInput {
    fun parameters(panel: String): JSONObject
    val method: String
    data class Text(val text: String) : BrowserInput {
        override val method = "mobile.browser.input.text"
        override fun parameters(panel: String) = JSONObject().put("panel_id", panel).put("text", text)
    }
    data class Key(val key: String, val modifiers: List<String> = emptyList()) : BrowserInput {
        override val method = "mobile.browser.input.key"
        override fun parameters(panel: String) = JSONObject().put("panel_id", panel).put("key", key).put("modifiers", JSONArray(modifiers))
    }
    data class Click(val x: Double, val y: Double) : BrowserInput {
        override val method = "mobile.browser.input.pointer"
        override fun parameters(panel: String) = JSONObject().put("panel_id", panel).put("kind", "click")
            .put("x", x).put("y", y).put("click_count", 1).put("button", "left")
    }
    data class Scroll(val dx: Double, val dy: Double, val x: Double, val y: Double, val phase: String) : BrowserInput {
        override val method = "mobile.browser.input.scroll"
        override fun parameters(panel: String) = JSONObject().put("panel_id", panel).put("dx", dx).put("dy", dy)
            .put("x", x).put("y", y).put("phase", phase)
        fun merge(next: Scroll): Scroll? = if ((next.phase == "changed" && phase in setOf("began", "changed")) ||
            (next.phase == "momentum_changed" && phase in setOf("momentum_began", "momentum_changed")))
            copy(dx = dx + next.dx, dy = dy + next.dy, x = next.x, y = next.y) else null
    }
    data class Navigation(val command: String, val url: String? = null) : BrowserInput {
        init { require(command in setOf("back", "forward", "reload", "navigate")) }
        override val method = "mobile.browser.$command"
        override fun parameters(panel: String) = JSONObject().put("panel_id", panel).also {
            if (command == "navigate") it.put("url", checkNotNull(url).trim())
        }
    }
    companion object {
        // iOS commits each line separately, with a native Return between lines.
        fun committed(text: String): List<BrowserInput> = buildList {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            lines.forEachIndexed { i, line ->
                if (line.isNotEmpty()) add(Text(line))
                if (i < lines.lastIndex) add(Key("return"))
            }
        }
    }
}
