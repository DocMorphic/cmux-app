package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TailscalePairingAuthorityTest {
    private class Fixture(audience: NativeMacBuildAudience? = null) {
        var scope: NativeTeamScope? = NativeTeamScope("login", "user", "team", 1)
        var state = JSONObject().put("task_session", "login").put("refresh_token", "fixture-refresh")
        var failSave = false
        val grants = TailscaleGrantStore({ JSONObject(state.toString()) }, { change ->
            val next = JSONObject(state.toString()); change(next)
            check(!failSave) { "fixture save failure" }; state = next
        })
        val pairing = PairingCode.Tailscale(listOf(PairingCode.Route("mac.tail.ts.net", 58465)), "user")
        var numeric = PairingCode.Route("100.99.1.2", 58465)
        var resolves = 0
        var resolveHook: suspend (() -> Boolean) -> Unit = {}
        var tokenCalls = 0
        var tokenHook: () -> Unit = {}
        var hostHook: () -> Unit = {}
        var workspaceHook: () -> Unit = {}
        var expected: NativeCredentialStore.PairedMac? = null
        var ticket: MobileAttachTicket? = null
        var device = "mac"
        var build = "default"
        var rejectWorkspace = false
        var enforceCompatibility = false
        var version = "0.64.24"
        val compatibility = NativeMacCompatibilityGate({ it == scope }, audience = audience)
        val transports = mutableListOf<Transport>()
        val authority = TailscalePairingAuthority({ scope }, { it == scope }, grants,
            resolve = { _, allowed -> check(allowed()); resolves++; resolveHook(allowed); numeric },
            dial = { route, allowed, token ->
                val transport = Transport(route, allowed, this).also { transports += it }
                MobileRpcClient(transport, token)
            }, expected = { expected }, admitCompatibility = { owner, client, host ->
                if (enforceCompatibility) compatibility.admit(owner, client, host, locallyAuthorizedTailscale = true)
            })
        suspend fun connect() = authority.connect(pairing, attachTicket = ticket) { tokenCalls++; tokenHook(); "fixture-access" }
        fun authorize() = authority.authorize(pairing)
        fun grant() = grants.find(checkNotNull(scope), TailscaleGrantStore.source(pairing))
        fun switch(next: NativeTeamScope?) {
            scope = next
            if (next == null) { state.remove("task_session"); state.remove("refresh_token") }
            else state.put("task_session", next.login)
        }
    }
    @Test fun consumerPairingRejectsDevBeforeSavingGrantButPassesActualLegacyRouteFlag() = runBlocking<Unit> {
        val f = Fixture(NativeMacBuildAudience.consumer); f.enforceCompatibility = true; f.authorize()
        try {
            f.build = "dev"; f.version = "999.0"
            assertTrue(runCatching { f.connect() }.exceptionOrNull() is MacBuildNotSupported)
            assertNull(f.grant()); assertTrue(f.compatibility.observations.value.isEmpty())
            f.build = ""; f.version = "0.64.17"
            f.compatibility.replace(NativeMacCompatibilityPolicy(emptyList()))
            f.connect().use { assertFalse(it.isClosed) }
            assertNull(checkNotNull(f.grant()).build)
        } finally { f.authority.close() }
    }
    private class Transport(val route: PairingCode.Route, val allowed: () -> Boolean, val fixture: Fixture) : MobileRpcTransport {
        val replies = Channel<ByteArray>(8)
        val methods = mutableListOf<String>()
        var closed = false
        override suspend fun connect() { check(allowed()) }
        override suspend fun write(bytes: ByteArray) {
            check(!closed && allowed())
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); methods += method
            assertEquals("fixture-access", request.getJSONObject("auth").getString("stack_access_token"))
            val response = JSONObject().put("id", request.getString("id"))
            when (method) {
                "mobile.host.status" -> {
                    assertFalse(request.getJSONObject("auth").has("attach_token"))
                    fixture.hostHook()
                    response.put("ok", true).put("result", JSONObject().put("mac_device_id", fixture.device)
                        .put("mac_instance_tag", fixture.build).put("mac_app_version", fixture.version))
                }
                "mobile.workspace.list" -> {
                    fixture.ticket?.let { assertEquals("synthetic-ticket", request.getJSONObject("auth").getString("attach_token")) }
                    fixture.workspaceHook()
                    if (fixture.rejectWorkspace) response.put("ok", false).put("error", JSONObject().put("code", "unauthorized").put("message", "fixture denial"))
                    else response.put("ok", true).put("result", JSONObject().put("workspaces", org.json.JSONArray()))
                }
                else -> error("Unexpected fixture method")
            }
            replies.send(MobileFrameCodec.encode(response.toString().toByteArray()))
        }
        override suspend fun read(): ByteArray? = replies.receiveCatching().getOrNull()?.also { check(!closed && allowed()) }
        override fun close() { closed = true; replies.close() }
    }

    private fun scopedTicket(device: String = "mac", host: String = "mac.tail.ts.net") = MobileAttachTicketCodec.decodeJson(
        """{"version":1,"workspaceID":"work","terminalID":"term","macDeviceID":"$device","macUserID":"user",
            "auth_token":"synthetic-ticket","routes":[{"id":"ts","kind":"tailscale",
            "endpoint":{"type":"host_port","host":"$host","port":58465}}]}""").getOrThrow()

    @Test fun scopedTicketIsAppliedBeforeFirstAuthenticatedWorkspaceRequestAndGrantPromotion() = runBlocking<Unit> {
        val f = Fixture(); f.ticket = scopedTicket(); f.authorize()
        try {
            f.connect().use { assertFalse(it.isClosed) }
            assertNotNull(f.grant())
            assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), f.transports.single().methods)
        } finally { f.authority.close() }
    }

    @Test fun wrongTicketHostOrRouteDoesNotSendItsBearerOrPromoteAGrant() = runBlocking<Unit> {
        for (ticket in listOf(scopedTicket(device = "other"), scopedTicket(host = "100.99.1.3"))) {
            val f = Fixture(); f.ticket = ticket; f.authorize()
            try {
                assertTrue(runCatching { f.connect() }.isFailure)
                assertNull(f.grant())
                assertTrue(f.transports.all { it.methods == listOf("mobile.host.status") && it.closed })
            } finally { f.authority.close() }
        }
    }

    @Test fun outdatedPairingDoesNotPromoteConsentToGrantAndUpgradeCanRetry() = runBlocking<Unit> {
        val f = Fixture(); f.enforceCompatibility = true; f.authorize()
        try {
            assertTrue(runCatching { f.connect() }.exceptionOrNull() is MacUpdateRequired)
            assertNull(f.grant()); assertFalse(f.authority.allowsSaved(f.pairing))
            assertTrue(f.transports.single().closed)
            f.version = "0.64.25"
            f.connect().use { assertFalse(it.isClosed) }
            assertNotNull(f.grant()); assertTrue(f.compatibility.warnings.value.isEmpty())
        } finally { f.authority.close() }
    }

    @Test fun savedRowScopeCapturedBeforeDispatchCannotDialAfterTeamSwitch() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.connect().close()
        val captured = checkNotNull(f.scope)
        f.switch(captured.copy(teamId = "other", generation = 2))
        f.authorize()
        val before = f.transports.size
        assertTrue(runCatching { f.authority.connect(f.pairing, captured) { error("must not request token") } }.isFailure)
        assertEquals(before, f.transports.size); f.authority.close()
    }

    @Test fun replacedConsentCancelsReadinessBeforeDialOrTokenAcquisition() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        val entered = CompletableDeferred<Unit>()
        f.resolveHook = { allowed ->
            entered.complete(Unit)
            TailscaleReadiness.await(TailscaleObservations().state, f.numeric, allowed, 2000, 10)
        }
        val pending = async { runCatching { f.connect() } }
        entered.await(); f.authorize()
        assertTrue(withTimeout(500) { pending.await() }.isFailure)
        assertEquals(0, f.tokenCalls); assertTrue(f.transports.isEmpty())
        f.authority.close()
    }
    @Test fun readinessDeadlineStopsQrHintsWithoutStartingAnotherWait() = runBlocking<Unit> {
        val f = Fixture()
        val pairing = f.pairing.copy(routes = listOf(f.numeric, f.numeric.copy(host = "100.99.1.3")))
        f.authority.authorize(pairing)
        f.resolveHook = { throw TailscaleReadinessException() }
        assertTrue(runCatching { f.authority.connect(pairing) { error("must not acquire token") } }
            .exceptionOrNull() is TailscaleReadinessException)
        assertEquals(1, f.resolves); assertTrue(f.transports.isEmpty()); f.authority.close()
    }
    @Test fun savedQrWithoutGrantCannotDialOrRequestToken() = runBlocking<Unit> {
        val f = Fixture()
        assertFalse(f.authority.allowsSaved(f.pairing))
        assertTrue(runCatching { f.connect() }.isFailure)
        assertEquals(0, f.resolves); assertEquals(0, f.tokenCalls); assertTrue(f.transports.isEmpty())
        f.authority.close()
    }

    @Test fun confirmationPromotesOnlyAfterProtectedProbeAndReconnectPinsNumericPeer() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        f.workspaceHook = { assertNull(f.grant()) }
        f.connect().close()
        assertEquals(f.numeric, f.grant()!!.route)
        assertEquals("mac", f.grant()!!.device)
        assertTrue(f.authority.allowsSaved(f.pairing))
        f.workspaceHook = {}; f.numeric = f.numeric.copy(host = "100.99.1.3")
        f.connect().close()
        assertEquals(1, f.resolves)
        assertEquals("100.99.1.2", f.transports.last().route.host)
        assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), f.transports.last().methods)
        f.authority.close()
    }

    @Test fun consentIsEphemeralAndClosedOwnerCannotBeRevived() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.authority.close()
        assertNull(f.grant()); assertFalse(f.authority.allowsSaved(f.pairing))
        assertTrue(runCatching { f.connect() }.isFailure)
        assertTrue(f.transports.isEmpty())
    }

    @Test fun wrongAccountAndRetiredTeamConsentNeverDial() = runBlocking<Unit> {
        val f = Fixture()
        assertTrue(runCatching { f.authority.authorize(f.pairing.copy(stackUserId = "someone")) }.isFailure)
        f.authorize(); f.switch(f.scope!!.copy(teamId = "other", generation = 2))
        assertTrue(runCatching { f.connect() }.isFailure)
        assertTrue(f.transports.isEmpty()); assertEquals(0, f.tokenCalls)
        f.authority.close()
    }

    @Test fun accountChangeDuringTokenAcquisitionSendsNoFrame() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.tokenHook = { f.switch(null) }
        assertTrue(runCatching { f.connect() }.isFailure)
        assertTrue(f.transports.single().methods.isEmpty()); assertTrue(f.transports.single().closed)
        f.authority.close()
    }

    @Test fun hostStatusAloneDoesNotGrantReconnect() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.rejectWorkspace = true
        assertTrue(runCatching { f.connect() }.isFailure)
        assertNull(f.grant()); assertFalse(f.authority.allowsSaved(f.pairing)); assertTrue(f.transports.single().closed)
        f.authority.close()
    }

    @Test fun aFailedConfirmedAttemptCannotFollowNewDnsAnswersWithoutAnotherConfirmation() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.rejectWorkspace = true
        assertTrue(runCatching { f.connect() }.isFailure)
        f.numeric = f.numeric.copy(host = "100.99.1.3"); f.rejectWorkspace = false
        f.connect().close()
        assertEquals(1, f.resolves); assertEquals("100.99.1.2", f.grant()!!.route.host)
        f.authorize(); f.connect().close()
        assertEquals(2, f.resolves); assertEquals("100.99.1.3", f.grant()!!.route.host)
        f.authority.close()
    }

    @Test fun newConfirmationRetiresAnOlderPendingHandshake() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        f.hostHook = { f.authorize() }
        assertTrue(runCatching { f.connect() }.isFailure)
        assertNull(f.grant()); assertTrue(f.transports.single().closed)
        f.hostHook = {}; f.connect().close()
        assertNotNull(f.grant()); f.authority.close()
    }

    @Test fun uuidAliasesMatchButOpaqueDeviceIdentifiersRemainCaseSensitive() = runBlocking<Unit> {
        val f = Fixture()
        val lower = "70a22df2-e59d-4d4d-bf9e-e0527a11bf65"
        f.expected = NativeCredentialStore.PairedMac("", lower.uppercase(), "Mac", "default")
        f.device = lower; f.authorize(); f.connect().close()
        assertEquals(lower, f.grant()!!.device)
        f.device = lower.uppercase(); f.connect().close()
        assertTrue(runCatching { NativeCredentialStore.PairedMac("", "Opaque", "Mac").requireMatchingHost(
            JSONObject().put("mac_device_id", "opaque")) }.isFailure)
        f.authority.close()
    }

    @Test fun forgetComputerClearsOnlyTheCapturedAccountTeamDeviceAndBuild() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.connect().close()
        val original = f.scope!!
        val grant = f.grant()!!
        f.grants.save(original, grant.copy(id = java.util.UUID.randomUUID().toString(), source = "a".repeat(64), build = "beta")) { true }
        f.switch(original.copy(teamId = "other", generation = 2))
        f.grants.save(f.scope!!, grant.copy(id = java.util.UUID.randomUUID().toString(), team = "other")) { true }
        TailscaleGrantStore.removeComputer(f.state, original, "mac", "default")
        assertNull(f.grants.find(original, grant.source))
        assertNotNull(f.grants.find(original, "a".repeat(64)))
        assertNotNull(f.grants.find(f.scope!!, grant.source))
        f.authority.close()
    }

    @Test fun hostIdentityChangeIsRejectedBeforeProtectedRequest() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.connect().close()
        f.device = "replacement"
        assertTrue(runCatching { f.connect() }.isFailure)
        assertEquals(listOf("mobile.host.status"), f.transports.last().methods)
        f.device = "mac"; f.build = "beta"
        assertTrue(runCatching { f.connect() }.isFailure)
        assertEquals(listOf("mobile.host.status"), f.transports.last().methods)
        f.authority.close()
    }

    @Test fun expectedSavedIdentityAlsoBindsFreshConfirmation() = runBlocking<Unit> {
        val f = Fixture(); f.expected = NativeCredentialStore.PairedMac("", "expected-mac", "Mac", "default")
        f.authorize()
        assertTrue(runCatching { f.connect() }.isFailure)
        assertNull(f.grant()); assertEquals(listOf("mobile.host.status"), f.transports.single().methods)
        f.authority.close()
    }

    @Test fun saveFailureClosesSessionAndDoesNotPublishGrant() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.failSave = true
        assertTrue(runCatching { f.connect() }.isFailure)
        assertNull(f.grant()); assertTrue(f.transports.single().closed)
        f.authority.close()
    }

    @Test fun scopeChangeDuringProbeCannotPersistOrReturnSession() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        f.workspaceHook = { f.switch(f.scope!!.copy(teamId = "other", generation = 2)) }
        assertTrue(runCatching { f.connect() }.isFailure)
        assertNull(f.grant()); assertTrue(f.transports.single().closed)
        f.authority.close()
    }

    @Test fun removalRetiresOpenConnectionAndDoesNotReuseConsumedConsent() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); val client = f.connect()
        val code = "cmux-ios://attach?v=2&ub=user&r=mac.tail.ts.net:58465"
        TailscaleGrantStore.removeForCode(f.state, code)
        f.authority.retireInvalid()
        assertTrue(client.isClosed); assertTrue(f.transports.single().closed)
        assertTrue(runCatching { f.connect() }.isFailure); assertEquals(1, f.transports.size)
        f.authority.close()
    }

    @Test fun savedGrantSurvivesSameUserReloginButNotAnotherTeamOrUser() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); val first = f.connect()
        val original = f.scope!!
        f.switch(original.copy(login = "new-login", generation = 2)); f.authority.retireInvalid()
        assertTrue(first.isClosed); assertTrue(f.authority.allowsSaved(f.pairing))
        f.connect().close()
        f.switch(f.scope!!.copy(teamId = "other", generation = 3))
        assertFalse(f.authority.allowsSaved(f.pairing)); assertTrue(runCatching { f.connect() }.isFailure)
        f.switch(original.copy(login = "new-login", userId = "other-user", generation = 4))
        assertFalse(f.authority.allowsSaved(f.pairing.copy(stackUserId = null)))
        f.authority.close()
    }

    @Test fun malformedSavedGrantFailsClosedEvenWithFreshConsent() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.connect().close()
        val row = f.state.getJSONArray("tailscale_grants_v1").getJSONObject(0)
        row.put("host", "100.100.100.100")
        assertFalse(f.authority.allowsSaved(f.pairing))
        f.authorize(); assertTrue(runCatching { f.connect() }.isFailure)
        assertEquals(1, f.transports.size)
        f.authority.close()
    }

    @Test fun replacingRouteCommitsAtomicallyAndRejectsStaleOrSiblingEdits() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.connect().close()
        val original = f.grant()!!
        val replacement = original.copy(id = java.util.UUID.randomUUID().toString(), source = "b".repeat(64),
            route = PairingCode.Route("100.99.1.3", 58465))
        f.failSave = true
        assertTrue(runCatching { f.grants.save(f.scope!!, replacement, original) { true } }.isFailure)
        assertEquals(original, f.grant())
        f.failSave = false
        assertTrue(runCatching { f.grants.save(f.scope!!, replacement.copy(build = "beta"), original) { true } }.isFailure)
        f.grants.save(f.scope!!, replacement, original) { true }
        assertNull(f.grant()); assertEquals(replacement, f.grants.find(f.scope!!, replacement.source))
        assertTrue(runCatching { f.grants.save(f.scope!!, original, original) { true } }.isFailure)
        f.authority.close()
    }

    @Test fun deletingOneDisplayedRouteAlsoRemovesItsDuplicateSourceGrants() = runBlocking<Unit> {
        val f = Fixture(); f.authorize(); f.connect().close()
        val original = f.grant()!!
        val duplicate = original.copy(id = java.util.UUID.randomUUID().toString(), source = "b".repeat(64))
        val sibling = original.copy(id = java.util.UUID.randomUUID().toString(), source = "c".repeat(64), build = "beta")
        f.grants.save(f.scope!!, duplicate) { true }; f.grants.save(f.scope!!, sibling) { true }
        val target = NativeComputerTarget("mac", "default", "Mac")
        assertEquals(listOf(duplicate), f.grants.computer(f.scope!!, target))
        f.grants.removeRoute(f.scope!!, target, duplicate) { true }
        assertNull(f.grant()); assertNull(f.grants.find(f.scope!!, duplicate.source))
        assertEquals(sibling, f.grants.find(f.scope!!, sibling.source))
        f.authority.close()
    }

    @Test fun manualRouteEntryAcceptsNumericPeersAndCannotPretendAnIrohCodeIsTailscale() {
        assertEquals(PairingCode.Route("100.99.1.2", 58465), tailscalePairingInput("100.99.1.2:58465").routes.single())
        assertEquals(PairingCode.Route("fd7a:115c:a1e0::2", 58465), tailscalePairingInput("[FD7A:115C:A1E0::2]:58465").routes.single())
        listOf("mac.tail.ts.net:58465", "100.100.100.100:58465", "cmux-ios://attach?v=3&i=peer").forEach {
            assertTrue(runCatching { tailscalePairingInput(it) }.isFailure)
        }
    }
}
