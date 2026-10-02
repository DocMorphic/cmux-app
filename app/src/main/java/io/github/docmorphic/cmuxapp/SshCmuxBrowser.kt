package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.Locale

internal enum class SshCmuxBrowserStatus { STARTING, LIVE, FAILED }
internal data class SshCmuxBrowserFrame(val sequence: Long, val width: Int, val height: Int,
    val imageWidth: Int, val imageHeight: Int, val png: String, val status: SshCmuxBrowserStatus?,
    val error: String?, val floor: Long?, val token: Long?)
internal data class SshCmuxBrowserState(val columns: Int, val rows: Int, val url: String, val title: String,
    val status: SshCmuxBrowserStatus, val error: String?, val stalled: Boolean,
    val floor: Long?, val token: Long?, val frame: SshCmuxBrowserFrame?)
internal sealed interface SshCmuxBrowserEvent {
    data class State(val value: SshCmuxBrowserState) : SshCmuxBrowserEvent
    data class Frame(val value: SshCmuxBrowserFrame) : SshCmuxBrowserEvent
    data class Ended(val disconnected: Boolean) : SshCmuxBrowserEvent
}

/** Strict IDs/tokens: never truncate floating point, strings or out-of-range values into authority. */
internal object SshCmuxBrowserWire {
    const val CAPABILITY = "browser-pointer-frame-guard-v1"
    private fun number(value: JSONObject, key: String, default: Long? = null): Long? {
        if (!value.has(key) || value.isNull(key)) return default
        val raw = value.get(key)
        require(raw is Int || raw is Long) { "Invalid browser $key" }
        return (raw as Number).toLong().also { require(it >= 0) { "Invalid browser $key" } }
    }
    private fun dimension(value: JSONObject, key: String, fallback: Int = 0): Int =
        checkNotNull(number(value, key, fallback.toLong())).also { require(it <= 16384) }.toInt()
    private fun status(value: JSONObject) = when (value.opt("status")) {
        "live" -> SshCmuxBrowserStatus.LIVE
        "failed" -> SshCmuxBrowserStatus.FAILED
        else -> SshCmuxBrowserStatus.STARTING
    }
    private fun frame(value: JSONObject, state: JSONObject? = null): SshCmuxBrowserFrame {
        val width = dimension(value, "width"); val height = dimension(value, "height")
        val iw = dimension(value, "image_width").takeIf { it > 0 } ?: width
        val ih = dimension(value, "image_height").takeIf { it > 0 } ?: height
        require(iw.toLong() * ih <= 16 * 1024 * 1024) { "Browser image exceeds its pixel limit" }
        val png = value.get("data") as? String ?: error("Invalid browser frame data")
        require(png.length <= 12 * 1024 * 1024) { "Browser image exceeds its encoded limit" }
        val authority = state ?: value
        return SshCmuxBrowserFrame(checkNotNull(number(value, "seq")), width, height, iw, ih, png,
            if (state == null && !value.has("status")) null else status(authority), authority.opt("error") as? String,
            number(authority, "pointer_frame_floor_seq"), number(authority, "pointer_frame_seq"))
    }
    fun parse(value: JSONObject): SshCmuxBrowserEvent? = when (value.opt("event")) {
        "browser-state" -> SshCmuxBrowserEvent.State(SshCmuxBrowserState(
            dimension(value, "cols"), dimension(value, "rows"), value.opt("url") as? String ?: "",
            value.opt("title") as? String ?: "", status(value), value.opt("error") as? String,
            value.opt("frames_stalled") == true, number(value, "pointer_frame_floor_seq"), number(value, "pointer_frame_seq"),
            value.optJSONObject("frame")?.let { frame(it, value) }))
        "frame" -> SshCmuxBrowserEvent.Frame(frame(value))
        else -> null
    }
}

/** New pointer authority must arrive with pixels; metadata alone can retain or revoke it. */
internal class SshCmuxBrowserPointerGuard {
    private var status = SshCmuxBrowserStatus.STARTING
    private var range: LongRange? = null
    private var presented: Long? = null
    val token: Long? get() {
        val current = presented ?: return null
        return current.takeIf { status == SshCmuxBrowserStatus.LIVE && range?.contains(it) == true }
    }
    private fun range(floor: Long?, token: Long?): LongRange? = token?.let { last ->
        val first = floor ?: last
        if (first >= 0 && first <= last) first..last else null
    }
    fun apply(state: SshCmuxBrowserState) {
        status = state.status
        val advertised = if (status == SshCmuxBrowserStatus.LIVE) range(state.floor, state.token) else null
        range = if (state.frame != null || advertised == range) advertised else null
        retain()
    }
    fun apply(frame: SshCmuxBrowserFrame) {
        frame.status?.let { status = it }
        range = if (frame.status == SshCmuxBrowserStatus.LIVE) range(frame.floor, frame.token) else null
        retain()
    }
    fun canAcknowledge(token: Long) = status == SshCmuxBrowserStatus.LIVE && range?.contains(token) == true &&
        (presented == null || token > checkNotNull(presented))
    fun acknowledge(token: Long): Boolean {
        if (!canAcknowledge(token)) return false
        presented = token; return true
    }
    fun revoke() { range = null; presented = null }
    private fun retain() { if (presented?.let { range?.contains(it) } != true) presented = null }
}

internal class SshCmuxBrowserAttachment internal constructor(val surface: Int, internal val events: (SshCmuxBrowserEvent) -> Unit) {
    var lease: String? = null; internal set
    internal var seeded = false
    internal var ended = false
    internal var detaching = false
    internal val operations = Mutex()
    internal val pointer = SshCmuxBrowserPointerGuard()
    internal val frames = sortedMapOf<Long, Long>()
    internal var newest = -1L
    internal fun receive(event: SshCmuxBrowserEvent) {
        fun admit(frame: SshCmuxBrowserFrame) {
            newest = frame.sequence
            frame.token?.let { frames[frame.sequence] = it }
            while (frames.size > 8) frames.remove(frames.firstKey())
        }
        when (event) {
            is SshCmuxBrowserEvent.State -> {
                val fresh = event.value.frame?.takeIf { it.sequence > newest }
                val state = event.value.copy(frame = fresh)
                pointer.apply(state); seeded = true
                fresh?.let(::admit)
                events(SshCmuxBrowserEvent.State(state))
            }
            is SshCmuxBrowserEvent.Frame -> if (event.value.sequence > newest) {
                pointer.apply(event.value); admit(event.value); events(event)
            }
            is SshCmuxBrowserEvent.Ended -> {
                pointer.revoke(); frames.clear(); events(event)
            }
        }
    }
}

internal object SshCmuxBrowserKeys {
    fun named(token: String, modifiers: List<String>): JSONObject? {
        var bits = 0
        for (name in modifiers) bits = bits or when (name.lowercase(Locale.ROOT)) {
            "option", "alt" -> 1; "control", "ctrl" -> 2; "command", "cmd", "meta" -> 4; "shift" -> 8; else -> 0
        }
        val name = token.lowercase(Locale.ROOT).replace("_", "")
        val named = when (name) {
            "return", "enter" -> "Enter" to 13; "delete", "backspace" -> "Backspace" to 8
            "forwarddelete" -> "Delete" to 46; "tab" -> "Tab" to 9; "escape", "esc" -> "Escape" to 27
            "up" -> "ArrowUp" to 38; "down" -> "ArrowDown" to 40; "left" -> "ArrowLeft" to 37; "right" -> "ArrowRight" to 39
            "home" -> "Home" to 36; "end" -> "End" to 35; "pageup" -> "PageUp" to 33; "pagedown" -> "PageDown" to 34
            "insert" -> "Insert" to 45
            else -> name.takeIf { it.startsWith("f") }?.drop(1)?.toIntOrNull()?.takeIf { it in 1..12 }?.let { "F$it" to (111 + it) }
        }
        val character = if (name == "space") " " else token.takeIf {
            it.isNotEmpty() && it.codePointCount(0, it.length) == 1 && !Character.isISOControl(it.codePointAt(0))
        }
        if (named == null && character == null) return null
        val key = named?.first ?: checkNotNull(character).let { if (bits and 8 != 0) it.uppercase(Locale.ROOT) else it }
        val physical = named?.first ?: when {
            character == " " -> "Space"
            character?.singleOrNull()?.lowercaseChar() in 'a'..'z' -> "Key${character!!.uppercase(Locale.ROOT)}"
            character?.singleOrNull() in '0'..'9' -> "Digit$character"
            else -> "" // A character does not identify a physical key on every keyboard layout.
        }
        return JSONObject().put("key", key).put("code", physical)
            .put("windows_virtual_key_code", named?.second ?: 0).put("modifiers", bits).also {
                // Shortcut keys must not also insert text into the focused element.
                if (bits and 7 == 0) {
                    if (key == "Enter") it.put("text", "\r")
                    else if (character != null) it.put("text", key)
                }
            }
    }
}
