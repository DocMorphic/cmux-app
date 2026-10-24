package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Token-independent, encrypted display cache; it never supplies a team/connection proof. */
internal object NativeAuthUserCache {
    const val KEY="auth_cached_user"
    fun write(root:JSONObject,environment:String,user:NativeSignInUser) {
        root.put(KEY,JSONObject().put("version",1).put("environment",environment).put("user",user.id)
            .put("verified_for_login",root.opt("task_session") ?: JSONObject.NULL)
            .put("name",user.name ?: JSONObject.NULL).put("email",user.email ?: JSONObject.NULL)
            .put("selected",user.selectedTeam ?: JSONObject.NULL).put("profile_image_url",user.profileImageUrl ?: JSONObject.NULL)
            .put("demonstration_content_enabled",user.demonstrationContentEnabled))
    }
    fun read(root:JSONObject?,environment:String):NativeSignInUser?=runCatching {
        val value=root?.optJSONObject(KEY) ?: root?.optJSONObject(NativeAccountProfileCache.KEY) ?: return null
        if(value.opt("version")!=1 || value.optString("environment")!=environment)return null
        val id=value.opt("user") as? String ?: return null
        fun optional(key:String):String? {
            val raw=value.opt(key)
            if(raw==null || raw===JSONObject.NULL)return null
            require(raw is String)
            return raw
        }
        val demo=value.opt("demonstration_content_enabled")
        require(demo==null || demo===JSONObject.NULL || demo is Boolean)
        NativeSignInUser(id,optional("name"),optional("email"),optional("selected"),optional("profile_image_url"),demo==true)
    }.getOrNull()
}
