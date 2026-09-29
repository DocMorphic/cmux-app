package io.github.docmorphic.cmuxapp

/** Common terminal key sequences used by the Android accessory bar. */
object TerminalKeyEncoding {
    fun encode(key: String, control: Boolean = false, alt: Boolean = false,
               shift: Boolean = false, applicationCursorKeys: Boolean = false): String {
        if (alt && !control && !shift) when (key) {
            "Left" -> return "\u001bb"
            "Right" -> return "\u001bf"
            "Delete" -> return "\u001b\u007f"
        }
        val sequence = when (key) {
            "Esc" -> "\u001b"
            "Tab" -> if (shift) "\u001b[Z" else "\t"
            "Enter" -> "\r"
            "Backspace" -> "\u007f"
            "Delete" -> tilde(3, control, alt, shift)
            "Up" -> arrow("A", control, alt, shift, applicationCursorKeys)
            "Down" -> arrow("B", control, alt, shift, applicationCursorKeys)
            "Right" -> arrow("C", control, alt, shift, applicationCursorKeys)
            "Left" -> arrow("D", control, alt, shift, applicationCursorKeys)
            "Home" -> arrow("H", control, alt, shift, applicationCursorKeys)
            "End" -> arrow("F", control, alt, shift, applicationCursorKeys)
            "PageUp" -> tilde(5, control, alt, shift)
            "PageDown" -> tilde(6, control, alt, shift)
            "Insert" -> tilde(2, control, alt, shift)
            "F1", "F2", "F3", "F4" -> {
                val letter = ('P' + key.drop(1).toInt() - 1).toString()
                val modifier = modifier(control, alt, shift)
                if (modifier == 1) "\u001bO$letter" else "\u001b[1;${modifier}$letter"
            }
            "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12" ->
                tilde(listOf(15, 17, 18, 19, 20, 21, 23, 24)[key.drop(1).toInt() - 5], control, alt, shift)
            else -> return text(key, control, alt, shift)

        }
        return if (alt && key !in setOf("Up", "Down", "Left", "Right", "Home", "End", "Delete", "Insert", "PageUp", "PageDown",
                "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12")) "\u001b$sequence" else sequence
    }

    fun text(value: String, control: Boolean = false, alt: Boolean = false, shift: Boolean = false): String {
        var result = if (shift) value.uppercase(java.util.Locale.ROOT) else value
        if (control && result.length == 1) {
            result = when (val upper = result[0].uppercaseChar()) {
                in 'A'..'Z' -> (upper.code - 'A'.code + 1).toChar().toString()
                '@', ' ', '2' -> "\u0000"
                '[', '3' -> "\u001b"
                '\\', '4' -> "\u001c"
                ']', '5' -> "\u001d"
                '^', '6' -> "\u001e"
                '_', '7', '/' -> "\u001f"
                '?', '8' -> "\u007f"
                else -> result
            }
        }
        return if (alt) "\u001b$result" else result
    }

    private fun modifier(control: Boolean, alt: Boolean, shift: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (control) 4 else 0)

    private fun tilde(number: Int, control: Boolean, alt: Boolean, shift: Boolean): String {
        val modifier = modifier(control, alt, shift)
        return if (modifier == 1) "\u001b[$number~" else "\u001b[$number;${modifier}~"
    }

    fun paste(text: String, bracketedPaste: Boolean): String =
        if (bracketedPaste) "\u001b[200~$text\u001b[201~" else text

    private fun arrow(letter: String, control: Boolean, alt: Boolean,
                      shift: Boolean, applicationCursorKeys: Boolean): String {
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (control) 4 else 0)
        return if (modifier == 1) {
            if (applicationCursorKeys) "\u001bO$letter" else "\u001b[$letter"
        } else "\u001b[1;${modifier}$letter"
    }
}
