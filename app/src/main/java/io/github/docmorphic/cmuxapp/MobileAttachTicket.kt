package io.github.docmorphic.cmuxapp

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Base64

/** Decoded discovery data. Neither this object nor its routes admit a connection. */
class MobileAttachTicket internal constructor(
    internal val workspaceId: String, internal val terminalId: String?, internal val deviceId: String, internal val displayName: String?,
    internal val userEmail: String?, internal val userId: String?, internal val compatibilityVersion: Long?,
    internal val appVersion: String?, internal val appBuild: String?, internal val routes: List<MobileAttachRoute>,
    internal val expiresAtMillis: Long?, private val authToken: String?
) {
    internal fun context() = MobileAttachTicketContext(workspaceId, terminalId, authToken, expiresAtMillis)
    internal fun constrainingRoutes(routes: List<MobileAttachRoute>, fallbackDisplayName: String) = MobileAttachTicket(
        workspaceId, terminalId, deviceId, displayName ?: fallbackDisplayName, userEmail, userId,
        compatibilityVersion, appVersion, appBuild, routes, expiresAtMillis, authToken)
    internal fun requireAccount(user: String, email: String?) {
        require(userId == null || userId == user) { "This ticket belongs to another cmux account" }
        require(userEmail == null || (email != null && userEmail.trim().equals(email.trim(), true))) {
            "Sign in with the cmux account used by this Mac"
        }
    }
    internal fun requireHost(status: org.json.JSONObject) {
        require(canonicalMacDeviceId(status.optString("mac_device_id")) == deviceId) { "This ticket reaches a different Mac" }
    }
    override fun toString() = "MobileAttachTicket(redacted)"
}

internal data class MobileAttachRoute(val id: String, val kind: String, val priority: Long, val endpoint: MobileAttachEndpoint)
internal sealed interface MobileAttachEndpoint {
    data class HostPort(val host: String, val port: Int) : MobileAttachEndpoint
    data class Peer(val identity: String, val hints: List<MobileAttachHint>) : MobileAttachEndpoint
    data class Url(val url: String) : MobileAttachEndpoint
}

/** Untrusted reachability metadata, including inert historical fields. Never a dialing permission. */
internal data class MobileAttachHint(val kind: String, val value: String, val source: String, val privacy: String,
    val observedAtMillis: Long?, val expiresAtMillis: Long?, val profileSource: String?, val profileId: String?,
    val inertLegacy: Boolean)

internal object MobileAttachTicketCodec {
    /** Raw JSON from an authenticated ticket response, or decoded v1 QR payload. */
    fun decodeJson(json: String): Result<MobileAttachTicket> = safely { decode(PairingTicketJson.decode(json)) }

    /** Older URL grammars only. The current v2/v3 route parser stays separate until UI integration. */
    fun decodeLegacyUrl(input: String, nowMillis: Long = System.currentTimeMillis()): Result<MobileAttachTicket> = safely {
        require(input.length <= 100_000)
        val uri = URI(input.trim())
        require(PairingCodeParser.isCmuxScheme(uri.scheme) && uri.host in setOf("pair", "attach"))
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
        val query = linkedMapOf<String, String>()
        for (item in uri.rawQuery.orEmpty().split('&')) {
            val parts = item.split('=', limit = 2); require(parts.size == 2)
            fun unescape(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            val key = unescape(parts[0]); require(!query.containsKey(key)); query[key] = unescape(parts[1])
        }
        require(query.keys.none { key -> SECRET_MARKERS.any { key.contains(it, ignoreCase = true) } })
        val version = query["v"]?.toLongOrNull()
        require(uri.host == "pair" || version == null || version <= 1) // v2/v3 belong to the public route decoder.
        val encoded = checkNotNull(query["payload"]).replace('-', '+').replace('_', '/')
        require(encoded.length <= 90_000)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= 65_536)
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val value = PairingTicketJson.decode(json)
        if (uri.host == "pair") ancient(value, nowMillis) else decode(value, pairingUrl = true)
    }

    private fun <T> safely(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (_: Exception) { Result.failure(IllegalArgumentException("Invalid or unsupported cmux pairing ticket")) }

    private fun decode(value: Map<String, Any?>, pairingUrl: Boolean = false): MobileAttachTicket {
        val compact = value.containsKey("v")
        require(value.integer(if (compact) "v" else "version") == 1L)
        val workspace = if (compact) value.string("w") ?: "" else value.requiredString("workspaceID")
        val terminal = value.string(if (compact) "t" else "terminalID")
        val device = canonicalMacDeviceId(value.requiredString(if (compact) "d" else "macDeviceID"))
        val user = value.string(if (compact) "u" else "macUserID")
        val expiry = if (compact) null else value.date("expiresAt")
        val token = if (compact) null else value.string("auth_token", 4096) ?: value.string("authToken", 4096)
        require(token == null || token.isNotBlank())
        val occurrences = mutableMapOf<String, Int>()
        val routes = value.list(if (compact) "r" else "routes").also { require(it.size in 1..32) }.map { row ->
            val route = row.objectValue()
            val kind = route.requiredString(if (compact) "k" else "kind")
            require(kind in KINDS)
            val count = (occurrences[kind] ?: 0) + 1; occurrences[kind] = count
            val id = if (compact) route.string("i") ?: if (count == 1) kind else "${kind}_$count" else route.requiredString("id")
            val priority = route.optionalInteger(if (compact) "p" else "priority") ?: 0
            val endpoint = endpoint(route[if (compact) "e" else "endpoint"].objectValue(), compact)
            require(when (kind) {
                "tailscale", "debug_loopback" -> endpoint is MobileAttachEndpoint.HostPort
                "iroh" -> endpoint is MobileAttachEndpoint.Peer
                else -> endpoint is MobileAttachEndpoint.Url
            })
            MobileAttachRoute(id, kind, priority, endpoint)
        }
        return MobileAttachTicket(workspace, terminal, device, if (compact) null else value.string("macDisplayName"),
            if (compact) user?.takeIf { '@' in it } else value.string("macUserEmail"),
            if (compact) user?.takeUnless { '@' in it } else user,
            value.optionalInteger(if (compact) "pc" else "macPairingCompatibilityVersion") ?: if (compact || pairingUrl) 0 else null,
            value.string(if (compact) "av" else "macAppVersion"), value.string(if (compact) "ab" else "macAppBuild"),
            routes, expiry, token).also { it.context() }
    }

    private fun ancient(value: Map<String, Any?>, nowMillis: Long): MobileAttachTicket {
        require(value.keys.none { key -> SECRET_MARKERS.any { key.contains(it, ignoreCase = true) } })
        require(value.integer("version") == 1L)
        val expiry = checkNotNull(value.date("expires_at")); require(expiry > nowMillis)
        val kind = value.requiredString("transport"); require(kind in setOf("tailscale", "debug_loopback"))
        val host = value.requiredString("host"); require(host.isNotBlank())
        val port = value.integer("port"); require(port in 1..65535)
        return MobileAttachTicket("workspace-main", null, canonicalMacDeviceId(value.requiredString("mac_device_id")),
            value.string("mac_display_name"), null, null, 0, null, null,
            listOf(MobileAttachRoute(kind, kind, 0, MobileAttachEndpoint.HostPort(host, port.toInt()))), expiry, null)
    }

    private fun endpoint(value: Map<String, Any?>, compact: Boolean): MobileAttachEndpoint {
        fun key(short: String, full: String) = if (compact) short else full
        // Swift's synthesized compact DTO decoder type-checks even fields unused by explicit `t`.
        if (compact) {
            for (field in listOf("t", "h", "i", "rh", "ru", "u")) value.string(field, 8192)
            value.optionalInteger("p")
            value.optionalList("da")?.forEach { require(it is String) }
            value.optionalList("ph")?.forEach { hint(it.objectValue()) }
        }
        val type = value.string(key("t", "type")) ?: if (!compact) error("Missing endpoint type") else when {
            value["u"] != null -> "url"
            value["i"] != null -> "peer"
            value["h"] != null && value["p"] != null -> "host_port"
            else -> error("Missing endpoint type")
        }
        return when (type) {
            "host_port" -> {
                val host = value.requiredString(key("h", "host")); require(host.isNotBlank())
                val port = value.integer(key("p", "port")); require(port in 1..65535)
                MobileAttachEndpoint.HostPort(host, port.toInt())
            }
            "url" -> MobileAttachEndpoint.Url(value.requiredString(key("u", "url"), 8192).also { require(it.isNotBlank()) })
            "peer" -> {
                val identity = value.requiredString(key("i", "id")); require(HEX_ID.matches(identity))
                val hints = value.optionalList(key("ph", "path_hints"))?.map { hint(it.objectValue()) } ?: buildList {
                    value.string(key("rh", "relay_hint"))?.let { add(legacyHint("relay_identifier", it, "public_internet")) }
                    value.optionalList(key("da", "direct_addrs"))?.forEach {
                        add(legacyHint("direct_address", it as? String ?: error("Invalid legacy address"), "private_network"))
                    }
                    value.string(key("ru", "relay_url"), 2048)?.let { add(legacyHint("relay_url", it, "public_internet")) }
                }
                require(hints.size <= 16)
                MobileAttachEndpoint.Peer(identity, hints)
            }
            else -> error("Invalid endpoint type")
        }
    }

    private fun legacyHint(kind: String, value: String, privacy: String): MobileAttachHint {
        require(value.isNotBlank() && value.length <= 2048)
        val inert = privacy != "public_internet" || runCatching { MobileAttachHintAddress.validate(kind, value, true) }.isFailure
        return MobileAttachHint(kind, value, "native", privacy, null, null, null, null, inert)
    }

    private fun hint(value: Map<String, Any?>): MobileAttachHint {
        val kind = value.requiredString("kind"); require(kind in setOf("direct_address", "relay_identifier", "relay_url"))
        val text = value.requiredString("value", 2048); require(text.isNotBlank())
        val source = value.requiredString("source"); require(source in SOURCES)
        val privacy = value.requiredString("privacy_scope"); require(privacy in setOf("public_internet", "local_network", "private_network"))
        val observed = value.date("observed_at"); val expiry = value.date("expires_at")
        val profile = value["network_profile"]?.objectValue()
        val profileId = profile?.requiredString("profile_id") ?: value.string("network_profile_id")
        val profileSource = profile?.requiredString("source") ?: profileId?.let { source }
        require(profileId == null || HEX_ID.matches(profileId)); require(profileSource == null || profileSource in SOURCES)
        val current = privacy == "public_internet" || (observed != null && expiry != null && profileId != null)
        if (kind != "direct_address") require(source == "native" && privacy == "public_internet")
        when (source) {
            "native" -> require(privacy == "public_internet" || (!current && observed == null && expiry == null && profileId == null))
            "lan" -> require(privacy == "local_network")
            else -> require(privacy == "private_network")
        }
        if (privacy == "public_internet") require(profileId == null)
        else if (current) {
            require(profileSource == source)
            val lifetime = Math.subtractExact(checkNotNull(expiry), checkNotNull(observed))
            require(lifetime in 1..3_600_000)
        }
        if (current) MobileAttachHintAddress.validate(kind, text, privacy == "public_internet")
        return MobileAttachHint(kind, text, source, privacy, observed, expiry, profileSource, profileId, !current)
    }

    private fun Any?.objectValue(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return this as? Map<String, Any?> ?: error("Invalid ticket object")
    }
    private fun Map<String, Any?>.string(key: String, max: Int = 1024): String? = this[key]?.let {
        (it as? String ?: error("Invalid ticket string")).also { text -> require(text.length <= max) }
    }
    private fun Map<String, Any?>.requiredString(key: String, max: Int = 1024) = checkNotNull(string(key, max))
    private fun Map<String, Any?>.optionalInteger(key: String): Long? = this[key]?.let {
        (it as? BigDecimal ?: error("Invalid ticket integer")).longValueExact()
    }
    private fun Map<String, Any?>.integer(key: String) = checkNotNull(optionalInteger(key))
    private fun Map<String, Any?>.date(key: String): Long? = string(key, 64)?.let { Instant.parse(it).toEpochMilli() }
    private fun Map<String, Any?>.optionalList(key: String): List<Any?>? = this[key]?.let { it as? List<*> ?: error("Invalid ticket list") }
    private fun Map<String, Any?>.list(key: String) = checkNotNull(optionalList(key))
    private val HEX_ID = Regex("[0-9a-f]{64}")
    private val KINDS = setOf("tailscale", "iroh", "websocket", "debug_loopback")
    private val SOURCES = setOf("native", "lan", "tailscale", "custom_vpn")
    private val SECRET_MARKERS = listOf("token", "secret", "auth", "password", "bearer", "credential", "jwt")
}
