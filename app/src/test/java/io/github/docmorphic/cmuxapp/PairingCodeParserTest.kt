package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeParserTest {
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
    }
}
