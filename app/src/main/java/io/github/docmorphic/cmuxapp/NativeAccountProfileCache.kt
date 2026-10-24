package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** Last verified display projection; never stores/restores connection authority. */
internal class NativeAccountProfileCache(private val load: () -> JSONObject?,
                                         private val update: ((JSONObject) -> Unit) -> Unit) {
    fun read(login: String, environment: String): NativeAccountTeamsState? = decode(load(), login, environment)
    /** Configuration selection happens before exposing any remembered team. Reads stay display-only. */
    fun rememberedSelection(environment:String):String? {
        val before=load()
        if(!NativeTeamSelectionStore.hasForeignEnvironment(before,environment))return NativeTeamSelectionStore.read(before,environment)
        var selected:String?=null
        update {root->
            if(NativeTeamSelectionStore.hasForeignEnvironment(root,environment))NativeTeamSelectionStore.write(root,environment,null)
            selected=NativeTeamSelectionStore.read(root,environment)
        }
        return selected
    }

    fun save(login: String, environment: String, state: NativeAccountTeamsState) = update { root ->
        if (!owns(root, login)) return@update
        check(!state.cached && state.userId != null)
        NativeTeamSelectionStore.write(root,environment,state.selectedTeamId)
        val value = JSONObject().put("version", 1).put("login", login).put("environment", environment)
            .put("user", state.userId).put("selected", NativeTeamSelectionStore.normalized(state.selectedTeamId) ?: JSONObject.NULL)
            .put("name", state.displayName ?: JSONObject.NULL).put("email", state.email ?: JSONObject.NULL)
            .put("teams", JSONArray(state.teams.map { JSONObject().put("id", it.id).put("name", it.name) }))
        root.put(KEY, value)
    }

    fun remove(login: String) = update { root ->
        if (owns(root, login)) {NativeTeamSelectionStore.migrate(root);root.remove(KEY)}
    }
    /** An explicit selection is separate from cached membership and never verifies saved rows. */
    fun updateSelection(login:String,environment:String,user:String,selected:String?)=update {root->
        if(!owns(root,login))return@update
        val saved=root.optJSONObject(KEY)
        if(saved!=null && (saved.opt("version")!=1 || saved.opt("login")!=login || saved.opt("environment")!=environment || saved.opt("user")!=user))return@update
        NativeTeamSelectionStore.write(root,environment,selected)
        saved?.put("selected",NativeTeamSelectionStore.normalized(selected) ?: JSONObject.NULL)
    }

    companion object {
        const val KEY = "account_profile_cache"
        /** User publication precedes the fallible membership phase; empty teams grant no authority. */
        fun writeVerifiedUser(root: JSONObject, login: String, environment: String, user: NativeSignInUser) {
            check(owns(root, login))
            NativeTeamSelectionStore.migrate(root)
            val selected=NativeTeamSelectionStore.read(root,environment)
            NativeAuthUserCache.write(root, environment, user)
            root.put(KEY, JSONObject().put("version", 1).put("login", login).put("environment", environment)
                .put("user", user.id).put("selected", selected ?: JSONObject.NULL)
                .put("name", user.name ?: JSONObject.NULL).put("email", user.email ?: JSONObject.NULL)
                .put("teams", JSONArray()))
        }
        // Interactive completion stages this cache before removing its pending receipt.
        private fun owns(root: JSONObject, login: String) = nativeSessionOwns(root,login) ||
            login.isNotBlank() && root.opt("task_session")==login && (root.opt("refresh_token") as? String)?.isNotBlank()==true

        fun prune(root: JSONObject) {
            NativeTeamSelectionStore.migrate(root)
            val saved = root.optJSONObject(KEY) ?: return
            if (!owns(root, saved.optString("login"))) root.remove(KEY)
        }

        private fun decode(root: JSONObject?, login: String, environment: String): NativeAccountTeamsState? = runCatching {
            if (root == null || !owns(root, login)) return null
            val saved = root.optJSONObject(KEY) ?: return null
            if (saved.opt("version") != 1 || saved.opt("login") != login || saved.opt("environment") != environment) return null
            fun id(value: Any?): String = value as? String
                ?: error("Invalid cached identity")
            fun optional(key: String): String? {
                val value = saved.opt(key)
                if (value == null || value === JSONObject.NULL) return null
                require(value is String)
                return value
            }
            val user = id(saved.opt("user"))
            val items = saved.getJSONArray("teams")
            val teams = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val name = item.opt("name"); require(name is String)
                NativeTeam(id(item.opt("id")), name)
            }
            val selected = NativeTeamSelectionStore.read(root,environment)
            // The coordinator persists explicit selection independently; its effective
            // member falls back separately. Cached rows still grant no connection proof.
            NativeAccountTeamsState(userId = user, teams = teams, selectedTeamId = selected,
                displayName = optional("name"), email = optional("email"), cached = true)
        }.getOrNull()
    }
}
