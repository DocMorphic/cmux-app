package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.Locale

/** Stack verifies the lowercased six-character prefix followed by its opaque nonce.
 * Matches CMUXAuthMagicLinkCode at upstream 4c5272e9. Pending challenges stay in memory.
 */
internal class NativeEmailSignIn(
    private val request: suspend (String, JSONObject) -> JSONObject,
    private val publish: (accessToken: String, refreshToken: String) -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var nonce: String? = null

    suspend fun sendCode(email: String) {
        val normalized = email.trim()
        if (!normalized.contains('@') || normalized.length > 254) throw NativeSignInFailure(NativeSignInError.INVALID_EMAIL)
        val attempt = synchronized(lock) { nonce = null; ++generation }
        val result = request("/auth/otp/send-sign-in-code", JSONObject()
            .put("email", normalized).put("callback_url", "https://cmux.com/auth/callback"))
        currentCoroutineContext().ensureActive()
        val challenge = (result.opt("nonce") as? String)?.takeIf { it.isNotBlank() }
            ?: error("Could not prepare sign-in. Request a new email code.")
        synchronized(lock) {
            check(generation == attempt) { "This sign-in request was replaced. Request a new email code." }
            nonce = challenge
        }
    }

    suspend fun signIn(code: String, storeTokens: (String, String) -> Unit = publish) {
        val prefix = code.trim().lowercase(Locale.ROOT)
        if (!prefix.matches(Regex("[a-z0-9]{6}"))) throw NativeSignInFailure(NativeSignInError.INVALID_CODE_SHORT)
        val (attempt, challenge) = synchronized(lock) {
            generation to (nonce ?: throw NativeSignInFailure(NativeSignInError.INVALID_CODE_SHORT))
        }
        val result = request("/auth/otp/sign-in", JSONObject().put("code", prefix + challenge))
        currentCoroutineContext().ensureActive()
        val access = (result.opt("access_token") as? String)?.takeIf { it.isNotBlank() }
            ?: error("Sign-in returned no session. Request a new email code.")
        val refresh = (result.opt("refresh_token") as? String)?.takeIf { it.isNotBlank() }
            ?: error("Sign-in returned no session. Request a new email code.")
        synchronized(lock) {
            check(generation == attempt && nonce == challenge) { "This sign-in request was replaced. Request a new email code." }
            storeTokens(access, refresh)
            nonce = null
            generation++
        }
    }

    fun clear() = synchronized(lock) { nonce = null; generation++ }

    /** Same lock order as signIn's publish: challenge lock, then credential transaction. */
    fun clearIf(retireSession: () -> Boolean): Boolean = synchronized(lock) {
        retireSession().also { if (it) { nonce = null; generation++ } }
    }
}
