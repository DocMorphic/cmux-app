package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NativeForegroundTailscaleRoutesTest {
    private class Fixture : AutoCloseable {
        val original = NativeTeamScope("fixture", "user", "original", 1)
        val selected = original.copy(teamId = "picker", generation = 2)
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
            "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original", "default"), original)
        val pairing = PairingCode.Tailscale(listOf(PairingCode.Route("100.64.0.8", 58465)), "user")
        val live = AtomicBoolean(true); val member = AtomicBoolean(true)
        val screenOwnsRoot = AtomicBoolean(true)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected))
        val storageLock = Any()
        var state = JSONObject().put("task_session", "fixture").put("refresh_token", "fixture-refresh")
        var beforeWrite: () -> Unit = {}; var afterWrite: () -> Unit = {}
        var token: suspend () -> String? = { "fixture-access" }
        var afterRequest: (String) -> Unit = {}
        var version = "0.65.0"; var hostDevice = "device"; var hostBuild = "default"
        var manualError: String? = null; var malformedTicket = false; var rejectWorkspace = false; var stall = false
        var borrowRoot = false
        val probes = CopyOnWriteArrayList<Wire>()
        val rootWire = Wire(this)
        val root = MobileRpcClient(rootWire, { "fixture-root" })
        val management = NativeForegroundComputerManagement(original, mac, { live.get() && !root.isClosed }, member::get)
        val access = NativeForegroundComputerDetailAccess(original, mac, root, management) { live.get() && screenOwnsRoot.get() }
        val gate = NativeMacCompatibilityGate({ it == teams.value.scope })
        val runtime = NativeIrohRuntime(teams, { it == teams.value.scope }, { "fixture" }, { _, _ ->
            object : IrohAccountBackend {
                override val state = MutableStateFlow(IrohV2ControlState())
                override suspend fun start() {}
                override suspend fun refresh() {}
                override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Route editor must not use Iroh dial")
                override fun close() {}
            }
        }, validateForegroundRoute = gate::verifyComputerRoute)
        fun read() = synchronized(storageLock) { JSONObject(state.toString()) }
        fun update(action: (JSONObject) -> Unit) = synchronized(storageLock) {
            beforeWrite(); val next = JSONObject(state.toString()); action(next); state = next; afterWrite()
        }
        val grants = TailscaleGrantStore(::read, ::update)
        val routes = NativeForegroundTailscaleRoutes(access, runtime, ::read, ::update,
            resolve = { route, allowed -> check(allowed()); route },
            dial = { _, allowed, bearer ->
                if (borrowRoot) root else MobileRpcClient(Wire(this, allowed).also { probes += it }, bearer)
            }, token = { token() }, forceToken = { token() })
        fun seed(owner: NativeTeamScope, build: String = "default", pairing: PairingCode.Tailscale = this.pairing): TailscaleSavedGrant {
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), owner.userId, owner.teamId,
                TailscaleGrantStore.source(pairing), "device", build, pairing.routes.single())
            grants.save(owner, grant) { true }; return grant
        }
        override fun close() { runtime.close(); root.close() }
    }
    private class Wire(val fixture: Fixture, val allowed: () -> Boolean = { true }) : MobileRpcTransport {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        val methods = CopyOnWriteArrayList<String>()
        var closed = false
        override suspend fun connect() { check(allowed()) }
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            check(allowed())
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); methods += method
            if (fixture.stall) return
            val result = when (method) {
                "mobile.host.status" -> JSONObject().put("mac_device_id", fixture.hostDevice)
                    .put("mac_instance_tag", fixture.hostBuild).put("mac_app_version", fixture.version)
                "mobile.attach_ticket.create" -> {
                    assertFalse(request.getJSONObject("auth").has("attach_token"))
                    if (fixture.malformedTicket) JSONObject() else JSONObject().put("ticket", JSONObject(
                        """{"version":1,"workspaceID":"","macDeviceID":"device","macUserID":"user",
                        "auth_token":"fixture-route-ticket","routes":[{"id":"fixture","kind":"tailscale",
                        "endpoint":{"type":"host_port","host":"100.64.0.8","port":58465}}]}"""))
                }
                "mobile.workspace.list" -> {
                    if (fixture.manualError == null) assertEquals("fixture-route-ticket", request.getJSONObject("auth").getString("attach_token"))
                    JSONObject()
                }
                else -> error("Unexpected probe method")
            }
            fixture.afterRequest(method)
            val failure = if (method == "mobile.attach_ticket.create") fixture.manualError
                else if (method == "mobile.workspace.list" && fixture.rejectWorkspace) "forbidden" else null
            val reply = JSONObject().put("id", request.getString("id")).put("ok", failure == null)
            if (failure != null) reply.put("error", JSONObject().put("code", failure).put("message", "Fixture error"))
            else reply.put("result", result)
            input.send(MobileFrameCodec.encode(reply.toString().toByteArray()))
        }
        override fun close() { closed = true; input.close() }
    }

    @Test fun routeProbeUsesOriginalOwnerTicketAndPolicyAndPreservesPickerAndSibling() = runBlocking<Unit> {
        Fixture().use { f ->
            f.root.connect(); val picker = f.seed(f.selected); val sibling = f.seed(f.original, "nightly",
                f.pairing.copy(routes = listOf(PairingCode.Route("100.64.0.9", 58465))))
            f.routes.pair(f.pairing)
            assertEquals(f.selected, f.teams.value.scope)
            assertEquals(picker, f.grants.find(f.selected, picker.source))
            assertEquals(sibling, f.grants.find(f.original, sibling.source))
            assertEquals(f.original.teamId, f.routes.routes().single().team)
            assertEquals(listOf("mobile.host.status", "mobile.attach_ticket.create", "mobile.workspace.list"), f.probes.single().methods)
            assertTrue(f.probes.single().closed); assertFalse(f.root.isClosed); assertTrue(f.rootWire.methods.isEmpty())
            assertTrue(f.gate.observations.value.isEmpty())
            assertTrue(runCatching { f.gate.admit(f.original, f.root, JSONObject().put("mac_device_id", "device")) }.isFailure)
        }
    }

    @Test fun wrongIdentityBuildTicketAccessOrVersionCannotSaveAndNeverRetiresRoot() = runBlocking<Unit> {
        for (mode in listOf("device", "build", "ticket", "access", "version")) Fixture().use { f ->
            f.root.connect()
            when (mode) {
                "device" -> f.hostDevice = "other"
                "build" -> f.hostBuild = "nightly"
                "ticket" -> f.malformedTicket = true
                "access" -> f.rejectWorkspace = true
                "version" -> f.version = "0.64.24"
            }
            assertTrue(mode, runCatching { f.routes.pair(f.pairing) }.isFailure)
            assertTrue(f.routes.routes().isEmpty()); assertTrue(f.probes.all { it.closed }); assertFalse(f.root.isClosed)
            if (mode in listOf("device", "build")) assertEquals(listOf("mobile.host.status"), f.probes.single().methods)
        }
    }

    @Test fun oldHostWithoutManualTicketStillNeedsAuthenticatedWorkspaceAccess() = runBlocking<Unit> {
        Fixture().use { f ->
            f.root.connect(); f.manualError = "method_not_found"
            f.routes.pair(f.pairing); assertEquals(1, f.routes.routes().size)
            assertFalse(f.root.isClosed); assertTrue(f.probes.single().closed)
        }
    }

    @Test fun permissionLossDuringTokenLookupAndCredentialTransactionNeverPromotesRoute() = runBlocking<Unit> {
        Fixture().use { f ->
            f.root.connect(); val started = CompletableDeferred<Unit>(); val token = CompletableDeferred<String>()
            f.token = { started.complete(Unit); token.await() }
            val result = async { runCatching { f.routes.pair(f.pairing) } }
            withTimeout(2000) { started.await() }; f.member.set(false); token.complete("fixture-access")
            assertTrue(withTimeout(2000) { result.await() }.isFailure)
            assertTrue(f.probes.single().methods.isEmpty()); assertTrue(f.probes.single().closed)
            assertNull(f.state.optJSONArray("tailscale_grants_v1"))
        }
        Fixture().use { f ->
            f.root.connect(); f.beforeWrite = { f.member.set(false) }
            assertTrue(runCatching { f.routes.pair(f.pairing) }.isFailure)
            assertNull(f.state.optJSONArray("tailscale_grants_v1")); assertTrue(f.probes.single().closed)
        }
    }

    @Test fun replacingActiveGrantCanFinishItsCommitAfterTheOldRootCloses() = runBlocking<Unit> {
        Fixture().use { f ->
            f.root.connect()
            val old = f.seed(f.original, pairing = checkNotNull(PairingCodeParser.parse(f.mac.code).getOrNull() as? PairingCode.Tailscale))
            val picker = f.seed(f.selected)
            f.afterWrite = { f.root.close() }
            f.routes.pair(f.pairing, old)
            assertTrue(f.root.isClosed); assertTrue(f.probes.single().closed)
            assertNull(f.grants.find(f.original, old.source))
            assertEquals(f.pairing.routes.single(), f.grants.find(f.original, TailscaleGrantStore.source(f.pairing))?.route)
            assertEquals(picker, f.grants.find(f.selected, picker.source))
        }
    }

    @Test fun removeAndLateGuardOnlyChangeOriginalOwnerRoutes() = runBlocking<Unit> {
        Fixture().use { f ->
            f.root.connect(); val original = f.seed(f.original); val picker = f.seed(f.selected)
            assertTrue(runCatching { f.routes.remove(picker) }.isFailure)
            f.beforeWrite = { f.member.set(false) }
            assertTrue(runCatching { f.routes.remove(original) }.isFailure)
            assertEquals(original, f.grants.find(f.original, original.source))
            assertEquals(picker, f.grants.find(f.selected, picker.source))
        }
        Fixture().use { f ->
            f.root.connect(); val original = f.seed(f.original); val picker = f.seed(f.selected)
            f.routes.remove(original)
            assertNull(f.grants.find(f.original, original.source)); assertEquals(picker, f.grants.find(f.selected, picker.source))
            assertFalse(f.root.isClosed)
        }
    }

    @Test fun changedScreenContextDuringCredentialCommitCannotUseStillLiveManagementRoot() = runBlocking<Unit> {
        Fixture().use { f ->
            f.root.connect(); f.beforeWrite = { f.screenOwnsRoot.set(false) }
            assertTrue(runCatching { f.routes.pair(f.pairing) }.isFailure)
            assertNull(f.state.optJSONArray("tailscale_grants_v1"))
            assertTrue(f.probes.single().closed); assertFalse(f.root.isClosed)
            assertTrue(f.management.available()); assertFalse(f.access.current())
        }
    }

    @Test fun lostMembershipCancelsBlockedProbeAndBorrowingRootIsRejected() = runBlocking<Unit> {
        Fixture().use { f ->
            f.root.connect(); f.stall = true
            val pending = async { runCatching { f.routes.pair(f.pairing) } }
            withTimeout(2000) { while (f.probes.firstOrNull()?.methods?.isEmpty() != false) delay(5) }
            f.member.set(false)
            assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            assertTrue(f.probes.single().closed); assertFalse(f.root.isClosed)
        }
        Fixture().use { f ->
            f.root.connect(); f.borrowRoot = true
            assertTrue(runCatching { f.routes.pair(f.pairing) }.isFailure)
            assertFalse(f.root.isClosed); assertTrue(f.rootWire.methods.isEmpty())
        }
    }

    @Test fun commitFenceAllowsOnlyCompletionAndCannotReenableNetworkWork() {
        var root = true; var member = true
        NativeForegroundRouteProbe({ root }, { member }).use { permit ->
            assertTrue(permit.canProbe()); permit.beginCommit(); root = false
            assertTrue(permit.current()); assertFalse(permit.canProbe())
            assertTrue(runCatching { permit.beginCommit() }.isFailure)
            member = false; assertFalse(permit.current())
            root = true; member = true; assertFalse(permit.canProbe())
            permit.close(); assertFalse(permit.current())
        }
    }
}
