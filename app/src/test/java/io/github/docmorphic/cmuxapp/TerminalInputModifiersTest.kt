package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.TerminalInputModifiers.Key
import org.junit.Assert.*
import org.junit.Test

class TerminalInputModifiersTest {
    @Test fun eachModifierIsOneShotOrDoubleTapLockedAndCanBeTurnedOff() {
        for (key in Key.entries) {
            val armed = TerminalInputModifiers().tap(key, 1000)
            assertEquals(key, armed.armed)
            assertFalse(armed.sticky)
            assertNull(armed.consume().armed)
            val locked = armed.tap(key, 1399)
            assertTrue(locked.sticky)
            assertEquals(locked, locked.consume().consume())
            assertNull(locked.tap(key, 1400).armed)
            assertNull(armed.tap(key, 1400).armed) // Exact iOS 400 ms boundary.
            assertFalse(armed.tap(key, 999).sticky)
        }
    }

    @Test fun changingModifierReplacesStickyStateAndConsumptionClearsTapWindow() {
        val locked = TerminalInputModifiers().tap(Key.CONTROL, 100).tap(Key.CONTROL, 150)
        val alternate = locked.tap(Key.ALT, 200)
        assertEquals(Key.ALT, alternate.armed)
        assertFalse(alternate.sticky)
        val next = alternate.consume().tap(Key.ALT, 250)
        assertFalse(next.sticky)
        assertEquals(Key.ALT, next.armed)
    }

    @Test fun commandTextMatchesIosReadlineTableAndPreservesUnmappedCommits() {
        val command = TerminalInputModifiers(Key.COMMAND)
        val codes = mapOf("a" to 1, "e" to 5, "k" to 11, "u" to 21, "w" to 23, "l" to 12, "c" to 3, "d" to 4)
        for ((key, code) in codes) {
            assertEquals(code.toChar().toString(), command.text(key))
            assertEquals(code.toChar().toString(), command.text(key.uppercase()))
        }
        for (value in listOf("z", "ab", "你", "👩🏽‍💻", "", "\r")) assertEquals(value, command.text(value))
        assertEquals("\u0001", command.special("Left"))
        assertEquals("\u0005", command.special("Right"))
        assertEquals("\u0015", command.special("Backspace"))
        assertEquals("\u001bOA", command.special("Up", applicationCursorKeys = true))
    }

    @Test fun accessoryModifiersFollowIosSpecialKeyActionsWithoutChangingHardwareCombinations() {
        val alt = TerminalInputModifiers(Key.ALT)
        assertEquals("\u001bb", alt.special("Left", applicationCursorKeys = true))
        assertEquals("\u001bf", alt.special("Right"))
        assertEquals("\u001b\u007f", alt.special("Backspace"))
        assertEquals("\u001b\u001b[3~", alt.special("Delete"))
        assertEquals("\u001b\u0003", alt.special("CtrlC"))
        assertEquals("\u001b[Z", TerminalInputModifiers(Key.SHIFT).special("Tab"))
        assertEquals("\u001b[D", TerminalInputModifiers(Key.CONTROL).special("Left"))
        assertEquals("\u001b[C", TerminalInputModifiers(Key.SHIFT).special("Right"))
        assertEquals("\u001b[1;5D", TerminalKeyEncoding.encode("Left", control = true))
    }

    @Test fun textModifiersPreserveImeCommitsAndEmptyInput() {
        assertEquals("\u001f", TerminalInputModifiers(Key.CONTROL).text("/"))
        assertEquals("ni好", TerminalInputModifiers(Key.CONTROL).text("ni好"))
        assertEquals("\u001b你好", TerminalInputModifiers(Key.ALT).text("你好"))
        assertEquals("", TerminalInputModifiers(Key.ALT).text(""))
        assertEquals("É", TerminalInputModifiers(Key.SHIFT).text("é"))
        assertEquals("\u007f\u007f", TerminalInputModifiers().special("Backspace").repeat(2))
    }
}
