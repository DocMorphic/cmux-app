package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SshWorkspaceLastTabsTest {
    private val host = SshHostRecord(name = "SSH computer", endpoint = SshEndpoint("box.test", 22, "user"))
    private fun tree() = SshCmuxInventory.parse(JSONObject("""{
      "generation":"boot-a","registry_id":"registry-a","workspaces":[{
        "id":1,"key":"workspace-a","resource_id":"ws_a","name":"Project","screens":[{
          "id":2,"panes":[{"id":3,"tabs":[
            {"surface":4,"kind":"pty","tab_resource_id":"tab_a","terminal_resource_id":"term_a"},
            {"surface":5,"kind":"pty","tab_resource_id":"tab_b","terminal_resource_id":"term_b"},
            {"surface":6,"kind":"browser","tab_resource_id":"tab_c","content_resource_id":"browser_a"}
          ]}]}]}]}"""))
    private fun row(tree: SshCmuxTree = tree()) = sshCmuxFeedRows(host, "desktop", tree()).single()

    @Test fun encryptedStoreMapRoundTripRetainsSecondPaneWithoutBumpingUnchangedPreference() {
        val row = row(); val target = row.targets[1]; val key = sshWorkspaceTabKey("login", host, target)
        val tabs = NativeWorkspaceLastTabs()
        assertTrue(tabs.set(key, target.rememberedTab()!!))
        val persisted = tabs.json().toString()
        val cold = NativeWorkspaceLastTabs(JSONObject(persisted))
        assertEquals(target, row.reopenTarget(cold.get(key)))
        assertFalse(cold.set(key, target.rememberedTab()!!))
        assertEquals(persisted, cold.json().toString())
    }
    @Test fun stableResourcesSurviveRenumberingAndGenerationChangeForTerminalsAndBrowsers() {
        val original = row()
        val old = tree(); val workspace = old.workspaces.single()
        val changed = old.copy(generation = "boot-b", workspaces = listOf(workspace.copy(id = 90,
            screens = workspace.screens.map { screen -> screen.copy(id = 91, panes = screen.panes.map { pane ->
                pane.copy(id = 92, tabs = pane.tabs.map { it.copy(surface = it.surface + 100, screen = 91, pane = 92) })
            }) })))
        val fresh = row(changed)
        for (index in listOf(1, 2)) {
            assertEquals(fresh.targets[index], fresh.reopenTarget(original.targets[index].rememberedTab()))
            assertEquals(sshWorkspaceTabKey("login", host, original.targets[index]), sshWorkspaceTabKey("login", host, fresh.targets[index]))
        }
    }
    @Test fun replacedRegistrySessionOrTabFallsBackToFirstLivePane() {
        val row = row(); val saved = row.targets[1].rememberedTab()
        assertEquals(row.targets.first(), row.copy(registry = "replacement").reopenTarget(saved))
        assertEquals(row.targets.first(), row.copy(cmuxSession = "replacement").reopenTarget(saved))
        val workspace = row.cmuxWorkspace!!
        val changed = workspace.copy(screens = workspace.screens.map { s -> s.copy(panes = s.panes.map { p ->
            p.copy(tabs = p.tabs.map { if (it.surface == 5) it.copy(terminal = "replaced", resource = "new-tab") else it })
        }) })
        assertEquals(row.targets.first(), row.copy(cmuxWorkspace = changed).reopenTarget(saved))
    }
    @Test fun numericOnlySelectionCannotCrossAnUnknownOrChangedGeneration() {
        val target = SshWorkspaceTarget.Cmux(SshCmuxSelection("desktop", "registry-a", "boot-a", 1, null, null, 5, null, null, null))
        assertEquals(row().targets[1], row().reopenTarget(target.rememberedTab()))
        assertEquals(row().targets.first(), row().copy(generation = "boot-b").reopenTarget(target.rememberedTab()))
        assertEquals(row().targets.first(), row().copy(generation = null).reopenTarget(target.rememberedTab()))
    }
    @Test fun malformedWrongKindAndMissingPanePreferencesFallBackWithoutCreatingAnything() {
        val row = row()
        assertEquals(row.targets.first(), row.reopenTarget(NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, "bad json")))
        assertEquals(row.targets.first(), row.reopenTarget(NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, row.targets.last().encode())))
        assertEquals(row.targets.first(), row.reopenTarget(NativeWorkspaceTab.LocalBrowser))
        assertEquals(row.targets.first(), row.reopenTarget(SshWorkspaceTarget.Shell("missing").rememberedTab()))
        assertNull(row.copy(targets = emptyList(), cmuxWorkspace = null).reopenTarget(row.targets[1].rememberedTab()))
    }
    @Test fun accountEndpointKeyJumpAndHostAreIsolatedButRenameAndPauseAreNot() {
        val target = row().targets[1]
        val key = sshWorkspaceTabKey("login", host, target)
        assertEquals(key, sshWorkspaceTabKey("login", host.copy(name = "Renamed", autoConnectPaused = true), target))
        assertNotEquals(key, sshWorkspaceTabKey("new-login", host, target))
        listOf(host.copy(id = UUID.randomUUID()), host.copy(endpoint = host.endpoint.copy(port = 2222)),
            host.copy(endpoint = host.endpoint.copy(username = "other")), host.copy(keyId = UUID.randomUUID()),
            host.copy(jumpHostId = UUID.randomUUID())).forEach { assertNotEquals(key, sshWorkspaceTabKey("login", it, target)) }
        assertNotEquals(key, sshWorkspaceTabKey("login", host, (target as SshWorkspaceTarget.Cmux).copy(selection = target.selection.copy(registry = "new"))))
    }
    @Test fun tmuxPanePreferenceCannotCrossSessionCreationAndShellCannotCrossItsInstance() {
        val a = SshWorkspaceTarget.Tmux("server:session:created-a", 1, 2)
        val b = a.copy(pane = 3)
        val row = SshFeedRow(host, "row", "tmux", SshWorkspaceKind.TMUX, listOf(a, b))
        assertEquals(b, row.reopenTarget(b.rememberedTab()))
        assertEquals(a, row.reopenTarget(b.copy(workspace = "server:session:created-b").rememberedTab()))
        val shell = SshWorkspaceTarget.Shell("one")
        assertEquals(shell, row.copy(targets = listOf(shell)).reopenTarget(SshWorkspaceTarget.Shell("two").rememberedTab()))
        assertNotEquals(sshWorkspaceTabKey("login", host, a), sshWorkspaceTabKey("login", host, shell))
    }
    @Test fun oversizedRemoteIdentifiersDoNotCrashPreferenceWrites() {
        assertNull(SshWorkspaceTarget.Shell("x".repeat(4096)).rememberedTab())
    }
}
