package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeyEncodingTest {
    @Test fun unicodeAndNavigationModifiersArePreserved() {
        assertEquals("👩🏽‍💻", TerminalKeyEncoding.text("👩🏽‍💻"))
        assertEquals("中文", TerminalKeyEncoding.text("中文"))
        assertEquals("É", TerminalKeyEncoding.text("é", shift = true))
        assertEquals("\u001b[1;3H", TerminalKeyEncoding.encode("Home", alt = true))
        assertEquals("\u001b[6;5~", TerminalKeyEncoding.encode("PageDown", control = true))
        assertEquals("\u001b\u007f", TerminalKeyEncoding.encode("Delete", alt = true))
        assertEquals("\u001bOP", TerminalKeyEncoding.encode("F1"))
        assertEquals("\u001b[1;5P", TerminalKeyEncoding.encode("F1", control = true))
        assertEquals("\u001b[24;2~", TerminalKeyEncoding.encode("F12", shift = true))
        assertEquals("\u0000", TerminalKeyEncoding.encode("2", control = true))
        assertEquals("\u001f", TerminalKeyEncoding.encode("/", control = true))
        assertEquals("\u001bb", TerminalKeyEncoding.encode("Left", alt = true))
        assertEquals("\u001bf", TerminalKeyEncoding.encode("Right", alt = true, applicationCursorKeys = true))
    }

    @Test fun modifierCombinationsProduceTerminalBytes() {
        assertEquals("\u0003", TerminalKeyEncoding.encode("c", control = true))
        assertEquals("\u001bx", TerminalKeyEncoding.encode("x", alt = true))
        assertEquals("\u001b[1;5A", TerminalKeyEncoding.encode("Up", control = true))
        assertEquals("\u001b[1;4D", TerminalKeyEncoding.encode("Left", alt = true, shift = true))
        assertEquals("\u001b[Z", TerminalKeyEncoding.encode("Tab", shift = true))
        assertEquals("\u001bOA", TerminalKeyEncoding.encode("Up", applicationCursorKeys = true))
        assertEquals("\u001bOH", TerminalKeyEncoding.encode("Home", applicationCursorKeys = true))
        assertEquals("\u001b[1;5A", TerminalKeyEncoding.encode("Up", control = true,
            applicationCursorKeys = true))
        assertEquals("\u001b[200~hello\u001b[201~", TerminalKeyEncoding.paste("hello", true))
    }
}
