package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutedSidebarCreationTest {
    private fun source(id: String) = NativeFeedSource(NativeCredentialStore.PairedMac("secret-$id", id, "Mac $id"),
        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"anchor","title":"Anchor $id","group_id":"g"}]}""")),
        groups = listOf(NativeGroup("g", "Group $id", false, false, anchorWorkspaceId = "anchor")),
        availability = NativeFeedAvailability.CONNECTED,
        capabilities = setOf(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY, "workspace.create_in_group.v1"))
    private fun input(sources: List<NativeFeedSource>) = NativeSidebarInput(sources, emptyList(), sources.map {
        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, it.mac.instanceTag)!!, it.mac.name)
    }, NativeWorkspaceSortState(), creation = NativeSidebarCreation())
    @Test fun emptyMacStillOffersCreationAndFiltersDoNotRedirectItsDestination() {
        var value = input(listOf(source("A").copy(workspaces = emptyList(), groups = emptyList()), source("B")))
        val opened = mutableListOf<NativeSidebarTarget>()
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, { opened += it })
        val all = host.read(RoutedSidebarQuery(workspaceQuery = "no matches", workspaceUnread = true))!!
        assertTrue(all.rows.isEmpty()); assertEquals(listOf("Mac A", "Mac B"), all.creation.map { it.name })
        val b = all.creation.single { it.name == "Mac B" }
        val scoped = host.read(RoutedSidebarQuery(computer = b.key))!!
        assertEquals(listOf(b), scoped.creation)
        host.resolve(b.options.single().key)!!()
        assertEquals(NativeSidebarTarget.CreateWorkspace(value.sources[1].mac), opened.single())
        assertTrue(host.read(RoutedSidebarQuery(computer = "removed"))!!.creation.isEmpty())
        assertTrue(host.read(RoutedSidebarQuery(notifications = true))!!.creation.isEmpty())
        value = value.copy(creation = null)
        assertNull(host.resolve(b.options.single().key))
    }
    @Test fun groupCreationUsesOwningMacAndRevokesDeletedOfflineBusyAndReplacedTargets() {
        var value: NativeSidebarInput? = input(listOf(source("A"), source("B")))
        val opened = mutableListOf<NativeSidebarTarget>()
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, { opened += it })
        val key = host.read(RoutedSidebarQuery())!!.rows.single { it.title == "Group B" && it.kind == "group" }.createKey!!
        host.resolve(key)!!()
        assertEquals(NativeSidebarTarget.CreateWorkspace(value!!.sources[1].mac, "g"), opened.single())
        val original = value!!
        val callback = host.resolve(key)!!
        for (replacement in listOf(
            original.copy(creation = NativeSidebarCreation(busy = true)),
            original.copy(sources = original.sources.map { it.copy(groups = emptyList()) }),
            original.copy(sources = original.sources.map { it.copy(capabilities = emptySet()) }),
            original.copy(sources = original.sources.map { it.copy(availability = NativeFeedAvailability.OFFLINE) }),
            original.copy(sources = original.sources.map { it.copy(mac = it.mac.copy(accountTeamId = "replacement")) }))) {
            value = replacement
            assertNull(host.resolve(key))
            assertTrue(runCatching { callback() }.isFailure)
        }
        value = null; assertNull(host.resolve(key)); assertTrue(runCatching { callback() }.isFailure)
        assertEquals(1, opened.size)
    }
    @Test fun wireContainsOnlyDisplayDataAndTicketsRequireTransmittedEnabledDestinations() {
        val value = input(listOf(source("A"), source("B").copy(availability = NativeFeedAvailability.OFFLINE)))
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {})
        val exchange = RoutedSidebarExchange(); val page = exchange.begin(host.read(RoutedSidebarQuery())!!)
        val wire = RoutedSidebarWire.page(page)
        assertFalse(wire.contains("secret-")); assertFalse(wire.contains("accountTeamId"))
        assertEquals(page, RoutedSidebarWire.page(wire))
        val key = page.snapshot.creation.first().options.single().key
        val ticket = exchange.prepare(key) { host.resolve(it) != null }
        assertEquals(key, exchange.consume(ticket)); assertNull(exchange.consume(ticket))
        assertThrows(IllegalStateException::class.java) { exchange.prepare(page.snapshot.creation.last().options.single().key) { true } }
        val later = RoutedSidebarSnapshot(emptyList(), List(101) { index -> RoutedSidebarRow("row-$index", "group", "Group", createKey = "create-$index") })
        val first = exchange.begin(later)
        assertThrows(IllegalStateException::class.java) { exchange.prepare("create-100") { true } }
        exchange.page(first.revision, 100)
        assertNotNull(exchange.prepare("create-100") { true })
        exchange.begin(later.copy(rows = emptyList()))
        assertThrows(IllegalStateException::class.java) { exchange.prepare("create-100") { true } }
    }
    @Test fun sshKindsRoundTripInOrderWithDisabledReasonsAndRejectAmbiguousOptions() {
        val choices = listOf(RoutedSidebarCreateComputer("host", "Build host", options = listOf(
            RoutedSidebarCreateOption("cmux", SshWorkspaceKind.CMUX_TUI),
            RoutedSidebarCreateOption("tmux", SshWorkspaceKind.TMUX, "tmux is not installed"),
            RoutedSidebarCreateOption("shell", SshWorkspaceKind.SHELL))))
        assertEquals(choices, RoutedSidebarCreationWire.decode(RoutedSidebarCreationWire.encode(choices)))
        val exchange = RoutedSidebarExchange(); exchange.begin(RoutedSidebarSnapshot(emptyList(), emptyList(), creation = choices))
        assertThrows(IllegalStateException::class.java) { exchange.prepare("tmux") { true } }
        assertNotNull(exchange.prepare("shell") { true })
        val duplicate = choices.map { it.copy(options = listOf(it.options[0], it.options[0])) }
        assertThrows(IllegalArgumentException::class.java) { RoutedSidebarCreationWire.decode(RoutedSidebarCreationWire.encode(duplicate)) }
    }
    @Test fun oversizedMenuFailsBeforeReturningAnUnreceivableEmptyPage() {
        val choices = List(256) { index -> RoutedSidebarCreateComputer("computer-$index", "😀".repeat(64), options =
            SshWorkspaceKind.entries.map { RoutedSidebarCreateOption("$index-${it.name}", it, "😀".repeat(256)) }) }
        assertThrows(IllegalStateException::class.java) { RoutedSidebarExchange().begin(RoutedSidebarSnapshot(emptyList(), emptyList(), creation = choices)) }
    }
}
