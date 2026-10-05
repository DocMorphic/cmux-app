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
        var beforeGrantWrite: () -> Unit = {}
        val grants = TailscaleGrantStore({ JSONObject(state.toString()) }, { change ->
            beforeGrantWrite()
            val next = JSONObject(state.toString()); change(next)
            check(!failSave) { "fixture save failure" }; state = next
        })
        val pairing = PairingCode.Tailscale(listOf(PairingCode.Route("mac.tail.ts.net", 58465)), "user")
        var numeric = PairingCode.Route("100.99.1.2", 58465)
        var resolves = 0
        var routeAllowed = true
        var routeEpoch = 0
        var resolveHook: suspend (() -> Boolean) -> Unit = {}
        var tokenCalls = 0
        var tokenHook: () -> Unit = {}
        var hostHook: () -> Unit = {}
        var workspaceHook: () -> Unit = {}
        var expected: NativeCredentialStore.PairedMac? = null
        var ticket: MobileAttachTicket? = null
        var savedTicket: NativeSavedTicketAdmission? = null
        var savedCurrent = true
        var savedBearer: String? = "saved-fixture"
        var manual = false
        var manualError: String? = null
        var malformedManual = false
        var manualHook: () -> Unit = {}
        var device = "mac"
        var build = "default"
        var rejectWorkspace = false
        var enforceCompatibility = false
        var accessToken = "fixture-access"
        var rejectAccessToken: String? = null
        var forceRefresh: (suspend () -> String?)? = null
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
            }, manualTicket = { client, route, host, owner ->
                if (manual) ManualAttachTicketRequest.request(client, route, host, owner, null) else null
            }, savedRouteAdmission = { _, _ ->
                val captured = routeEpoch
                ({ routeAllowed && routeEpoch == captured })
            })
        suspend fun connect(admission: NativeTicketConnectionAdmission? = null, selected: PairingCode.Tailscale = pairing) =
            authority.connect(selected, attachTicket = ticket, savedTicket = savedTicket, admission = admission, forceToken = forceRefresh) { tokenCalls++; tokenHook(); accessToken }
        fun authorize() = authority.authorize(pairing)
        fun grant() = grants.find(checkNotNull(scope), TailscaleGrantStore.source(pairing))
        fun switch(next: NativeTeamScope?) {
            scope = next
            if (next == null) { state.remove("task_session"); state.remove("refresh_token") }
            else state.put("task_session", next.login)
        }
    }
    private fun externalTicket() = MobileAttachTicketCodec.decodeJson("""{"version":1,"workspaceID":"w","macDeviceID":"mac","macUserID":"user","auth_token":"synthetic-ticket",
        "routes":[{"id":"raw","kind":"tailscale","endpoint":{"type":"host_port","host":"100.99.1.2","port":58465}}]}""").getOrThrow()

    @Test fun externalTicketReusesExactDestinationDespiteDifferentPublicSourceWithoutResolvingAgain() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        try {
            f.connect().close()
            val grant = f.grant()!!; val resolves = f.resolves
            val selected = PairingCode.Tailscale(listOf(f.numeric), "user")
            f.ticket = externalTicket()
            val admission = NativeTicketConnectionAdmission(grant) { f.grant() == grant }
            val client = f.connect(admission, selected)
            assertEquals(resolves, f.resolves)
            val alias = f.grants.find(f.scope!!, TailscaleGrantStore.source(selected))!!
            assertEquals(grant.route, alias.route); assertEquals(grant.device, alias.device)
            assertEquals(grant, f.grant())
            f.grants.removeRoute(f.scope!!, NativeComputerTarget("mac", "default", "Mac"), grant) { true }
            f.authority.retireInvalid()
            assertTrue(client.isClosed)
        } finally { f.authority.close() }
    }
    @Test fun removingOriginalGrantInsideAliasTransactionCannotRecreateAuthority() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        try {
            f.connect().close(); val grant = f.grant()!!
            f.ticket = externalTicket()
            val selected = PairingCode.Tailscale(listOf(f.numeric), "user")
            f.beforeGrantWrite = { f.state.remove("tailscale_grants_v1") }
            assertTrue(runCatching { f.connect(NativeTicketConnectionAdmission(grant) { true }, selected) }.isFailure)
            assertNull(f.grants.find(f.scope!!, TailscaleGrantStore.source(selected)))
            assertTrue(f.transports.last().closed)
        } finally { f.authority.close() }
    }
    @Test fun externalTicketCannotUseAnUnconsumedFreshConsentToBypassSavedMethod() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        try {
            f.connect().close(); val grant = f.grant()!!
            val selected = PairingCode.Tailscale(listOf(f.numeric), "user")
            f.authority.authorize(selected); f.routeAllowed = false; f.ticket = externalTicket()
            val before = f.transports.size; val tokens = f.tokenCalls
            assertTrue(runCatching { f.connect(NativeTicketConnectionAdmission(grant) { true }, selected) }.isFailure)
            assertEquals(before, f.transports.size); assertEquals(tokens, f.tokenCalls)
        } finally { f.authority.close() }
    }
    @Test fun externalTicketAdmissionRetirementDuringTokenWaitClosesWithoutSavingAlias() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        try {
            f.connect().close(); val grant = f.grant()!!
            val selected = PairingCode.Tailscale(listOf(f.numeric), "user")
            var current = true; f.tokenHook = { current = false }; f.ticket = externalTicket()
            assertTrue(runCatching { f.connect(NativeTicketConnectionAdmission(grant) { current }, selected) }.isFailure)
            assertTrue(f.transports.last().closed)
            assertNull(f.grants.find(f.scope!!, TailscaleGrantStore.source(selected)))
        } finally { f.authority.close() }
    }
    @Test fun externalTicketCannotUseGrantForAnotherDestinationEvenWithAdmittedCallback() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        try {
            f.connect().close(); val grant = f.grant()!!
            f.ticket = externalTicket()
            val selected = PairingCode.Tailscale(listOf(f.numeric), "user")
            val before = f.transports.size
            assertTrue(runCatching { f.connect(NativeTicketConnectionAdmission(grant.copy(route = PairingCode.Route("100.99.1.3", 58465))) { true }, selected) }.isFailure)
            assertEquals(before, f.transports.size)
        } finally { f.authority.close() }
    }

    @Test fun savedRouteMethodDenialStopsBeforeDialWhileExplicitEntryRemainsEligible() = runBlocking<Unit> {
        val f = Fixture(); f.routeAllowed = false; f.authorize()
        try {
            f.connect().close() // Explicit entry, even with a different saved method.
            assertNotNull(f.grant()); assertFalse(f.authority.allowsSaved(f.pairing))
            val dials = f.transports.size; val tokens = f.tokenCalls
            assertTrue(runCatching { f.connect() }.isFailure)
            assertEquals(dials, f.transports.size); assertEquals(tokens, f.tokenCalls)
        } finally { f.authority.close() }
    }
    @Test fun savedRouteMethodChangeDuringAuthenticationCannotComplete() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        try {
            f.connect().close()
            f.tokenHook = { f.routeAllowed = false; f.routeEpoch++ }
            assertTrue(runCatching { f.connect() }.isFailure)
            assertTrue(f.transports.last().closed)
        } finally { f.authority.close() }
    }
    @Test fun savedRouteEpochChangeRetiresLiveClientEvenAfterMethodReturns() = runBlocking<Unit> {
        val f = Fixture(); f.authorize()
        try {
            f.connect().close()
            val client = f.connect()
            f.routeEpoch += 2 // A → B → A must not revive a retained connection.
            f.authority.retireInvalid()
            assertTrue(client.isClosed); assertTrue(f.transports.last().closed)
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(f.authority.allowsSaved(f.pairing)) // A new attempt captures the new epoch.
            f.connect().close()
        } finally { f.authority.close() }
    }
    @Test fun pairingRefreshesRejectedManualTicketRequestBeforeSavingItsExactGrant() = runBlocking<Unit> {
        val f = Fixture(); f.manual = true; f.rejectAccessToken = "fixture-access"; var refreshes = 0
        f.forceRefresh = { refreshes++; f.accessToken = "fresh-fixture"; f.accessToken }
        f.authorize()
        try {
            f.connect().use { it.workspaces() }
            assertEquals(1, refreshes); assertEquals(1, f.transports.size)
            assertEquals(2, f.transports.single().methods.count { it == "mobile.attach_ticket.create" })
            assertEquals(f.numeric, checkNotNull(f.grant()).route)
        } finally { f.authority.close() }
    }

    @Test fun pairingRevokedDuringForcedRefreshCannotRetryOrSaveGrant() = runBlocking<Unit> {
        val f = Fixture(); f.manual = true; f.rejectAccessToken = "fixture-access"
        f.forceRefresh = { f.switch(null); "fresh-fixture" }; f.authorize()
        try {
            assertTrue(runCatching { f.connect() }.isFailure)
            assertEquals(1, f.transports.single().methods.count { it == "mobile.attach_ticket.create" })
            assertFalse(f.state.has("tailscale_grants_v1")); assertTrue(f.transports.single().closed)
        } finally { f.authority.close() }
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
            assertEquals(fixture.accessToken, request.getJSONObject("auth").getString("stack_access_token"))
            if (method != "mobile.host.status" && request.getJSONObject("auth").getString("stack_access_token") == fixture.rejectAccessToken) {
                replies.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", false)
                    .put("error", JSONObject().put("code", "unauthorized").put("message", "Expired fixture token")).toString().toByteArray()))
                return
            }
            val response = JSONObject().put("id", request.getString("id"))
            when (method) {
                "mobile.attach_ticket.create" -> {
                    assertFalse(request.getJSONObject("auth").has("attach_token"))
                    assertEquals(3600, request.getJSONObject("params").getInt("ttl_seconds"))
                    fixture.manualHook()
                    if (fixture.manualError != null) response.put("ok", false).put("error",
                        JSONObject().put("code", fixture.manualError).put("message", "fixture error"))
                    else response.put("ok", true).put("result", if (fixture.malformedManual) JSONObject() else
                        JSONObject().put("ticket", JSONObject("""{"version":1,"workspaceID":"","macDeviceID":"mac","macUserID":"user",
                            "auth_token":"manual-fixture","routes":[{"id":"other","kind":"tailscale",
                            "endpoint":{"type":"host_port","host":"100.99.1.3","port":58465}}]}""")))
                }
                "mobile.host.status" -> {
                    assertFalse(request.getJSONObject("auth").has("attach_token"))
                    fixture.hostHook()
                    response.put("ok", true).put("result", JSONObject().put("mac_device_id", fixture.device)
                        .put("mac_instance_tag", fixture.build).put("mac_app_version", fixture.version))
                }
                "mobile.workspace.list" -> {
                    if (fixture.savedTicket != null) {
                        if (fixture.savedBearer == null) assertFalse(request.getJSONObject("auth").has("attach_token"))
                        else assertEquals(fixture.savedBearer, request.getJSONObject("auth").getString("attach_token"))
                    }
                    fixture.ticket?.let { assertEquals("synthetic-ticket", request.getJSONObject("auth").getString("attach_token")) }
                    if (fixture.manual && fixture.ticket == null && fixture.savedTicket == null) {
                        if (fixture.manualError == null) assertEquals("manual-fixture", request.getJSONObject("auth").getString("attach_token"))
                        else assertFalse(request.getJSONObject("auth").has("attach_token"))
                    }
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

    private suspend fun savedFixture(expiry: Long? = null, change: (NativeCredentialStore.PairedMac) -> NativeCredentialStore.PairedMac = { it }): Fixture {
        val f = Fixture(); f.authorize(); f.connect().close(); f.tokenCalls = 0
        val row = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=mac.tail.ts.net%3A58465&ub=user", "mac", "Mac", "default",
            accountUserId = "user", accountTeamId = "team", ticketRevision = "captured-revision")
        f.savedTicket = NativeSavedTicketAdmission(change(row), MobileAttachTicketContext("work", "term", "saved-fixture", expiry)) {
            check(f.savedCurrent) { "Saved ticket retired" }
        }
        f.manual = true
        return f
    }

    @Test fun savedTicketAuthenticatesFirstWorkspaceRequestWithoutRequestingReplacementTicket() = runBlocking<Unit> {
        val f = savedFixture()
        try {
            f.connect().use { client -> client.workspaces() }
            assertEquals(listOf("mobile.host.status", "mobile.workspace.list", "mobile.workspace.list"), f.transports.last().methods)
            assertEquals(1, f.resolves)
        } finally { f.authority.close() }
    }

    @Test fun expiredSavedTicketUsesAccountFallbackWithoutAcquiringWiderContext() = runBlocking<Unit> {
        val f = savedFixture(expiry = 0); f.savedBearer = null
        try {
            f.connect().close()
            assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), f.transports.last().methods)
        } finally { f.authority.close() }
    }

    @Test fun savedTicketBindingAndRetirementFailBeforeDialOrTokenLookup() = runBlocking<Unit> {
        val changes: List<(NativeCredentialStore.PairedMac) -> NativeCredentialStore.PairedMac> = listOf(
            { it.copy(code = "cmux-ios://attach?v=2&r=100.99.1.8:58465") },
            { it.copy(accountUserId = "other") }, { it.copy(accountTeamId = "other") }, { it.copy(ticketRevision = null) })
        for (change in changes) {
            val f = savedFixture(change = change)
            try {
                assertTrue(runCatching { f.connect() }.isFailure)
                assertEquals(1, f.transports.size); assertEquals(0, f.tokenCalls)
            } finally { f.authority.close() }
        }
        val f = savedFixture(); f.savedCurrent = false
        try {
            assertTrue(runCatching { f.connect() }.isFailure)
            assertEquals(1, f.transports.size); assertEquals(0, f.tokenCalls)
        } finally { f.authority.close() }
    }

    @Test fun savedTicketIdentityCannotOverrideAuthenticatedGrantIdentity() = runBlocking<Unit> {
        for (change in listOf<(NativeCredentialStore.PairedMac) -> NativeCredentialStore.PairedMac>(
            { it.copy(deviceId = "other") }, { it.copy(instanceTag = "nightly") })) {
            val f = savedFixture(change = change)
            try {
                assertTrue(runCatching { f.connect() }.isFailure)
                assertEquals(listOf("mobile.host.status"), f.transports.last().methods)
                assertTrue(f.transports.last().closed)
            } finally { f.authority.close() }
        }
    }

    @Test fun savedTicketRetiredDuringTokenLookupCannotWriteProtectedFrame() = runBlocking<Unit> {
        val f = savedFixture(); f.tokenHook = { if (f.tokenCalls == 2) f.savedCurrent = false }
        try {
            assertTrue(runCatching { f.connect() }.isFailure)
            assertEquals(listOf("mobile.host.status"), f.transports.last().methods)
            assertTrue(f.transports.last().closed)
        } finally { f.authority.close() }
    }

    @Test fun savedTicketRetirementClosesAnAlreadyAdmittedConnection() = runBlocking<Unit> {
        val f = savedFixture()
        try {
            f.connect().use { client ->
                f.savedCurrent = false; f.authority.retireInvalid()
                assertTrue(client.isClosed); assertTrue(f.transports.last().closed)
                assertTrue(runCatching { client.workspaces() }.isFailure)
                assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), f.transports.last().methods)
            }
        } finally { f.authority.close() }
    }

    @Test fun manualTicketAcquiredBeforeWorkspaceAdmissionAndReacquiredOnPinnedReconnect() = runBlocking<Unit> {
        val f = Fixture(); f.manual = true; f.authorize()
        try {
            f.workspaceHook = { assertNull(f.grant()) }
            f.connect().close(); assertNotNull(f.grant())
            f.workspaceHook = {}; f.numeric = f.numeric.copy(host = "100.99.1.4")
            f.connect().close()
            assertEquals(1, f.resolves)
            assertTrue(f.transports.all { it.route.host == "100.99.1.2" &&
                it.methods == listOf("mobile.host.status", "mobile.attach_ticket.create", "mobile.workspace.list") })
        } finally { f.authority.close() }
    }

    @Test fun unsupportedManualTicketStillRequiresProtectedWorkspaceAdmission() = runBlocking<Unit> {
        val f = Fixture(); f.manual = true; f.manualError = "method_not_found"; f.authorize()
        try {
            f.rejectWorkspace = true
            assertTrue(runCatching { f.connect() }.isFailure); assertNull(f.grant())
            f.rejectWorkspace = false
            f.connect().close(); assertNotNull(f.grant())
        } finally { f.authority.close() }
    }

    @Test fun invalidOrUnauthorizedManualTicketStopsWithoutTryingOtherHintsOrSavingGrant() = runBlocking<Unit> {
        for (malformed in listOf(true, false)) {
            val f = Fixture(); f.manual = true; f.malformedManual = malformed
            if (!malformed) f.manualError = "unauthorized"
            val pairing = f.pairing.copy(routes = listOf(f.numeric, f.numeric.copy(host = "100.99.1.3")))
            f.authority.authorize(pairing)
            try {
                val failure = runCatching { f.authority.connect(pairing) { "fixture-access" } }.exceptionOrNull()
                assertTrue(if (malformed) failure is InvalidManualAttachTicket else failure is MobileRpcException)
                assertNull(f.grants.find(checkNotNull(f.scope), TailscaleGrantStore.source(pairing)))
                assertEquals(1, f.transports.size)
                assertEquals(listOf("mobile.host.status", "mobile.attach_ticket.create"), f.transports.single().methods)
                assertTrue(f.transports.single().closed)
            } finally { f.authority.close() }
        }
    }

    @Test fun manualTicketReplyAfterAccountChangeCannotReachWorkspaceOrPromoteGrant() = runBlocking<Unit> {
        val f = Fixture(); f.manual = true; f.authorize(); f.manualHook = { f.switch(null) }
        try {
            assertTrue(runCatching { f.connect() }.isFailure)
            assertEquals(listOf("mobile.host.status", "mobile.attach_ticket.create"), f.transports.single().methods)
            assertTrue(f.transports.single().closed)
        } finally { f.authority.close() }
    }

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
