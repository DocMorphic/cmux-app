package io.github.docmorphic.cmuxapp

import java.net.InetAddress
import java.net.URI

/** Wire-shape checks ported from CmxIrohPathHint+AddressValidation. No DNS or connection attempt. */
internal object MobileAttachHintAddress {
    fun validate(kind: String, value: String, public: Boolean) {
        when (kind) {
            "direct_address" -> {
                require(value == value.trim() && value.length <= 80 && value.none { it in "/@?#%" })
                val split = if (value.startsWith('[')) value.indexOf("]:") else value.lastIndexOf(':')
                require(split > 0)
                val host = value.substring(if (value.startsWith('[')) 1 else 0, split)
                val port = value.substring(split + if (value.startsWith('[')) 2 else 1)
                require(PORT.matches(port) && port.toIntOrNull() in 1..65535 && port.toInt().toString() == port)
                require(if (value.startsWith('[')) ':' in host else ':' !in host)
                val bytes = numeric(host)
                require(allowed(bytes) && (!public || global(bytes)))
            }
            "relay_identifier" -> require(IDENTIFIER.matches(value))
            "relay_url" -> {
                require(value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
                val uri = URI(value)
                require(uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
                require(uri.port == -1 || uri.port in 1..65535)
                val host = checkNotNull(uri.host).removeSurrounding("[", "]").lowercase()
                if (':' in host || IPV4.matches(host)) require(global(numeric(host))) else {
                    require(host.length <= 253 && !host.endsWith('.') &&
                        listOf(".localhost", ".local", ".home.arpa").none(host::endsWith))
                    val labels = host.split('.')
                    require(labels.size >= 2 && labels.all { DNS_LABEL.matches(it) } && labels.last().any { it in 'a'..'z' })
                }
            }
            else -> error("Invalid hint kind")
        }
    }

    private fun numeric(host: String): List<Int> {
        if (':' !in host) {
            require(IPV4.matches(host))
            return host.split('.').map { item ->
                val value = item.toInt(); require(value in 0..255 && value.toString() == item); value
            }
        }
        require(host.all { it in "0123456789abcdefABCDEF:." })
        if ('.' in host) numeric(host.substringAfterLast(':')) // No non-canonical embedded IPv4 octets.
        return InetAddress.getByName(host).address.map { it.toInt() and 255 }.also { require(it.size == 4 || it.size == 16) }
    }

    private fun allowed(b: List<Int>): Boolean = if (b.size == 4) {
        b[0] != 0 && b[0] != 127 && b[0] < 224 && !(b[0] == 169 && b[1] == 254)
    } else {
        b.any { it != 0 } && !(b.take(15).all { it == 0 } && b[15] == 1) && b[0] != 255 &&
            !(b[0] == 254 && b[1] and 192 == 128) && b != listOf(253, 0, 14, 194) + List(10) { 0 } + listOf(2, 84)
    }

    private fun global(b: List<Int>): Boolean {
        if (!allowed(b)) return false
        if (b.size == 4) {
            val a = b[0]; val c = b[1]; val d = b[2]
            return !(a == 10 || a == 100 && c in 64..127 || a == 172 && c in 16..31 || a == 192 && c == 168 ||
                a == 192 && c == 0 && d in setOf(0, 2) || a == 192 && c == 88 && d == 99 ||
                a == 198 && c in 18..19 || a == 198 && c == 51 && d == 100 || a == 203 && c == 0 && d == 113)
        }
        return b[0] and 224 == 32 && !(b[0] == 32 && b[1] == 1 && (b[2] <= 1 || b[2] == 13 && b[3] == 184)) &&
            !(b[0] == 32 && b[1] == 2) && !(b[0] == 63 && b[1] == 255 && b[2] and 240 == 0)
    }

    private val PORT = Regex("[0-9]{1,5}")
    private val IPV4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
    private val IDENTIFIER = Regex("[a-zA-Z0-9.:_-]{1,255}")
    private val DNS_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
}
