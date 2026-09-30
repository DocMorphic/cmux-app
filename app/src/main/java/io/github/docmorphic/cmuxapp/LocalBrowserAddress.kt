package io.github.docmorphic.cmuxapp

import android.icu.text.IDNA
import java.net.URI

/** Address-bar policy ported from the pinned iOS BrowserURLResolver. No I/O. */
internal class LocalBrowserAddress(private val searchTemplate: String = DEFAULT_SEARCH,
    private val asciiHost: (String) -> String? = AndroidBrowserHost::toAscii) {
    fun resolve(input: String): String? {
        val searchText = input.trim(::space)
        if (searchText.isEmpty()) return null
        val compact = searchText.filterNot(::wrapped)
        val value = if (compact != searchText && safeCompaction(searchText, compact)) compact else searchText
        if (value.any(::wrapped)) return search(searchText)
        web(value)?.let { return it.url }
        if (!value.contains("://") && value.takeWhile { it !in "/?#" }.contains('@')) return search(searchText)
        if (looksLikeHost(value)) {
            val host = bareHost(value).lowercase()
            val scheme = if (localHost(host)) "http" else "https"
            val authority = if (!value.startsWith('[') && !value.contains('/') && value.count { it == ':' } >= 2) "[$value]" else value
            web("$scheme://$authority")?.let { return it.url }
        }
        return search(searchText)
    }

    fun search(query: String): String? = web(searchTemplate.replace("%@", encode(query, QUERY_VALUE, false)))?.url

    private fun safeCompaction(original: String, compact: String): Boolean {
        if (compact.isEmpty()) return false
        val firstWrap = original.indexOfFirst(::wrapped)
        if (web(compact) != null) {
            val separator = original.indexOf("://")
            if (separator < 0 || separator + 3 >= firstWrap) return false
            val end = original.indexOfAny(charArrayOf('/', '?', '#'), separator + 3)
            return end >= 0 && end < firstWrap
        }
        val end = original.indexOfAny(charArrayOf('/', '?', '#'))
        if (end < 0 || end >= firstWrap || compact.contains("://")) return false
        val parsed = web("https://$compact") ?: return false
        return (parsed.host == "localhost" || parsed.host.endsWith(".localhost") ||
            parsed.host.contains('.') || parsed.host.contains(':')) &&
            (parsed.tail.isNotEmpty() || parsed.port != null)
    }

    private data class Web(val url: String, val host: String, val tail: String, val port: String?)
    private fun web(value: String): Web? = runCatching {
        val separator = value.indexOf("://"); require(separator > 0)
        val scheme = value.substring(0, separator); require(scheme.lowercase() in setOf("http", "https"))
        val end = value.indexOfAny(charArrayOf('/', '?', '#'), separator + 3).let { if (it < 0) value.length else it }
        val authority = value.substring(separator + 3, end)
        require(authority.isNotEmpty() && authority.none(::space) && '\\' !in authority)
        val userEnd = authority.lastIndexOf('@')
        val userInfo = if (userEnd >= 0) authority.substring(0, userEnd + 1) else ""
        val hostPort = authority.substring(userEnd + 1)
        val host: String
        val port: String?
        if (hostPort.startsWith('[')) {
            val close = hostPort.indexOf(']'); require(close > 0)
            host = hostPort.substring(0, close + 1)
            val suffix = hostPort.substring(close + 1); require(suffix.isEmpty() || suffix.startsWith(':'))
            port = if (suffix.isEmpty()) null else suffix.drop(1)
            require(URI("$scheme://$host").host != null)
        } else {
            require(hostPort.count { it == ':' } <= 1)
            val split = hostPort.indexOf(':')
            val raw = if (split < 0) hostPort else hostPort.substring(0, split)
            require(raw.isNotEmpty() && raw.none { it in "[]%" || it.code < 32 || it.code == 127 })
            host = if (raw.any { it.code > 127 }) checkNotNull(asciiHost(raw)) else raw
            port = if (split < 0) null else hostPort.substring(split + 1)
        }
        require(host.isNotEmpty() && (port == null || port.all { it in '0'..'9' }))
        val tail = value.substring(end)
        val result = "$scheme://${encode(userInfo, AUTHORITY_USER, true)}$host${port?.let { ":$it" }.orEmpty()}${encode(tail, URL_TAIL, true)}"
        // URI syntax validation never performs DNS or network access.
        URI(result)
        Web(result, host, tail, port)
    }.getOrNull()

    private fun looksLikeHost(input: String): Boolean {
        if (input.any(::space)) return false
        val host = bareHost(input)
        if (host == "localhost" || host.count { it == ':' } >= 2) return true
        val dot = host.lastIndexOf('.')
        return dot > 0 && dot < host.lastIndex
    }
    private fun bareHost(input: String): String {
        val part = input.substringBefore('/')
        if (part.startsWith('[')) return part.indexOf(']').takeIf { it >= 0 }?.let { part.substring(1, it) } ?: part
        if (part.count { it == ':' } >= 2) return part
        return part.substringBefore(':')
    }
    private fun localHost(host: String): Boolean {
        if (host == "localhost" || host == "::1") return true
        val parts = host.split('.'); if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.any { it !in '0'..'9' }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return octets[0] == 127 || octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31)
    }

    companion object {
        const val DEFAULT_SEARCH = "https://duckduckgo.com/?q=%@"
        private const val UNRESERVED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        private const val QUERY_VALUE = UNRESERVED + "!$'()*,;:@/"
        private const val URL_TAIL = UNRESERVED + "!$&'()*+,;=:@/?#"
        private const val AUTHORITY_USER = UNRESERVED + "!$&'()*+,;=:@"
        private const val HEX = "0123456789ABCDEF"
        private fun wrapped(c: Char) = c in "\n\r\t\u000B\u000C\u0085\u2028\u2029"
        private fun space(c: Char) = c.isWhitespace() || Character.isSpaceChar(c) || c == '\u0085'
        private fun encode(text: String, allowed: String, preserveEscapes: Boolean): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return buildString {
                var i = 0
                while (i < bytes.size) {
                    val byte = bytes[i].toInt() and 255
                    val c = byte.toChar()
                    if (c == '%' && preserveEscapes && i + 2 < bytes.size &&
                        bytes[i + 1].toInt().toChar().isHex() && bytes[i + 2].toInt().toChar().isHex()) {
                        append('%'); append(bytes[++i].toInt().toChar()); append(bytes[++i].toInt().toChar())
                    } else if (byte < 128 && c in allowed) append(c)
                    else { append('%'); append(HEX[byte ushr 4]); append(HEX[byte and 15]) }
                    i++
                }
            }
        }
        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** UTS 46 avoids mapping distinct IDNA2008 hosts (for example ß) to another domain. */
internal object AndroidBrowserHost {
    private val idna by lazy { IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ) }
    fun toAscii(value: String): String? {
        val info = IDNA.Info(); val output = StringBuilder()
        idna.nameToASCII(value, output, info)
        return output.toString().takeUnless { info.hasErrors() }
    }
}
