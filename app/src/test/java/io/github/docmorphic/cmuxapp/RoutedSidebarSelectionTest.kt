package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RoutedSidebarSelectionTest {
    private val a = NativeCredentialStore.PairedMac("private-a", "A", "Mac A")
    private val b = NativeCredentialStore.PairedMac("private-b", "B", "Mac B")
    private fun source(mac: NativeCredentialStore.PairedMac) = NativeFeedSource(mac,
        workspaces = listOf(NativeWorkspace("same", "Workspace ${mac.name}", emptyList(), null, false, null, null,
            false, emptyList(), "g", "Preview", null)),
        groups = listOf(NativeGroup("g", "Group ${mac.name}", true, false, anchorWorkspaceId = "same")))
    private fun input(sources: List<NativeFeedSource>, ssh: List<SshFeedRow> = emptyList()) = NativeSidebarInput(
        sources, ssh, sources.map { NativeSortComputer(it.mac.deviceId, it.mac.name) } +
            ssh.map { NativeSortComputer(workspaceSshFilterId(it.host.id), it.host.name) }.distinct(), NativeWorkspaceSortState())

    @Test fun capturedPresentationSelectsOnlyExactMacAndLiveGroupAnchorAcrossRefreshes() {
        var value: NativeSidebarInput? = input(listOf(source(a), source(b)))
        val base = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {})
        val bound = base.withSelection(NativeSidebarSelection.Mac(a, "same"))
        fun selected() = bound.read(RoutedSidebarQuery())!!.rows.filter { it.selected }
        assertEquals(listOf("Group Mac A"), selected().map { it.title })
        val group = selected().single()
        val expanded = bound.read(RoutedSidebarQuery(groupExpansion = mapOf(group.key to true)))!!
        assertEquals(listOf("Group Mac A"), expanded.rows.filter { it.selected }.map { it.title })
        assertEquals(listOf("Workspace Mac A"), bound.read(RoutedSidebarQuery(workspaceQuery = "Workspace"))!!
            .rows.filter { it.selected }.map { it.title })
        assertTrue(base.read(RoutedSidebarQuery())!!.rows.none { it.selected })
        // A different presentation may show B without redirecting the still-open browser on A.
        assertEquals(listOf("Group Mac B"), base.withSelection(NativeSidebarSelection.Mac(b, "same"))
            .read(RoutedSidebarQuery())!!.rows.filter { it.selected }.map { it.title })
        assertEquals(listOf("Group Mac A"), selected().map { it.title })
        value = input(listOf(source(a.copy(code = "replacement")), source(b)))
        assertTrue(selected().isEmpty())
        value = input(listOf(source(a).copy(workspaces = emptyList()), source(b)))
        assertTrue(selected().isEmpty())
        value = null; assertNull(bound.read(RoutedSidebarQuery())); assertFalse(bound.current())
    }

    @Test fun selectedBitsRoundTripWithoutPrivateAuthorityAndLegacyDefaultsToUnselected() {
        val base = NativeRoutedSidebarHost("owner", "salt", { input(listOf(source(a))) }, { RoutedSidebarLease({}) {} }, {})
        val snapshot = base.withSelection(NativeSidebarSelection.Mac(a, "same")).read(RoutedSidebarQuery())!!
        val page = RoutedSidebarExchange().begin(snapshot)
        val encoded = RoutedSidebarWire.page(page)
        assertEquals(page, RoutedSidebarWire.page(encoded)); assertFalse(encoded.contains("private-a"))
        val old = JSONObject(encoded).apply { getJSONArray("rows").getJSONObject(0).remove("selected") }
        assertTrue(RoutedSidebarWire.page(old.toString()).snapshot.rows.none { it.selected })
        assertTrue(base.withSelection(NativeSidebarSelection.Mac(a, "same")).read(RoutedSidebarQuery(notifications = true))!!.rows.none { it.selected })
    }

    private val host = SshHostRecord(name = "SSH", endpoint = SshEndpoint("private-host.invalid", 22, "user"))
    private fun cmux(key: Boolean = true): SshFeedRow {
        val tree = SshCmuxInventory.parse(JSONObject("""{"generation":"one","registry_id":"r","workspaces":[{"id":1,${if (key) "\"key\":\"stable\"," else ""}"name":"Project","screens":[]}]}"""))
        return sshCmuxFeedRows(host, "session", tree).single()
    }
    @Test fun sshWorkspaceIdentitySurvivesPaneChangesButNotHostOrRegistryReplacement() {
        val row = cmux(); val selection = NativeSidebarSelection.Ssh(host, row.openTarget()!!)
        assertTrue(selection.matches(row)); assertTrue(selection.matches(row.copy(host = host.copy(name = "Renamed"))))
        assertTrue(selection.matches(row.copy(generation = "two"))) // stable workspace key survives a refresh
        for (changed in listOf(row.copy(host = host.copy(id = UUID.randomUUID())),
            row.copy(host = host.copy(endpoint = host.endpoint.copy(port = 2222))),
            row.copy(host = host.copy(keyId = UUID.randomUUID())), row.copy(host = host.copy(jumpHostId = UUID.randomUUID())),
            row.copy(registry = "other"), row.copy(cmuxSession = "other"),
            row.copy(cmuxWorkspace = row.cmuxWorkspace!!.copy(key = "replacement")))) assertFalse(selection.matches(changed))
        val numeric = cmux(false); val legacy = NativeSidebarSelection.Ssh(host, numeric.openTarget()!!)
        assertTrue(legacy.matches(numeric)); assertFalse(legacy.matches(numeric.copy(generation = "two")))
    }
    @Test fun tmuxAndShellRemainBoundToHostAndWorkspaceRatherThanCurrentPane() {
        val row = sshTmuxFeedRows(host, listOf(SshTmuxWorkspace(42, 2, 100, "Session", emptyList()))).single()
        val selection = NativeSidebarSelection.Ssh(host, SshWorkspaceTarget.Tmux(row.tmuxWorkspace!!.id, 3, 5))
        assertTrue(selection.matches(row)); assertFalse(selection.matches(row.copy(tmuxWorkspace = row.tmuxWorkspace.copy(created = 101))))
        val shell = row.copy(kind = SshWorkspaceKind.SHELL, targets = listOf(SshWorkspaceTarget.Shell("shell")))
        val selectedShell = NativeSidebarSelection.Ssh(host, shell.targets.single())
        assertTrue(selectedShell.matches(shell)); assertFalse(selectedShell.matches(shell.copy(targets = listOf(SshWorkspaceTarget.Shell("new")))))
        var value = input(emptyList(), listOf(row, row.copy(host = host.copy(id = UUID.randomUUID(), name = "Other"))))
        val base = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {})
        val bound = base.withSelection(selection)
        assertEquals(listOf("SSH"), bound.read(RoutedSidebarQuery())!!.rows.filter { it.selected }.map { it.computer })
        value = value.copy(ssh = emptyList()); assertTrue(bound.read(RoutedSidebarQuery())!!.rows.none { it.selected })
    }
}
