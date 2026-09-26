package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.UUID

class TaskCommandTest {
    @Test fun promptIsDataAndCommandMatchesIosTemplate() {
        val prompt = "Fix 'quotes' and \"shell\"\nsecond line"
        val params = TaskCommand.parameters(TaskCommand.Agent.CODEX, prompt, "/tmp/repo", UUID(1, 2))
        assertEquals("codex -- \"\$CMUX_TASK_PROMPT\"", params.getString("initial_command"))
        assertEquals(prompt, params.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
        assertEquals("Fix 'quotes' and \"shell\"", params.getString("title"))
    }

    @Test fun shellStartsWithoutAgentCommand() {
        val params = TaskCommand.parameters(TaskCommand.Agent.SHELL, "", null, UUID(1, 2))
        assertFalse(params.has("initial_command"))
        assertFalse(params.has("initial_env"))
    }
}
