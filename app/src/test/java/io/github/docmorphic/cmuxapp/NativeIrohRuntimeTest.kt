package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.json.JSONArray

class NativeIrohRuntimeTest {
    @Test fun applicationDiscoversExactCompanionButAdmissionStillRejectsAnotherAuthenticatedBundle() = runBlocking<Unit> {
        val account = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        val companion = mac.copy(recordId = "companion", endpointId = "cd".repeat(32), buildTag = NativeMacBuildAudience.COMPANION_TAG)
        backend.state.value = ready().copy(computers = listOf(mac, companion, mac.copy(recordId = "dev", endpointId = "ef".repeat(32), buildTag = "dev")))
        val gate = NativeMacCompatibilityGate({ account.value.scope == it }, audience = NativeMacBuildAudience.application)
        NativeIrohRuntime(account, { account.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 },
            audience = NativeMacBuildAudience.application,
            admitCompatibility = { owner, client, status, tailscale -> gate.admit(owner, client, status, tailscale) }).use { runtime ->
            val directory = withTimeout(2000) { runtime.state.first { it.ready } }
            assertEquals(setOf("default", NativeMacBuildAudience.COMPANION_TAG), directory.computers.map { it.buildTag }.toSet())
            suspend fun answer(index: Int, namespace: String) {
                val wire = withTimeout(2000) {
                    while (synchronized(backend.transports) { backend.transports.size <= index }) delay(1)
                    synchronized(backend.transports) { backend.transports[index] }
                }
                val request = withTimeout(2000) { wire.sent.receive() }
                assertEquals("mobile.host.status", request.getString("method"))
                wire.incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                    .put("ok", true).put("result", JSONObject().put("mac_device_id", companion.deviceId)
                        .put("mac_instance_tag", companion.buildTag).put("mac_client_namespace", namespace)
                        .put("mac_app_version", "0.65.0")).toString().toByteArray()))
                wire.answer(withTimeout(2000) { wire.sent.receive() })
            }
            val locator = PairingCodeParser.parse(PairingCodeParser.computer(companion, team)).getOrThrow() as PairingCode.Iroh
            val wrong = async { runCatching { runtime.connect(locator) } }
            answer(0, "mac:com.cmuxterm.app.debug.other")
            assertTrue(withTimeout(2000) { wrong.await() }.exceptionOrNull() is MacBuildNotSupported)
            assertTrue(gate.observations.value.isEmpty())
            val correct = async { runtime.connect(locator) }
            answer(1, NativeMacBuildAudience.COMPANION_NAMESPACE)
            withTimeout(2000) { correct.await() }.use { assertFalse(it.isClosed) }
            assertEquals(1, gate.observations.value.size)
        }
    }
    private data class Created(val account: NativeTeamScope, val backend: IrohAccountBackend, val alive: () -> Boolean)
    private inner class ContinuationFixture(val account: NativeAccountTeams, val server: MockWebServer,
        val runtime: NativeIrohRuntime, val initial: NativeTeamScope, val created: List<Created>) {
        val clients = mutableListOf<MobileRpcClient>()
        val holds = mutableListOf<NativeForegroundTransport>()
        fun backend(id: String = initial.teamId) = created.first { it.account.teamId == id }.backend as Backend
        suspend fun select(id: String): NativeTeamScope {
            server.teamSelection{MockResponse().setBody(user(id).toString())}
            val next = checkNotNull(account.select(id).scope)
            withTimeout(2000) { runtime.state.first { it.account == next && it.ready } }
            return next
        }
        suspend fun refresh(selected: String, vararg members: String) {
            server.teamUsers(2){MockResponse().setBody(user(selected).toString())}
            server.enqueue(MockResponse().setBody(members(*members).toString()))
            account.refresh()
        }
        suspend fun connect(scope: NativeTeamScope = initial, host: IrohV2Computer = mac): MobileRpcClient =
            runtime.connect(PairingCodeParser.parse(PairingCodeParser.computer(host, scope)).getOrThrow() as PairingCode.Iroh)
                .also { clients += it }
        fun reserve(client: MobileRpcClient, scope: NativeTeamScope = initial): NativeForegroundTransport =
            checkNotNull(runtime.reserveForeground(checkNotNull(account.captureContinuation(scope)), client)).also { holds += it }
        suspend fun foregroundRpc(client: MobileRpcClient, wire: PoolTestTransport) = coroutineScope {
            val read = async { client.workspaces() }
            wire.answer(withTimeout(2000) { wire.sent.receive() })
            assertEquals("mobile.workspace.list", withTimeout(2000) { read.await() }.getString("method"))
        }
    }
    private fun user(selected: String) = JSONObject().put("id", team.userId)
        .put("selected_team", JSONObject().put("id", selected))
    private fun members(vararg ids: String) = JSONObject().put("items", JSONArray(ids.map {
        JSONObject().put("id", it).put("display_name", it)
    }))
    private suspend fun continuation(block: suspend ContinuationFixture.() -> Unit) {
        MockWebServer().use { server ->
            NativeAccountTeams({ "fixture" }, { team.login }, server.url("/api/v1/")).use { account ->
                server.teamUsers(2){MockResponse().setBody(user(team.teamId).toString())}
                server.enqueue(MockResponse().setBody(members(team.teamId, "team-b").toString()))
                val initial = checkNotNull(account.refresh().scope)
                val created = java.util.concurrent.CopyOnWriteArrayList<Created>()
                NativeIrohRuntime(account.state, account::isCurrent, { "fixture" }, { selected, alive ->
                    Backend().also { created += Created(selected, it, alive) }
                }, { 1000 }, allowsContinuation = account::allowsContinuation).use { runtime ->
                    withTimeout(2000) { runtime.state.first { it.ready } }
                    val fixture = ContinuationFixture(account, server, runtime, initial, created)
                    try { fixture.block() }
                    finally { fixture.holds.forEach { it.close() }; fixture.clients.forEach { it.close() } }
                }
            }
        }
    }

    @Test fun initialTicketViewUsesActualForegroundRuntimeOwnershipAcrossSelectionAndReturn() = runBlocking<Unit> {
        continuation {
            val base = connect(); val wire = backend().transports.single()
            var authorizedRoute = true
            val client = attachAdmittedTicket(base, MobileAttachTicketContext("", null, "fixture-ticket", null),
                { account.isCurrent(initial) }, { runtime.retainsForeground(initial, it) }) { check(authorizedRoute) }
            clients += client
            val hold = reserve(client); hold.activate { true }
            select("team-b")
            coroutineScope {
                val rpc = async { client.workspaces() }
                val request = withTimeout(2000) { wire.sent.receive() }
                assertEquals("fixture-ticket", request.getJSONObject("auth").getString("attach_token"))
                wire.answer(request); withTimeout(2000) { rpc.await() }
            }
            select(initial.teamId); foregroundRpc(client, wire)
            assertEquals(1, backend().transports.size)
            authorizedRoute = false
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
            authorizedRoute = true; hold.close()
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun foregroundSurvivesTeamSelectionWhileSecondaryAndPendingConnectionsRetireAndReturnReusesEndpoint() = runBlocking<Unit> {
        continuation {
            val firstBackend = backend()
            val sibling = mac.copy(recordId = "secondary", endpointId = "cd".repeat(32), deviceId = "secondary-device")
            val stalled = mac.copy(recordId = "stalled", endpointId = "ef".repeat(32), deviceId = "stalled-device")
            firstBackend.state.value = ready().copy(computers = listOf(mac, sibling, stalled))
            val primary = connect(); val secondary = connect(host = sibling)
            val foregroundWire = firstBackend.transports.first()
            val hold = reserve(primary)
            assertFalse(runtime.retainsForeground(initial, primary))
            hold.activate { true }
            assertTrue(runtime.retainsForeground(initial, primary.compatibilityWire))
            assertFalse(runtime.retainsForeground(initial, secondary))
            assertFalse(runtime.retainsForeground(initial.copy(login = "other-login"), primary))
            val entered = CompletableDeferred<Unit>()
            firstBackend.prepareWire = { selected, wire -> if (selected == stalled) {
                wire.gate = CompletableDeferred(); entered.complete(Unit)
            } }
            val pending = async { runCatching { connect(host = stalled) } }
            try {
                withTimeout(2000) { entered.await() }
                val b = select("team-b")
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                withTimeout(2000) { secondary.disconnected.first() }
                assertFalse(primary.isClosed); assertEquals(0, firstBackend.closes.get())
                assertTrue(created.first().alive()); assertTrue(firstBackend.permissions[0]())
                assertFalse(firstBackend.permissions[1]()); assertFalse(firstBackend.permissions[2]())
                assertEquals(b, runtime.state.value.account)
                assertTrue(runCatching { runtime.connectSaved(pairing(initial), NativeComputerTarget.from(mac), initial) }.isFailure)
                assertTrue(runCatching { runtime.networking(initial) }.isFailure)
                assertNull(runtime.powerSession(initial, NativeComputerTarget.from(mac)))
                foregroundRpc(primary, foregroundWire)
                val returned = select(initial.teamId)
                assertNotEquals(initial.generation, returned.generation)
                assertTrue(runtime.retainsForeground(returned, primary))
                assertEquals(1, created.count { it.account.teamId == initial.teamId })
                connect(returned).close()
                assertEquals(3, firstBackend.transports.size) // foreground, secondary and retired stalled candidate
                foregroundRpc(primary, foregroundWire)
                hold.close(); select("team-b")
                withTimeout(2000) { primary.disconnected.first(); while (firstBackend.closes.get() == 0) delay(1) }
            } finally { pending.cancel(); pending.join() }
        }
    }

    @Test fun continuationCannotSurviveMembershipRemovalOrReviveOnReaddition() = runBlocking<Unit> {
        continuation {
            val primary = connect(); val firstBackend = backend(); val hold = reserve(primary)
            hold.activate { true }; select("team-b")
            refresh("team-b", "team-b")
            withTimeout(2000) { primary.disconnected.first(); while (firstBackend.closes.get() == 0) delay(1) }
            assertFalse(created.first().alive()); assertEquals(0, backend("team-b").closes.get())
            refresh("team-b", initial.teamId, "team-b")
            assertTrue(runCatching { hold.activate { true } }.isFailure)
            val returned = select(initial.teamId)
            connect(returned).use { assertFalse(it.isClosed) }
            assertEquals(2, created.count { it.account.teamId == initial.teamId })
        }
    }

    @Test fun parkedForegroundStillRequiresLiveDirectoryAndExactConnectionSettings() = runBlocking<Unit> {
        for (remove in listOf(true, false)) continuation {
            val firstBackend = backend(); val primary = connect(); reserve(primary).activate { true }
            select("team-b")
            if (remove) firstBackend.state.value = ready().copy(computers = emptyList())
            else firstBackend.connectionSettings.update(NativeComputerTarget.from(mac), { true }) {
                it.copy(method = NativeMacConnectionMethod.DIRECT)
            }
            assertFalse(firstBackend.permissions[0]())
            withTimeout(2000) { primary.disconnected.first(); while (firstBackend.closes.get() == 0) delay(1) }
            assertEquals(0, backend("team-b").closes.get())
        }
    }

    @Test fun lateSelectedOperationCannotPublishAfterTeamRoundTripEvenWhenForegroundBackendIsReused() = runBlocking<Unit> {
        continuation {
            val primary = connect(); reserve(primary).activate { true }
            val firstBackend = backend(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            firstBackend.networkRefresh = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val pending = async { runCatching { runtime.networking(initial, true) } }
            try {
                withTimeout(2000) { entered.await() }
                select("team-b"); val returned = select(initial.teamId)
                release.complete(Unit)
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                assertEquals(returned, runtime.state.value.account)
                assertEquals(1, created.count { it.account.teamId == initial.teamId })
                assertNotNull(runtime.networking(returned))
                foregroundRpc(primary, firstBackend.transports.first())
            } finally { release.complete(Unit); pending.cancel(); pending.join() }
        }
    }

    @Test fun unactivatedUnownedOrRejectedReservationCannotKeepOrPromoteAConnection() = runBlocking<Unit> {
        continuation {
            val primary = connect(); val hold = reserve(primary)
            assertTrue(runCatching { hold.activate { false } }.isFailure)
            val proof = checkNotNull(account.captureContinuation(initial))
            MobileRpcClient(PoolTestTransport(), { "fixture" }).use { unknown ->
                unknown.connect(); assertNull(runtime.reserveForeground(proof, unknown))
            }
            assertNull(runtime.reserveForeground(NativeTeamContinuation(initial, Any()), primary))
            select("team-b"); withTimeout(2000) { primary.disconnected.first() }
            assertTrue(runCatching { hold.activate { true } }.isFailure)
        }
    }

    @Test fun replacementForegroundAndOldHandleCleanupCannotRetireNewSelectedTransport() = runBlocking<Unit> {
        continuation {
            val primary = connect(); val firstBackend = backend(); val old = reserve(primary)
            old.activate { true }; val b = select("team-b")
            val replacement = connect(b); val next = reserve(replacement, b); next.activate { true }
            old.close()
            withTimeout(2000) { primary.disconnected.first(); while (firstBackend.closes.get() == 0) delay(1) }
            assertFalse(replacement.isClosed); assertEquals(0, backend("team-b").closes.get())
            foregroundRpc(replacement, backend("team-b").transports.first())
            next.close(); assertFalse(replacement.isClosed)
        }
    }

    @Test fun foregroundReservationIsSingleUseAndBackendAuthFailureRetiresItWithoutOldTeamReenrollment() = runBlocking<Unit> {
        continuation {
            val primary = connect(); val firstBackend = backend(); val hold = reserve(primary)
            hold.activate { true }
            assertTrue(runCatching { hold.activate { true } }.isFailure)
            select("team-b")
            firstBackend.state.value = IrohV2ControlState(failure = "unauthorized")
            val failure = withTimeout(2000) { primary.disconnected.first() }
            assertTrue(failure is IrohV2ServerFailure)
            assertEquals("unauthorized", (failure as IrohV2ServerFailure).code)
            assertTrue(nativeAutomaticReconnectStops(failure))
            withTimeout(2000) { while (firstBackend.closes.get() == 0) delay(1) }
            assertEquals(1, created.count { it.account.teamId == initial.teamId })
            assertEquals(0, backend("team-b").closes.get())
        }
    }

    @Test fun replacementKeyOwnerWaitsForOldEndpointTeardownAndSkipsSupersededSelection() = runBlocking<Unit> {
        continuation {
            val firstBackend = backend(); val closing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            firstBackend.awaitClose = { closing.complete(Unit); release.await() }
            server.teamSelection{MockResponse().setBody(user("team-b").toString())}
            account.select("team-b")
            try {
                withTimeout(2000) { closing.await() }
                server.teamSelection{MockResponse().setBody(user(initial.teamId).toString())}
                val returned = checkNotNull(account.select(initial.teamId).scope)
                assertEquals(1, created.size)
                release.complete(Unit)
                withTimeout(2000) { runtime.state.first { it.account == returned && it.ready } }
                assertEquals(2, created.size)
                assertTrue(created.all { it.account.teamId == initial.teamId })
                assertEquals(1, firstBackend.closes.get())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun consumerDiscoveryExcludesDevAndCannotDialItThroughAStaleLocatorOrDiagnostic() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val dev = mac.copy(endpointId = "cd".repeat(32), recordId = "dev", buildTag = "dev")
        val nightly = mac.copy(endpointId = "ef".repeat(32), recordId = "nightly", buildTag = "nightly")
        backend.state.value = ready().copy(computers = listOf(mac, dev, nightly))
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 },
            audience = NativeMacBuildAudience.consumer).use { runtime ->
            val state = withTimeout(2000) { runtime.state.first { it.ready } }
            assertEquals(setOf("default", "nightly"), state.computers.map { it.buildTag }.toSet())
            assertFalse(state.connectionKeys.containsKey(dev.endpointId))
            val locator = PairingCodeParser.parse(PairingCodeParser.computer(dev, team)).getOrThrow() as PairingCode.Iroh
            assertTrue(runCatching { runtime.connect(locator) }.exceptionOrNull() is MacBuildNotSupported)
            assertTrue(runCatching { runtime.connect(locator.copy(buildTag = null)) }.exceptionOrNull() is MacBuildNotSupported)
            assertEquals(NativeConnectionReport.Failure.BUILD, runtime.checkComputer(team, NativeComputerTarget.from(dev)).failure)
            assertTrue(backend.transports.isEmpty())
            runtime.connect(pairing()).use { assertFalse(it.isClosed) }
        }
    }
    @Test fun compatibilityAdmissionVerifiesHostBeforePublishingAndReportsUpdateInDiagnostics() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val gate = NativeMacCompatibilityGate({ teams.value.scope == it })
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 },
            admitCompatibility = { owner, client, host, tailscale -> gate.admit(owner, client, host, tailscale) }).use { runtime ->
            suspend fun answerAttempt(index: Int, device: String, version: String) {
                val wire = withTimeout(2000) {
                    while (synchronized(backend.transports) { backend.transports.size <= index }) delay(1)
                    synchronized(backend.transports) { backend.transports[index] }
                }
                val request = withTimeout(2000) { wire.sent.receive() }
                assertEquals("mobile.host.status", request.getString("method"))
                wire.incoming.send(MobileFrameCodec.encode(org.json.JSONObject().put("id", request.getString("id"))
                    .put("ok", true).put("result", org.json.JSONObject().put("mac_device_id", device)
                        .put("mac_instance_tag", "default").put("mac_app_version", version)).toString().toByteArray()))
                if (device == mac.deviceId) wire.answer(withTimeout(2000) { wire.sent.receive() })
            }
            val wrongHost = async { runCatching { runtime.connect(pairing()) } }
            answerAttempt(0, "different-mac", "0.1")
            assertTrue(withTimeout(2000) { wrongHost.await() }.isFailure)
            assertTrue(gate.warnings.value.isEmpty())
            val oldHost = async { runtime.checkComputer(team, NativeComputerTarget.from(mac)) }
            answerAttempt(1, mac.deviceId, "0.64.24")
            assertEquals(NativeConnectionReport.Failure.UPDATE, withTimeout(2000) { oldHost.await() }.failure)
            assertEquals(1, gate.warnings.value.size)
            val newHost = async { runtime.connect(pairing()) }
            answerAttempt(2, mac.deviceId, "0.64.25")
            withTimeout(2000) { newHost.await() }.use { assertFalse(it.isClosed) }
            assertTrue(gate.warnings.value.isEmpty())
            MobileRpcClient(PoolTestTransport(), { "fixture" }).use { probe ->
                probe.connect()
                val host = org.json.JSONObject().put("mac_device_id", mac.deviceId).put("mac_instance_tag", mac.buildTag)
                    .put("mac_app_version", "0.64.24")
                assertTrue(runCatching { runtime.admitAuthenticatedHost(team, probe, host) }.exceptionOrNull() is MacUpdateRequired)
            }
        }
    }
    private val team = NativeTeamScope("login-a", "user-a", "team-a", 1)
    private val mac = IrohV2Computer("record", "ab".repeat(32), "mac-id", "default", "Mac", emptyList())
    private fun ready() = IrohV2ControlState(ready = true, computers = listOf(mac), permissionExpiresAt = 2000)
    private inner class Backend : IrohAccountBackend {
        override val state = MutableStateFlow(ready())
        var pathJson: String? = null
        override val privatePaths = NativePrivatePathStore({ pathJson }, { pathJson = it })
        var settingJson: String? = null
        override val connectionSettings = NativeMacConnectionStore({ settingJson }, { settingJson = it })
        override val routeRevisions = MutableStateFlow(0L)
        var grants = emptyList<TailscaleSavedGrant>()
        override fun dialIntent(mac: IrohV2Computer): NativeMacDialIntent {
            val intent = connectionSettings.state.value.intent(mac)
            return if (intent.method == NativeMacConnectionMethod.TAILSCALE)
                intent.copy(tailscale = grants.filter { it.device == mac.deviceId && it.build == mac.buildTag }) else intent
        }
        val intents = java.util.concurrent.CopyOnWriteArrayList<NativeMacDialIntent>()
        val dialedComputers = java.util.concurrent.CopyOnWriteArrayList<IrohV2Computer>()
        val permissions = java.util.concurrent.CopyOnWriteArrayList<() -> Boolean>()
        override fun transport(mac: IrohV2Computer, permits: () -> Boolean, intent: NativeMacDialIntent): MobileRpcTransport {
            intents += intent; permissions += permits; dialedComputers += mac
            return transport(mac, permits)
        }
        val closes = AtomicInteger()
        val refreshes = AtomicInteger()
        val transports = mutableListOf<PoolTestTransport>()
        var startAction: suspend () -> Unit = { }
        var dial: suspend () -> Unit = { }
        var prepareWire: (IrohV2Computer, PoolTestTransport) -> Unit = { _, _ -> }
        var networkRefresh: suspend () -> Unit = { refreshes.incrementAndGet() }
        var awaitClose: suspend () -> Unit = {}
        override suspend fun awaitClosed() { awaitClose() }
        override suspend fun refreshNetworking() { networkRefresh() }
        override suspend fun start() { startAction() }
        override suspend fun refresh() { refreshes.incrementAndGet() }
        override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport {
            assertTrue(permits())
            val wire = PoolTestTransport().also { synchronized(transports) { transports += it } }
            prepareWire(mac, wire)
            return object : MobileRpcTransport by wire {
                override suspend fun connect() { dial(); wire.connect() }
            }
        }
        override fun close() { closes.incrementAndGet() }
    }
    private fun pairing(scope: NativeTeamScope = team) = PairingCodeParser.parse(PairingCodeParser.computer(mac, scope)).getOrThrow() as PairingCode.Iroh

    @Test fun savedMacUsesCurrentPeerButFreshPairingDoesNotFollowAChangedEndpoint() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val replacement = mac.copy(recordId = "new-record", endpointId = "cd".repeat(32))
        backend.state.value = ready().copy(computers = listOf(replacement))
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            assertTrue(backend.transports.isEmpty())
            runtime.connectSaved(pairing(), NativeComputerTarget.from(mac), team).use { client ->
                assertFalse(client.isClosed)
                assertEquals(listOf(replacement), backend.dialedComputers)
                assertEquals(runtime.state.value.connectionKeys[replacement.endpointId], runtime.state.value.connectionKey(pairing()))
            }
        }
    }

    @Test fun savedPeerRotationRetiresOldWireAndPreservesThisBuildsDirectPreference() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val target = NativeComputerTarget.from(mac)
        backend.connectionSettings.update(target, { true }) {
            NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT, listOf(NativeDirectAddress("192.168.1.7:58465")))
        }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            runtime.connectSaved(pairing(), target, team).use { first ->
                val oldKey = runtime.state.value.connectionKey(pairing())
                val replacement = mac.copy(recordId = "new-record", endpointId = "cd".repeat(32))
                backend.state.value = ready().copy(computers = listOf(replacement))
                withTimeout(2000) { runtime.state.first { it.connectionKey(pairing()) != oldKey } }
                withTimeout(2000) { while (!first.isClosed) delay(1) }
                runtime.connectSaved(pairing(), target, team).use { second ->
                    assertFalse(second.isClosed)
                    assertEquals(listOf(mac, replacement), backend.dialedComputers)
                    assertTrue(backend.intents.all { it.method == NativeMacConnectionMethod.DIRECT && it.addresses == listOf("192.168.1.7:58465") })
                }
            }
        }
    }

    @Test fun savedRouteCannotFollowSiblingAmbiguityOtherAccountOrConflictingIdentity() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            val target = NativeComputerTarget.from(mac)
            for (directory in listOf(emptyList(), listOf(mac.copy(buildTag = "nightly")),
                listOf(mac, mac.copy(endpointId = "cd".repeat(32), recordId = "duplicate")),
                listOf(mac.copy(deviceId = "other-device")))) {
                backend.state.value = ready().copy(computers = directory)
                assertTrue(runCatching { runtime.connectSaved(pairing(), target, team) }.isFailure)
            }
            backend.state.value = ready()
            assertTrue(runCatching { runtime.connectSaved(pairing(), target, team.copy(generation = 2)) }.isFailure)
            assertTrue(runCatching { runtime.connectSaved(pairing().copy(userId = "other"), target, team) }.isFailure)
            assertTrue(runCatching { runtime.connectSaved(pairing().copy(macDeviceId = "other"), target, team) }.isFailure)
            assertTrue(runCatching { runtime.connectSaved(pairing().copy(buildTag = "nightly"), target, team) }.isFailure)
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun discoveryKeyDoesNotAliasAReusedPeerToAnotherDeviceOrBuild() {
        val other = mac.copy(deviceId = "other-device")
        val duplicate = mac.copy(recordId = "duplicate", endpointId = "cd".repeat(32))
        val key = mapOf(mac.endpointId to "old", duplicate.endpointId to "new")
        assertNull(NativeComputersState(team, true, computers = listOf(other), connectionKeys = key).connectionKey(pairing()))
        assertNull(NativeComputersState(team, true, computers = listOf(mac, duplicate), connectionKeys = key).connectionKey(pairing()))
        assertNull(NativeComputersState(team, true, computers = listOf(mac.copy(buildTag = "nightly")), connectionKeys = key).connectionKey(pairing()))
        val legacy = pairing().copy(macDeviceId = null, buildTag = null)
        assertEquals("new", NativeComputersState(team, true, computers = listOf(duplicate), connectionKeys = key)
            .connectionKey(legacy, NativeComputerTarget.from(mac)))
    }

    @Test fun legacySavedRowWithoutBuildTagCanUseOnlyItsExactPeerAndDevice() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val legacy = pairing().copy(buildTag = null)
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 },
            audience = NativeMacBuildAudience.consumer).use { runtime ->
            runtime.connectSaved(legacy, null, team).use { assertFalse(it.isClosed) }
            assertEquals(listOf(mac), backend.dialedComputers)
            backend.state.value = ready().copy(computers = listOf(mac.copy(endpointId = "cd".repeat(32))))
            assertTrue(runCatching { runtime.connectSaved(legacy, null, team) }.isFailure)
            backend.state.value = ready().copy(computers = listOf(mac.copy(deviceId = "different-device")))
            assertTrue(runCatching { runtime.connectSaved(legacy, null, team) }.isFailure)
            backend.state.value = ready().copy(computers = listOf(mac.copy(buildTag = "dev")))
            assertTrue(runCatching { runtime.connectSaved(legacy, null, team) }.exceptionOrNull() is MacBuildNotSupported)
            assertEquals(1, backend.dialedComputers.size)
        }
    }

    @Test fun legacySavedRowCannotOmitItsVerifiedDeviceOrCapturedAccount() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val legacy = pairing().copy(buildTag = null)
            assertTrue(runCatching { runtime.connectSaved(legacy.copy(macDeviceId = null), null, team) }.isFailure)
            assertTrue(runCatching { runtime.connectSaved(legacy.copy(macDeviceId = ""), null, team) }.isFailure)
            assertTrue(runCatching { runtime.connectSaved(legacy, null, team.copy(login = "retired")) }.isFailure)
            assertTrue(runCatching { runtime.connectSaved(legacy.copy(userId = "another-user"), null, team) }.isFailure)
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun stalledMacDialDoesNotBlockSiblingAndDirectoryRevocationKeepsSiblingLive() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val sibling = mac.copy(endpointId = "cd".repeat(32), recordId = "sibling", deviceId = "other-mac")
        backend.state.value = ready().copy(computers = listOf(mac, sibling))
        val entered = CompletableDeferred<Unit>()
        backend.prepareWire = { selected, wire -> if (selected == mac) {
            wire.gate = CompletableDeferred(); entered.complete(Unit)
        } }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val pending = async { runCatching { runtime.connect(pairing()) } }
            try {
                withTimeout(2000) { entered.await() }
                val locator = PairingCodeParser.parse(PairingCodeParser.computer(sibling, team)).getOrThrow() as PairingCode.Iroh
                val active = withTimeout(2000) { runtime.connect(locator) }
                val shared = withTimeout(2000) { runtime.connect(locator) }
                assertEquals(2, synchronized(backend.transports) { backend.transports.size })
                assertFalse(pending.isCompleted)
                backend.state.value = ready().copy(computers = listOf(sibling))
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                active.close(); assertFalse(shared.isClosed)
                val wire = synchronized(backend.transports) { backend.transports.last() }
                val request = async { shared.workspaces() }
                wire.answer(withTimeout(2000) { wire.sent.receive() })
                assertEquals("mobile.workspace.list", withTimeout(2000) { request.await() }.getString("method"))
                shared.close()
            } finally { runtime.close(); pending.await() }
        }
    }

    @Test fun tailscaleRouteChangesWakeDisconnectedConsumersAndRetireOnlyTheirMac() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val sibling = mac.copy(endpointId = "cd".repeat(32), recordId = "sibling", buildTag = "debug")
        backend.state.value = ready().copy(computers = listOf(mac, sibling))
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val other = runtime.connect(PairingCodeParser.parse(PairingCodeParser.computer(sibling, team)).getOrThrow() as PairingCode.Iroh)
            backend.connectionSettings.update(NativeComputerTarget.from(mac), { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            val blockedKey = withTimeout(2000) { runtime.state.first { it.connectionKeys[mac.endpointId]?.contains("TAILSCALE") == true } }.connectionKeys[mac.endpointId]
            assertEquals(1, backend.intents.size)
            backend.grants = listOf(TailscaleSavedGrant(java.util.UUID.randomUUID().toString(), team.userId, team.teamId,
                "a".repeat(64), mac.deviceId, mac.buildTag, PairingCode.Route("100.99.1.2", 58465)))
            backend.routeRevisions.value++
            withTimeout(2000) { runtime.state.first { it.connectionKeys[mac.endpointId] != blockedKey } }
            val active = runtime.connect(pairing())
            assertEquals(NativeMacConnectionMethod.TAILSCALE, backend.intents.last().method)
            assertEquals(backend.grants, backend.intents.last().tailscale)
            val connectedKey = runtime.state.value.connectionKeys[mac.endpointId]
            backend.grants = emptyList(); backend.routeRevisions.value++
            assertFalse(backend.permissions[backend.permissions.size - 1]())
            withTimeout(2000) { active.disconnected.first() }
            assertNotEquals(connectedKey, runtime.state.value.connectionKeys[mac.endpointId])
            assertFalse(other.isClosed)
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            active.close(); other.close()
        }
    }

    @Test fun routingChangesRetireOnlyTargetAndDirectNeverFallsBackWithoutAddresses() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val sibling = mac.copy(endpointId = "cd".repeat(32), recordId = "sibling", buildTag = "debug")
        backend.state.value = ready().copy(computers = listOf(mac, sibling))
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val original = runtime.connect(pairing())
            val other = runtime.connect(PairingCodeParser.parse(PairingCodeParser.computer(sibling, team)).getOrThrow() as PairingCode.Iroh)
            val target = NativeComputerTarget.from(mac)
            backend.connectionSettings.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
            assertFalse(backend.permissions[0]())
            withTimeout(2000) { original.disconnected.first() }
            assertFalse(other.isClosed)
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            assertEquals(2, backend.intents.size)
            backend.connectionSettings.update(target, { true }) { it.copy(addresses = listOf(NativeDirectAddress("10.1.0.2:58470"))) }
            val direct = runtime.connect(pairing())
            assertEquals(NativeMacConnectionMethod.DIRECT, backend.intents.last().method)
            assertEquals(listOf("10.1.0.2:58470"), backend.intents.last().addresses)
            backend.connectionSettings.update(target, { true }) { it.copy(addresses = it.addresses.map { row -> row.copy(label = "Desk") }) }
            runtime.connect(pairing()).close()
            assertEquals(3, backend.intents.size)
            assertFalse(direct.isClosed); assertFalse(other.isClosed)
            direct.close(); other.close(); original.close()
        }
    }

    @Test fun rapidModeRoundTripCannotReuseOldAutomaticLease() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val old = runtime.connect(pairing()); val oldPermission = backend.permissions.single()
            val target = NativeComputerTarget.from(mac)
            backend.connectionSettings.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
            backend.connectionSettings.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.IROH) }
            assertFalse(oldPermission())
            withTimeout(2000) { old.disconnected.first() }
            runtime.connect(pairing()).close()
            assertEquals(2, backend.intents.size); assertNotEquals(backend.intents[0], backend.intents[1])
            old.close()
        }
    }

    @Test fun settingsChangedDuringHandshakeCannotPublishStaleCandidate() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        backend.dial = { entered.complete(Unit); release.await() }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val pending = async { runCatching { runtime.connect(pairing()) } }
            entered.await()
            backend.connectionSettings.update(NativeComputerTarget.from(mac), { true }) { it.copy(method = NativeMacConnectionMethod.DIRECT) }
            assertFalse(backend.permissions.single().invoke())
            release.complete(Unit)
            assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            // The route observer marks the wire closed before invoking transport.close().
            // Admission rejection may resume this coroutine between those two operations.
            withTimeout(2000) { while (backend.transports.single().closes.get() == 0) delay(1) }
        }
    }

    @Test fun corruptedSettingsRetireConnectionsAndRecoveryRequiresNewLease() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team)); val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val old = runtime.connect(pairing())
            backend.settingJson = "not json"; backend.connectionSettings.reload()
            assertFalse(backend.permissions.single().invoke())
            withTimeout(2000) { old.disconnected.first() }
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            backend.settingJson = "[]"; backend.connectionSettings.reload()
            runtime.connect(pairing()).close()
            assertEquals(2, backend.intents.size)
            old.close()
        }
    }

    @Test fun networkingRefreshUsesCurrentBackendAndRejectsWrongTeam() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        backend.state.value = ready().copy(mode = "http", directoryRevision = 4)
        backend.networkRefresh = {
            backend.refreshes.incrementAndGet()
            backend.state.value = ready().copy(mode = "websocket", directoryRevision = 5)
        }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            assertEquals(4L, runtime.networking(team).revision)
            assertEquals(0, backend.refreshes.get())
            val refreshed = runtime.networking(team, true)
            assertEquals(5L, refreshed.revision)
            assertEquals(NativeNetworkingSnapshot.Discovery.PUSH, refreshed.discovery)
            assertEquals(1, backend.refreshes.get())
            assertTrue(runCatching { runtime.networking(team.copy(teamId = "other"), true) }.isFailure)
            assertEquals(1, backend.refreshes.get())
        }
    }

    @Test fun networkingDoesNotReturnLateRefreshAfterAccountChanges() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        backend.networkRefresh = { entered.complete(Unit); release.await() }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            val read = async { runCatching { runtime.networking(team, true) } }
            withTimeout(2000) { entered.await() }
            teams.value = NativeAccountTeamsState()
            release.complete(Unit)
            assertTrue(withTimeout(2000) { read.await() }.isFailure)
        }
    }

    @Test fun privatePathOperationsRejectWrongTeamAndRetiredLogin() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            val path = NativePrivatePath(mac.deviceId, mac.buildTag, mac.name, listOf("10.0.0.2:58470"), true)
            assertEquals(listOf(path), runtime.privatePaths(team) { it.upsert(path) })
            var invoked = false
            assertTrue(runCatching {
                runtime.privatePaths(team.copy(teamId = "other")) { invoked = true; it.reset() }
            }.isFailure)
            assertFalse(invoked)
            teams.value = NativeAccountTeamsState()
            assertTrue(runCatching { runtime.privatePaths(team) { invoked = true; it.reset() } }.isFailure)
            assertFalse(invoked)
            assertTrue(backend.privatePaths.load().single().enabled)
        }
    }

    @Test fun oneScopedOwnerDiscoversAndSharesMacUntilLastConsumerReleasesIt() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            val a = runtime.connect(pairing())
            val b = runtime.connect(pairing())
            assertEquals(1, backend.transports.size)
            runtime.refresh()
            withTimeout(2000) { while (backend.refreshes.get() == 0) delay(1) }
            assertEquals(0, backend.closes.get())
            assertFalse(a.isClosed)
            a.close()
            assertEquals(0, backend.transports.single().closes.get())
            b.close()
            assertEquals(1, backend.transports.single().closes.get())
        }
    }

    @Test fun locatorScopeAndMacIdentityAreCheckedAgainstLiveDirectory() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            for (wrong in listOf(pairing().copy(userId = "other"), pairing().copy(teamId = "other"),
                pairing().copy(macDeviceId = "other"), pairing().copy(buildTag = "other"), pairing().copy(endpointId = "cc".repeat(32)))) {
                assertTrue(runCatching { runtime.connect(wrong) }.isFailure)
            }
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun directoryRevocationClosesExistingWireAndRejectsReconnect() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val client = runtime.connect(pairing())
            backend.state.value = ready().copy(computers = emptyList())
            withTimeout(2000) { client.disconnected.first() }
            assertTrue(client.isClosed)
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            client.close()
        }
    }

    @Test fun teamChangeClosesOldOwnerAndCannotReuseItsSavedLocator() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val created = Channel<Backend>(4)
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> Backend().also { created.send(it) } }, { 1000 }).use { runtime ->
            val firstBackend = withTimeout(2000) { created.receive() }
            val old = runtime.connect(pairing())
            val next = team.copy(teamId = "team-b", generation = 2)
            teams.value = NativeAccountTeamsState(scope = next)
            withTimeout(2000) { runtime.state.first { it.account == next && it.ready }; old.disconnected.first() }
            assertTrue(firstBackend.closes.get() >= 1)
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            runtime.connect(pairing(next)).close()
            old.close()
        }
    }

    @Test fun lateBackendCreationCannotRestoreSignedOutAccount() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            backend
        }, { 1000 }).use { runtime ->
            withTimeout(2000) { entered.await() }
            teams.value = NativeAccountTeamsState()
            release.complete(Unit)
            withTimeout(2000) { runtime.state.first { it.account == null }; while (backend.closes.get() == 0) delay(1) }
            assertFalse(runtime.state.value.ready)
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun expiryDeniesNewConnectionsWithoutWaitingForAnotherServerEvent() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        var time = 1000L
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { time }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            time = 2000
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun childDialTimeoutIsReconnectableWithoutCancellingTheCaller() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend().apply { dial = { withTimeout(30) { awaitCancellation() } } }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val failure = runCatching { runtime.connect(pairing()) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure?.cause is TimeoutCancellationException)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(1, backend.transports.single().closes.get())
            backend.dial = { }
            runtime.connect(pairing()).close()
            assertEquals(2, backend.transports.size)
        }
    }

    @Test fun cancellingCallerDuringDialStillPropagatesAndClosesCandidate() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val entered = CompletableDeferred<Unit>()
        val backend = Backend().apply { dial = { entered.complete(Unit); awaitCancellation() } }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val pending = async { runtime.connect(pairing()) }
            withTimeout(2000) { entered.await() }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(1, backend.transports.single().closes.get())
            assertEquals(0, backend.closes.get())
            backend.dial = { }
            runtime.connect(pairing()).close()
        }
    }

    @Test fun startupRevocationCannotBeHiddenByItsTransportCancellation() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val count = AtomicInteger()
        val backend = Backend().apply { startAction = {
            state.value = IrohV2ControlState(failure = "device_revoked")
            throw CancellationException("Account session changed")
        } }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ ->
            count.incrementAndGet(); backend
        }, { 1000 }, retryDelayMillis = 10).use { runtime ->
            val result = withTimeout(2000) { runtime.state.first { it.error != null } }
            assertEquals("This device’s cmux access was revoked", result.error)
            delay(100)
            assertEquals(1, count.get())
        }
    }

    @Test fun runningAuthenticationFailureStopsAutomaticReenrollment() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val count = AtomicInteger()
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ ->
            count.incrementAndGet(); backend
        }, { 1000 }, retryDelayMillis = 10).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            backend.state.value = IrohV2ControlState(failure = "unauthorized")
            withTimeout(2000) { runtime.state.first { it.error == "Sign in again to connect to your computers" } }
            delay(100)
            assertEquals(1, count.get())
            assertFalse(runtime.state.value.ready)
        }
    }
}
