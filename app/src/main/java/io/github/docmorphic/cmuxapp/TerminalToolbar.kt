package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stable Android IDs; the ordering and actions follow the pinned iOS accessory configuration. */
internal enum class TerminalToolbarButton(val label: String, val key: String? = null,
    val modifier: TerminalInputModifiers.Key? = null) {
    CONTROL("Ctrl", modifier = TerminalInputModifiers.Key.CONTROL),
    ALT("Alt", modifier = TerminalInputModifiers.Key.ALT),
    COMMAND("Cmd", modifier = TerminalInputModifiers.Key.COMMAND),
    SHIFT("Shift", modifier = TerminalInputModifiers.Key.SHIFT),
    PASTE("Paste"), TAB("Tab", "Tab"), ESCAPE("Esc", "Esc"), RETURN("↵", "Enter"),
    CTRL_C("^C", "CtrlC"), CTRL_D("^D", "CtrlD"),
    CLAUDE("Claude", "claude --dangerously-skip-permissions\r"),
    CODEX("Codex", "codex --yolo -c model_reasoning_effort=xhigh --search\r"),
    OLLAMA("Ollama", "ollama run "),
    UP("↑", "Up"), DOWN("↓", "Down"), LEFT("←", "Left"), RIGHT("→", "Right"),
    CLEAR("^L", "CtrlL"), TILDE("~", "~"), DOLLAR("$", "$"), SLASH("/", "/"),
    AT("@", "@"), PIPE("|", "|"), CTRL_Z("^Z", "CtrlZ"),
    HOME("Home", "Home"), END("End", "End"), PAGE_UP("Pg↑", "PageUp"), PAGE_DOWN("Pg↓", "PageDown"),
    FILES("Files"), ZOOM_OUT("A−"), ZOOM_IN("A+"),
    // Android keeps its existing explicit deletion controls available too.
    BACKSPACE("⌫", "Backspace"), DELETE("⌦", "Delete");

    val id get() = "builtin.$name"
    val description get() = when (this) {
        RETURN -> "Return"; UP -> "Up Arrow"; DOWN -> "Down Arrow"; LEFT -> "Left Arrow"; RIGHT -> "Right Arrow"
        PAGE_UP -> "Page Up"; PAGE_DOWN -> "Page Down"; ZOOM_OUT -> "Zoom Out"; ZOOM_IN -> "Zoom In"
        BACKSPACE -> "Backspace"; DELETE -> "Delete"; CLEAR -> "Clear (Control-L)"
        else -> label
    }
}

internal data class TerminalToolbarAction(val id: String = UUID.randomUUID().toString(), val title: String, val text: String) {
    val itemId get() = "custom.$id"
    // Matches CustomToolbarAction.output, including existing literal CRs.
    val output get() = text.replace('\n', '\r')
    fun validate() {
        require(UUID.fromString(id).toString() == id)
        require(title.isNotBlank() && title.length <= 128 && title.none(Char::isISOControl))
        require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
    }
}

internal data class TerminalToolbarLayout(val order: List<String>, val enabled: Set<String>,
    val actions: List<TerminalToolbarAction>) {
    val visible get() = order.filter { it in enabled }
    fun button(id: String) = TerminalToolbarButton.entries.firstOrNull { it.id == id }
    fun action(id: String) = actions.firstOrNull { it.itemId == id }
    fun label(id: String) = button(id)?.description ?: action(id)?.title.orEmpty()
    fun toggle(id: String, shown: Boolean) = if (id !in order) this
        else copy(enabled = if (shown) enabled + id else enabled - id)
    /** Destination is the insertion boundary in the original list, as in SwiftUI onMove. */
    fun move(id: String, destination: Int): TerminalToolbarLayout {
        val from = order.indexOf(id)
        if (from < 0 || destination !in 0..order.size) return this
        val next = order.toMutableList().apply { removeAt(from); add(destination - if (from < destination) 1 else 0, id) }
        return copy(order = next)
    }
    fun save(action: TerminalToolbarAction): TerminalToolbarLayout {
        action.validate()
        if (actions.any { it.id == action.id }) return copy(actions = actions.map { if (it.id == action.id) action else it })
        require(actions.size < 128)
        return copy(actions = actions + action, order = order + action.itemId, enabled = enabled + action.itemId)
    }
    fun remove(id: String) = if (action(id) == null) this else copy(
        order = order - id, enabled = enabled - id, actions = actions.filter { it.itemId != id })
    fun reset() = defaults(actions)

    fun encode(): String = JSONObject().put("version", 1).put("order", JSONArray(order))
        .put("enabled", JSONArray(order.filter { it in enabled }))
        .put("actions", JSONArray(actions.map { JSONObject().put("id", it.id).put("title", it.title).put("text", it.text) })).toString()

    companion object {
        const val PREFERENCE = "terminal_toolbar_v1"
        fun defaults(actions: List<TerminalToolbarAction> = emptyList()): TerminalToolbarLayout {
            val order = TerminalToolbarButton.entries.map { it.id } + actions.map { it.itemId }
            return TerminalToolbarLayout(order, order.toSet() - TerminalToolbarButton.FILES.id, actions)
        }
        fun decode(saved: String?): TerminalToolbarLayout {
            if (saved == null) return defaults()
            val value = JSONObject(saved)
            require(value.getInt("version") == 1)
            val rawActions = value.getJSONArray("actions")
            require(rawActions.length() <= 128)
            val actions = (0 until rawActions.length()).map { index -> rawActions.getJSONObject(index).let {
                TerminalToolbarAction(it.getString("id"), it.getString("title"), it.getString("text")).also { action -> action.validate() }
            } }
            require(actions.map { it.id }.distinct().size == actions.size)
            val canonical = defaults(actions)
            fun ids(key: String) = value.getJSONArray(key).let { array ->
                require(array.length() <= 1024)
                (0 until array.length()).map { array.getString(it) }.filter { it in canonical.order }.distinct()
            }
            val order = (ids("order") + canonical.order).distinct()
            // Explicitly empty enabled stays empty. Newly added IDs append without undoing user choices.
            return TerminalToolbarLayout(order, ids("enabled").toSet(), actions)
        }
    }
}
