package io.github.docmorphic.cmuxapp

/** iOS accessory behavior: one armed modifier, promoted to sticky by a second tap. */
data class TerminalInputModifiers(
    val armed: Key? = null,
    val sticky: Boolean = false,
    private val lastTapMillis: Long? = null
) {
    enum class Key(val label: String) { CONTROL("Ctrl"), ALT("Alt"), COMMAND("Cmd"), SHIFT("Shift") }

    fun tap(key: Key, nowMillis: Long): TerminalInputModifiers {
        if (armed == key && sticky) return TerminalInputModifiers()
        if (armed == key && lastTapMillis != null && nowMillis >= lastTapMillis && nowMillis - lastTapMillis < 400)
            return copy(sticky = true, lastTapMillis = null)
        return if (armed == key) TerminalInputModifiers() else TerminalInputModifiers(key, lastTapMillis = nowMillis)
    }

    fun consume(): TerminalInputModifiers = if (sticky) this else TerminalInputModifiers()

    fun text(value: String): String = when (armed) {
        Key.CONTROL -> TerminalKeyEncoding.text(value, control = true)
        Key.ALT -> if (value.isEmpty()) value else "\u001b$value"
        Key.COMMAND -> commandText(value)
        Key.SHIFT -> TerminalKeyEncoding.text(value, shift = true)
        null -> value
    }

    /** Toolbar special keys follow iOS readline actions; hardware retains its VT modifier encoding. */
    fun special(key: String, applicationCursorKeys: Boolean = false): String {
        val base = when (key) {
            "CtrlC" -> "\u0003"
            "CtrlD" -> "\u0004"
            "CtrlZ" -> "\u001a"
            "CtrlL" -> "\u000c"
            else -> TerminalKeyEncoding.encode(key, applicationCursorKeys = applicationCursorKeys)
        }
        return when (armed) {
            Key.ALT -> when (key) { "Left" -> "\u001bb"; "Right" -> "\u001bf"; else -> "\u001b$base" }
            Key.COMMAND -> when (key) { "Left" -> "\u0001"; "Right" -> "\u0005"; "Backspace" -> "\u0015"; else -> base }
            Key.SHIFT -> if (key == "Tab") "\u001b[Z" else base
            else -> base
        }
    }

    companion object {
        fun commandText(value: String): String = if (value.length != 1) value else when (value.lowercase(java.util.Locale.ROOT)) {
            "a" -> "\u0001"; "e" -> "\u0005"; "k" -> "\u000b"; "u" -> "\u0015"
            "w" -> "\u0017"; "l" -> "\u000c"; "c" -> "\u0003"; "d" -> "\u0004"
            else -> value
        }
    }
}
