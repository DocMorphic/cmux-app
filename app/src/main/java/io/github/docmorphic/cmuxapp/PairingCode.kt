package io.github.docmorphic.cmuxapp

import java.net.URI

/** A bounded preview of the public cmux attach QR grammar. This never authenticates a Mac. */
sealed interface PairingCode {
    data class Tailscale(val routes: List<Route>, val stackUserId: String?) : PairingCode
    data class Iroh(val endpointId: String, val macDeviceId: String?) : PairingCode
    data class Route(val host: String, val port: Int)
}

object PairingCodeParser {
    private const val MAX_CODE_LENGTH = 4096

    fun parse(input: String): Result<PairingCode> = runCatching {
        require(input.length <= MAX_CODE_LENGTH) { "Pairing code is too long" }
        val uri = URI(input.trim())
        require(isCmuxScheme(uri.scheme) && uri.host == "attach") {
            "Expected a cmux attach QR code"
        }
        val parameters = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { item ->
            val parts = item.split('=', limit = 2)
            require(parts.size == 2) { "Malformed pairing code" }
            parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
        }
        require(parameters.none { (key, _) ->
            listOf("token", "secret", "auth", "password", "bearer", "credential", "jwt").any { key.contains(it, ignoreCase = true) }
        }) { "Pairing codes cannot contain credentials" }
        fun single(key: String): String? = parameters.singleOrNull { it.first == key }?.second
        when (single("v")) {
            "2" -> {
                val routes = parameters.filter { it.first == "r" }.map { parseRoute(it.second) }
                require(routes.isNotEmpty() && routes.size <= 8) { "Expected 1 to 8 private network routes" }
                PairingCode.Tailscale(routes, single("ub"))
            }
            "3" -> {
                val endpointId = single("i").orEmpty()
                require(endpointId.isNotBlank() && endpointId.length <= 256) { "Missing Iroh endpoint ID" }
                PairingCode.Iroh(endpointId, single("d"))
            }
            else -> error("This pairing code version is not supported yet")
        }
    }

    private fun isCmuxScheme(value: String?): Boolean = value in setOf(
        "cmux-ios", "cmux-ios-dev", "cmux-ios-com.cmux.app",
        "cmux-ios-dev.cmux.app.beta", "cmux-ios-dev.cmux.app.internal",
        "cmux-ios-dev.cmux.app.demo", "cmux-ios-dev.cmux.ios"
    ) || value?.startsWith("cmux-ios-dev.cmux.ios.") == true

    private fun parseRoute(value: String): PairingCode.Route {
        val routeUri = URI("tcp://$value")
        val host = routeUri.host ?: error("Invalid route host")
        val port = routeUri.port
        require(port in 1..65535) { "Invalid route port" }
        require(host != "localhost" && host != "127.0.0.1" && host != "::1") {
            "A phone cannot connect to the Mac through a loopback address"
        }
        require(isTailscaleHost(host)) { "The Mac route must be a Tailscale address" }
        return PairingCode.Route(host, port)
    }

    private fun isTailscaleHost(host: String): Boolean {
        if (host.lowercase().endsWith(".ts.net") && host.length > ".ts.net".length) return true
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127 &&
            parts.drop(2).all { it in 0..255 }
    }
}
