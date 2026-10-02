package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** Last verified display projection; never stores/restores connection authority. */
internal class NativeAccountProfileCache(private val load: () -> JSONObject?,
                                         private val update: ((JSONObject) -> Unit) -> Unit) {
    fun read(login: String, environment: String): NativeAccountTeamsState? = decode(load(), login, environment)

    fun save(login: String, environment: String, state: NativeAccountTeamsState) = update { root ->
        if (!owns(root, login)) return@update
        check(!state.cached && state.userId != null)
        val value = JSONObject().put("version", 1).put("login", login).put("environment", environment)
            .put("user", state.userId).put("selected", state.selectedTeamId ?: JSONObject.NULL)
            .put("name", state.displayName ?: JSONObject.NULL).put("email", state.email ?: JSONObject.NULL)
            .put("teams", JSONArray(state.teams.map { JSONObject().put("id", it.id).put("name", it.name) }))
        root.put(KEY, value)
    }

    fun remove(login: String) = update { root ->
        if (owns(root, login)) root.remove(KEY)
    }

    companion object {
        const val KEY = "account_profile_cache"
        private fun owns(root: JSONObject, login: String) = login.isNotBlank() &&
            root.optString("task_session") == login && root.optString("refresh_token").isNotBlank()

        fun prune(root: JSONObject) {
            val saved = root.optJSONObject(KEY) ?: return
            if (!owns(root, saved.optString("login"))) root.remove(KEY)
        }

        private fun decode(root: JSONObject?, login: String, environment: String): NativeAccountTeamsState? = runCatching {
            if (root == null || !owns(root, login)) return null
            val saved = root.optJSONObject(KEY) ?: return null
            if (saved.opt("version") != 1 || saved.opt("login") != login || saved.opt("environment") != environment) return null
            fun id(value: Any?): String = (value as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: error("Invalid cached identity")
            fun optional(key: String, limit: Int): String? {
                val value = saved.opt(key)
                if (value == null || value === JSONObject.NULL) return null
                require(value is String && value.length <= limit)
                return value.takeIf { it.isNotBlank() }
            }
            val user = id(saved.opt("user"))
            val items = saved.getJSONArray("teams"); require(items.length() <= 4096)
            val teams = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val name = item.opt("name"); require(name is String && name.length <= 512)
                NativeTeam(id(item.opt("id")), name)
            }
            require(teams.map { it.id }.distinct().size == teams.size)
            val selected = optional("selected", 128)
            require(if (teams.isEmpty()) selected == null else teams.any { it.id == selected })
            NativeAccountTeamsState(userId = user, teams = teams, selectedTeamId = selected,
                displayName = optional("name", 512), email = optional("email", 512), cached = true)
        }.getOrNull()
    }
}
