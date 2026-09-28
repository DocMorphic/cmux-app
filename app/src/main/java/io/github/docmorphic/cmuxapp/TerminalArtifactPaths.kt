package io.github.docmorphic.cmuxapp

import java.net.URI
import java.nio.file.Paths

/** Conservative path detector matching the iOS local Files-count fallback. */
internal object TerminalArtifactPaths {
    private val leading = "\"'`([{<"
    private val trailing = "\"'`)]}>,;:!?"
    private val forbidden = "<>\"'\\`"
    fun paths(text: String): List<String> = stripEscapes(text).splitToSequence(Regex("[\\s\\p{Z}]+"))
        .mapNotNull(::normalized).distinct().toList()
    fun normalized(raw: String): String? {
        var candidate = raw.trim { it in leading }
        val link = candidate.indexOf("](")
        if (link >= 0) {
            val destination = candidate.substring(link + 2)
            if (destination.startsWith('/') || destination.startsWith("~/") || destination.startsWith("file://")) candidate = destination
        }
        while (candidate.isNotEmpty() && (candidate.last() in trailing || candidate.endsWith('.') && !candidate.endsWith(".."))) candidate = candidate.dropLast(1)
        if (candidate.startsWith("file://")) candidate = runCatching { URI(candidate).path }.getOrNull() ?: candidate
        var index = 0
        while (index < candidate.length) {
            if (candidate[index] == ':') {
                var end = index + 1
                while (end < candidate.length && candidate[end] in '0'..'9') end++
                if (end > index + 1 && (end == candidate.length || candidate[end] == ':')) { candidate = candidate.substring(0, index); break }
            }
            index++
        }
        if (candidate.isEmpty() || candidate.any { it in forbidden || it == '(' || it == ')' } || '\u0000' in candidate) return null
        if (candidate.startsWith('/')) {
            val normalized = runCatching { Paths.get(candidate).normalize().toString() }.getOrNull() ?: return null
            if (normalized == "/" || normalized == "/.") return null
            return candidate
        }
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return null
        return candidate.takeIf { '/' in it && "://" !in it }
    }

    /** One scalar pass; OSC/DCS payloads cannot inject file paths or consume surrounding text. */
    private fun stripEscapes(text: String): String = buildString {
        var state = 0; var allowsBell = false
        text.codePoints().forEachOrdered { value ->
            when (state) {
                0 -> when (value) {
                    0x1b -> state = 1
                    0x9d -> { state = 4; allowsBell = true }
                    0x90, 0x98, 0x9e, 0x9f -> { state = 4; allowsBell = false }
                    0x9b -> state = 3
                    0x9c -> Unit
                    else -> appendCodePoint(value)
                }
                1 -> when (value) {
                    0x5b -> state = 3
                    0x5d -> { state = 4; allowsBell = true }
                    0x50, 0x58, 0x5e, 0x5f -> { state = 4; allowsBell = false }
                    in 0x20..0x2f -> state = 2
                    in 0x30..0x7e -> state = 0
                    else -> { appendCodePoint(value); state = 0 }
                }
                2 -> if (value !in 0x20..0x2f) { state = 0; if (value !in 0x30..0x7e) appendCodePoint(value) }
                3 -> if (value in 0x40..0x7e) state = 0
                4 -> if (value == 0x9c || allowsBell && value == 7) state = 0 else if (value == 0x1b) state = 5
                5 -> if (value == 0x5c || value == 0x9c || allowsBell && value == 7) state = 0 else if (value != 0x1b) state = 4
            }
        }
    }
}
