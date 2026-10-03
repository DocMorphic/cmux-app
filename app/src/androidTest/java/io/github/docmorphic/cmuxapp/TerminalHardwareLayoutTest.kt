package io.github.docmorphic.cmuxapp

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

/** Android's actual virtual key map, not a mocked getUnicodeChar implementation. */
class TerminalHardwareLayoutTest {
    private val right = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
    private val left = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
    private fun key(code: Int, meta: Int = 0, action: Int = KeyEvent.ACTION_DOWN) =
        KeyEvent(0, 0, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    @Test fun rightAltUsesTheActiveCharacterLevelWhileLeftAltRetainsTerminalMeaning() {
        val mapped = key(KeyEvent.KEYCODE_C, right).getUnicodeChar(right)
        assertEquals('ç'.code, mapped)
        val input = TerminalHardwareInput()
        assertEquals("ç", input.sequence(key(KeyEvent.KEYCODE_C, right)))
        assertEquals("\u001bc", input.sequence(key(KeyEvent.KEYCODE_C, left)))
        assertEquals("\u001bc", input.sequence(key(KeyEvent.KEYCODE_C, right), alt = true))
        assertEquals("\u001b\u0003", input.sequence(key(KeyEvent.KEYCODE_C, right or KeyEvent.META_CTRL_ON)))
        assertEquals("C", input.sequence(key(KeyEvent.KEYCODE_C, KeyEvent.META_SHIFT_ON)))
    }

    @Test fun deadKeyComposesAndRepeatedOrIncompatibleAccentsAreNotLost() {
        val acute = key(KeyEvent.KEYCODE_E, right)
        val grave = key(KeyEvent.KEYCODE_GRAVE, right)
        assertTrue(acute.getUnicodeChar(right) and KeyCharacterMap.COMBINING_ACCENT != 0)
        assertTrue(grave.getUnicodeChar(right) and KeyCharacterMap.COMBINING_ACCENT != 0)
        val accent = acute.getUnicodeChar(right) and KeyCharacterMap.COMBINING_ACCENT_MASK
        val input = TerminalHardwareInput()
        assertEquals("", input.sequence(acute))
        assertNull(input.sequence(key(KeyEvent.KEYCODE_E, right, KeyEvent.ACTION_UP)))
        assertEquals("é", input.sequence(key(KeyEvent.KEYCODE_E)))
        assertEquals("", input.sequence(acute))
        assertNull(input.sequence(key(KeyEvent.KEYCODE_ALT_RIGHT, right)))
        assertEquals(String(Character.toChars(accent)), input.sequence(acute))
        assertEquals("e", input.sequence(key(KeyEvent.KEYCODE_E)))
        assertEquals("", input.sequence(acute))
        assertNull(input.sequence(key(KeyEvent.KEYCODE_ALT_RIGHT, right)))
        assertEquals(String(Character.toChars(accent)), input.sequence(grave))
        assertEquals("è", input.sequence(key(KeyEvent.KEYCODE_E)))
        assertEquals("", input.sequence(acute))
        assertEquals(String(Character.toChars(accent)) + "x", input.sequence(key(KeyEvent.KEYCODE_X)))
    }

    @Test fun navigationCancelsPendingAccentAndRespectsTerminalModes() {
        val input = TerminalHardwareInput()
        assertEquals("", input.sequence(key(KeyEvent.KEYCODE_E, right)))
        assertEquals("\u001bOA", input.sequence(key(KeyEvent.KEYCODE_DPAD_UP), applicationCursorKeys = true))
        assertEquals("e", input.sequence(key(KeyEvent.KEYCODE_E)))
        assertEquals("", input.sequence(key(KeyEvent.KEYCODE_E, right)))
        assertEquals("\u0003", input.sequence(key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)))
        assertEquals("e", input.sequence(key(KeyEvent.KEYCODE_E)))
        assertEquals("\u001b[A", input.sequence(key(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals("\u001bb", input.sequence(key(KeyEvent.KEYCODE_DPAD_LEFT, left)))
        assertEquals("\u001b[Z", input.sequence(key(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)))
        assertEquals("\u001b[1;5A", input.sequence(key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON), applicationCursorKeys = true))
    }
}
