package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshCmuxProviderTest {
    private class Pipe : SshExecPipe {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = input.receiveAsFlow()
        val sent = mutableListOf<JSONObject>()
        var held: JSONObject? = null
        var holdCommand: String? = null
        var tree = JSONObject().put("generation", "boot-a").put("registry_id", "registry-a").put("workspace_revision", 0).put("workspaces", JSONArray())
        var uniqueResourceSession = false
        var beforeResourceReply: (String) -> Unit = {}
        var beforeListReply: () -> Unit = {}
        var supportsIdle = false
        var rejectIdle = false
        var layoutMutation: (JSONObject) -> Unit = {}
        var closed = false
        fun event(name: String) = feed(JSONObject().put("event", name))
        fun feed(value: JSONObject) { check(input.trySend((value.toString()+"\n").toByteArray()).isSuccess) }
        fun reply(request: JSONObject, data: Any = JSONObject(), resource: Boolean = false) = feed(JSONObject().put("id", request.getString("id"))
            .put("ok", true).put(if (resource) "result" else "data", data))
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(bytes.toString(Charsets.UTF_8)); sent += request
            val command = request.optString("cmd")
            if (command == "set-terminal-idle-policy" && rejectIdle) {
                feed(JSONObject().put("id", request.getString("id")).put("ok", false).put("error", "policy unavailable")); return
            }
            if (command in listOf("new-screen", "new-tab", "split")) layoutMutation(request)
            if (holdCommand == command) { check(held == null); held = request; return }
            if (request.has("operation")) {
                val result = when (request.getString("operation")) {
                    "machine.list" -> JSONArray().put(JSONObject().put("id", "machine-a"))
                    "session.list" -> JSONArray().put(JSONObject().put("id", "session-a").put("name", "fixture"))
                        .also { if (!uniqueResourceSession) it.put(JSONObject().put("id", "session-b").put("name", "fixture")) }
                    "terminal.close" -> JSONObject()
                    else -> error("Unexpected resource mutation")
                }
                beforeResourceReply(request.getString("operation"))
                reply(request, result, true); return
            }
            val data = when (command) {
                "identify" -> JSONObject().put("app", "cmux-tui").put("version", "fixture").put("protocol", 12)
                    .put("session", "fixture").put("pid", 1).put("generation", "boot-a")
                    .put("capabilities", JSONArray(listOf("workspace-registry-v1", "attach-initial-size") + if (supportsIdle) listOf("terminal-idle-close-v1") else emptyList<String>()))
                "list-workspaces" -> tree
                "create-terminal" -> JSONObject().put("surface", 1)
                "new-screen", "new-tab", "split" -> JSONObject().put("surface", 5)
                else -> JSONObject()
            }
            if (command == "list-workspaces") beforeListReply()
            reply(request, data)
        }
        override fun close() { closed = true; input.close() }
        fun count(command: String) = sent.count { it.optString("cmd") == command }
    }
    private suspend fun TestScope.open(pipe: Pipe, owner: CoroutineScope = backgroundScope, idle: () -> Long? = { 86400L }): SshCmuxProvider {
        val control = SshCmuxControl(pipe, owner)
        val result = async { control.handshake("fixture"); SshCmuxProvider.open(control, owner, idle) { true } }
        runCurrent(); return result.await()
    }
    @Test fun topologyRefreshCoalescesAndOverflowResubscribesBeforeRelisting() = runTest {
        val pipe = Pipe(); val provider = open(pipe)
        pipe.holdCommand = "list-workspaces"; pipe.event("tree-changed"); runCurrent()
        val first = checkNotNull(pipe.held)
        repeat(5) { pipe.event("tree-changed") }; pipe.event("overflow"); runCurrent()
        pipe.tree.put("workspace_revision", 2); pipe.holdCommand = null; pipe.held = null
        pipe.reply(first, JSONObject(pipe.tree.toString()).put("workspace_revision", 1)); runCurrent()
        assertEquals(2L, provider.state.value.tree?.revision)
        assertEquals(3, pipe.count("list-workspaces")); assertEquals(2, pipe.count("subscribe"))
        assertFalse(provider.state.value.loading); provider.close()
    }
    @Test fun canceledScreenWaitDoesNotCancelOrReplaySubmittedCreation() = runTest {
        val pipe = Pipe(); val provider = open(pipe)
        pipe.holdCommand = "create-workspace"
        val create = async { provider.createWorkspace("test", listOf("/bin/cat")) }; runCurrent()
        val sent = checkNotNull(pipe.held); create.cancelAndJoin()
        pipe.holdCommand = null; pipe.held = null; pipe.reply(sent); runCurrent()
        assertEquals(1, pipe.count("create-workspace")); assertEquals(1, pipe.count("create-terminal"))
        val terminal = pipe.sent.single { it.optString("cmd") == "create-terminal" }
        assertEquals(sent.getString("key"), terminal.getString("key"))
        assertEquals("boot-a", terminal.getString("expected_generation"))
        assertTrue(Regex("[0-9a-f]{32}").matches(terminal.getString("terminal_id")))
        assertFalse(provider.state.value.ended); provider.close()
    }
    @Test fun ownerRetirementClosesRelayAndMalformedRefreshRetainsLastGoodTree() = runTest {
        val pipe = Pipe(); val owner = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val provider = open(pipe, owner); val good = provider.state.value.tree
        pipe.tree.put("workspaces", "bad"); provider.refresh(); runCurrent()
        assertEquals(good, provider.state.value.tree); assertNotNull(provider.state.value.error)
        owner.cancel(); runCurrent()
        assertTrue(pipe.closed); assertTrue(provider.state.value.ended)
        assertTrue(runCatching { provider.createWorkspace() }.isFailure)
    }
    @Test fun duplicateResourceSessionNamesCannotChooseAnArbitraryDestructiveTarget() = runTest {
        val pipe = Pipe()
        pipe.tree.put("workspaces", JSONArray("""[{"id":1,"key":"workspace-a","name":"one","screens":[{"id":2,"panes":[{"id":3,"tabs":[{"surface":4,"kind":"pty","terminal_resource_id":"term_a"}]}]}]}]"""))
        val provider = open(pipe)
        val ending = async { runCatching { provider.endWorkspace(provider.state.value.tree!!.workspaces.single()) } }; runCurrent()
        assertTrue(ending.await().isFailure)
        assertTrue(pipe.sent.none { it.optString("operation") == "terminal.close" })
        assertEquals(0, pipe.count("close-workspace")); provider.close()
    }
    @Test fun revokedCloseWaitsForProviderQueueThenSendsNothing() = runTest {
        val pipe = Pipe(); pipe.tree.put("workspaces", paneTree()); val provider = open(pipe)
        val captured = provider.state.value.tree!!.workspaces.single()
        pipe.holdCommand = "list-workspaces"; provider.refresh(); runCurrent()
        val held = checkNotNull(pipe.held); var allowed = true
        val close = async { runCatching { provider.endWorkspace(captured) { allowed } } }; runCurrent()
        allowed = false; pipe.holdCommand = null; pipe.held = null; pipe.reply(held, pipe.tree); runCurrent()
        assertTrue(close.await().isFailure)
        assertEquals(0, pipe.count("close-workspace")); assertTrue(pipe.sent.none { it.optString("operation") == "terminal.close" })
        provider.close()
    }
    @Test fun closeRechecksAfterResourceLookupButCompletesOnceDestructiveRequestWasSent() = runTest {
        val pipe = Pipe().apply { uniqueResourceSession = true; tree.put("workspaces", paneTree()) }
        val provider = open(pipe); val workspace = provider.state.value.tree!!.workspaces.single(); var allowed = true
        pipe.beforeResourceReply = { if (it == "session.list") allowed = false }
        val rejected = async { runCatching { provider.endWorkspace(workspace) { allowed } } }; runCurrent()
        assertTrue(rejected.await().isFailure); assertTrue(pipe.sent.none { it.optString("operation") == "terminal.close" })
        allowed = true
        pipe.beforeResourceReply = { if (it == "terminal.close") allowed = false }
        val sent = async { provider.endWorkspace(workspace) { allowed } }; runCurrent(); sent.await()
        assertEquals(1, pipe.sent.count { it.optString("operation") == "terminal.close" })
        assertEquals(1, pipe.count("close-workspace")); provider.close()
    }
    @Test fun emptyWorkspaceCloseRechecksAfterItsFinalRead() = runTest {
        val pipe = Pipe().apply {
            uniqueResourceSession = true
            tree.put("workspaces", JSONArray("""[{"id":1,"key":"empty","name":"Empty","screens":[]}]"""))
        }
        val provider = open(pipe); val workspace = provider.state.value.tree!!.workspaces.single()
        var allowed = true; var reads = 0
        pipe.beforeListReply = { if (++reads == 2) allowed = false }
        val close = async { runCatching { provider.endWorkspace(workspace) { allowed } } }; runCurrent()
        assertTrue(close.await().isFailure); assertEquals(0, pipe.count("close-workspace")); provider.close()
    }
    @Test fun idlePolicyUsesCurrentHostChoiceAndPolicyRejectionDoesNotReplayCreation() = runTest {
        val pipe = Pipe().apply { supportsIdle = true }
        var seconds: Long? = 3600
        val provider = open(pipe, idle = { seconds })
        val first = async { provider.createWorkspace() }; runCurrent(); first.await()
        assertEquals(3600L, pipe.sent.single { it.optString("cmd") == "set-terminal-idle-policy" }.getLong("idle_close_seconds"))
        seconds = null; pipe.rejectIdle = true
        val second = async { provider.createWorkspace() }; runCurrent(); second.await()
        assertFalse(pipe.sent.last { it.optString("cmd") == "set-terminal-idle-policy" }.has("idle_close_seconds"))
        assertEquals(2, pipe.count("create-workspace")); assertEquals(2, pipe.count("create-terminal"))
        assertFalse(provider.state.value.ended); provider.close()
    }
    private fun paneTree() = JSONArray("""[{"id":1,"key":"workspace-a","resource_id":"ws_a","name":"one","screens":[{"id":2,"panes":[{"id":3,"resource_id":"pane_a","tabs":[{"surface":4,"kind":"pty","tab_resource_id":"tab_a","terminal_resource_id":"term_a"}]}]}]}]""")
    @Test fun layoutCreationResolvesExactPaneAndSelectsReturnedTerminalWithoutFakeMutationGuards() = runTest {
        val pipe = Pipe().apply { tree.put("workspaces", paneTree()) }
        val provider = open(pipe); val before = provider.state.value.tree!!.workspaces.single(); val pane = before.screens.single().panes.single()
        pipe.layoutMutation = { request ->
            assertEquals(3, request.getInt("pane")); assertEquals("right", request.getString("dir"))
            assertFalse(request.has("expected_generation")); assertFalse(request.has("mutation_id"))
            pipe.tree.getJSONArray("workspaces").getJSONObject(0).getJSONArray("screens").getJSONObject(0)
                .getJSONArray("panes").put(JSONObject("""{"id":6,"resource_id":"pane_b","tabs":[{"surface":5,"kind":"pty","tab_resource_id":"tab_b","terminal_resource_id":"term_b"}]}"""))
        }
        val create = async { provider.split(before, pane, true) }; runCurrent()
        val selected = create.await(); assertEquals("term_b", selected.terminalResource); assertEquals(5, selected.surface)
        assertEquals("workspace-a", selected.workspaceKey); assertEquals(1, pipe.count("split"))
        provider.close()
    }
    @Test fun paneMovedOrReplacedBeforeCreationIsRefusedBeforeSendingMutation() = runTest {
        val pipe = Pipe().apply { tree.put("workspaces", paneTree()) }
        val provider = open(pipe); val before = provider.state.value.tree!!.workspaces.single(); val pane = before.screens.single().panes.single()
        pipe.tree.getJSONArray("workspaces").getJSONObject(0).getJSONArray("screens").getJSONObject(0)
            .getJSONArray("panes").getJSONObject(0).put("resource_id", "replacement")
        val create = async { runCatching { provider.newTab(before, pane) } }; runCurrent()
        assertTrue(create.await().isFailure); assertEquals(0, pipe.count("new-tab")); provider.close()
    }
    @Test fun colorsRejectEscapeInjectionAndOnlyEmitBoundedPaletteIndices() {
        val colors = JSONObject().put("fg", "#aAbBcC").put("bg", "#000000\u001b]52;c;secret\u0007")
            .put("cursor_style", "bar").put("cursor_blink", false)
            .put("palette", JSONObject().put("1", "#112233").put("256", "#445566").put("2", "bad"))
        val text = SshCmuxColors.replay(colors).toString(Charsets.UTF_8)
        assertTrue(text.contains("\u001b]10;#aAbBcC\u0007"))
        assertTrue(text.contains("\u001b]4;1;#112233\u0007")); assertTrue(text.endsWith("\u001b[6 q"))
        assertFalse(text.contains("secret")); assertFalse(text.contains("445566")); assertFalse(text.contains("bad"))
    }
}
