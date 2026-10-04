package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedSidebarSshCloseTest {
    private val a = SshHostRecord(name = "Host A", endpoint = SshEndpoint("a.test", 22, "user"))
    private val b = a.copy(id = UUID.randomUUID(), name = "Host B")
    private fun rows(host: SshHostRecord) = sshTmuxFeedRows(host, listOf(SshTmuxWorkspace(42, 2, 100L, "Session ${host.name}", emptyList())))
    private fun input(rows: List<SshFeedRow>) = NativeSidebarInput(emptyList(), rows,
        rows.map { NativeSortComputer(workspaceSshFilterId(it.host.id), it.host.name) }.distinct(), NativeWorkspaceSortState(),
        sshAvailability = rows.associate { it.host.id to NativeFeedAvailability.CONNECTED })

    @Test fun issuedCloseUsesExactSshHostAndNeverMacMutationOrNavigation() = runTest {
        var value: NativeSidebarInput? = input(rows(a) + rows(b)); var target: SshFeedRow? = null
        var permitted: (() -> Boolean)? = null; var online = true; var caller = true
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, { fail("No navigation") },
            mutateWorkspace = { _, _, _ -> fail("No Mac RPC") }, canCloseSsh = { online }, closeSsh = { row, gate -> target = row; permitted = gate })
        val snapshot = host.read(RoutedSidebarQuery())!!; val row = snapshot.rows.single { it.computer == "Host B" }
        assertEquals(setOf(RoutedSidebarMutationKind.CLOSE), row.mutations); assertEquals(SshWorkspaceKind.TMUX, row.sshKind)
        val exchange = RoutedSidebarExchange(); val page = exchange.begin(snapshot)
        val wire = RoutedSidebarWire.page(page); assertEquals(page, RoutedSidebarWire.page(wire)); assertFalse(wire.contains("a.test"))
        assertTrue(exchange.permitsMutation(RoutedSidebarMutation(row.key, RoutedSidebarMutationKind.CLOSE)))
        assertFalse(exchange.permitsMutation(RoutedSidebarMutation(row.key, RoutedSidebarMutationKind.RENAME, "name")))
        host.mutate(RoutedSidebarMutation(row.key, RoutedSidebarMutationKind.CLOSE)) { caller }
        assertEquals(b.id, target!!.host.id); assertTrue(permitted!!())
        caller = false; assertFalse(permitted!!()); caller = true; online = false; assertFalse(permitted!!())
        assertTrue(host.read(RoutedSidebarQuery())!!.rows.all { it.mutations.isEmpty() })
        online = true; value = null; assertFalse(permitted!!())
    }
    @Test fun endpointKeysGenerationAndMembershipChangesRetireCapturedClose() = runTest {
        val tree = SshCmuxInventory.parse(JSONObject("""{"generation":"one","registry_id":"r","workspaces":[{"id":1,"key":"w","name":"Project","screens":[{"id":2,"panes":[{"id":3,"tabs":[{"surface":4,"kind":"pty","terminal_resource_id":"term-a"}]}]}]}]}"""))
        val original = sshCmuxFeedRows(a, "session", tree).single()
        var value = input(listOf(original)); var writes = 0
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            canCloseSsh = { true }, closeSsh = { _, gate -> check(gate()); writes++ })
        val key = host.read(RoutedSidebarQuery())!!.rows.single().key
        val changed = listOf(original.copy(host = a.copy(endpoint = a.endpoint.copy(port = 2222))),
            original.copy(host = a.copy(keyId = UUID.randomUUID())), original.copy(host = a.copy(jumpHostId = UUID.randomUUID())),
            original.copy(generation = "two"), original.copy(registry = "r2"),
            sshCmuxFeedRows(a, "session", tree.copy(workspaces = emptyList())).firstOrNull())
        for (replacement in changed) {
            value = input(listOfNotNull(replacement))
            assertTrue(runCatching { host.mutate(RoutedSidebarMutation(key, RoutedSidebarMutationKind.CLOSE)) { true } }.isFailure)
        }
        val changedTree = SshCmuxInventory.parse(JSONObject(treeJsonWithNewMember()))
        value = input(sshCmuxFeedRows(a, "session", changedTree))
        assertNotEquals(key, host.read(RoutedSidebarQuery())!!.rows.single().key)
        assertTrue(runCatching { host.mutate(RoutedSidebarMutation(key, RoutedSidebarMutationKind.CLOSE)) { true } }.isFailure)
        assertEquals(0, writes)
    }
    private fun treeJsonWithNewMember() = """{"generation":"one","registry_id":"r","workspaces":[{"id":1,"key":"w","name":"Project","screens":[{"id":2,"panes":[{"id":3,"tabs":[{"surface":4,"kind":"pty","terminal_resource_id":"term-new"}]}]}]}]}"""
    @Test fun revokedQueuedCallerAndHostErrorsPropagateWithoutImplicitRetry() = runTest {
        var value = input(rows(a)); val release = CompletableDeferred<Unit>(); var writes = 0; var allowed = true
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {}, canCloseSsh = { true },
            closeSsh = { _, gate -> release.await(); check(gate()) { "Host changed" }; writes++; error("Remote rejected close") })
        val command = RoutedSidebarMutation(host.read(RoutedSidebarQuery())!!.rows.single().key, RoutedSidebarMutationKind.CLOSE)
        val queued = async { runCatching { host.mutate(command) { allowed } } }; runCurrent()
        allowed = false; release.complete(Unit); runCurrent(); assertTrue(queued.await().isFailure); assertEquals(0, writes)
        allowed = true
        assertEquals("Remote rejected close", runCatching { host.mutate(command) { allowed } }.exceptionOrNull()?.message)
        assertEquals(1, writes)
    }
    @Test fun shellHasNoQuestionAndPersistentKindsRetainTheirWarnings() {
        val tmux = rows(a).single(); assertEquals("End Session", tmux.confirmation!!.actionTitle)
        val shell = tmux.copy(kind = SshWorkspaceKind.SHELL); assertNull(shell.confirmation)
        assertEquals("Close Workspace", tmux.copy(kind = SshWorkspaceKind.CMUX_TUI).confirmation!!.actionTitle)
        val malformed = RoutedSidebarWire.page(RoutedSidebarExchange().begin(RoutedSidebarSnapshot(emptyList(),
            listOf(RoutedSidebarRow("row", "workspace", "Title", sshKind = SshWorkspaceKind.SHELL))))).replace("SHELL", "UNKNOWN")
        assertTrue(runCatching { RoutedSidebarWire.page(malformed) }.isFailure)
    }
}
