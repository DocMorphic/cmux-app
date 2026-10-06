package io.github.docmorphic.cmuxapp

import java.util.Locale

/** In-memory only. The native WireGuard parser remains responsible for IP/route validation. */
internal class CloudWireGuardConfig private constructor(val text: String) {
    override fun toString() = "CloudWireGuardConfig(redacted)"
    companion object {
        fun make(enrollment: CloudTunnelEnrollment, key: CloudWireGuardKey): CloudWireGuardConfig =
            if (enrollment.clientConfig.isBlank()) fromFields(enrollment, key) else complete(enrollment.clientConfig, key)

        fun complete(serverConfig: String, key: CloudWireGuardKey): CloudWireGuardConfig {
            require(serverConfig.length <= 65536 && '\u0000' !in serverConfig) { "Invalid Cloud tunnel configuration" }
            val lines = serverConfig.lines().toMutableList()
            var section = ""
            val seen = mutableSetOf<String>()
            val replaced = mutableSetOf<String>()
            for (index in lines.indices) {
                val value = lines[index].substringBefore('#').trim()
                if (value.startsWith('[')) {
                    require(value in listOf("[Interface]", "[Peer]") || value.lowercase(Locale.ROOT) in listOf("[interface]", "[peer]")) { "Invalid Cloud tunnel section" }
                    section = value.lowercase(Locale.ROOT)
                    require(seen.add(section)) { "Duplicate Cloud tunnel section" }
                    lines[index] = if (section == "[interface]") "[Interface]" else "[Peer]"
                } else if ('=' in value) {
                    val name = value.substringBefore('=').trim().lowercase(Locale.ROOT)
                    if (name == "privatekey" || name == "persistentkeepalive") {
                        require(section == if (name == "privatekey") "[interface]" else "[peer]") { "Misplaced Cloud tunnel setting" }
                        require(replaced.add(name)) { "Duplicate Cloud tunnel setting" }
                        lines[index] = if (name == "privatekey") "PrivateKey = ${key.privateKeyBase64()}" else "PersistentKeepalive = 25"
                    }
                }
            }
            require(seen == setOf("[interface]", "[peer]")) { "Missing Cloud tunnel section" }
            if ("privatekey" !in replaced) lines.add(lines.indexOf("[Interface]") + 1, "PrivateKey = ${key.privateKeyBase64()}")
            if ("persistentkeepalive" !in replaced) lines.add(lines.indexOf("[Peer]") + 1, "PersistentKeepalive = 25")
            return CloudWireGuardConfig(lines.joinToString("\n"))
        }

        private fun fromFields(enrollment: CloudTunnelEnrollment, key: CloudWireGuardKey): CloudWireGuardConfig {
            fun field(value: String): String {
                require(value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() || it == '#' || it == '=' || it == '\\' }) { "Invalid Cloud tunnel field" }
                return value
            }
            fun hostAddress(value: String, bits: Int) = field(value).let { if ('/' in it) it else "$it/$bits" }
            require(enrollment.endpointPort in 1..65535 && enrollment.routes.size in 1..256) { "Invalid Cloud tunnel endpoint or routes" }
            val lines = mutableListOf("[Interface]", "PrivateKey = ${key.privateKeyBase64()}")
            enrollment.addressV4?.let { lines += "Address = ${hostAddress(it, 32)}" }
            enrollment.addressV6?.let { lines += "Address = ${hostAddress(it, 128)}" }
            lines += listOf("MTU = 1200", "", "[Peer]", "PublicKey = ${field(enrollment.serverPublicKey)}",
                "AllowedIPs = ${enrollment.routes.joinToString(", ") { field(it) }}")
            enrollment.endpointHost?.let {
                val host = field(it)
                lines += "Endpoint = ${if (':' in host && !host.startsWith('[')) "[$host]" else host}:${enrollment.endpointPort}"
            }
            lines += "PersistentKeepalive = 25"
            return CloudWireGuardConfig(lines.joinToString("\n") + "\n")
        }
    }
}
