package io.github.docmorphic.cmuxapp

import android.view.KeyEvent
import android.view.KeyCharacterMap

/** Uses the keyboard's active layout and combines dead keys before encoding UTF-8. */
class TerminalHardwareInput {
    private var accent = 0
    fun sequence(event: KeyEvent, applicationCursorKeys: Boolean = false,
                 control: Boolean = false, alt: Boolean = false, shift: Boolean = false): String? {
        if (event.action == KeyEvent.ACTION_MULTIPLE) return event.characters
        if (event.action != KeyEvent.ACTION_DOWN) return null
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_INSERT -> "Insert"
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${event.keyCode - KeyEvent.KEYCODE_F1 + 1}"
            else -> null
        }
        if (key != null) {
            accent = 0
            return TerminalKeyEncoding.encode(key, control || event.isCtrlPressed, alt || event.isAltPressed,
                shift || event.isShiftPressed, applicationCursorKeys)
        }
        val textModifiers = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val character = event.getUnicodeChar(textModifiers)
        if (character and KeyCharacterMap.COMBINING_ACCENT != 0) {
            accent = character and KeyCharacterMap.COMBINING_ACCENT_MASK
            return ""
        }
        if (character < 32 || character > 0x10ffff) return null
        val text = if (accent != 0) {
            val combined = KeyEvent.getDeadChar(accent, character)
            val result = if (combined != 0) String(Character.toChars(combined))
                else String(Character.toChars(accent)) + String(Character.toChars(character))
            accent = 0
            result
        } else String(Character.toChars(character))
        return TerminalKeyEncoding.text(text, control || event.isCtrlPressed, alt || event.isAltPressed, shift)
    }
}
