package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Saved navigation data only. Account-login and host scope are supplied by
 * the enclosing saved-state key; no credentials or terminal bytes are saved. */
internal sealed interface SshWorkspaceTarget {
    data class Cmux(val selection: SshCmuxSelection) : SshWorkspaceTarget
    data class Tmux(val workspace: String, val window: Int, val pane: Int) : SshWorkspaceTarget
    data class Shell(val id: String) : SshWorkspaceTarget
    fun encode(): String = JSONObject().also { value -> when (this) {
        is Shell -> value.put("kind", "shell").put("id", id)
        is Tmux -> value.put("kind", "tmux").put("workspace", workspace).put("window", window).put("pane", pane)
        is Cmux -> selection.let { ref -> value.put("kind", "cmux").put("session", ref.session).put("registry", ref.registry)
            .put("generation", ref.generation).put("workspace", ref.workspace).put("workspaceKey", ref.workspaceKey)
            .put("workspaceResource", ref.workspaceResource).put("surface", ref.surface).put("tabResource", ref.tabResource)
            .put("terminalResource", ref.terminalResource).put("terminalId", ref.terminalId) }
    } }.toString()
    companion object {
        fun decode(text: String): SshWorkspaceTarget? = runCatching {
            require(text.length <= 16384)
            val value = JSONObject(text)
            fun string(key: String) = (value.get(key) as String).also { require(it.isNotEmpty()) }
            fun optional(key: String) = if (value.isNull(key)) null else string(key)
            fun id(key: String): Int {
                val raw = value.get(key); require(raw is Int || raw is Long)
                return (raw as Number).toLong().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            }
            when (string("kind")) {
                "shell" -> Shell(string("id"))
                "tmux" -> Tmux(string("workspace"), id("window"), id("pane"))
                "cmux" -> Cmux(SshCmuxSelection(string("session"), optional("registry"), optional("generation"),
                    id("workspace"), optional("workspaceKey"), optional("workspaceResource"), id("surface"),
                    optional("tabResource"), optional("terminalResource"), optional("terminalId")))
                else -> error("Unknown saved SSH workspace type")
            }
        }.getOrNull()
    }
}
