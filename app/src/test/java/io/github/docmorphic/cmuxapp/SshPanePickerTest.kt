package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshPanePickerTest {
    @Test fun cmuxGroupsPanesKeepsBrowsersSeparateAndTargetsScreensActivePane() {
        val tree = SshCmuxInventory.parse(JSONObject("""{"generation":"g","registry_id":"r","workspaces":[
          {"id":1,"key":"w","name":"Work","screens":[{"id":2,"active_pane":5,"panes":[
            {"id":3,"tabs":[{"surface":4,"kind":"pty","title":"First"}]},
            {"id":5,"tabs":[{"surface":6,"kind":"pty","title":"Second"},{"surface":7,"kind":"browser","title":"Web"},
              {"surface":8,"kind":"pty","title":"Ended","dead":true}]}]},
            {"id":9,"panes":[{"id":10,"tabs":[{"surface":11,"kind":"browser","title":"Only browser"}]}]}]}]}"""))
        val layout = sshCmuxPicker("session", tree, tree.workspaces.single())
        val section = layout.sections.single()
        assertEquals("New Screen", layout.newTerminalTitle)
        assertEquals("Screen 1", section.title); assertEquals(5, section.targetPane)
        assertEquals(listOf("First", "Second"), section.rows.map { it.title })
        assertEquals(listOf("Pane 1", "Pane 2"), section.rows.map { it.paneLabel })
        assertEquals(listOf(false, true), section.rows.map { it.startsPane })
        assertEquals(SshPaneAction.entries, section.actions)
        assertEquals(listOf("Web", "Only browser"), layout.browsers.map { it.title })
        val target = section.rows.last().target as SshWorkspaceTarget.Cmux
        assertEquals(6, target.selection.resolve("session", tree)?.second?.surface)
        assertNull(target.selection.resolve("session", tree.copy(registry = "replacement")))
    }
    @Test fun tmuxSortsByWindowAndPaneWhileKeepingDurableIds() {
        val rows = listOf(SshTmuxPaneRow(20, 7, 2, "Later", 0, 80, 24, 1),
            SshTmuxPaneRow(11, 6, 1, "First", 1, 80, 24, 2), SshTmuxPaneRow(10, 6, 1, "First", 0, 80, 24, 2))
        val workspace = SshTmuxWorkspace(1, 2, 3, "Work", rows)
        val layout = sshTmuxPicker(workspace)
        assertEquals("New Window", layout.newTerminalTitle)
        assertEquals(listOf("1: First", "2: Later"), layout.sections.map { it.title })
        assertEquals(listOf("Pane 1", "Pane 2"), layout.sections.first().rows.map { it.title })
        assertEquals(listOf(false, true), layout.sections.first().rows.map { it.startsPane })
        assertEquals(SshWorkspaceTarget.Tmux(workspace.id, 7, 20), layout.sections.last().rows.single().target)
        assertTrue(layout.sections.all { it.actions == listOf(SshPaneAction.SPLIT_RIGHT, SshPaneAction.SPLIT_DOWN) })
    }
}
