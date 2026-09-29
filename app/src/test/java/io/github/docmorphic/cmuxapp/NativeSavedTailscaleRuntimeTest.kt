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
    private class Fixture : AutoCloseable {
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
        val hostEntered = Channel<Unit>(16)
        val local: NativeSavedTailscaleRuntime
        init {
            settings.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            local = NativeSavedTailscaleRuntime(teams, { teams.value.scope == it }, { tokenHook(); "fixture-token" }) { owner ->
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
    private class Wire(val grant: TailscaleSavedGrant, val allowed: () -> Boolean, val fixture: Fixture) : MobileRpcTransport {
        val replies = Channel<ByteArray>(32)
        val methods = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        override suspend fun connect() { check(allowed()) }
        override suspend fun write(bytes: ByteArray) {
            check(!closed && allowed())
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); methods += method
            assertEquals("fixture-token", request.getJSONObject("auth").getString("stack_access_token"))
            val result = when (method) {
                "mobile.host.status" -> {
                    fixture.hostEntered.send(Unit); fixture.hostGate?.await()
                    JSONObject().put("mac_device_id", if (fixture.badIdentity) "other" else grant.device)
                        .put("mac_instance_tag", grant.build).put("capabilities", JSONArray())
                }
                "mobile.workspace.list" -> JSONObject().put("workspaces", JSONArray())
                else -> error("Unexpected fixture request: $method")
            }
            replies.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                .put("ok", true).put("result", result).toString().toByteArray()))
        }
        override suspend fun read(): ByteArray? = replies.receiveCatching().getOrNull()?.also { check(allowed() && !closed) }
        override fun close() { closed = true; replies.close(); fixture.hostGate?.cancel() }
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
