package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSavedTicketSessionTest {
    @Test fun initialTicketFactoryClosesAcquiredHandleWhenScopeOrRouteFailsFinalAdmission() = runBlocking<Unit> {
        for (mode in listOf("scope", "route", "race")) {
            val wire = PoolTestTransport()
            val client = MobileRpcClient(wire, { "fixture-account" }); client.connect()
            var selected = mode != "scope"
            val failure = runCatching { attachAdmittedTicket(client, MobileAttachTicketContext("", null, "fixture-ticket", null),
                { selected }, { true }) {
                if (mode == "route") error("Fixture route replaced")
                if (mode == "race") selected = false
            } }
            assertTrue(failure.isFailure); assertTrue(client.isClosed)
            assertEquals(1, wire.closes.get()); assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    private class Fixture {
        val owner = NativeTeamScope("login", "user", "team", 1)
        val peer = "a".repeat(64)
        val original = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
            PairingCodeParser.computer(IrohV2Computer("record", peer, "device", "default", "Mac", emptyList()), owner),
            "device", "Mac", "default"), owner)
        val state = JSONObject().put("task_session", owner.login).put("refresh_token", "fixture-refresh")
            .put("pairings", JSONArray().put(NativePairingRecords.encode(original)))
        val ticket = MobileAttachTicketCodec.decodeJson(JSONObject().put("version", 1).put("workspaceID", "workspace")
            .put("terminalID", "terminal").put("macDeviceID", "device").put("macUserID", "user")
            .put("auth_token", "fixture-ticket").put("expiresAt", "2030-01-01T00:00:00Z")
            .put("routes", JSONArray().put(JSONObject().put("id", "iroh").put("kind", "iroh")
                .put("endpoint", JSONObject().put("type", "peer").put("id", peer)))).toString()).getOrThrow()
        val mac = NativeAttachTicketStore.install(state, owner, original, ticket, null)
        val context = checkNotNull(NativeAttachTicketStore.read(state, owner, mac))
        var selected = true
        var held: MobileRpcClient? = null
        var routeUnchanged = true
        var afterAuthorization: () -> Unit = {}
        val session = NativeSavedTicketSession(mac, context, { selected }, { held === it }) {
            check(routeUnchanged)
            checkNotNull(NativeAttachTicketStore.read(state, owner, mac))
            afterAuthorization()
        }
    }

    @Test fun boundForegroundKeepsOriginalTicketAndRpcButCannotAdmitAnotherHandle() = runBlocking<Unit> {
        val f = Fixture(); val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture-account" }).use { root ->
            root.connect()
            root.withAttachTicket(f.context, f.session.admission::requireCurrent).use { client ->
                f.session.bind(client); f.held = client; f.selected = false
                val pending = async { client.workspaces() }
                val request = withTimeout(2000) { wire.sent.receive() }
                assertEquals("fixture-ticket", request.getJSONObject("auth").getString("attach_token"))
                assertEquals("fixture-account", request.getJSONObject("auth").getString("stack_access_token"))
                wire.answer(request); withTimeout(2000) { pending.await() }
                val another = NativeSavedTicketSession(f.mac, f.context, { f.selected }, { true }) {}
                assertTrue(runCatching { another.bind(client) }.isFailure)
                assertTrue(runCatching { another.admission.requireCurrent() }.isFailure)
                assertTrue(runCatching { f.session.bind(root) }.isFailure)
                f.held = null
                assertTrue(runCatching { client.workspaces() }.isFailure)
                assertTrue(wire.sent.tryReceive().isFailure)
            }
        }
    }

    @Test fun retainedTicketStillRejectsHiddenRemovedReplacedAndChangedOwnerOrRoute() = runBlocking<Unit> {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { it.state.put("pairings", JSONArray()) },
            { NativeComputerVisibility.setVisible(it.state, it.owner.login, it.mac, false) { true } },
            { it.state.put("task_session", "replacement-login") },
            { it.state.put("refresh_token", "") },
            { NativeAttachTicketStore.install(it.state, it.owner, it.mac, it.ticket, null) },
            { it.routeUnchanged = false }
        )
        for (mutate in mutations) {
            val f = Fixture(); val wire = PoolTestTransport()
            MobileRpcClient(wire, { "fixture-account" }).use { root ->
                root.connect()
                root.withAttachTicket(f.context, f.session.admission::requireCurrent).use { client ->
                    f.session.bind(client); f.held = client; f.selected = false
                    mutate(f)
                    assertTrue(runCatching { client.workspaces() }.isFailure)
                    assertTrue(wire.sent.tryReceive().isFailure)
                }
            }
        }
    }

    @Test fun scopeChangeDuringAuthorizationCannotBindOrSendEvenIfItWasSelectedBeforeRead() = runBlocking<Unit> {
        val f = Fixture(); val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture-account" }).use { root ->
            root.connect()
            f.afterAuthorization = { f.selected = false }
            assertTrue(runCatching { f.session.bind(root) }.isFailure)
            assertTrue(runCatching { f.session.admission.requireCurrent() }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun renameAndExpiredSelectionContextDoNotGainFreshMutationOrConnectionAuthority() = runBlocking<Unit> {
        val f = Fixture()
        f.state.put("pairings", JSONArray().put(NativePairingRecords.encode(f.mac.copy(name = "Renamed"))))
        f.session.admission.requireCurrent()
        assertNull(f.context.tokenFor("mobile.workspace.list", JSONObject(), 1_893_456_000_000))
        assertFalse(f.context.allowsMacWorkspaceMutations(false, 1_893_456_000_000))
        val wire = PoolTestTransport()
        val client = MobileRpcClient(wire, { "fixture-account" }); client.connect(); f.session.bind(client)
        client.close()
        assertTrue(runCatching { f.session.admission.requireCurrent() }.isFailure)
    }
}
