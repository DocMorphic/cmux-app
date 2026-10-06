package io.github.docmorphic.cmuxapp

import java.net.InetAddress

/** The system VPN routes private Cloud networks, never the public Internet or DNS. */
internal object CloudVpnRoutePolicy {
    fun permits(cidr: String): Boolean {
        val parts = cidr.trim().split('/')
        if (parts.size != 2 || !parts[1].matches(Regex("[0-9]{1,3}"))) return false
        val prefix = parts[1].toInt()
        val host = parts[0]
        if (':' in host) {
            // Numeric-only grammar prevents DNS, zone identifiers and URI parsing.
            if (!host.matches(Regex("[0-9a-fA-F:]{2,39}")) || prefix !in 7..128) return false
            val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
            return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        }
        val octets = host.split('.')
        if (prefix !in 0..32 || octets.size != 4 || octets.any {
            !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255
        }) return false
        val a = octets[0].toInt(); val b = octets[1].toInt()
        return (a == 10 && prefix >= 8) || (a == 172 && b in 16..31 && prefix >= 12) ||
            (a == 192 && b == 168 && prefix >= 16) || (a == 100 && b in 64..127 && prefix >= 10)
    }

    fun permits(enrollment: CloudTunnelEnrollment): Boolean = enrollment.routes.isNotEmpty() &&
        enrollment.routes.size <= 256 && enrollment.routes.all(::permits) &&
        listOfNotNull(enrollment.addressV4?.let { if ('/' in it) it else "$it/32" },
            enrollment.addressV6?.let { if ('/' in it) it else "$it/128" }).all(::permits)

    /** Revalidate the installed text; server clientConfig may disagree with structured fields. */
    fun permitsConfiguration(text: String): Boolean {
        if (text.length !in 1..65536 || '\u0000' in text) return false
        var section = ""
        val sections = mutableSetOf<String>()
        var routes = 0
        var addresses = 0
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[')) {
                section = line.lowercase(java.util.Locale.ROOT)
                if (section !in setOf("[interface]", "[peer]") || !sections.add(section)) return false
                continue
            }
            if ('=' !in line) return false
            val name = line.substringBefore('=').trim().lowercase(java.util.Locale.ROOT)
            val value = line.substringAfter('=').trim()
            val allowed = when (section) {
                "[interface]" -> setOf("privatekey", "address", "mtu")
                "[peer]" -> setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive")
                else -> emptySet()
            }
            // DNS, scripts, Table and application-routing overrides are not accepted.
            if (name !in allowed || value.isEmpty()) return false
            if (name == "address" || name == "allowedips") {
                val entries = value.split(',').map(String::trim)
                if (entries.any { !permits(it) }) return false
                if (name == "allowedips") routes += entries.size else addresses += entries.size
            }
        }
        return sections == setOf("[interface]", "[peer]") && routes in 1..256 && addresses in 1..256
    }

    fun configuration(enrollment: CloudTunnelEnrollment, key: CloudWireGuardKey): CloudWireGuardConfig {
        require(permits(enrollment)) { "Cloud VPN configuration must contain only private routes" }
        return CloudWireGuardConfig.make(enrollment, key).also {
            require(permitsConfiguration(it.text)) { "Cloud VPN configuration must contain only private routes" }
        }
    }
}
