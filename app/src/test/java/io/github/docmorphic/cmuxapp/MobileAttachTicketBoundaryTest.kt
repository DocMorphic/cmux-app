package io.github.docmorphic.cmuxapp

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileAttachTicketBoundaryTest {
    private val base = """{"v":1,"d":"mac","r":[{"k":"tailscale","e":{"h":"100.64.0.7","p":58465}}]}"""
    private fun url(json: String = base, prefix: String = "cmux-ios://attach?v=1&payload=") =
        prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun rejectsAmbiguousOrPermissiveJsonWithoutEchoingInput() {
        for (json in listOf(
            base.replace("\"v\":1", "\"v\":1,\"v\":1"),
            base.replace("\"v\":1", "\"v\":1,\"\\u0076\":1"),
            base + "trailing-sensitive-value", base.replace("\"v\"", "'v'"),
            base.replace("\"v\"", "v"), base.replace("\"v\":1", "\"v\":01"),
            base.replace("\"v\":1", "\"v\":NaN"), base.replace("\"v\":1", "/*comment*/\"v\":1"),
            base.dropLast(1) + ",}", base.replace("\"mac\"", "\"\\ud800\""),
            base.replace("\"mac\"", "\"\\udc00\""), base.replace("\"mac\"", "\"a\nb\""))) {
            val failure = MobileAttachTicketCodec.decodeJson(json).exceptionOrNull()
            assertNotNull(json, failure)
            assertEquals("Invalid or unsupported cmux pairing ticket", failure!!.message)
            assertNull(failure.cause)
        }
    }

    @Test fun boundsDepthBytesCollectionsStringsAndIntegers() {
        val tooMany = List(257) { "null" }.joinToString(",")
        for (json in listOf(" ".repeat(65_537),
            base.dropLast(1) + ",\"extra\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}",
            base.dropLast(1) + ",\"extra\":[$tooMany]}",
            base.replace("\"mac\"", "\"${"m".repeat(1025)}\""),
            base.replace("\"v\":1", "\"v\":1e1000000000"),
            base.replace("\"v\":1", "\"v\":9223372036854775808"),
            base.replace("\"v\":1", "\"v\":1.5")))
            assertTrue(MobileAttachTicketCodec.decodeJson(json).isFailure)
    }

    @Test fun rejectsDuplicateQueryCredentialsDecoratedUrlsAndFutureRouteVersions() {
        val valid = url()
        for (input in listOf(valid + "&payload=another", valid + "&auth_token=synthetic-secret",
            valid + "&%61uth=synthetic-secret", valid + "#fragment",
            valid.replace("://attach?", "://user@attach?"), valid.replace("://attach?", "://attach:80?"),
            valid.replace("://attach?", "://attach/path?"), valid.replace("v=1&", "v=3&"),
            valid.replace("v=1&", "v=4&"), valid.replace("cmux-ios:", "https:"),
            valid + "&v=1", "cmux-ios://attach?payload=%ff", "x".repeat(100_001)))
            assertTrue(input.take(80), MobileAttachTicketCodec.decodeLegacyUrl(input).isFailure)
    }

    @Test fun rejectsMalformedUtf8AndSupportsPercentEncodedBase64AndSchemes() {
        val invalidUtf8 = Base64.getEncoder().encodeToString(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        assertTrue(MobileAttachTicketCodec.decodeLegacyUrl("cmux-ios://attach?payload=$invalidUtf8").isFailure)
        for (scheme in listOf("cmux-ios", "cmux-ios-dev")) {
            val encoded = Base64.getEncoder().encodeToString(base.toByteArray()).replace("=", "%3D")
            assertTrue(MobileAttachTicketCodec.decodeLegacyUrl("$scheme://attach?payload=$encoded").isSuccess)
        }
    }

    @Test fun noBearerSurvivesCompactDecodingOrAppearsInDiagnostics() {
        val json = base.dropLast(1) + ",\"auth_token\":\"synthetic-bearer\",\"e\":\"2100-01-01T00:00:00Z\"}"
        val ticket = MobileAttachTicketCodec.decodeJson(json).getOrThrow()
        assertNull(ticket.context().tokenFor("workspace.list", JSONObject(), 0))
        assertNull(ticket.expiresAtMillis)
        assertEquals("MobileAttachTicket(redacted)", ticket.toString())
        assertEquals("MobileAttachTicketContext(redacted)", ticket.context().toString())
    }

    @Test fun legacyDecoderDoesNotChangeCurrentPairingUiAdmission() {
        assertTrue(MobileAttachTicketCodec.decodeLegacyUrl(url()).isSuccess)
        assertTrue(PairingCodeParser.parse(url()).isFailure)
    }
}
