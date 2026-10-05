package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class NativeSavedTailscaleRuntimeTest {
    @Test fun manualTicketIsSharedByAdmittedLeasesAndReacquiredAfterLastClose() = runBlocking<Unit> {
        Fixture(manualTickets = true).use { f ->
            val first = checkNotNull(f.local.connectIfSelected(f.pairing()))
            val second = checkNotNull(f.local.connectIfSelected(f.pairing()))
            assertEquals(1, f.wires.size)
            assertEquals(listOf("mobile.host.status", "mobile.attach_ticket.create", "mobile.workspace.list"), f.wires.single().methods)
            first.workspaces(); second.workspaces()
            first.close(); assertFalse(second.isClosed); second.workspaces(); second.close()
            assertTrue(f.wires.single().closed)
            checkNotNull(f.local.connectIfSelected(f.pairing())).use { it.workspaces() }
            assertEquals(2, f.wires.size)
            assertTrue(f.wires.all { it.methods.count { method -> method == "mobile.attach_ticket.create" } == 1 })
        }
    }

    @Test fun unsupportedManualTicketUsesAccountAdmissionWithoutPublishingCredentials() = runBlocking<Unit> {
        Fixture(manualTickets = true).use { f ->
            f.ticketError = "method_not_found"; f.expectedTicket = null
            checkNotNull(f.local.connectIfSelected(f.pairing())).use { it.workspaces() }
            assertEquals(1, f.wires.size)
        }
    }

    @Test fun malformedOrUnauthorizedManualTicketNeverPublishesSharedConnection() = runBlocking<Unit> {
        for (malformed in listOf(true, false)) Fixture(manualTickets = true).use { f ->
            f.malformedTicket = malformed
            if (!malformed) f.ticketError = "unauthorized"
            val failure = runCatching { f.local.connectIfSelected(f.pairing()) }.exceptionOrNull()
            assertTrue(if (malformed) failure is InvalidManualAttachTicket else failure is MobileRpcException)
            assertEquals(listOf("mobile.host.status", "mobile.attach_ticket.create"), f.wires.single().methods)
            assertTrue(f.wires.single().closed); assertNull(f.local.powerSession(f.team, f.target))
        }
    }

    @Test fun grantRevokedDuringTicketRequestCannotPublishOrReachWorkspace() = runBlocking<Unit> {
        Fixture(manualTickets = true).use { f ->
            f.ticketHook = { f.setRoutes(emptyList()) }
            assertTrue(runCatching { f.local.connectIfSelected(f.pairing()) }.isFailure)
            assertEquals(listOf("mobile.host.status", "mobile.attach_ticket.create"), f.wires.single().methods)
            assertTrue(f.wires.single().closed); assertNull(f.local.powerSession(f.team, f.target))
        }
    }

    private class Fixture(private val enforceCompatibility: Boolean = false,
        audience: NativeMacBuildAudience? = null, manualTickets: Boolean = false) : AutoCloseable {
        val team = NativeTeamScope("login", "user", "team", 1)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val target = NativeComputerTarget("mac", "default", "Mac")
        var disk: String? = null
        val settings = NativeMacConnectionStore({ disk }, { disk = it })
        val revisions = MutableStateFlow(0L)
        val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), team.userId, team.teamId, "a".repeat(64),
            target.deviceId, target.buildTag, PairingCode.Route("100.99.1.2", 58465))
        val grants = MutableStateFlow(listOf(grant))
        val wires = CopyOnWriteArrayList<Wire>()
        @Volatile var badIdentity = false
        @Volatile var transportFailure: Exception? = null
        @Volatile var tokenHook: suspend () -> Unit = {}
        @Volatile var hostGate: CompletableDeferred<Unit>? = null
        val hostGates = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, CompletableDeferred<Unit>>()
        val hostEntered = Channel<Unit>(16)
        var version = "0.64.24"
        var ticketError: String? = null
        var malformedTicket = false
        var ticketHook: () -> Unit = {}
        var expectedTicket: String? = if (manualTickets) "saved-ticket" else null
        val compatibility = NativeMacCompatibilityGate({ teams.value.scope == it })
        val local: NativeSavedTailscaleRuntime
        init {
            settings.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            local = NativeSavedTailscaleRuntime(teams, { teams.value.scope == it }, { tokenHook(); "fixture-token" },
                admitCompatibility = { owner, client, host -> if (enforceCompatibility) compatibility.admit(owner, client, host, true) },
                audience = audience, manualTicket = { client, route, host, scope, email ->
                    if (manualTickets) ManualAttachTicketRequest.request(client, route, host, scope, email) else null
                }) { owner ->
                NativeSavedTailscaleAccount(settings, revisions, { selected -> grants.value.filter {
                    it.user == owner.userId && it.team == owner.teamId && it.device == selected.deviceId && it.build == selected.buildTag
                } }, { selected, allowed ->
                    transportFailure?.let { throw it }
                    Wire(selected.first(), allowed, this).also { wires += it }
                })
            }
        }
        fun pairing(target: NativeComputerTarget = this.target) = PairingCode.Iroh("unused-iroh-peer", target.deviceId,
            team.userId, team.teamId, target.buildTag)
        fun setRoutes(values: List<TailscaleSavedGrant>) { grants.value = values; revisions.value++ }
        override fun close() = local.close()
    }
    @Test fun consumerSavedTailscaleCannotDialDevelopmentTargetOrBorrowItsPowerSession() = runBlocking<Unit> {
        Fixture(audience = NativeMacBuildAudience.consumer).use { f ->
            val dev = f.target.copy(buildTag = "dev")
            f.settings.update(dev, { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            f.setRoutes(listOf(f.grant.copy(build = "dev")))
            assertTrue(runCatching { f.local.connectIfSelected(f.team, dev) }.exceptionOrNull() is MacBuildNotSupported)
            assertNull(f.local.powerSession(f.team, dev))
            assertTrue(f.wires.isEmpty())
        }
    }
    private class Wire(val grant: TailscaleSavedGrant, val allowed: () -> Boolean, val fixture: Fixture) : MobileRpcTransport {
        private val hostGate = fixture.hostGates[grant.device to grant.build] ?: fixture.hostGate
        val replies = Channel<ByteArray>(32)
        val methods = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        override fun tailscalePeer() = grant.route.also { check(!closed && allowed()) }
        override suspend fun connect() { check(allowed()) }
        override suspend fun write(bytes: ByteArray) {
            check(!closed && allowed())
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); methods += method
            assertEquals("fixture-token", request.getJSONObject("auth").getString("stack_access_token"))
            if (method == "mobile.attach_ticket.create") {
                assertFalse(request.getJSONObject("auth").has("attach_token"))
                fixture.ticketHook()
                val reply = JSONObject().put("id", request.getString("id")).put("ok", fixture.ticketError == null)
                if (fixture.ticketError != null) reply.put("error", JSONObject().put("code", fixture.ticketError).put("message", "fixture"))
                else reply.put("result", if (fixture.malformedTicket) JSONObject() else JSONObject().put("ticket", JSONObject(
                    """{"version":1,"workspaceID":"","macDeviceID":"${grant.device}","macUserID":"user","auth_token":"saved-ticket",
                        "routes":[{"id":"ts","kind":"tailscale","endpoint":{"type":"host_port","host":"100.99.1.8","port":12345}}]}""")))
                replies.send(MobileFrameCodec.encode(reply.toString().toByteArray())); return
            }
            val result = when (method) {
                "mobile.host.status" -> {
                    fixture.hostEntered.send(Unit); hostGate?.await()
                    JSONObject().put("mac_device_id", if (fixture.badIdentity) "other" else grant.device)
                        .put("mac_instance_tag", grant.build).put("mac_app_version", fixture.version).put("capabilities", JSONArray())
                }
                "mobile.workspace.list" -> {
                    if (fixture.expectedTicket == null) assertFalse(request.getJSONObject("auth").has("attach_token"))
                    else assertEquals(fixture.expectedTicket, request.getJSONObject("auth").getString("attach_token"))
                    JSONObject().put("workspaces", JSONArray())
                }
                else -> error("Unexpected fixture request: $method")
            }
            replies.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                .put("ok", true).put("result", result).toString().toByteArray()))
        }
        override suspend fun read(): ByteArray? = replies.receiveCatching().getOrNull()?.also { check(allowed() && !closed) }
        override fun close() { closed = true; replies.close(); hostGate?.cancel() }
    }

    @Test fun savedTailscaleCannotBypassVersionGateAndNewPolicyRetiresItsSharedWire() = runBlocking<Unit> {
        Fixture(enforceCompatibility = true).use { f ->
            assertTrue(runCatching { f.local.connectIfSelected(f.pairing()) }.exceptionOrNull() is MacUpdateRequired)
            assertNull(f.local.powerSession(f.team, f.target))
            f.version = "0.64.25"
            val foreground = checkNotNull(f.local.connectIfSelected(f.pairing()))
            val background = checkNotNull(f.local.connectIfSelected(f.pairing()))
            f.compatibility.replace(checkNotNull(NativeMacCompatibilityPolicy.decode(
                """{"entries":[{"minIOSVersion":"1.0.6","stableMinVersion":"0.65.0"}]}""")))
            assertTrue(withTimeout(1000) { foreground.disconnected.first() } is MacUpdateRequired)
            assertTrue(withTimeout(1000) { background.disconnected.first() } is MacUpdateRequired)
            assertNull(f.local.powerSession(f.team, f.target))
            foreground.close(); background.close()
        }
    }

    @Test fun stalledHostProbeDoesNotBlockSiblingAndGrantRevocationKeepsSiblingLive() = runBlocking<Unit> {
        Fixture().use { f ->
            val sibling = f.target.copy(deviceId = "other-mac")
            val otherGrant = f.grant.copy(id = UUID.randomUUID().toString(), source = "b".repeat(64), device = sibling.deviceId)
            f.settings.update(sibling, { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            f.setRoutes(listOf(f.grant, otherGrant))
            f.hostGates[f.target.deviceId to f.target.buildTag] = CompletableDeferred()
            val pending = async { runCatching { f.local.connectIfSelected(f.pairing()) } }
            try {
                withTimeout(2000) { f.hostEntered.receive() }
                val active = checkNotNull(withTimeout(2000) { f.local.connectIfSelected(f.pairing(sibling)) })
                val shared = checkNotNull(withTimeout(2000) { f.local.connectIfSelected(f.pairing(sibling)) })
                assertEquals(2, f.wires.size)
                assertFalse(pending.isCompleted)
                f.setRoutes(listOf(otherGrant))
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                active.close(); assertFalse(shared.isClosed)
                assertEquals(0, withTimeout(2000) { shared.workspaces() }.getJSONArray("workspaces").length())
                assertFalse(f.wires.last().closed)
                shared.close(); assertTrue(f.wires.last().closed)
            } finally { f.close(); pending.await() }
        }
    }

    @Test fun savedTailscaleConnectsAndChecksWhileIrohStartupNeverCompletes() = runBlocking<Unit> {
        Fixture().use { f ->
            val startup = CompletableDeferred<Unit>()
            NativeIrohRuntime(f.teams, { f.teams.value.scope == it }, { "unused" },
                { _, _ -> startup.complete(Unit); awaitCancellation() }, savedTailscale = f.local).use { facade ->
                startup.await()
                val first = withTimeout(2000) { facade.connect(f.pairing()) }
                assertFalse(facade.state.value.ready)
                val second = withTimeout(2000) { facade.connect(f.pairing()) }
                assertEquals(1, f.wires.size)
                val report = withTimeout(2000) { facade.checkComputer(f.team, f.target) }
                assertTrue(report.identity); assertTrue(report.accountAccess); assertNull(report.failure)
                assertEquals(1, f.wires.size)
                val key = withTimeout(2000) { facade.state.first { it.localConnectionKeys.isNotEmpty() } }.connectionKey(f.pairing())
                assertNotNull(key)
                assertNotNull(facade.powerSession(f.team, f.target)?.let { power ->
                    withTimeoutOrNull(50) { power.run() }; power
                })
                first.close(); assertFalse(f.wires.single().closed)
                second.close(); assertTrue(f.wires.single().closed)
            }
        }
    }

    @Test fun unavailableVpnProducesTailscaleAdviceInComputerDetails() = runBlocking<Unit> {
        Fixture().use { f ->
            f.transportFailure = TailscaleReadinessException()
            NativeIrohRuntime(f.teams, { f.teams.value.scope == it }, { "unused" },
                { _, _ -> awaitCancellation() }, savedTailscale = f.local).use { facade ->
                val report = withTimeout(2000) { facade.checkComputer(f.team, f.target) }
                assertEquals(NativeConnectionReport.Failure.TAILSCALE, report.failure)
                assertTrue(report.shareText().contains("Open Tailscale"))
                assertFalse(report.shareText().contains(f.grant.route.host))
                assertFalse(report.identity); assertFalse(report.accountAccess)
            }
        }
    }

    @Test fun repeatedIrohBrokerFailuresCannotRetireSavedTailscaleLease() = runBlocking<Unit> {
        Fixture().use { f ->
            val attempts = java.util.concurrent.atomic.AtomicInteger()
            NativeIrohRuntime(f.teams, { f.teams.value.scope == it }, { "unused" }, { _, _ ->
                attempts.incrementAndGet(); throw java.io.IOException("fixture broker unavailable")
            }, retryDelayMillis = 20, savedTailscale = f.local).use { facade ->
                val client = withTimeout(2000) { facade.connect(f.pairing()) }
                withTimeout(2000) { while (attempts.get() < 3) delay(10) }
                assertFalse(client.isClosed); assertFalse(f.wires.single().closed)
                client.workspaces(); client.close()
            }
        }
    }

    @Test fun explicitTailscaleWithoutGrantFailsBeforeAnyTransportAndNeverUsesBroker() = runBlocking<Unit> {
        Fixture().use { f ->
            f.setRoutes(emptyList())
            assertTrue(runCatching { withTimeout(1000) { f.local.connectIfSelected(f.pairing()) } }.isFailure)
            assertTrue(f.wires.isEmpty())
            f.settings.update(f.target, { true }) { it.copy(method = NativeMacConnectionMethod.IROH) }
            assertNull(f.local.connectIfSelected(f.pairing()))
            assertTrue(f.wires.isEmpty())
        }
    }

    @Test fun grantRemovalClosesItsMacButKeepsSiblingBuildConnected() = runBlocking<Unit> {
        Fixture().use { f ->
            val sibling = f.target.copy(buildTag = "debug")
            val otherGrant = f.grant.copy(id = UUID.randomUUID().toString(), source = "b".repeat(64), build = sibling.buildTag)
            f.settings.update(sibling, { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            f.setRoutes(listOf(f.grant, otherGrant))
            val a = checkNotNull(f.local.connectIfSelected(f.pairing()))
            val b = checkNotNull(f.local.connectIfSelected(f.pairing(sibling)))
            f.setRoutes(listOf(otherGrant))
            assertFalse(f.wires[0].allowed())
            withTimeout(2000) { a.disconnected.first() }
            assertFalse(b.isClosed); b.workspaces()
            a.close(); b.close()
        }
    }

    @Test fun modeRoundTripCannotReviveAnOldLease() = runBlocking<Unit> {
        Fixture().use { f ->
            val old = checkNotNull(f.local.connectIfSelected(f.pairing()))
            f.settings.update(f.target, { true }) { it.copy(method = NativeMacConnectionMethod.IROH) }
            f.settings.update(f.target, { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            assertFalse(f.wires[0].allowed())
            withTimeout(2000) { old.disconnected.first() }
            checkNotNull(f.local.connectIfSelected(f.pairing())).close()
            assertEquals(2, f.wires.size); old.close()
        }
    }

    @Test fun scopeChangeClosesLeaseAndOldLocatorCannotReachAnotherTeam() = runBlocking<Unit> {
        Fixture().use { f ->
            val old = checkNotNull(f.local.connectIfSelected(f.pairing()))
            f.teams.value = NativeAccountTeamsState(scope = f.team.copy(teamId = "other", generation = 2))
            withTimeout(2000) { old.disconnected.first() }
            assertTrue(runCatching { f.local.connectIfSelected(f.pairing()) }.isFailure)
            assertEquals(1, f.wires.size); old.close()
        }
    }

    @Test fun tokenAcquisitionRaceCannotWriteUnderOldScope() = runBlocking<Unit> {
        Fixture().use { f ->
            f.tokenHook = { f.teams.value = NativeAccountTeamsState(scope = f.team.copy(teamId = "other", generation = 2)) }
            assertTrue(runCatching { f.local.connectIfSelected(f.pairing()) }.isFailure)
            assertTrue(f.wires.single().methods.isEmpty())
            assertTrue(f.wires.single().closed)
        }
    }

    @Test fun grantRevocationDuringHostProbePreventsPublishingCandidate() = runBlocking<Unit> {
        Fixture().use { f ->
            f.hostGate = CompletableDeferred()
            val pending = async { runCatching { f.local.connectIfSelected(f.pairing()) } }
            withTimeout(2000) { f.hostEntered.receive() }
            f.setRoutes(emptyList())
            assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            assertTrue(f.wires.single().closed)
        }
    }

    @Test fun wrongHostCannotCompleteAuthenticatedAdmission() = runBlocking<Unit> {
        Fixture().use { f ->
            f.badIdentity = true
            assertTrue(runCatching { f.local.connectIfSelected(f.pairing()) }.isFailure)
            assertEquals(listOf("mobile.host.status"), f.wires.single().methods)
            assertTrue(f.wires.single().closed)
        }
    }

    @Test fun corruptSettingsRetireSessionsAndCannotFallBackToIroh() = runBlocking<Unit> {
        Fixture().use { f ->
            val client = checkNotNull(f.local.connectIfSelected(f.pairing()))
            f.disk = "not json"; f.settings.reload()
            withTimeout(2000) { client.disconnected.first() }
            assertTrue(runCatching { f.local.connectIfSelected(f.pairing()) }.isFailure)
            assertEquals(1, f.wires.size); client.close()
        }
    }

    @Test fun closeRejectsNewAcquisitionsWithoutWaitingForADiscoveryTimeout() = runBlocking<Unit> {
        val f = Fixture(); f.close()
        val failure = runCatching { withTimeout(500) { f.local.connectIfSelected(f.pairing()) } }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure is TimeoutCancellationException)
        assertTrue(f.wires.isEmpty())
    }

    @Test fun closeInterruptsAnAcquisitionWaitingForAccountScope() = runBlocking<Unit> {
        Fixture().use { f ->
            f.teams.value = NativeAccountTeamsState()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { f.local.connectIfSelected(f.pairing()) }.exceptionOrNull()
            }
            assertFalse(waiting.isCompleted)
            f.close()
            val failure = withTimeout(500) { waiting.await() }
            assertNotNull(failure)
            assertFalse(failure is TimeoutCancellationException)
            assertTrue(f.wires.isEmpty())
        }
    }
}
