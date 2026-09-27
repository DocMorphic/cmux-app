package io.github.docmorphic.cmuxapp

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Reuses the tested Android IME composition endpoint, with browser-native key RPCs. */
@Composable
internal fun BrowserKeyboardProxy(queue: BrowserInputQueue, focus: Boolean, enabled: Boolean, request: Int, modifier: Modifier) {
    val hardware = remember(queue) { BrowserHardwareInput() }
    val lastRequest = remember(queue) { intArrayOf(-1) }
    AndroidView(modifier = modifier, factory = { context ->
        TerminalKeyboardView(context).apply {
            imeAction = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = "Browser keyboard input"
            onText = { queue.offer(BrowserInput.committed(it)) }
            onPaste = { queue.offer(BrowserInput.committed(it)) }
            onReturn = { queue.offer(BrowserInput.Key("return")) }
            onDelete = { before, after -> queue.offer(List(before) { BrowserInput.Key("delete") } + List(after) { BrowserInput.Key("forward_delete") }) }
            onKey = { event -> hardware.input(event)?.let { queue.offer(it); true } ?: false }
        }
    }, update = { view ->
        view.isEnabled = enabled && focus
        if (focus && enabled) {
            if (!view.hasFocus() || lastRequest[0] != request) view.showKeyboard()
            lastRequest[0] = request
        }
        else if (view.hasFocus()) view.dispose()
    }, onRelease = { it.dispose() })
}

internal class BrowserHardwareInput {
    private var accent = 0
    fun input(event: KeyEvent): List<BrowserInput>? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        val modifiers = buildList {
            if (event.isCtrlPressed) add("control")
            if (event.isAltPressed) add("option")
            if (event.isMetaPressed) add("command")
            if (event.isShiftPressed) add("shift")
        }
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "return"
            KeyEvent.KEYCODE_DEL -> "delete"
            KeyEvent.KEYCODE_FORWARD_DEL -> "forward_delete"
            KeyEvent.KEYCODE_TAB -> "tab"
            KeyEvent.KEYCODE_ESCAPE -> "escape"
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            KeyEvent.KEYCODE_MOVE_HOME -> "home"
            KeyEvent.KEYCODE_MOVE_END -> "end"
            KeyEvent.KEYCODE_PAGE_UP -> "page_up"
            KeyEvent.KEYCODE_PAGE_DOWN -> "page_down"
            KeyEvent.KEYCODE_INSERT -> "insert"
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "f${event.keyCode - KeyEvent.KEYCODE_F1 + 1}"
            else -> null
        }
        if (key != null) { accent = 0; return listOf(BrowserInput.Key(key, modifiers)) }
        val character = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv())
        if (character and KeyCharacterMap.COMBINING_ACCENT != 0) { accent = character and KeyCharacterMap.COMBINING_ACCENT_MASK; return emptyList() }
        if (character !in 32..0x10ffff) return null
        val text = if (accent != 0) {
            val composed = KeyEvent.getDeadChar(accent, character)
            val value = if (composed != 0) String(Character.toChars(composed)) else String(Character.toChars(accent)) + String(Character.toChars(character))
            accent = 0; value
        } else String(Character.toChars(character))
        return if (modifiers.any { it != "shift" }) listOf(BrowserInput.Key(if (text == " ") "space" else text.lowercase(), modifiers))
            else listOf(BrowserInput.Text(text))
    }
}
