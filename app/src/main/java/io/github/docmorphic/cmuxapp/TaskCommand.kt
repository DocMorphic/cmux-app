package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.UUID

/** Mirrors cmux iOS's built-in task templates without placing the prompt in shell source. */
object TaskCommand {
    enum class Agent(val label: String, val command: String?) {
        CLAUDE("Claude", "claude -- \"\$CMUX_TASK_PROMPT\""),
        CODEX("Codex", "codex -- \"\$CMUX_TASK_PROMPT\""),
        OPENCODE("OpenCode", "opencode --prompt \"\$CMUX_TASK_PROMPT\""),
        SHELL("Shell", null)
    }

    fun parameters(agent: Agent, rawPrompt: String, directory: String?, operationId: UUID): JSONObject {
        val prompt = rawPrompt.trim()
        require(agent == Agent.SHELL || prompt.isNotEmpty()) { "Enter a task prompt" }
        val params = JSONObject().put("operation_id", operationId.toString())
        prompt.lineSequence().firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }?.let {
            params.put("title", it)
        }
        directory?.trim()?.takeIf { it.isNotEmpty() }?.let { params.put("working_directory", it) }
        agent.command?.let { command ->
            params.put("initial_command", command)
            params.put("initial_env", JSONObject().put("CMUX_TASK_PROMPT", prompt))
        }
        return params
    }
}
