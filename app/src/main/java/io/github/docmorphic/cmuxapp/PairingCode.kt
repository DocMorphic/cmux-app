package io.github.docmorphic.cmuxapp

import java.net.URI

/** A bounded preview of the public cmux attach QR grammar. This never authenticates a Mac. */
sealed interface PairingCode {
    data class Tailscale(val routes: List<Route>, val stackUserId: String?) : PairingCode
    data class Iroh(val endpointId: String, val macDeviceId: String?, val userId: String? = null,
                    val teamId: String? = null, val buildTag: String? = null) : PairingCode
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
        fun single(key: String): String? {
            val values = parameters.filter { it.first == key }
            require(values.size <= 1) { "Duplicate pairing field" }
            return values.singleOrNull()?.second
        }
        when (single("v")) {
            "2" -> {
                val routes = parameters.filter { it.first == "r" }.map { parseRoute(it.second) }
                require(routes.isNotEmpty() && routes.size <= 8) { "Expected 1 to 8 private network routes" }
                PairingCode.Tailscale(routes, single("ub"))
            }
            "3" -> {
                val endpointId = single("i").orEmpty()
                require(endpointId.isNotBlank() && endpointId.length <= 256) { "Missing Iroh endpoint ID" }
                val user = single("ub")
                val team = single("t")
                val device = single("d")
                val build = single("b")
                require(listOf(user, team, device, build).all { it == null || (it.isNotBlank() && it.length <= 128) }) { "Invalid computer identity" }
                if (uri.scheme == "cmux-android") require(user != null && team != null && device != null && build != null) { "Missing computer scope" }
                PairingCode.Iroh(endpointId, device, user, team, build)
            }
            else -> error("This pairing code version is not supported yet")
        }
    }

    private fun isCmuxScheme(value: String?): Boolean = value in setOf(
        "cmux-android", "cmux-ios", "cmux-ios-dev", "cmux-ios-com.cmux.app",
        "cmux-ios-dev.cmux.app.beta", "cmux-ios-dev.cmux.app.internal",
        "cmux-ios-dev.cmux.app.demo", "cmux-ios-dev.cmux.ios"
    ) || value?.startsWith("cmux-ios-dev.cmux.ios.") == true

    /** A saved lookup hint only; connecting still requires a fresh authorized directory entry. */
    internal fun computer(computer: IrohV2Computer, scope: NativeTeamScope): String {
        val fields = listOf("v" to "3", "i" to computer.endpointId, "d" to computer.deviceId,
            "ub" to scope.userId, "t" to scope.teamId, "b" to computer.buildTag)
        return "cmux-android://attach?" + fields.joinToString("&") { (key, value) ->
            "$key=${java.net.URLEncoder.encode(value, "UTF-8")}" }
    }

    private fun parseRoute(value: String): PairingCode.Route {
        val routeUri = URI("tcp://$value")
        val host = (routeUri.host ?: error("Invalid route host")).removeSurrounding("[", "]")
        val port = routeUri.port
        require(routeUri.rawUserInfo == null && routeUri.rawPath.isNullOrEmpty() &&
            routeUri.rawQuery == null && routeUri.rawFragment == null) { "Invalid route address" }
        require(port in 1..65535) { "Invalid route port" }
        require(host != "localhost" && host != "127.0.0.1" && host != "::1") {
            "A phone cannot connect to the Mac through a loopback address"
        }
        val peer = TailscalePeerAddress.canonical(host)
        require(peer != null || TailscalePeerAddress.isMagicDnsName(host)) { "The Mac route must be a Tailscale peer address" }
        return PairingCode.Route(peer ?: host.lowercase(), port)
    }
}
