package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeLocalLogoutTest {
    @Test fun localRetirementRemovesOnlyAccountAuthorityAndCachedIdentity() {
        val paired=JSONArray().put(JSONObject().put("name","Saved Mac").put("code","fixture-pairing"))
        val root=JSONObject().put("access_token","old-access").put("refresh_token","old-refresh")
            .put("task_session","old-login").put("task_drafts",JSONObject()).put(NativeSignInCompletion.PENDING,"old-login")
            .put(NativeAuthUserCache.KEY,JSONObject()).put(NativeAccountProfileCache.KEY,JSONObject())
            .put(NativeTeamSelectionStore.KEY,JSONObject().put("selected","remembered"))
            .put("pairings",paired).put("installation_marker","retain")
        NativeLocalLogout.retire(root)
        assertEquals("",root.getString("access_token"));assertEquals("",root.getString("refresh_token"))
        for(key in listOf("task_session","task_drafts",NativeSignInCompletion.PENDING,NativeAuthUserCache.KEY,NativeAccountProfileCache.KEY,NativeTeamSelectionStore.KEY))
            assertFalse(key,root.has(key))
        assertEquals(paired.toString(),root.getJSONArray("pairings").toString())
        assertEquals("retain",root.getString("installation_marker"))
        val retired=root.toString();NativeLocalLogout.retire(root);assertEquals(retired,root.toString())
    }
}
