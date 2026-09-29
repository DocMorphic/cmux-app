package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeParserTest {
    @Test fun ipv6RoutesCanonicalizeAndRejectServiceOrDecoratedAddresses() {
        val parsed = PairingCodeParser.parse("cmux-ios://attach?v=2&r=%5BFD7A:115C:A1E0:0:0:0:0:2%5D:58465")
            .getOrThrow() as PairingCode.Tailscale
        assertEquals(PairingCode.Route("fd7a:115c:a1e0::2", 58465), parsed.routes.single())
        listOf("100.100.100.100:58465", "100.064.0.1:58465", "[fd7a:115c:a1e0::53]:58465",
            "100.99.1.1:58465/path", "someone@100.99.1.1:58465", "100.99.1.1:58465?x=y",
            "100.99.1.1:58465#part").forEach { route ->
            assertTrue(route, PairingCodeParser.parse("cmux-ios://attach?v=2&r=" +
                java.net.URLEncoder.encode(route, "UTF-8")).isFailure)
        }
    }

    @Test fun acceptsTailscaleRoutes() {
        val parsed = PairingCodeParser.parse("cmux-ios://attach?v=2&ub=user123&pc=1&r=mac.tailnet.ts.net:58465")
            .getOrThrow() as PairingCode.Tailscale
        assertEquals("user123", parsed.stackUserId)
        assertEquals(PairingCode.Route("mac.tailnet.ts.net", 58465), parsed.routes.single())
    }

    @Test fun acceptsCurrentBundleSpecificReleaseScheme() {
        val parsed = PairingCodeParser.parse("cmux-ios-com.cmux.app://attach?v=2&ub=user123&pc=1&r=100.99.4.3:58465")
            .getOrThrow() as PairingCode.Tailscale
        assertEquals("100.99.4.3", parsed.routes.single().host)
    }

    @Test fun acceptsIrohIdentity() {
        val parsed = PairingCodeParser.parse("cmux-ios://attach?v=3&i=endpoint123&d=mac123")
            .getOrThrow() as PairingCode.Iroh
        assertEquals("endpoint123", parsed.endpointId)
        assertEquals("mac123", parsed.macDeviceId)
    }

    @Test fun rejectsLoopbackAndUnknownVersions() {
        assertTrue(PairingCodeParser.parse("cmux-ios://attach?v=2&r=127.0.0.1:58465").isFailure)
        assertTrue(PairingCodeParser.parse("cmux-ios://attach?v=4&i=peer").isFailure)
        assertTrue(PairingCodeParser.parse("cmux-ios://attach?v=2&r=example.com:58465").isFailure)
    }

    @Test fun localComputerLocatorRoundTripsItsAccountTeamAndBuildWithoutCredentials() {
        val mac = IrohV2Computer("record", "ab".repeat(32), "device+id", "beta/test", "Mac", emptyList())
        val scope = NativeTeamScope("login-not-in-locator", "user/a", "team&b", 4)
        val code = PairingCodeParser.computer(mac, scope)
        assertEquals(PairingCode.Iroh(mac.endpointId, mac.deviceId, scope.userId, scope.teamId, mac.buildTag),
            PairingCodeParser.parse(code).getOrThrow())
        assertTrue(!code.contains(scope.login))
        assertTrue(PairingCodeParser.parse(code + "&t=other").isFailure)
        assertTrue(PairingCodeParser.parse(code + "&access_token=secret").isFailure)
        assertTrue(PairingCodeParser.parse("cmux-android://attach?v=3&i=peer&d=mac").isFailure)
    }
}
