package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeForegroundWorkspaceAccessTest {
    private val mac = NativeCredentialStore.PairedMac("fixture-route", "fixture-mac", "Fixture Mac", "default", "user", "team", "fixture-origin")
    private val supported = setOf(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY, "workspace.actions.v1", "workspace.read_state.v1",
        "workspace.close.v1", "workspace.move.v1", "workspace.group_create.v1", "workspace.group_actions.v1",
        "workspace.create_in_group.v1", "workspace.task_create.v1")
    private fun listing(window: String = "current-window", pinned: Boolean = false) = JSONObject()
        .put("workspaces", JSONArray().put(JSONObject().put("id", "workspace").put("title", "Fixture")
            .put("window_id", window).put("terminals", JSONArray())))
        .put("groups", JSONArray().put(JSONObject().put("id", "group").put("name", "Group").put("is_pinned", pinned)))

    private inner class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val frames = CopyOnWriteArrayList<JSONObject>()
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        var live = true
        var capabilities = supported
        var fresh = listing()
        var connects = 0
        var reply: suspend (JSONObject) -> JSONObject = { req ->
            when (req.getString("method")) {
                "mobile.host.status" -> JSONObject().put("capabilities", JSONArray(capabilities.toList()))
                "mobile.workspace.list" -> fresh
                else -> JSONObject().put("workspaces", fresh.getJSONArray("workspaces"))
            }
        }
        val wire = object : MobileRpcTransport {
            override suspend fun connect() { connects++ }
            override suspend fun read() = incoming.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                val request = JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8))
                frames += request
                val response = JSONObject().put("id", request.getString("id"))
                try { response.put("ok", true).put("result", reply(request)) }
                catch (failure: MobileRpcException) { response.put("ok", false).put("error", JSONObject()
                    .put("code", failure.code).put("message", "Fixture rejection")) }
                incoming.send(MobileFrameCodec.encode(response.toString().toByteArray()))
            }
            override fun close() { incoming.close() }
        }
        val client = MobileRpcClient(wire, { "fixture" })
        val snapshots = NativeWorkspaceSnapshots { "login" }
        val access = NativeForegroundWorkspaceAccess(scope, mac, client, snapshots, { capabilities }, { live })
        suspend fun start() { client.connect(); client.hostStatus(); assertTrue(snapshots.read(mac, client).accept()); frames.clear() }
        fun methods() = frames.map { it.getString("method") }
        override fun close() { client.close(); scope.cancel() }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        Fixture().use { it.start(); withTimeout(5_000) { block(it) } }
    }

    @Test fun actionsResolveCurrentWindowAndReuseTheAdmittedClient() = runBlocking<Unit> {
        fixture { f ->
            f.fresh = listing("new-window")
            assertTrue(f.snapshots.read(mac, f.client).accept()); f.frames.clear()
            f.access.workspaceAction("workspace", "rename", "New title")
            val frame = f.frames.first()
            assertEquals("workspace.action", frame.getString("method"))
            assertEquals("new-window", frame.getJSONObject("params").getString("window_id"))
            assertEquals("New title", frame.getJSONObject("params").getString("title"))
            assertEquals(listOf("workspace.action", "mobile.workspace.list"), f.methods())
            assertEquals(1, f.connects); assertNotNull(f.snapshots.latest(mac))
        }
    }

    @Test fun terminalBrowserAndGroupedTaskUseTheSameOwnerAndRefreshAfterEachWrite() = runBlocking<Unit> {
        fixture { f ->
            f.access.workspaceAction("workspace", "terminal.create")
            f.access.workspaceAction("workspace", "browser.create")
            f.access.createGroup()
            f.access.createWorkspace("group")
            f.access.createTask(JSONObject().put("group_id", "group").put("task", "Fixture task")) { true }
            assertEquals(listOf("terminal.create", "mobile.workspace.list", "mobile.browser.create", "mobile.workspace.list",
                "workspace.group.create", "mobile.workspace.list", "workspace.create", "mobile.workspace.list",
                "workspace.create", "mobile.workspace.list"), f.methods())
            assertEquals(1, f.connects)
            val creates = f.frames.filter { it.getString("method") == "workspace.create" }
            assertTrue(creates.all { it.getJSONObject("params").getString("group_id") == "group" })
        }
    }

    @Test fun absentCapabilitiesRemovedDestinationsAndPinnedGroupsRejectBeforeMutation() = runBlocking<Unit> {
        fixture { f ->
            f.capabilities = setOf(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY)
            assertTrue(runCatching { f.access.workspaceAction("workspace", "close") }.isFailure)
            assertTrue(runCatching { f.access.createGroup() }.isFailure)
            assertTrue(runCatching { f.access.createTask(JSONObject()) { true } }.isFailure)
            f.capabilities = supported
            assertTrue(runCatching { f.access.createWorkspace("removed") }.isFailure)
            assertTrue(runCatching { f.access.workspaceAction("removed", "rename", "Title") }.isFailure)
            f.fresh = listing(pinned = true); assertTrue(f.snapshots.read(mac, f.client).accept())
            assertTrue(runCatching { f.access.groupAction("group", "ungroup") }.isFailure)
            assertTrue(f.methods().all { it == "mobile.workspace.list" })
        }
    }

    @Test fun lostAuthorityRejectsAnInFlightResultAndTheQueuedMutationWithoutAnotherWrite() = runBlocking<Unit> {
        fixture { f ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.reply = { request ->
                if (request.getString("method") == "workspace.action") { entered.complete(Unit); release.await() }
                JSONObject()
            }
            val first = async { runCatching { f.access.workspaceAction("workspace", "pin") } }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.access.workspaceAction("workspace", "unpin") } }
            f.live = false; release.complete(Unit)
            assertTrue(first.await().isFailure); assertTrue(queued.await().isFailure)
            assertEquals(listOf("workspace.action"), f.methods()); assertEquals(1, f.connects)
        }
    }

    @Test fun anUnconfirmedWriteReconcilesTheListWithoutReplayingTheMutation() = runBlocking<Unit> {
        fixture { f ->
            f.reply = { request ->
                if (request.getString("method") == "workspace.action") throw MobileRpcException("internal_error", "Fixture", true)
                f.fresh
            }
            assertTrue(runCatching { f.access.workspaceAction("workspace", "pin") }.isFailure)
            assertEquals(listOf("workspace.action", "mobile.workspace.list"), f.methods())
            assertNotNull(f.snapshots.latest(mac)); assertFalse(f.client.isClosed)
        }
    }

    @Test fun authorizationRejectionRetiresTheOwnerAndPreventsAnotherMutation() = runBlocking<Unit> {
        fixture { f ->
            f.reply = { throw MobileRpcException("unauthorized", "Fixture", true) }
            assertTrue(runCatching { f.access.workspaceAction("workspace", "pin") }.isFailure)
            assertTrue(f.client.isClosed)
            assertTrue(runCatching { f.access.workspaceAction("workspace", "unpin") }.isFailure)
            assertEquals(listOf("workspace.action"), f.methods())
        }
    }

    @Test fun rejectionDuringPostWriteRefreshCannotReturnSuccess() = runBlocking<Unit> {
        fixture { f ->
            f.reply = { request ->
                if (request.getString("method") == "mobile.workspace.list") throw MobileRpcException("unauthorized", "Fixture", true)
                JSONObject()
            }
            assertTrue(runCatching { f.access.workspaceAction("workspace", "pin") }.isFailure)
            assertTrue(f.client.isClosed)
            assertEquals(listOf("workspace.action", "mobile.workspace.list"), f.methods())
        }
    }

    @Test fun expiredUiActionCannotWriteOrRefreshAndLeavesTheHealthyOwnerAvailable() = runBlocking<Unit> {
        fixture { f ->
            assertTrue(runCatching { f.access.workspaceAction("workspace", "pin", canSend = { false }) }.isFailure)
            assertTrue(f.frames.isEmpty()); assertFalse(f.client.isClosed); assertNotNull(f.snapshots.latest(mac))
        }
    }
}
