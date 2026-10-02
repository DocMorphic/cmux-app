package io.github.docmorphic.cmuxapp

import android.view.KeyEvent
import android.view.KeyCharacterMap

/** Uses the keyboard's active layout and combines dead keys before encoding UTF-8. */
class TerminalHardwareInput {
    private var accent = 0
    fun sequence(event: KeyEvent, applicationCursorKeys: Boolean = false,
                 control: Boolean = false, alt: Boolean = false, shift: Boolean = false, command: Boolean = false): String? {
        if (event.action == KeyEvent.ACTION_MULTIPLE) return event.characters?.let {
            if (command) TerminalInputModifiers.commandText(it) else TerminalKeyEncoding.text(it, control, alt, shift)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return null
        // Modifier presses carry no character and must not consume a pending
        // dead key (for example pressing Right Alt again for a second accent).
        if (KeyEvent.isModifierKey(event.keyCode)) return null
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
            if (command) return TerminalInputModifiers(TerminalInputModifiers.Key.COMMAND).special(key, applicationCursorKeys)
            return TerminalKeyEncoding.encode(key, control || event.isCtrlPressed, alt || event.isAltPressed,
                shift || event.isShiftPressed, applicationCursorKeys)
        }
        val textModifiers = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val baseCharacter = event.getUnicodeChar(textModifiers)
        // Right Alt can select a printable/dead-key level in the active Android
        // layout (AltGr). Left Alt and explicit terminal modifier chords retain
        // their escape-prefix behavior. Unmapped right-Alt keys also fall back.
        val layoutCharacter = if (!control && !alt && !command && !event.isCtrlPressed && !event.isMetaPressed &&
            event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0 && event.metaState and KeyEvent.META_ALT_LEFT_ON == 0)
            event.getUnicodeChar(textModifiers or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON) else 0
        val layoutAlt = layoutCharacter != 0 && layoutCharacter != baseCharacter
        val character = if (layoutAlt) layoutCharacter else baseCharacter
        if (control || command || event.isCtrlPressed || alt || (event.isAltPressed && !layoutAlt)) accent = 0
        if (character and KeyCharacterMap.COMBINING_ACCENT != 0) {
            val next = character and KeyCharacterMap.COMBINING_ACCENT_MASK
            val previous = accent
            accent = if (previous == next) 0 else next
            return if (previous == 0) "" else String(Character.toChars(previous))
        }
        if (character < 32 || character > 0x10ffff) return null
        val text = if (accent != 0) {
            val combined = KeyEvent.getDeadChar(accent, character)
            val result = if (combined != 0) String(Character.toChars(combined))
                else String(Character.toChars(accent)) + String(Character.toChars(character))
            accent = 0
            result
        } else String(Character.toChars(character))
        return if (command) TerminalInputModifiers.commandText(text)
            else TerminalKeyEncoding.text(text, control || event.isCtrlPressed, alt || (event.isAltPressed && !layoutAlt), shift)
    }
}
