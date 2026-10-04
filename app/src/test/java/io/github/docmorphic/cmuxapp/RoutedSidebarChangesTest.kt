package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutedSidebarChangesTest {
    private val a = NativeCredentialStore.PairedMac("private-a", "A", "Mac A")
    private val b = NativeCredentialStore.PairedMac("private-b", "B", "Mac B")
    private fun source(mac: NativeCredentialStore.PairedMac, count: Long) = NativeFeedSource(mac,
        workspaces = listOf(NativeWorkspace("same", "Workspace ${mac.name}", emptyList(), null, false, null, null, false, emptyList(), null, "Preview", null)),
        capabilities = setOf(WORKSPACE_CHANGES_CAPABILITY), availability = NativeFeedAvailability.CONNECTED,
        changes = mapOf("same" to WorkspaceChangesChip(count, count * 2, count)))
    private fun input(sources: List<NativeFeedSource>) = NativeSidebarInput(sources, emptyList(),
        sources.map { NativeSortComputer(it.mac.deviceId, it.mac.name) }, NativeWorkspaceSortState())

    @Test fun chipsAndReadsUseTheOwningMacAndRevocationRetiresTheCapture() = runTest {
        var value: NativeSidebarInput? = input(listOf(source(a, 1), source(b, 3)))
        val reads = mutableListOf<String>()
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, { fail("No navigation") },
            readChanges = { mac, workspace, gate -> WorkspaceChangesAccess(workspace.id, workspace.title, gate) {
                reads += mac.deviceId; JSONObject().put("workspace_id", workspace.id)
            } })
        val rows = host.read(RoutedSidebarQuery())!!.rows
        val selected = rows.single { it.computer == "Mac B" }; assertEquals(3L, selected.changes!!.files)
        val exchange = RoutedSidebarExchange(); val page = exchange.begin(host.read(RoutedSidebarQuery())!!)
        assertTrue(exchange.permitsChanges(selected.key))
        val encoded = RoutedSidebarWire.page(page); assertEquals(page, RoutedSidebarWire.page(encoded)); assertFalse(encoded.contains("private-b"))
        val access = host.changes(selected.key); access.read(WorkspaceChangesRead.Files); assertEquals(listOf("B"), reads)
        value = input(listOf(source(a, 1), source(b, 0).copy(availability = NativeFeedAvailability.OFFLINE)))
        assertTrue(access.current()) // Existing preview may show an error and retry after reconnection.
        assertTrue(host.read(RoutedSidebarQuery())!!.rows.single { it.computer == "Mac B" }.changes == null)
        value = input(listOf(source(a, 1), source(b, 3).copy(capabilities = emptySet())))
        assertFalse(access.current()); assertTrue(runCatching { access.read(WorkspaceChangesRead.Files) }.isFailure)
        assertTrue(runCatching { host.changes(selected.key) }.isFailure)
        value = input(listOf(source(a, 1), source(b.copy(code = "replacement"), 3)))
        assertFalse(access.current()); assertTrue(runCatching { host.changes(selected.key) }.isFailure)
        value = null; assertFalse(access.current()); assertEquals(listOf("B"), reads)
    }
    @Test fun onlyIssuedChangesRowsAreAdmittedAndLegacyWireHasNoChip() {
        val rows = (0..110).map { RoutedSidebarRow("row-$it", "workspace", "Workspace", changes = WorkspaceChangesChip(1, 0, 0)) }
        val exchange = RoutedSidebarExchange(); val page = exchange.begin(RoutedSidebarSnapshot(emptyList(), rows))
        assertTrue(exchange.permitsChanges("row-0")); assertFalse(exchange.permitsChanges("row-110"))
        exchange.page(page.revision, page.next!!); assertTrue(exchange.permitsChanges("row-110"))
        exchange.begin(RoutedSidebarSnapshot(emptyList(), listOf(rows[0].copy(changes = null))))
        assertFalse(exchange.permitsChanges("row-0")); assertFalse(exchange.permitsChanges("row-110"))
        val encoded = RoutedSidebarWire.page(RoutedSidebarExchange().begin(RoutedSidebarSnapshot(emptyList(), listOf(rows[0]))))
        val legacy = JSONObject(encoded).apply { getJSONArray("rows").getJSONObject(0).remove("changes") }
        assertNull(RoutedSidebarWire.page(legacy.toString()).snapshot.rows.single().changes)
        for (bad in listOf(-1, 0, 0.5, "1")) {
            val invalid = JSONObject(encoded).apply { getJSONArray("rows").getJSONObject(0).getJSONObject("changes").put("files", bad) }
            assertTrue(runCatching { RoutedSidebarWire.page(invalid.toString()) }.isFailure)
        }
    }
    @Test fun inFlightReadCannotPublishAfterAccountOrPairingRetires() = runTest {
        var current = true; var calls = 0; val release = CompletableDeferred<Unit>()
        val access = WorkspaceChangesAccess("w", "Workspace", { current }) { calls++; release.await(); JSONObject() }
        val read = async { runCatching { access.read(WorkspaceChangesRead.Files) } }
        yield(); current = false; release.complete(Unit)
        assertTrue(read.await().isFailure); assertEquals(1, calls)
        assertTrue(runCatching { access.read(WorkspaceChangesRead.Diff("file", 100)) }.isFailure); assertEquals(1, calls)
    }
}
