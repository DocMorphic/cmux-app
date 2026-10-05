package io.github.docmorphic.cmuxapp

import java.math.BigDecimal

/** Strict bounded JSON for bearer-bearing input. Errors deliberately contain no input text. */
internal object PairingTicketJson {
    fun decode(text: String): Map<String, Any?> {
        require(text.length <= 65_536) { "Pairing ticket is too large" }
        val reader = Reader(text)
        val value = reader.value(0)
        reader.space()
        require(reader.done()) { "Invalid pairing ticket JSON" }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: error("Invalid pairing ticket JSON")
    }

    private class Reader(private val text: String) {
        private var index = 0
        fun done() = index == text.length
        fun space() { while (index < text.length && text[index] in " \t\r\n") index++ }
        private fun take(c: Char): Boolean {
            space()
            if (index < text.length && text[index] == c) { index++; return true }
            return false
        }
        private fun expect(c: Char) { require(take(c)) { "Invalid pairing ticket JSON" } }
        fun value(depth: Int): Any? {
            require(depth <= 16) { "Pairing ticket JSON is too deeply nested" }
            space(); require(index < text.length) { "Invalid pairing ticket JSON" }
            return when (text[index]) {
                '{' -> {
                    index++
                    val result = linkedMapOf<String, Any?>()
                    if (!take('}')) {
                        do {
                            space(); val key = string()
                            require(result.size < 256 && !result.containsKey(key)) { "Invalid or duplicate pairing ticket field" }
                            expect(':'); result[key] = value(depth + 1)
                        } while (take(','))
                        expect('}')
                    }
                    result
                }
                '[' -> {
                    index++
                    val result = mutableListOf<Any?>()
                    if (!take(']')) {
                        do {
                            require(result.size < 256) { "Pairing ticket array is too large" }
                            result += value(depth + 1)
                        } while (take(','))
                        expect(']')
                    }
                    result
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }
        private fun literal(word: String, result: Any?): Any? {
            require(text.startsWith(word, index)) { "Invalid pairing ticket JSON" }
            index += word.length; return result
        }
        private fun number(): BigDecimal {
            val start = index
            while (index < text.length && text[index] !in ",]} \t\r\n") index++
            val token = text.substring(start, index)
            require(token.length in 1..128 && NUMBER.matches(token)) { "Invalid pairing ticket number" }
            return try { BigDecimal(token).also { require(it.scale() in -1000..1000) } }
            catch (_: Exception) { error("Invalid pairing ticket number") }
        }
        private fun string(): String {
            require(index < text.length && text[index++] == '"') { "Invalid pairing ticket JSON" }
            val result = StringBuilder()
            var closed = false
            while (index < text.length) {
                val c = text[index++]
                if (c == '"') { closed = true; break }
                require(c >= ' ') { "Invalid pairing ticket string" }
                if (c != '\\') result.append(c) else {
                    require(index < text.length) { "Invalid pairing ticket string" }
                    result.append(when (val escaped = text[index++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            require(index + 4 <= text.length) { "Invalid pairing ticket string" }
                            val hex = text.substring(index, index + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid pairing ticket string" }
                            index += 4; hex.toInt(16).toChar()
                        }
                        else -> error("Invalid pairing ticket string")
                    })
                }
            }
            require(closed) { "Invalid pairing ticket string" }
            var i = 0
            while (i < result.length) {
                if (result[i].isHighSurrogate()) {
                    require(i + 1 < result.length && result[i + 1].isLowSurrogate()) { "Invalid pairing ticket Unicode" }; i++
                } else require(!result[i].isLowSurrogate()) { "Invalid pairing ticket Unicode" }
                i++
            }
            return result.toString()
        }
    }
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
