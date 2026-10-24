package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAuthUserCacheTest {
    private val user = NativeSignInUser("user", "Saved Person", "saved@example.test", "team")
    @Test fun presentationSurvivesMissingTokensAndChangingLoginWithoutBecomingMembershipProof() {
        val root = JSONObject().put("task_session", "old-login").put("refresh_token", "refresh")
        NativeAccountProfileCache.writeVerifiedUser(root, "old-login", "environment", user)
        root.remove("task_session"); root.remove("refresh_token")
        NativeAccountProfileCache.prune(root)
        assertFalse(root.has(NativeAccountProfileCache.KEY))
        assertEquals(user, NativeAuthUserCache.read(root, "environment"))
        root.put("task_session", "replacement").put("refresh_token", "new-refresh")
        assertEquals(user, NativeAuthUserCache.read(root, "environment"))
        assertEquals("old-login", root.getJSONObject(NativeAuthUserCache.KEY).getString("verified_for_login"))
        assertNull(NativeAuthUserCache.read(root, "other-environment"))
    }
    @Test fun legacyProfileCanPrimePresentationButMalformedOrForeignCachesCannot() {
        val root = JSONObject().put("task_session", "login").put("refresh_token", "refresh")
        NativeAccountProfileCache.writeVerifiedUser(root, "login", "environment", user)
        root.remove(NativeAuthUserCache.KEY)
        assertEquals(user.copy(selectedTeam = null), NativeAuthUserCache.read(root, "environment"))
        NativeAuthUserCache.write(root, "environment", user)
        val original = root.getJSONObject(NativeAuthUserCache.KEY).toString()
        for ((key, value) in listOf("version" to 2, "user" to 9, "name" to JSONObject(),
            "profile_image_url" to false, "demonstration_content_enabled" to 1)) {
            root.put(NativeAuthUserCache.KEY, JSONObject(original).put(key, value))
            assertNull("$key must not produce a cached identity", NativeAuthUserCache.read(root, "environment"))
        }
    }
}
