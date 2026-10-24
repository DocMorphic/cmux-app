package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Local account incarnation, never a substitute for refresh credentials or online membership. */
internal fun nativeSessionOwns(root:JSONObject?,login:String?):Boolean {
    if(root==null || login.isNullOrBlank() || root.opt("task_session")!=login || NativeSignInCompletion.isPending(root))return false
    if((root.opt("refresh_token") as? String)?.isNotBlank()==true)return true
    if((root.opt("access_token") as? String).isNullOrBlank())return false
    val user=root.optJSONObject(NativeAuthUserCache.KEY) ?: return false
    return user.opt("version")==1 && user.opt("verified_for_login")==login && user.opt("user") is String &&
        (user.opt("environment") as? String)?.isNotBlank()==true
}
