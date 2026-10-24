package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Explicit local logout retires account authority while retaining saved Mac records. */
internal object NativeLocalLogout {
    const val PENDING = "auth_logout_pending"
    fun retire(root: JSONObject) {
        root.put("access_token", "").put("refresh_token", "")
        for (key in listOf("task_session", "task_drafts", NativeSignInCompletion.PENDING,
            NativeAuthUserCache.KEY, NativeAccountProfileCache.KEY)) root.remove(key)
    }
}
