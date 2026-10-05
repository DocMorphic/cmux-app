package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeSavedTailscaleRouteTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val pairing = PairingCode.Tailscale(listOf(PairingCode.Route("100.99.1.2", 58465)), "user")
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=2&r=100.99.1.2%3A58465&ub=user", "mac", "Mac", "default"), team)
    private val primary = TailscaleSavedGrant(UUID.randomUUID().toString(), "user", "team", TailscaleGrantStore.source(pairing),
        "mac", "default", pairing.routes.single())
    private val replacement = primary.copy(id = UUID.randomUUID().toString(), source = "b".repeat(64), route = PairingCode.Route("100.99.1.3", 58465))
    private var state = JSONObject().put("task_session", "login").put("refresh_token", "fixture")
    private val grants = TailscaleGrantStore({ state }, { it(state) })

    @Test fun replacementAndAdditionalRoutesUseOwnedExactBuildWithNewestFirstAndNoOriginalSourceRequirement() {
        grants.save(team, primary) { true }
        grants.save(team, replacement) { true }
        assertEquals(listOf(replacement, primary), NativeSavedTailscaleRoutes.candidates(mac, team, grants))
        grants.removeRoute(team, NativeComputerTarget("mac", "default", "Mac"), primary) { true }
        assertEquals(listOf(replacement), NativeSavedTailscaleRoutes.candidates(mac, team, grants))
        assertTrue(NativePairingRecords.usable(mac, team, grants))
        assertTrue(NativeSavedTailscaleRoutes.candidates(mac.copy(instanceTag = "nightly"), team, grants).isEmpty())
        assertTrue(NativeSavedTailscaleRoutes.candidates(mac, team.copy(teamId = "other"), grants).isEmpty())
        assertTrue(NativeSavedTailscaleRoutes.candidates(mac.copy(accountUserId = null, accountTeamId = null, stableOrigin = null), team, grants).isEmpty())
        grants.removeRoute(team, NativeComputerTarget("mac", "default", "Mac"), replacement) { true }
        assertFalse(NativePairingRecords.usable(mac, team, grants))
    }

    @Test fun historicalUnscopedAndPreTagRowsCannotAdoptAnUnrelatedSource() {
        grants.save(team, primary.copy(build = null)) { true }
        grants.save(team, replacement.copy(build = null)) { true }
        val old = mac.copy(instanceTag = null, accountUserId = null, accountTeamId = null, stableOrigin = null)
        assertEquals(listOf(primary.copy(build = null)), NativeSavedTailscaleRoutes.candidates(old, team, grants))
        assertEquals(listOf(primary.copy(build = null)), NativeSavedTailscaleRoutes.candidates(old.copy(accountUserId = "user", accountTeamId = "team", stableOrigin = mac.stableOrigin), team, grants))
    }

    @Test fun retryTriesOnlyCapturedGrantsAndOnlyOriginalSourceReceivesSavedTicket() = runBlocking<Unit> {
        val attempted = mutableListOf<TailscaleSavedGrant>()
        val ticket = NativeSavedTicketAdmission(mac.copy(ticketRevision = "revision"), MobileAttachTicketContext("w", "s", "fixture", null)) {}
        val client = MobileRpcClient(object : MobileRpcTransport {
            override suspend fun connect() = Unit
            override suspend fun read(): ByteArray? = null
            override suspend fun write(bytes: ByteArray) = Unit
            override fun close() = Unit
        }, { "fixture" })
        val result = connectSavedTailscaleRoutes(pairing, listOf(replacement, primary), ticket,
            { NativeSavedTailscaleRouteAdmission(mac, it) { true } }) { admission, context ->
            attempted += admission.grant
            if (admission.grant == replacement) { assertNull(context); throw java.net.ConnectException("unreachable") }
            assertSame(ticket, context)
            client
        }
        assertSame(client, result); assertEquals(listOf(replacement, primary), attempted); result.close()
    }

    @Test fun accountOrGrantRetirementDuringNetworkFailureCannotContinueToAnotherAddress() = runBlocking<Unit> {
        var current = true; var attempts = 0
        val failure = runCatching {
            connectSavedTailscaleRoutes(pairing, listOf(replacement, primary), null,
                { NativeSavedTailscaleRouteAdmission(mac, it) { current } }) { _, _ ->
                attempts++; current = false; throw java.net.ConnectException("unreachable")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException); assertEquals(1, attempts)
    }

    @Test fun authenticationIdentityAndCancellationFailuresNeverTryAnotherAddress() = runBlocking<Unit> {
        for (failure in listOf(IllegalStateException("wrong host"), IllegalArgumentException("invalid ticket"), TailscaleReadinessException(), CancellationException("cancelled"))) {
            var attempts = 0
            val caught = runCatching {
                connectSavedTailscaleRoutes(pairing, listOf(replacement, primary), null,
                    { NativeSavedTailscaleRouteAdmission(mac, it) { true } }) { _, _ -> attempts++; throw failure }
            }.exceptionOrNull()
            assertSame(failure, caught); assertEquals(1, attempts)
        }
    }
}
