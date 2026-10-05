package io.github.docmorphic.cmuxapp.iroh

import org.junit.Assert.*
import org.junit.Test

class IrxDialTargetTest {
    private val peer = "ab".repeat(32)
    private val address = "192.168.1.20:58470"

    @Test fun automaticAcceptsPrivateAddressesWithoutARemoteRelayHint() {
        val target = IrxDialTarget.create(IrxEndpointPathMode.AUTOMATIC, peer, null, listOf(address))
        assertNull(target.relayUrl)
        assertEquals(listOf(address), target.directAddresses)
        assertArrayEquals(ByteArray(32) { 0xab.toByte() }, target.peerBytes)
    }

    @Test fun automaticIdentityOnlyDoesNotInventARelayOrAnAddress() {
        val target = IrxDialTarget.create(IrxEndpointPathMode.AUTOMATIC, peer, null, emptyList())
        assertNull(target.relayUrl); assertTrue(target.directAddresses.isEmpty())
        // Native dialing decides reachability; absence of hints is not an auth failure.
    }

    @Test fun automaticPreservesAnExplicitHttpsHintAndPrivateCandidates() {
        val relay = "https://relay.example.invalid/"
        val target = IrxDialTarget.create(IrxEndpointPathMode.AUTOMATIC, peer, relay, listOf(address))
        assertEquals(relay, target.relayUrl); assertEquals(listOf(address), target.directAddresses)
    }

    @Test fun malformedAutomaticHintsStillFailInsteadOfBecomingAbsent() {
        for (relay in listOf("", "http://relay.example.invalid/", "not-a-relay", "https://invalid host/"))
            assertTrue(relay, runCatching { IrxDialTarget.create(IrxEndpointPathMode.AUTOMATIC, peer, relay, listOf(address)) }.isFailure)
    }

    @Test fun directDiscardsRelayHintsAndStillRequiresExplicitCandidates() {
        for (relay in listOf(null, "https://relay.example.invalid/", "not-a-relay")) {
            val target = IrxDialTarget.create(IrxEndpointPathMode.DIRECT_ONLY, peer, relay, listOf(address))
            assertNull(target.relayUrl); assertEquals(listOf(address), target.directAddresses)
            assertTrue(runCatching { IrxDialTarget.create(IrxEndpointPathMode.DIRECT_ONLY, peer, relay, emptyList()) }.isFailure)
        }
    }

    @Test fun pathModesKeepTheirCandidateLimits() {
        for ((mode, limit) in listOf(IrxEndpointPathMode.AUTOMATIC to 8, IrxEndpointPathMode.DIRECT_ONLY to 16)) {
            val routes = (1..limit).map { "192.168.1.$it:58470" }
            assertEquals(routes, IrxDialTarget.create(mode, peer, null, routes).directAddresses)
            assertTrue(runCatching { IrxDialTarget.create(mode, peer, null, routes + address) }.isFailure)
        }
    }

    @Test fun missingRelayDoesNotRelaxPeerIdentityValidation() {
        for (mode in IrxEndpointPathMode.entries) for (invalid in listOf("", peer.uppercase(), peer.drop(1), "g".repeat(64)))
            assertTrue(runCatching { IrxDialTarget.create(mode, invalid, null, listOf(address)) }.isFailure)
    }

    @Test fun capturedCandidatesCannotChangeAfterPolicyValidation() {
        val routes = mutableListOf(address)
        val target = IrxDialTarget.create(IrxEndpointPathMode.AUTOMATIC, peer, null, routes)
        routes.clear()
        assertEquals(listOf(address), target.directAddresses)
    }
}
