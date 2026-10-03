package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TailscaleReadinessTest {
    private val route = PairingCode.Route("100.99.1.2", 58465)
    private val tunnel = TailscaleTunnel(1, "tun0", setOf("100.99.1.1"))
    private fun ready(cache: TailscaleObservations, value: TailscaleTunnel = tunnel) {
        cache.available(value.network); cache.capabilities(value.network, true); cache.link(value.network, value)
    }

    @Test fun startupWaitsForBothCapabilityAndLinkCallbacks() = runBlocking<Unit> {
        val cache = TailscaleObservations()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            TailscaleReadiness.await(cache.state, route, { true }, 2000)
        }
        assertFalse(pending.isCompleted)
        cache.available(1); cache.capabilities(1, true); yield()
        assertFalse(pending.isCompleted)
        cache.link(1, tunnel)
        assertEquals(listOf(tunnel), withTimeout(500) { pending.await() }.tunnels)
    }

    @Test fun linkBeforeCapabilitiesCannotAuthorizeAndNonVpnRemainsUnavailable() = runBlocking<Unit> {
        val cache = TailscaleObservations()
        cache.available(1); cache.link(1, tunnel); cache.capabilities(1, false)
        assertTrue(cache.state.value!!.tunnels.isEmpty())
        val pending = async(start = CoroutineStart.UNDISPATCHED) { TailscaleReadiness.await(cache.state, route, { true }, 2000) }
        cache.capabilities(1, true)
        assertEquals(listOf(tunnel), withTimeout(500) { pending.await() }.tunnels)
    }

    @Test fun ambiguousTunnelsWaitUntilExactlyOneRemains() = runBlocking<Unit> {
        val cache = TailscaleObservations(); ready(cache)
        ready(cache, tunnel.copy(network = 2, name = "tun1", peers = setOf("100.99.2.1")))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { TailscaleReadiness.await(cache.state, route, { true }, 2000) }
        assertFalse(pending.isCompleted); cache.lost(2)
        assertEquals(listOf(tunnel), withTimeout(500) { pending.await() }.tunnels)
    }

    @Test fun blockedNetworkWaitsAndUnblockingAdvancesProofGeneration() = runBlocking<Unit> {
        val cache = TailscaleObservations(); ready(cache)
        val original = cache.state.value!!
        cache.blocked(1, true)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { TailscaleReadiness.await(cache.state, route, { true }, 2000) }
        assertFalse(pending.isCompleted); cache.blocked(1, false)
        val restored = withTimeout(500) { pending.await() }
        assertEquals(original.tunnels, restored.tunnels)
        assertTrue(restored.generation > original.generation)
    }

    @Test fun duplicateCallbacksDoNotInvalidateButLossAndReturnCannotReviveProof() {
        val cache = TailscaleObservations(); ready(cache)
        val original = cache.state.value
        ready(cache); cache.blocked(1, false)
        assertEquals(original, cache.state.value)
        cache.lost(1); cache.link(1, tunnel); cache.capabilities(1, true)
        assertTrue(cache.state.value!!.tunnels.isEmpty()) // Late callbacks cannot restore a lost Network.
        ready(cache)
        assertEquals(original!!.tunnels, cache.state.value!!.tunnels)
        assertNotEquals(original.generation, cache.state.value!!.generation)
    }

    @Test fun interfaceOrAddressChangesInvalidateEvenIfImmediatelyRestored() {
        val cache = TailscaleObservations(); ready(cache)
        val original = cache.state.value!!
        cache.link(1, tunnel.copy(peers = setOf("100.99.1.3")))
        cache.link(1, tunnel)
        assertTrue(cache.state.value!!.generation > original.generation)
        val restored = cache.state.value!!
        cache.link(1, tunnel.copy(name = "tun9"))
        assertTrue(cache.state.value!!.generation > restored.generation)
    }

    @Test fun absentTunnelUsesActionableDeadlineFailure() = runBlocking<Unit> {
        val failure = runCatching { TailscaleReadiness.await(TailscaleObservations().state, route, { true }, 60) }.exceptionOrNull()
        assertTrue(failure is TailscaleReadinessException)
        assertTrue(failure!!.message!!.contains("Open Tailscale"))
        assertFalse(failure.message!!.contains(route.host))
    }

    @Test fun callerCancellationIsNotConvertedToReadinessTimeout() = runBlocking<Unit> {
        val failure = runCatching { withTimeout(50) {
            TailscaleReadiness.await(TailscaleObservations().state, route, { true }, 2000)
        } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
    }

    @Test fun revokedConsentInterruptsWaitEvenWithoutMoreNetworkCallbacks() = runBlocking<Unit> {
        val cache = TailscaleObservations(); var allowed = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            TailscaleReadiness.await(cache.state, route, { allowed }, 2000, 10)
        }.exceptionOrNull() }
        allowed = false
        val failure = withTimeout(500) { pending.await() }
        assertTrue(failure is IllegalStateException)
    }

    @Test fun invalidRouteAndSelfPeerFailWithoutWaitingForDeadline() = runBlocking<Unit> {
        val cache = TailscaleObservations()
        for (invalid in listOf(route.copy(host = "example.com"), route.copy(port = 0))) {
            val failure = runCatching { withTimeout(500) { TailscaleReadiness.await(cache.state, invalid, { true }) } }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
        ready(cache)
        val failure = runCatching { withTimeout(500) {
            TailscaleReadiness.await(cache.state, route.copy(host = "100.99.1.1"), { true })
        } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
