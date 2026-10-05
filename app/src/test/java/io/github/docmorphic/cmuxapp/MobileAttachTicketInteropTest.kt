package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Expected values are emitted by pinned upstream Swift, not this decoder. */
@RunWith(Parameterized::class)
class MobileAttachTicketInteropTest(private val name: String, private val fixture: JSONObject) {
    @Test fun legacyUrlMatchesSwift() {
        val decoded = MobileAttachTicketCodec.decodeLegacyUrl(fixture.getString("url"), 1_800_000_000_000)
        assertEquals(name, fixture.getBoolean("accepted"), decoded.isSuccess)
        if (decoded.isSuccess) assertEquals(name, normalized(fixture.getJSONObject("expected")), snapshot(decoded.getOrThrow()))
        // Raw RPC tickets retain absent compatibility; only URL input normalizes it to zero.
        if (fixture.getString("url").contains("://pair?")) return
        val raw = MobileAttachTicketCodec.decodeJson(fixture.getString("json"))
        assertEquals(name, fixture.getBoolean("accepted"), raw.isSuccess)
        if (raw.isSuccess) assertEquals(name, normalized(fixture.getJSONObject("rawExpected")), snapshot(raw.getOrThrow()))
    }

    private fun snapshot(ticket: MobileAttachTicket): Map<String, Any?> = mapOf(
        "workspace" to ticket.workspaceId, "terminal" to ticket.terminalId, "device" to ticket.deviceId,
        "name" to ticket.displayName, "email" to ticket.userEmail, "user" to ticket.userId,
        "compatibility" to ticket.compatibilityVersion, "appVersion" to ticket.appVersion, "appBuild" to ticket.appBuild,
        "expires" to ticket.expiresAtMillis,
        "token" to ticket.context().tokenFor("workspace.list", JSONObject(), Long.MIN_VALUE),
        "routes" to ticket.routes.map { route -> mapOf(
            "id" to route.id, "kind" to route.kind, "priority" to route.priority,
            "endpoint" to when (val endpoint = route.endpoint) {
                is MobileAttachEndpoint.HostPort -> mapOf("type" to "host_port", "host" to endpoint.host, "port" to endpoint.port.toLong())
                is MobileAttachEndpoint.Url -> mapOf("type" to "url", "url" to endpoint.url)
                is MobileAttachEndpoint.Peer -> mapOf("type" to "peer", "identity" to endpoint.identity,
                    "hints" to endpoint.hints.map { hint -> mapOf(
                        "kind" to hint.kind, "value" to hint.value, "source" to hint.source, "privacy" to hint.privacy,
                        "observed" to hint.observedAtMillis, "expires" to hint.expiresAtMillis,
                        "profileSource" to hint.profileSource, "profileId" to hint.profileId, "inert" to hint.inertLegacy
                    ) })
            }
        ) }
    )

    private fun normalized(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { normalized(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        is Number -> value.toLong()
        else -> value
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val resource = checkNotNull(MobileAttachTicketInteropTest::class.java.getResourceAsStream("/pairing/attach-tickets.json"))
            val root = resource.bufferedReader().use { JSONObject(it.readText()) }
            val fixtures = root.getJSONArray("fixtures")
            return (0 until fixtures.length()).map { i ->
                val fixture = fixtures.getJSONObject(i)
                arrayOf(fixture.getString("name"), fixture)
            }
        }
    }
}
