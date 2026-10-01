package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SshTmuxInventoryTest {
    @Test fun inventoryGroupsStableSessionsAndKeepsWindowColons() {
        val rows = SshTmuxInventory.parse("12:\$0:100:@2:1:%3:1:40:24:2:work:build: logs\n12:\$0:100:@2:1:%2:0:39:24:2:work:build: logs\n12:\$1:101:@2:1:%2:0:39:24:2:work-cmux-ios-a:copy\n")
        assertEquals(1, rows.size); assertEquals("12:0:100", rows[0].id); assertEquals("\$0", rows[0].target)
        assertEquals(listOf(2, 3), rows[0].panes.map { it.id }); assertEquals("build: logs", rows[0].panes[0].windowName)
    }
    @Test fun malformedOrRepeatedPaneCannotBecomeAnActionTarget() {
        val row = "12:\$0:100:@2:1:%3:1:40:24:2:work:build"
        for (invalid in listOf("garbage", row.replace("%3", "%-1"), row + "\n" + row))
            assertThrows(Exception::class.java) { SshTmuxInventory.parse(invalid) }
    }
    @Test fun mutationIncludesServerAndCreationGuardAndQuotesEveryShellWord() {
        val workspace = SshTmuxWorkspace(123, 8, 1000, "a ' quoted", emptyList())
        val command = SshTmuxInventory.guarded("/bin/tmux", workspace, "kill-session -t ${SshTmuxEncoding.quote(workspace.target)}")
        assertTrue(command.contains("'\$8'")); assertTrue(command.contains("#{==:#{pid},123}")); assertTrue(command.contains("#{==:#{session_created},1000}"))
        assertTrue(command.contains("CMUX_STALE_TARGET")); assertFalse(command.contains(workspace.name))
        assertTrue(SshTmuxInventory.supportsShellEnvironment("tmux 3.7c"))
        assertTrue(SshTmuxInventory.supportsShellEnvironment("tmux next-3.8"))
        assertFalse(SshTmuxInventory.supportsShellEnvironment("tmux 3.1c"))
        assertFalse(SshTmuxInventory.supportsShellEnvironment("unknown"))
    }
}
