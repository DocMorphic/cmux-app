package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedSidebarTest {
    private fun rows(count: Int) = List(count) { RoutedSidebarRow("row-$it", "workspace", "Workspace $it") }
    private fun snapshot(count: Int) = RoutedSidebarSnapshot(listOf(RoutedSidebarComputer("mac", "Mac")), rows(count))
    @Test fun allPagesStayWithinByteBudgetAndKeepOrderWithMultibyteContent() {
        val value = snapshot(310).copy(rows = rows(310).map { it.copy(preview = "🧑".repeat(4096), subtitle = "ä".repeat(2048)) })
        val exchange = RoutedSidebarExchange()
        var page = exchange.begin(value)
        val all = mutableListOf<String>()
        do {
            val wire = RoutedSidebarWire.page(page)
            assertTrue(wire.toByteArray().size <= RoutedSidebarWire.MAX_BYTES)
            assertEquals(page, RoutedSidebarWire.page(wire))
            all += page.snapshot.rows.map { it.key }
            val next = page.next ?: break
            page = exchange.page(page.revision, next)
        } while (true)
        assertEquals(value.rows.map { it.key }, all)
    }
    @Test fun undisplayedRowsAndNonDestinationsCannotGetNavigationTickets() {
        val exchange = RoutedSidebarExchange()
        exchange.begin(snapshot(120).copy(rows = rows(120).mapIndexed { i, row -> if (i == 0) row.copy(canOpen = false) else row }))
        assertThrows(IllegalStateException::class.java) { exchange.prepare("row-119") { true } }
        assertThrows(IllegalStateException::class.java) { exchange.prepare("row-0") { true } }
        assertThrows(IllegalStateException::class.java) { exchange.prepare("row-1") { false } }
    }
    @Test fun ticketsAreOneUseAndLatestSelectionWinsWithoutWrongTicketConsumingIt() {
        val exchange = RoutedSidebarExchange(); exchange.begin(snapshot(2))
        val first = exchange.prepare("row-0") { true }; val last = exchange.prepare("row-1") { true }
        assertNull(exchange.consume(first)); assertNull(exchange.consume("wrong"))
        assertEquals("row-1", exchange.consume(last)); assertNull(exchange.consume(last))
    }
    @Test fun refreshInvalidatesPagingAndOldIssuedRowsButPreservesPreparedNavigation() {
        val exchange = RoutedSidebarExchange(); val old = exchange.begin(snapshot(120))
        val ticket = exchange.prepare("row-0") { true }
        exchange.begin(snapshot(1).copy(rows = listOf(RoutedSidebarRow("new", "workspace", "New"))))
        assertThrows(IllegalStateException::class.java) { exchange.page(old.revision, 100) }
        assertThrows(IllegalStateException::class.java) { exchange.prepare("row-0") { true } }
        assertEquals("row-0", exchange.consume(ticket))
    }
    @Test fun globalActionsRoundTripAndUseOneUseIssuedTicketsWithoutWorkspaceRows() {
        val actions = RoutedSidebarActionKind.entries.map { RoutedSidebarAction("action-${it.name}", it) }
        val exchange = RoutedSidebarExchange()
        val page = exchange.begin(snapshot(0).copy(actions = actions))
        assertEquals(page, RoutedSidebarWire.page(RoutedSidebarWire.page(page)))
        for (action in actions) {
            val ticket = exchange.prepare(action.key) { true }
            assertEquals(action.key, exchange.consume(ticket)); assertNull(exchange.consume(ticket))
        }
        exchange.begin(snapshot(0))
        assertThrows(IllegalStateException::class.java) { exchange.prepare(actions.first().key) { true } }
    }
    @Test fun ambiguousOrUnknownGlobalActionsAreRejected() {
        val action = RoutedSidebarAction("settings", RoutedSidebarActionKind.SETTINGS)
        assertThrows(IllegalArgumentException::class.java) {
            RoutedSidebarExchange().begin(snapshot(0).copy(actions = listOf(action, action.copy(key = "other"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutedSidebarExchange().begin(snapshot(1).copy(actions = listOf(action.copy(key = "row-0"))))
        }
        val valid = RoutedSidebarExchange().begin(snapshot(0).copy(actions = listOf(action)))
        val wire = JSONObject(RoutedSidebarWire.page(valid))
        wire.getJSONArray("actions").getJSONObject(0).put("kind", "SIGN_OUT")
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.page(wire.toString()) }
        wire.getJSONArray("actions").getJSONObject(0).put("kind", "SETTINGS")
        wire.getJSONArray("actions").put(JSONObject().put("key", "other").put("kind", "SETTINGS"))
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.page(wire.toString()) }
    }
    @Test fun globalActionsRevalidateOwnerAndAvailabilityAtCallbackTime() {
        var current: NativeSidebarInput? = input(source()).copy(actions = RoutedSidebarActionKind.entries.toSet())
        val destinations = mutableListOf<NativeSidebarTarget>()
        fun host(salt: String = "salt") = NativeRoutedSidebarHost("owner", salt, { current }, { RoutedSidebarLease({}) {} }, { destinations += it })
        val host = host(); val snapshot = host.read(RoutedSidebarQuery())!!
        val task = snapshot.actions.single { it.kind == RoutedSidebarActionKind.NEW_TASK }
        val openTask = host.resolve(task.key)!!
        assertNull(host("other-owner-salt").resolve(task.key))
        assertEquals(setOf(RoutedSidebarActionKind.SETTINGS, RoutedSidebarActionKind.COMPUTERS),
            host.read(RoutedSidebarQuery(notifications = true))!!.actions.map { it.kind }.toSet())
        current = current!!.copy(actions = current!!.actions - RoutedSidebarActionKind.NEW_TASK)
        assertNull(host.resolve(task.key)); assertThrows(IllegalStateException::class.java) { openTask() }
        val settings = host.resolve(snapshot.actions.single { it.kind == RoutedSidebarActionKind.SETTINGS }.key)!!
        settings(); assertEquals(listOf(NativeSidebarTarget.Action(RoutedSidebarActionKind.SETTINGS)), destinations)
        current = null
        assertNull(host.resolve(task.key)); assertThrows(IllegalStateException::class.java) { settings() }
        assertEquals(1, destinations.size)
    }
    @Test fun queryRejectsMalformedComputerInsteadOfTruncatingAuthority() {
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.query("""{"computer":"${"a".repeat(129)}"}""") }
        val query = RoutedSidebarQuery(true, "workspace", "résumé", "mac", true, false, setOf("other"), setOf("updates"), mapOf("group" to true))
        assertEquals(query, RoutedSidebarWire.query(RoutedSidebarWire.query(query)))
    }
    @Test fun outgoingQueryCannotExceedTheIpcBudget() {
        val query = RoutedSidebarQuery(expanded = (0..4000).map { it.toString().padStart(64, 'x') }.toSet())
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.query(query) }
    }
    @Test fun invalidPagingCannotClaimCompletionOrMoveBackward() {
        val wire = RoutedSidebarWire.page(RoutedSidebarExchange().begin(snapshot(120)))
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.page(JSONObject(wire).put("next", JSONObject.NULL).toString()) }
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.page(JSONObject(wire).put("next", 0).toString()) }
    }
    @Test fun duplicateKeysCannotCreateAmbiguousDestinations() {
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarExchange().begin(snapshot(2).copy(rows = rows(1) + rows(1))) }
    }
    @Test fun leaseDeactivatesAndReleasesExactlyOnce() {
        val states = mutableListOf<Boolean>(); var releases = 0
        val lease = RoutedSidebarLease(states::add) { releases++ }
        lease.active(true); lease.close(); lease.close(); lease.active(true)
        assertEquals(listOf(true, false), states); assertEquals(1, releases)
    }
    @Test fun controllerLoadsOnlyWhenVisibleAndForegroundAndPaginatesConsistently() = runTest {
        var reads = 0; val visibility = mutableListOf<Boolean>(); val exchange = RoutedSidebarExchange()
        val controller = RoutedSidebarController(backgroundScope, { visibility += it }, { _, revision, offset ->
            reads++; if (offset == 0) exchange.begin(snapshot(220)) else exchange.page(revision!!, offset)
        }, { "ticket" })
        controller.initialize(RoutedSidebarQuery()); controller.configure(true, true); runCurrent(); assertEquals(0, reads)
        controller.visible(true); runCurrent(); assertEquals(100, controller.state.value.snapshot!!.rows.size)
        controller.more(); runCurrent(); assertEquals(200, controller.state.value.snapshot!!.rows.size)
        controller.more(); runCurrent(); assertEquals(220, controller.state.value.snapshot!!.rows.size); assertFalse(controller.state.value.more)
        controller.configure(true, false); runCurrent(); val stopped = reads
        advanceTimeBy(10_000); runCurrent(); assertEquals(stopped, reads); assertEquals(false, visibility.last())
    }
    @Test fun staleNoncancellableResponseCannotOverwriteNewSearch() = runTest {
        val release = CompletableDeferred<Unit>()
        val controller = RoutedSidebarController(backgroundScope, {}, { query, _, _ ->
            if (query.text == "old") withContext(NonCancellable) { release.await() }
            RoutedSidebarExchange().begin(snapshot(1).copy(rows = listOf(RoutedSidebarRow("key", "workspace", query.text))))
        }, { "ticket" })
        controller.initialize(RoutedSidebarQuery(workspaceQuery = "old")); controller.configure(true, true); controller.visible(true); runCurrent()
        controller.query(RoutedSidebarQuery(workspaceQuery = "new")); runCurrent()
        assertEquals("new", controller.state.value.snapshot!!.rows.single().title)
        release.complete(Unit); runCurrent(); assertEquals("new", controller.state.value.snapshot!!.rows.single().title)
    }
    @Test fun controllerKeepsIndependentSearchDraftsAndRejectsOldEditorEvents() = runTest {
        val controller = RoutedSidebarController(backgroundScope, {}, { _, _, _ -> RoutedSidebarExchange().begin(snapshot(0)) }, { "ticket" })
        controller.beginSearch(); val generation = controller.state.value.search.generation
        controller.edit("workspace", generation); controller.tab(true)
        controller.beginSearch(); controller.edit("stale", generation)
        assertEquals("", controller.state.value.query.text)
        controller.edit("notice", controller.state.value.search.generation); controller.tab(false)
        assertEquals("workspace", controller.state.value.query.text)
        controller.tab(true); assertEquals("notice", controller.state.value.query.text)
    }
    @Test fun mismatchedPageRevisionIsReportedWithoutPublishingMixedRows() = runTest {
        val exchange = RoutedSidebarExchange()
        val controller = RoutedSidebarController(backgroundScope, {}, { _, revision, offset ->
            if (offset == 0) exchange.begin(snapshot(120)) else exchange.page(revision!!, offset).copy(revision = "wrong")
        }, { "ticket" })
        controller.configure(true, true); controller.visible(true); runCurrent(); controller.more(); runCurrent()
        assertNotNull(controller.state.value.error); assertEquals(100, controller.state.value.snapshot!!.rows.size)
    }
    private fun source(code: String = "secret-code", title: String = "Workspace") = NativeFeedSource(
        NativeCredentialStore.PairedMac(code = code, name = "Mac", deviceId = "device"), workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"workspace","title":"$title","current_directory":"/private/path"}]}""")))
    private fun input(source: NativeFeedSource) = NativeSidebarInput(listOf(source), emptyList(),
        listOf(NativeSortComputer(workspaceMacFilterId(source.mac.deviceId, null)!!, "Mac")), NativeWorkspaceSortState(), locale = Locale.US)
    @Test fun displayProjectionDoesNotSerializePairingOrDirectoryAndSearchStillFindsDirectory() {
        val source = source(); val host = NativeRoutedSidebarHost("owner", "salt", { input(source) }, { RoutedSidebarLease({}) {} }, {})
        val snapshot = host.read(RoutedSidebarQuery(workspaceQuery = "/private/path"))!!
        assertEquals(1, snapshot.rows.size)
        val wire = RoutedSidebarWire.page(RoutedSidebarExchange().begin(snapshot))
        assertFalse(wire.contains("secret-code")); assertFalse(wire.contains("/private/path")); assertFalse(wire.contains("device"))
    }
    @Test fun changedPairingAndRemovedWorkspaceRejectPreviouslyResolvedCallbacks() {
        var current: NativeSidebarInput? = input(source()); var navigations = 0
        val host = NativeRoutedSidebarHost("owner", "salt", { current }, { RoutedSidebarLease({}) {} }, { navigations++ })
        val key = host.read(RoutedSidebarQuery())!!.rows.single().key
        val navigate = host.resolve(key)!!
        current = input(source("replacement"))
        assertNull(host.resolve(key)); assertThrows(IllegalStateException::class.java) { navigate() }; assertEquals(0, navigations)
        current = null; assertNull(host.read(RoutedSidebarQuery())); assertFalse(host.current())
    }
    @Test fun missingScopedComputerDoesNotExposeAllComputerRows() {
        val host = NativeRoutedSidebarHost("owner", "salt", { input(source()) }, { RoutedSidebarLease({}) {} }, {})
        val result = host.read(RoutedSidebarQuery(computer = "removed"))!!
        assertTrue(result.rows.isEmpty()); assertNotNull(result.status)
    }
    @Test fun stableOwnerAndSaltSurviveHostRecreationAndAdoptScope() {
        val value = input(source()); var adopted: NativeSidebarPresentation? = null
        fun host() = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            { NativeSidebarPresentation(value.computers.single().id, true, "workspaces", "query", true, false) }, { adopted = it })
        val a = host(); val b = host()
        assertEquals(a.read(RoutedSidebarQuery()), b.read(RoutedSidebarQuery()))
        b.adopt(a.initialQuery())
        assertEquals(NativeSidebarPresentation(value.computers.single().id, true, "workspaces", "query", true, false), adopted)
    }
    @Test fun unreadFiltersAndSearchQueriesStayIndependentAcrossTabSwitches() = runTest {
        val controller = RoutedSidebarController(backgroundScope, {}, { _, _, _ -> RoutedSidebarExchange().begin(snapshot(0)) }, { "ticket" })
        controller.initialize(RoutedSidebarQuery(workspaceQuery = "alpha", notificationQuery = "notice", workspaceUnread = true))
        controller.tab(true)
        assertEquals("notice", controller.state.value.query.text); assertFalse(controller.state.value.query.unread)
        controller.query(controller.state.value.query.withUnread(true))
        controller.beginSearch(); controller.edit("new notice", controller.state.value.search.generation); controller.finishSearch()
        controller.tab(false)
        assertEquals("alpha", controller.state.value.query.text); assertTrue(controller.state.value.query.unread)
        controller.query(controller.state.value.query.withUnread(false)); controller.tab(true)
        assertEquals("new notice", controller.state.value.query.text); assertTrue(controller.state.value.query.unread)
        assertEquals(RoutedSidebarQuery(true, "alpha", "new notice", workspaceUnread = false, notificationUnread = true), controller.state.value.query)
    }
    private fun mixed(): NativeSidebarInput {
        fun mac(id: String, unread: Boolean) = NativeFeedSource(NativeCredentialStore.PairedMac("secret-$id", "device", id, id),
            workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"shared","title":"$id workspace","has_unread":$unread}]}""")))
        val a = mac("stable", true); val b = mac("nightly", false)
        val sshHost = SshHostRecord(name = "SSH", endpoint = SshEndpoint("example.test", 22, "user"))
        val ssh = sshTmuxFeedRows(sshHost, listOf(SshTmuxWorkspace(1, 1, 1, "SSH workspace", emptyList())))
        return NativeSidebarInput(listOf(a, b), ssh, listOf(
            NativeSortComputer(workspaceMacFilterId("device", "stable")!!, "stable"),
            NativeSortComputer(workspaceMacFilterId("device", "nightly")!!, "nightly"),
            NativeSortComputer(workspaceSshFilterId(sshHost.id), "SSH")), NativeWorkspaceSortState(), locale = Locale.US)
    }
    @Test fun compoundFilterKeepsExactBuildAndSshIdentityAndComposesWithUnread() {
        val input = mixed()
        val host = NativeRoutedSidebarHost("owner", "salt", { input }, { RoutedSidebarLease({}) {} }, {})
        val snapshot = host.read(RoutedSidebarQuery())!!
        val stable = snapshot.computers.single { it.name == "stable" }.key
        val ssh = snapshot.computers.single { it.name == "SSH" }.key
        assertEquals(3, snapshot.filterMachines.size)
        val filtered = host.read(RoutedSidebarQuery(machines = setOf(stable, ssh)))!!
        assertEquals(setOf("stable workspace", "SSH workspace"), filtered.rows.map { it.title }.toSet())
        assertEquals(listOf("stable workspace"), host.read(RoutedSidebarQuery(workspaceUnread = true, machines = setOf(stable, ssh)))!!.rows.map { it.title })
        val scoped = host.read(RoutedSidebarQuery(computer = stable, machines = setOf(ssh)))!!
        assertTrue(scoped.selectedMachines.isEmpty()); assertTrue(scoped.filterMachines.isEmpty())
        assertEquals(listOf("stable workspace"), scoped.rows.map { it.title })
    }
    @Test fun unavailableMachineFiltersArePrunedAndDoNotResurrectOnLaterPolls() = runTest {
        var available = true
        val controller = RoutedSidebarController(backgroundScope, {}, { query, _, _ ->
            RoutedSidebarExchange().begin(snapshot(0).copy(selectedMachines = if (available) query.machines else emptySet()))
        }, { "ticket" })
        controller.initialize(RoutedSidebarQuery(machines = setOf("mac"))); controller.configure(true, true); controller.visible(true); runCurrent()
        available = false; advanceTimeBy(1501); runCurrent(); assertTrue(controller.state.value.query.machines.isEmpty())
        available = true; advanceTimeBy(1501); runCurrent(); assertTrue(controller.state.value.query.machines.isEmpty())
    }
    @Test fun fullPresentationAdoptsBothQueriesFiltersAndMappedMachineKeys() {
        val input = mixed(); var adopted: NativeSidebarPresentation? = null
        val initial = NativeSidebarPresentation(null, true, "workspace query", "notice query", true, false,
            input.computers.take(2).map { it.id }.toSet())
        val host = NativeRoutedSidebarHost("owner", "salt", { input }, { RoutedSidebarLease({}) {} }, {}, { initial }, { adopted = it })
        val query = RoutedSidebarWire.query(RoutedSidebarWire.query(host.initialQuery()))
        assertFalse(query.machines.any { it in initial.machines }); host.adopt(query)
        assertEquals(initial, adopted)
    }
    @Test fun hostSortWritesLocalPreferencesAndRejectsMissingOrReplacedComputerKeys() {
        var value: NativeSidebarInput? = mixed(); var saved: String? = null
        val store = NativeWorkspaceSortStore({ saved }, { saved = it })
        val host = NativeRoutedSidebarHost("owner", "salt", { value?.copy(sort = store.state.value) }, { RoutedSidebarLease({}) {} }, {},
            saveSort = { mode, order -> mode?.let(store::setMode); order?.let(store::setPriority) })
        val old = host.read(RoutedSidebarQuery())!!
        host.sort(RoutedSidebarSort.Mode(NativeWorkspaceSortMode.PRIORITY))
        val order = old.computers.reversed().map { it.key }
        host.sort(RoutedSidebarSort.Order(order))
        assertEquals(order, host.read(RoutedSidebarQuery())!!.computers.map { it.key })
        assertEquals(store.state.value, NativeWorkspaceSortStore({ saved }, {}).state.value)
        val before = saved
        assertThrows(IllegalStateException::class.java) { host.sort(RoutedSidebarSort.Order(order.drop(1))) }
        value = value!!.copy(computers = value!!.computers.dropLast(1))
        assertThrows(IllegalStateException::class.java) { host.sort(RoutedSidebarSort.Order(order)) }
        value = null
        assertThrows(IllegalStateException::class.java) { host.sort(RoutedSidebarSort.Mode(NativeWorkspaceSortMode.ACTIVITY)) }
        assertEquals(before, saved)
    }
    @Test fun sortingRequiresAnIssuedEditableSnapshotAndAnExactComputerSet() {
        val exchange = RoutedSidebarExchange()
        val mode = RoutedSidebarSort.Mode(NativeWorkspaceSortMode.ACTIVITY)
        assertFalse(exchange.permitsSort(mode)); exchange.begin(snapshot(0)); assertFalse(exchange.permitsSort(mode))
        exchange.begin(snapshot(0).copy(sortMode = NativeWorkspaceSortMode.AUTOMATIC))
        assertTrue(exchange.permitsSort(mode)); assertTrue(exchange.permitsSort(RoutedSidebarSort.Order(listOf("mac"))))
        assertFalse(exchange.permitsSort(RoutedSidebarSort.Order(listOf("mac", "mac"))))
        assertFalse(exchange.permitsSort(RoutedSidebarSort.Order(listOf("forged")))); assertFalse(exchange.permitsSort(RoutedSidebarSort.Order(emptyList())))
    }
    @Test fun sortWireRejectsUnknownModesAmbiguousCommandsAndDuplicateKeys() {
        val order = RoutedSidebarSort.Order(listOf("a", "b"))
        assertEquals(order, RoutedSidebarWire.sort(RoutedSidebarWire.sort(order)))
        assertEquals(RoutedSidebarSort.Mode(NativeWorkspaceSortMode.ACTIVITY), RoutedSidebarWire.sort("""{"mode":"recentActivity"}"""))
        assertThrows(IllegalStateException::class.java) { RoutedSidebarWire.sort("""{"mode":"remote-command"}""") }
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.sort("""{"mode":"automatic","order":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarWire.sort("""{"order":["a","a"]}""") }
    }
    @Test fun sortCommandsSerializeAndErrorsRemainVisibleAfterSuccessfulFeedRefresh() = runTest {
        val gate = CompletableDeferred<Unit>(); val calls = mutableListOf<RoutedSidebarSort>()
        val controller = RoutedSidebarController(backgroundScope, {}, { _, _, _ -> RoutedSidebarExchange().begin(snapshot(0)) }, { "ticket" },
            saveSort = { command -> calls += command; if (calls.size == 1) gate.await() else error("Computers changed") })
        controller.configure(true, true); controller.visible(true); runCurrent()
        val mode = RoutedSidebarSort.Mode(NativeWorkspaceSortMode.PRIORITY)
        val order = RoutedSidebarSort.Order(listOf("mac"))
        val first = backgroundScope.async { controller.sort(mode) }; val second = backgroundScope.async { controller.sort(order) }
        runCurrent(); assertEquals(listOf(mode), calls); assertTrue(controller.state.value.saving)
        gate.complete(Unit); runCurrent(); assertTrue(first.await()); assertFalse(second.await())
        advanceTimeBy(2000); runCurrent()
        assertEquals("Computers changed", controller.state.value.actionError); assertEquals(1, controller.state.value.orderGeneration)
        controller.retry(); runCurrent(); assertNull(controller.state.value.actionError)
        controller.visible(false); runCurrent(); assertFalse(controller.sort(mode)); assertEquals(2, calls.size)
    }

}
