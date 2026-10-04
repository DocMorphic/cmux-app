package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedSidebarCustomizationTest {
    private fun source(device: String) = NativeFeedSource(NativeCredentialStore.PairedMac("secret-$device", device, device, stableOrigin = device),
        availability = NativeFeedAvailability.CONNECTED, capabilities = setOf("workspace.actions.v1", WORKSPACE_METADATA_CAPABILITY),
        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","title":"Workspace $device","description":"Original"}]}""")))
    private fun input(sources: List<NativeFeedSource>) = NativeSidebarInput(sources, emptyList(), sources.map {
        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name)
    }, NativeWorkspaceSortState())

    @Test fun wirePreservesNullUnicodeTruncationAndRebaseWithoutUnboundedMessages() {
        val draft = WorkspaceCustomizationDraft("Workspace", "null\n中 🚀", null, true, true)
        val editor = RoutedSidebarCustomization("opaque", "ticket", draft)
        assertEquals(editor, RoutedSidebarCustomizationWire.editor(RoutedSidebarCustomizationWire.editor(editor)))
        val command = RoutedSidebarCustomizationSave(editor.key, editor.ticket, draft, draft.copy(name = "Changed"))
        assertEquals(command, RoutedSidebarCustomizationWire.save(RoutedSidebarCustomizationWire.save(command)))
        val result = WorkspaceCustomizationResult(false, draft, draft.copy(color = "#ABCDEF"), "Host rejected save")
        assertEquals(result, RoutedSidebarCustomizationWire.result(RoutedSidebarCustomizationWire.result(result)))
        val oversized = draft.copy(description = "界🚀".repeat(10_000)).forSidebar()
        assertTrue(oversized.descriptionTruncated)
        assertTrue(oversized.description!!.toByteArray().size <= WORKSPACE_DESCRIPTION_MAX_BYTES)
        assertTrue(runCatching { oversized.copy(description = "Overwrite").validate(oversized) }.isFailure)
        assertTrue(runCatching { RoutedSidebarCustomizationWire.editor(editor.copy(draft = draft.copy(name = "界".repeat(100_000)))) }.isFailure)
        assertTrue(runCatching { RoutedSidebarCustomizationWire.save(command.copy(ticket = "")) }.isFailure)
    }

    @Test fun fullDraftAndSaveRemainBoundToExactMacDespiteCollidingWorkspaceIds() = runTest {
        var value: NativeSidebarInput? = input(listOf(source("A"), source("B").let { it.copy(workspaces = it.workspaces.map { row ->
            row.copy(description = "x".repeat(3000) + " complete")
        }) }))
        var saved: NativeSidebarMutationTarget? = null
        var permission: (() -> Boolean)? = null
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            customizeWorkspace = { target, _, _, guard -> saved = target; permission = guard; WorkspaceCustomizationResult(true) })
        val snapshot = host.read(RoutedSidebarQuery())!!
        val row = snapshot.rows.single { it.title == "Workspace B" }
        assertTrue(row.canCustomize)
        val editor = host.customization(row.key)
        assertTrue(editor.value.draft.description!!.endsWith(" complete"))
        assertFalse(RoutedSidebarCustomizationWire.editor(editor.value).contains("secret-"))
        val command = RoutedSidebarCustomizationSave(row.key, editor.value.ticket, editor.value.draft, editor.value.draft.copy(pinned = true))
        assertTrue(editor.save(command) { true }.succeeded)
        assertEquals("B", saved!!.mac.deviceId); assertEquals("w", saved!!.id); assertTrue(permission!!.invoke())
        value = value!!.copy(sources = value!!.sources.map { if (it.mac.deviceId == "B") it.copy(mac = it.mac.copy(deviceId = "replacement")) else it })
        assertFalse(editor.current()); assertFalse(permission!!.invoke())
        assertTrue(runCatching { editor.save(command) { true } }.isFailure)
        value = null; assertFalse(editor.current())
    }

    @Test fun offlineEditorRemainsDiscoverableButCapabilityOrWorkspaceRemovalRetiresIt() = runTest {
        var value = input(listOf(source("A").copy(availability = NativeFeedAvailability.OFFLINE)))
        var calls = 0
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            customizeWorkspace = { _, _, _, _ -> calls++; WorkspaceCustomizationResult(true) })
        val row = host.read(RoutedSidebarQuery())!!.rows.single()
        assertTrue(row.canCustomize); assertTrue(row.mutations.isEmpty())
        val editor = host.customization(row.key)
        assertTrue(editor.current())
        value = value.copy(sources = value.sources.map { it.copy(capabilities = emptySet()) })
        assertFalse(editor.current()); assertFalse(host.read(RoutedSidebarQuery())!!.rows.single().canCustomize)
        assertTrue(runCatching { editor.save(RoutedSidebarCustomizationSave(row.key, editor.value.ticket,
            editor.value.draft, editor.value.draft.copy(name = "No write"))) { true } }.isFailure)
        value = input(listOf(source("A")))
        assertTrue(editor.current())
        value = value.copy(sources = value.sources.map { it.copy(workspaces = emptyList()) })
        assertFalse(editor.current()); assertEquals(0, calls)
    }

    @Test fun editorRejectsForgedBaselineAndRetainsAuthoritativePartialSaveBaselineForRetry() = runTest {
        val original = WorkspaceCustomizationDraft("Original", "Old", null, false)
        val latest = original.copy(name = "Renamed")
        val submitted = latest.copy(description = "New")
        var calls = 0; var allowed = true
        val editor = RoutedSidebarCustomizationEditor("row", original, { allowed }) { baseline, draft, guard ->
            assertTrue(guard()); calls++
            if (calls == 1) WorkspaceCustomizationResult(false, latest, submitted, "Description rejected")
            else { assertEquals(latest, baseline); assertEquals(submitted, draft); WorkspaceCustomizationResult(true) }
        }
        fun command(baseline: WorkspaceCustomizationDraft) = RoutedSidebarCustomizationSave("row", editor.value.ticket, baseline, submitted)
        assertTrue(runCatching { editor.save(command(latest)) { true } }.isFailure)
        val result = editor.save(command(original)) { true }
        assertFalse(result.succeeded); assertEquals(submitted, result.display)
        assertTrue(runCatching { editor.save(command(original)) { true } }.isFailure)
        assertTrue(editor.save(command(result.baseline!!)) { true }.succeeded)
        assertTrue(runCatching { editor.save(command(latest).copy(key = "another")) { true } }.isFailure)
        assertTrue(runCatching { editor.save(command(latest).copy(ticket = "another")) { true } }.isFailure)
        allowed = false; assertTrue(runCatching { editor.save(command(latest)) { true } }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun exchangeIssuesOnlyTransmittedEditorsAndRoundTripsEditorLifetime() {
        val rows = (0..110).map { RoutedSidebarRow("w$it", "workspace", "Workspace", canCustomize = true) }
        val exchange = RoutedSidebarExchange()
        val first = exchange.begin(RoutedSidebarSnapshot(emptyList(), rows, editorTicket = "editor"))
        assertEquals(first, RoutedSidebarWire.page(RoutedSidebarWire.page(first)))
        assertTrue(exchange.permitsCustomization("w0")); assertFalse(exchange.permitsCustomization("w110"))
        exchange.clear(); assertFalse(exchange.permitsCustomization("w0"))
    }

    @Test fun controllerKeepsEditorThroughFilteredRowsAndClosesOnHostRevocation() = runTest {
        val editor = RoutedSidebarCustomization("w", "ticket", WorkspaceCustomizationDraft("Original", null, null, false))
        var issued = false; var permitted = true; var visibleRow = true
        val closed = mutableListOf<String>(); var calls = 0
        val pending = CompletableDeferred<Unit>()
        val controller = RoutedSidebarController(backgroundScope, {}, { _, _, _ -> RoutedSidebarExchange().begin(
            RoutedSidebarSnapshot(emptyList(), if (visibleRow) listOf(RoutedSidebarRow("w", "workspace", "Workspace", canCustomize = true)) else emptyList(),
                editorTicket = editor.ticket.takeIf { issued && permitted })) }, { "ticket" },
            readEditor = { issued = true; editor }, closeEditor = { closed += it },
            customizeWorkspace = { calls++; pending.await(); WorkspaceCustomizationResult(true) })
        controller.initialize(RoutedSidebarQuery()); controller.configure(true, true); controller.visible(true); runCurrent()
        controller.editWorkspace("w"); runCurrent(); assertEquals(editor, controller.state.value.editor)
        visibleRow = false; advanceTimeBy(1600); runCurrent(); assertEquals(editor, controller.state.value.editor)
        controller.visible(false); runCurrent(); assertEquals(editor, controller.state.value.editor)
        controller.configure(true, false)
        assertTrue(runCatching { controller.customize(editor, editor.draft, editor.draft) }.isFailure)
        controller.configure(true, true); runCurrent()
        val save = async { controller.customize(editor, editor.draft, editor.draft.copy(pinned = true)) }; runCurrent()
        assertTrue(runCatching { controller.customize(editor, editor.draft, editor.draft) }.isFailure)
        assertEquals(1, calls); pending.complete(Unit); runCurrent(); assertTrue(save.await().succeeded)
        permitted = false; advanceTimeBy(1600); runCurrent()
        assertNull(controller.state.value.editor); assertEquals(listOf(editor.ticket), closed)
        assertTrue(runCatching { controller.customize(editor, editor.draft, editor.draft) }.isFailure)
    }
}
