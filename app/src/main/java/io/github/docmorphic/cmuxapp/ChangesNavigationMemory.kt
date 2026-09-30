package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject

internal class ChangesNavigationState(selected: String? = null, collapsed: Set<String> = emptySet()) {
    var selected by mutableStateOf(selected)
    var collapsed by mutableStateOf(collapsed)
}

/** Only navigation identifiers enter task state. A caller must admit the owner before binding. */
internal class ChangesNavigationMemory {
    private var login: String? = null
    private var scope: String? = null
    private var state = ChangesNavigationState()
    fun bind(login: String, key: NativeWorkspaceTabKey): ChangesNavigationState {
        if (this.login != login || scope != key.encoded) {
            this.login = login; scope = key.encoded; state = ChangesNavigationState()
        }
        return state
    }
    fun retainLogin(login: String?) { if (this.login != login) clear() }
    fun clear() { login = null; scope = null; state = ChangesNavigationState() }
    fun encode(): String {
        val login = login?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: return ""
        val scope = scope?.takeIf { it.length <= 16384 } ?: return ""
        val value = JSONObject().put("version", 1).put("login", login).put("scope", scope)
            .put("selected", state.selected?.takeIf(::validPath) ?: JSONObject.NULL)
        val folders = JSONArray(); value.put("collapsed", folders)
        // A host can return many long paths; keep this task-state contribution bounded.
        for (path in state.collapsed.filter(::validPath).sorted().take(32)) {
            folders.put(path)
            if (value.toString().length > MAX_ENCODED) { folders.remove(folders.length() - 1); break }
        }
        return value.toString().takeIf { it.length <= MAX_ENCODED }.orEmpty()
    }
    companion object {
        private const val MAX_ENCODED = 49_152
        private fun validPath(path: String) = path.isNotEmpty() && path.length <= 4096 && '\u0000' !in path
        fun decode(encoded: String): ChangesNavigationMemory = runCatching {
            require(encoded.length <= MAX_ENCODED)
            val value = JSONObject(encoded)
            require(value.opt("version") == 1)
            val login = value.opt("login") as? String ?: error("Invalid owner")
            val scope = value.opt("scope") as? String ?: error("Invalid scope")
            require(login.isNotBlank() && login.length <= 4096 && scope.isNotBlank() && scope.length <= 16384)
            val selected = if (value.isNull("selected")) null else value.get("selected").let {
                require(it is String && validPath(it)); it as String
            }
            val folders = value.getJSONArray("collapsed")
            require(folders.length() <= 32)
            val collapsed = (0 until folders.length()).map { index ->
                val path = folders.get(index)
                require(path is String && validPath(path)); path as String
            }.toSet()
            ChangesNavigationMemory().also {
                it.login = login; it.scope = scope; it.state = ChangesNavigationState(selected, collapsed)
            }
        }.getOrElse { ChangesNavigationMemory() }
        val saver = Saver<ChangesNavigationMemory, String>(save = { it.encode() }, restore = ::decode)
    }
}
