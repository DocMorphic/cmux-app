package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeyEncodingTest {
    @Test fun modifierCombinationsProduceTerminalBytes() {
        assertEquals("\u0003", TerminalKeyEncoding.encode("c", control = true))
        assertEquals("\u001bx", TerminalKeyEncoding.encode("x", alt = true))
        assertEquals("\u001b[1;5A", TerminalKeyEncoding.encode("Up", control = true))
        assertEquals("\u001b[1;4D", TerminalKeyEncoding.encode("Left", alt = true, shift = true))
        assertEquals("\u001b[Z", TerminalKeyEncoding.encode("Tab", shift = true))
    }
}
