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

    fun parameters(agent: Agent, rawPrompt: String, directory: String?, operationId: UUID,
        modelId: String? = null, effortId: String? = null, workspaceName: String = "", groupId: String? = null): JSONObject =
        parameters(agent.command, rawPrompt, directory, operationId, modelId, effortId, workspaceName, groupId)

    fun parameters(command: String?, rawPrompt: String, directory: String?, operationId: UUID,
        modelId: String? = null, effortId: String? = null, workspaceName: String = "", groupId: String? = null): JSONObject {
        val prompt = rawPrompt.trim()
        require(command.isNullOrBlank() || prompt.isNotEmpty()) { "Enter a task prompt" }
        val params = JSONObject().put("operation_id", operationId.toString())
        val title = workspaceName.trim().takeIf { it.isNotEmpty() }
            ?: prompt.lineSequence().firstOrNull()?.let { taskGraphemes(it).take(60).joinToString("") }?.takeIf { it.isNotBlank() }
        title?.let { params.put("title", it) }
        groupId?.let { require(it.isNotBlank()); params.put("group_id", it) }
        directory?.trim()?.takeIf { it.isNotEmpty() }?.let { params.put("working_directory", it) }
        command?.takeIf { it.isNotBlank() }?.let { command ->
            params.put("initial_command", TaskAgentCommand.detect(command)?.apply(command, modelId, effortId) ?: command)
            params.put("initial_env", JSONObject().put("CMUX_TASK_PROMPT", prompt))
        }
        return params
    }
}
