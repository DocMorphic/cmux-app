package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class TailscalePathProofTest {
    private val self = "100.99.1.1"
    private val peer = "100.99.1.2"
    private val tunnel = TailscaleTunnel(42, "tun0", setOf(self, "fd7a:115c:a1e0::1"))
    private val route = PairingCode.Route(peer, 58465)

    @Test fun canonicalNumericPeersSupportBothFamilies() {
        listOf("100.64.0.1", "100.127.255.254", peer, "fd7a:115c:a1e0::2").forEach {
            assertEquals(it, TailscalePeerAddress.canonical(it))
        }
        assertEquals("fd7a:115c:a1e0::2", TailscalePeerAddress.canonical("FD7A:115C:A1E0:0:0:0:0:2"))
    }

    @Test fun reservedServicesNonPeersAndAmbiguousSyntaxAreRejected() {
        listOf("100.100.0.1", "100.100.100.100", "100.115.92.1", "100.115.93.255",
            "fd7a:115c:a1e0::53", "fd7a:115c:a1e0:0:0:0:0:0053", "100.63.255.255", "100.128.0.1",
            "192.168.1.1", "127.0.0.1", "0.0.0.0", "100.064.0.1", "100.64.0.01", "+100.64.0.1",
            "100.64.1", "1681915905", "100.64.0.256", "100.64.0.1 ", " 100.64.0.1",
            "::ffff:100.64.0.1", "::ffff:6440:1", "::1", "fd7a:115c:a1e1::1", "fe80::1%tun0",
            "[fd7a:115c:a1e0::2]", "mac.tail.ts.net", "", "100.64.0.1\n").forEach {
            assertNull(it, TailscalePeerAddress.canonical(it))
        }
    }

    @Test fun magicDnsRequiresCompleteAsciiLabelsAndExactSuffix() {
        assertTrue(TailscalePeerAddress.isMagicDnsName("mac.tail-net.TS.NET"))
        listOf("ts.net", ".ts.net", "mac..ts.net", "-mac.ts.net", "mac-.ts.net", "mac.ts.net.evil",
            "mac.ts.net/", "mac.ts.net.", "x@mac.ts.net", "mác.ts.net", "a".repeat(64) + ".ts.net").forEach {
            assertFalse(it, TailscalePeerAddress.isMagicDnsName(it))
        }
    }

    @Test fun preparationRequiresOneNamedTunnelWithTailnetAddressesAndRemotePeer() {
        assertEquals(route, TailscalePathProof.prepare(listOf(tunnel), route).route)
        listOf(emptyList(), listOf(tunnel, tunnel.copy(network = 43)),
            listOf(tunnel.copy(name = "")), listOf(tunnel.copy(peers = emptySet())),
            listOf(tunnel.copy(peers = setOf("192.168.1.1")))).forEach {
            assertTrue(runCatching { TailscalePathProof.prepare(it, route) }.isFailure)
        }
        listOf(self, "fd7a:115c:a1e0::1", "mac.tail.ts.net", "100.100.100.100").forEach {
            assertTrue(runCatching { TailscalePathProof.prepare(listOf(tunnel), route.copy(host = it)) }.isFailure)
        }
        assertTrue(runCatching { TailscalePathProof.prepare(listOf(tunnel), route.copy(port = 0)) }.isFailure)
    }

    @Test fun establishedConnectionMustKeepTunnelAndBothExactEndpoints() {
        val proof = TailscalePathProof.prepare(listOf(tunnel), route)
        proof.validate(listOf(tunnel), self, peer, 58465)
        listOf(emptyList(), listOf(tunnel.copy(network = 44)), listOf(tunnel.copy(name = "tun1")),
            listOf(tunnel.copy(peers = setOf(self))), listOf(tunnel, tunnel.copy(network = 45))).forEach {
            assertTrue(runCatching { proof.validate(it, self, peer, 58465) }.isFailure)
        }
        assertTrue(runCatching { proof.validate(listOf(tunnel), "192.168.1.5", peer, 58465) }.isFailure)
        assertTrue(runCatching { proof.validate(listOf(tunnel), self, "100.99.1.3", 58465) }.isFailure)
        assertTrue(runCatching { proof.validate(listOf(tunnel), self, peer, 58466) }.isFailure)
    }

    @Test fun ipv6EndpointSpellingsAreComparedByCanonicalAddress() {
        val proof = TailscalePathProof.prepare(listOf(tunnel), route.copy(host = "FD7A:115C:A1E0:0:0:0:0:2"))
        proof.validate(listOf(tunnel), "fd7a:115c:a1e0:0:0:0:0:1", "fd7a:115c:a1e0:0:0:0:0:2", 58465)
    }
}
