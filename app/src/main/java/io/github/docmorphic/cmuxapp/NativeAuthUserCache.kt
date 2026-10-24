package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Token-independent, encrypted display cache; it never supplies a team/connection proof. */
internal object NativeAuthUserCache {
    const val KEY="auth_cached_user"
    fun write(root:JSONObject,environment:String,user:NativeSignInUser) {
        root.put(KEY,JSONObject().put("version",1).put("environment",environment).put("user",user.id)
            .put("verified_for_login",root.opt("task_session") ?: JSONObject.NULL)
            .put("name",user.name ?: JSONObject.NULL).put("email",user.email ?: JSONObject.NULL)
            .put("selected",user.selectedTeam ?: JSONObject.NULL))
    }
    fun read(root:JSONObject?,environment:String):NativeSignInUser?=runCatching {
        val value=root?.optJSONObject(KEY) ?: root?.optJSONObject(NativeAccountProfileCache.KEY) ?: return null
        if(value.opt("version")!=1 || value.optString("environment")!=environment)return null
        val id=(value.opt("user") as? String)?.takeIf { it.isNotBlank() && it.length<=128 } ?: return null
        fun optional(key:String,limit:Int):String? {
            val raw=value.opt(key)
            if(raw==null || raw===JSONObject.NULL)return null
            require(raw is String && raw.length<=limit)
            return raw
        }
        NativeSignInUser(id,optional("name",512),optional("email",512),optional("selected",128))
    }.getOrNull()
}
