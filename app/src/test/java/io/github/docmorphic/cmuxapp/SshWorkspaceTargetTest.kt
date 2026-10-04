package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshWorkspaceTargetTest {
    @Test fun savedTargetsRetainExactDurableIdentitiesAndNullableLegacyFields() {
        val targets = listOf(SshWorkspaceTarget.Shell("local-shell"), SshWorkspaceTarget.Tmux("tmux-host", 0, 9),
            SshWorkspaceTarget.CmuxWorkspace(SshCmuxWorkspaceSelection("desktop", "registry", "generation", 1, "key", "ws_1")),
            SshWorkspaceTarget.Cmux(SshCmuxSelection("owner λ", "registry", "generation", 1, "key", "ws_1", 4, "tab_1", "term_1", "host-term")),
            SshWorkspaceTarget.Cmux(SshCmuxSelection("owner", null, "generation", 1, null, null, 4, null, null, null)))
        targets.forEach { assertEquals(it, SshWorkspaceTarget.decode(it.encode())) }
    }
    @Test fun workspaceDestinationSurvivesPaneRemovalButRejectsReplacementOwners() {
        val workspace = SshCmuxWorkspace(1, "key", "resource", "Empty", false, emptyList())
        val tree = SshCmuxTree("generation", "registry", 1, listOf(workspace))
        val reference = SshCmuxWorkspaceSelection.capture("session", tree, workspace)
        assertEquals(workspace, reference.resolve("session", tree))
        val renumbered = workspace.copy(id = 50)
        assertEquals(renumbered, reference.resolve("session", tree.copy(generation = "new", workspaces = listOf(renumbered))))
        assertNull(reference.resolve("other", tree))
        assertNull(reference.resolve("session", tree.copy(registry = "other")))
        assertNull(reference.resolve("session", tree.copy(workspaces = listOf(workspace.copy(resource = "replacement")))))
        val numeric = reference.copy(key = null, resource = null)
        assertNull(numeric.resolve("session", tree.copy(generation = null)))
        assertNull(numeric.resolve("session", tree.copy(generation = "other")))
        val destination = SshWorkspaceTarget.CmuxWorkspace(reference)
        assertNull(destination.rememberedTab())
        assertTrue(sshBrowserWorkspace(destination, "Empty").terminals.isEmpty())
    }
    @Test fun malformedTargetsDoNotCoerceIdentityOrNumericIds() {
        val base = SshWorkspaceTarget.Tmux("workspace", 1, 2).encode()
        for (bad in listOf(-1, 1.5, "1", 4294967296L, JSONObject.NULL)) {
            assertNull(SshWorkspaceTarget.decode(JSONObject(base).put("pane", bad).toString()))
        }
        assertNull(SshWorkspaceTarget.decode(JSONObject(base).put("workspace", "").toString()))
        assertNull(SshWorkspaceTarget.decode(JSONObject(base).put("kind", "new-provider").toString()))
        assertNull(SshWorkspaceTarget.decode("not json"))
        assertNull(SshWorkspaceTarget.decode(" ".repeat(16385)))
    }
}
