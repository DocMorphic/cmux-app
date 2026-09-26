package io.github.docmorphic.cmuxapp

/** Common terminal key sequences used by the Android accessory bar. */
object TerminalKeyEncoding {
    fun encode(key: String, control: Boolean = false, alt: Boolean = false, shift: Boolean = false): String {
        val sequence = when (key) {
            "Esc" -> "\u001b"
            "Tab" -> if (shift) "\u001b[Z" else "\t"
            "Enter" -> "\r"
            "Backspace" -> "\u007f"
            "Delete" -> "\u001b[3~"
            "Up" -> arrow("A", control, alt, shift)
            "Down" -> arrow("B", control, alt, shift)
            "Right" -> arrow("C", control, alt, shift)
            "Left" -> arrow("D", control, alt, shift)
            "Home" -> "\u001b[H"
            "End" -> "\u001b[F"
            "PageUp" -> "\u001b[5~"
            "PageDown" -> "\u001b[6~"
            else -> {
                require(key.length == 1) { "Unsupported key" }
                val character = if (shift) key.uppercase()[0] else key[0]
                if (control) {
                    val upper = character.uppercaseChar()
                    when (upper) {
                        in 'A'..'Z' -> (upper.code - 'A'.code + 1).toChar().toString()
                        '@', ' ' -> "\u0000"
                        '[' -> "\u001b"
                        '\\' -> "\u001c"
                        ']' -> "\u001d"
                        '^' -> "\u001e"
                        '_' -> "\u001f"
                        '?' -> "\u007f"
                        else -> character.toString()
                    }
                } else character.toString()
            }
        }
        return if (alt && key !in setOf("Up", "Down", "Left", "Right")) "\u001b$sequence" else sequence
    }

    private fun arrow(letter: String, control: Boolean, alt: Boolean, shift: Boolean): String {
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (control) 4 else 0)
        return if (modifier == 1) "\u001b[$letter" else "\u001b[1;${modifier}$letter"
    }
}
