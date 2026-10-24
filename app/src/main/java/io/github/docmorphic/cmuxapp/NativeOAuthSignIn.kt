package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class NativeOAuthProvider(val wire: String) { APPLE("apple"), GOOGLE("google"), GITHUB("github") }

/** Memory-only PKCE material; never put the verifier in an Intent, Bundle, log or browser URL. */
internal class NativeOAuthChallenge(val state: String, val verifier: String,
    val path: String = "/" + BuildConfig.APPLICATION_ID) {
    val redirect = "$SCHEME://success$path?cmux_flow=$state"
    val errorRedirect = "$SCHEME://error$path?cmux_flow=$state"
    val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    override fun toString() = "NativeOAuthChallenge(redacted)"
    companion object {
        const val SCHEME = "stack-auth-mobile-oauth-url"
        fun create(): NativeOAuthChallenge {
            val random = SecureRandom(); val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            fun value(length: Int) = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
            return NativeOAuthChallenge(value(32), value(64))
        }
    }
}

internal object NativeOAuthCallback {
    private fun query(value: String): Pair<URI, Map<String, String>>? = runCatching {
        require(value.length <= 32_768 && value.none { it.code < 32 || it.code == 127 })
        val uri = URI(value)
        require(uri.scheme == NativeOAuthChallenge.SCHEME && uri.host in setOf("success", "error") &&
            uri.userInfo == null && uri.port == -1 && uri.rawFragment == null &&
            uri.rawPath.orEmpty().matches(Regex("/[A-Za-z][A-Za-z0-9_.]+")))
        val fields = linkedMapOf<String, String>()
        for (part in uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
            val pair = part.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            require(key !in fields)
            fields[key] = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        uri to fields
    }.getOrNull()

    fun flow(value: String): String? = query(value)?.second?.get("cmux_flow")?.takeIf { it.matches(Regex("[A-Za-z0-9]{32}")) }

    fun code(value: String, challenge: NativeOAuthChallenge): String {
        val (uri, fields) = query(value) ?: throw NativeSignInFailure(NativeSignInError.INVALID_CALLBACK)
        if (uri.rawPath != challenge.path || fields["cmux_flow"] != challenge.state || fields["state"]?.let { it != challenge.state } == true)
            throw NativeSignInFailure(NativeSignInError.INVALID_CALLBACK)
        val code = fields["code"]?.takeIf { it.isNotBlank() }
        val error = fields["error"]?.takeIf { it.isNotBlank() }
        if (code != null && error != null) throw NativeSignInFailure(NativeSignInError.INVALID_CALLBACK)
        if (error != null) {
            val safeCode = if (error == "access_denied" || error == "oauth_cancelled") "OAUTH_CANCELLED" else error
            throw NativeAccountRequestFailure(safeCode, false, "OAuth request failed")
        }
        if (uri.host != "success" || code == null || fields["state"] != challenge.state)
            throw NativeSignInFailure(NativeSignInError.INVALID_CALLBACK)
        return code
    }
}

internal fun nativeOAuthProviderUrl(location: String): String {
    val uri = runCatching { URI(location) }.getOrNull()
    if (location.length > 32_768 || location.any { it.code <= 32 || it.code == 127 } ||
        uri?.scheme?.lowercase(Locale.ROOT) != "https" || uri.userInfo != null || uri.host.isNullOrBlank() ||
        uri.rawAuthority.orEmpty().any { it in "\\@%" })
        throw NativeAccountRequestFailure("INVALID_URL", false, "Invalid OAuth provider URL")
    return location
}

/** Source Stack JSON authorization-location preflight and form-encoded PKCE exchange. */
internal class NativeOAuthClient(private val project: String, private val publishableKey: String,
    private val origin: HttpUrl = "https://api.stack-auth.com/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val capturedRefreshLeadMillis:Long=kotlin.random.Random.nextLong(60_000,120_001)) {
    fun authorizationUrl(provider: NativeOAuthProvider, challenge: NativeOAuthChallenge): HttpUrl =
        origin.newBuilder().encodedPath("/api/v1/auth/oauth/authorize/${provider.wire}")
            .addQueryParameter("client_id", project).addQueryParameter("client_secret", publishableKey)
            .addQueryParameter("redirect_uri", challenge.redirect).addQueryParameter("error_redirect_uri", challenge.errorRedirect)
            .addQueryParameter("scope", "legacy").addQueryParameter("state", challenge.state)
            .addQueryParameter("grant_type", "authorization_code").addQueryParameter("response_type", "code")
            .addQueryParameter("type", "authenticate").addQueryParameter("code_challenge", challenge.codeChallenge)
            .addQueryParameter("code_challenge_method", "S256").addQueryParameter("stack_response_mode", "json").build()

    suspend fun prepare(provider: NativeOAuthProvider, challenge: NativeOAuthChallenge): String {
        val result = request(Request.Builder().url(authorizationUrl(provider, challenge)).header("Accept", "application/json").build())
        return nativeOAuthProviderUrl(result.opt("location") as? String ?: throw NativeSignInFailure(NativeSignInError.BROWSER_SIGN_IN_FAILED))
    }
    suspend fun exchange(callback: String, challenge: NativeOAuthChallenge): Pair<String, String> {
        val code = NativeOAuthCallback.code(callback, challenge)
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", challenge.redirect).add("code_verifier", challenge.verifier)
            .add("client_id", project).add("client_secret", publishableKey).build()
        val result = request(Request.Builder().url(origin.newBuilder().encodedPath("/api/v1/auth/oauth/token").build())
            .header("x-hexclave-project-id", project).post(body).build(), successStatus = 200)
        val access = (result.opt("access_token") as? String)?.takeIf { it.isNotBlank() }
        val refresh = (result.opt("refresh_token") as? String)?.takeIf { it.isNotBlank() }
        if (access == null || refresh == null) throw NativeSignInFailure(NativeSignInError.BROWSER_SIGN_IN_FAILED)
        return access to refresh
    }
    val accountEnvironment: String get() = "${origin.newBuilder().encodedPath("/api/v1/").build()}#$project"
    suspend fun refreshAccess(refresh: String): String {
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh)
            .add("client_id", project).add("client_secret", publishableKey).build()
        val result = request(Request.Builder().url(origin.newBuilder().encodedPath("/api/v1/auth/oauth/token").build())
            .header("x-hexclave-project-id", project).header("x-hexclave-publishable-client-key", publishableKey)
            .header("x-hexclave-access-type", "client").post(body).build(), successStatus = 200,
            accountRequest = true, unauthorizedStatuses = setOf(400, 401), rawHttpStatus = true)
        return (result.opt("access_token") as? String)?.takeIf { it.isNotBlank() }
            ?: throw NativeSignInFailure(NativeSignInError.SERVER_ERROR)
    }
    suspend fun freshCapturedAccess(access:String?,refresh:String):String? {
        if(nativeCapturedAccessIsFresh(access,System.currentTimeMillis(),capturedRefreshLeadMillis)) return access
        return try { refreshAccess(refresh) } catch(_:Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive();null
        }
    }
    /** DELETE uses only this ephemeral pair; a rejected access token can refresh it once. */
    suspend fun revokeCapturedSession(captured:NativeCapturedSession) {
        if(captured.access==null && captured.refresh==null) return
        var access=captured.access;var refreshed=false;var networkAttempt=0
        val nonce=java.util.UUID.randomUUID().toString()
        while(true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val builder=Request.Builder().url(origin.newBuilder().encodedPath("/api/v1/auth/sessions/current").build())
                .delete().header("x-hexclave-project-id",project).header("x-hexclave-publishable-client-key",publishableKey)
                .header("x-hexclave-access-type","client").header("x-hexclave-client-version","cmux-android@0.2.0")
                .header("x-hexclave-override-error-status","true").header("x-hexclave-random-nonce",nonce)
            access?.let { builder.header("x-hexclave-access-token",it) }
            captured.refresh?.let { builder.header("x-hexclave-refresh-token",it) }
            try {
                request(builder.build(),accountRequest=true,unauthorizedStatuses=emptySet(),discardBody=true)
                return
            } catch(failure:NativeAccountRequestFailure) {
                if(failure.code!="invalid_access_token" || refreshed || captured.refresh==null) throw failure
                refreshed=true;access=refreshAccess(captured.refresh)
            } catch(failure:IOException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if(networkAttempt>=5) throw failure
                kotlinx.coroutines.delay((1L shl networkAttempt++)*1000)
            }
        }
    }

    private fun userRequest(access: String) = Request.Builder().url(origin.newBuilder().encodedPath("/api/v1/users/me").build())
        .header("x-stack-project-id", project).header("x-stack-publishable-client-key", publishableKey)
        .header("x-stack-access-type", "client").header("x-stack-client-version", "cmux-android@0.2.0")
        .header("x-stack-access-token", access).header("x-stack-override-error-status", "true")
        .header("x-stack-random-nonce", java.util.UUID.randomUUID().toString()).build()
    suspend fun currentUser(access: String): NativeSignInUser = NativeSignInUser.decode(request(userRequest(access), accountRequest = true))

    /** Match getUser(.throw): invalid JSON is nil, most errors are USER_NOT_SIGNED_IN. */
    suspend fun restoredCurrentUser(access: String, refreshRejectedAccess: suspend () -> String?): NativeSignInUser? {
        suspend fun read(token: String) = request(userRequest(token), accountRequest = true,
            unauthorizedStatuses = emptySet(), missingUserJson = true)
        return try {
            val payload = try { read(access) } catch (failure: NativeAccountRequestFailure) {
                if (failure.code != "invalid_access_token") throw failure
                read(refreshRejectedAccess() ?: throw NativeSessionUserUnavailable())
            }
            NativeSignInUser.decode(payload)
        } catch (_: NativeMissingUserPayload) { null }
        catch (failure: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (failure is NativeAccountRequestFailure && failure.code == "RATE_LIMITED") throw failure
            throw NativeSessionUserUnavailable()
        }
    }
    private class NativeMissingUserPayload : Exception()
    private suspend fun request(request: Request, successStatus: Int? = null, accountRequest: Boolean = false,
        unauthorizedStatuses: Set<Int> = setOf(401), missingUserJson: Boolean = false, discardBody:Boolean=false,
        rawHttpStatus:Boolean=false): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    val buffer = ByteArrayOutputStream()
                    response.body?.byteStream()?.use { stream ->
                        val bytes = ByteArray(8192)
                        while (true) {
                            val size = stream.read(bytes); if (size < 0) break
                            if (buffer.size() + size > 262_144) throw NativeSignInFailure(if (accountRequest) NativeSignInError.SERVER_ERROR else NativeSignInError.BROWSER_SIGN_IN_FAILED)
                            buffer.write(bytes, 0, size)
                        }
                    }
                    val raw = buffer.toString("UTF-8")
                    val status = if(rawHttpStatus) response.code else
                        (response.header("x-hexclave-actual-status") ?: response.header("x-stack-actual-status"))?.toIntOrNull() ?: response.code
                    val known = if(rawHttpStatus) null else response.header("x-hexclave-known-error") ?: response.header("x-stack-known-error")
                    if (status !in 200..299 || (successStatus != null && status != successStatus) || !known.isNullOrBlank()) {
                        val parsed = runCatching { JSONObject(raw) }.getOrNull()
                        if (accountRequest && status in unauthorizedStatuses) throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
                        val code = known ?: (parsed?.opt("error") as? String) ?: (parsed?.opt("code") as? String)
                            ?: if (status == 429) "RATE_LIMITED" else if (accountRequest) "SERVER_ERROR" else "OAUTH_ERROR"
                        throw NativeAccountRequestFailure(code, false, "OAuth request failed")
                    }
                    if(discardBody) JSONObject() else runCatching { JSONObject(raw) }.getOrElse { if (missingUserJson) throw NativeMissingUserPayload(); throw NativeSignInFailure(if (accountRequest) NativeSignInError.SERVER_ERROR else NativeSignInError.BROWSER_SIGN_IN_FAILED) }
                } }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
}

/** Own browser/exchange cancellation; the completion receipt guards credential writes. */
internal class NativeOAuthSignIn(private val prepare: suspend (NativeOAuthProvider, NativeOAuthChallenge) -> String,
    private val browser: suspend (String, String) -> String,
    private val exchange: suspend (String, NativeOAuthChallenge) -> Pair<String, String>,
    private val publish: (String, String) -> Unit,
    private val challenge: () -> NativeOAuthChallenge = NativeOAuthChallenge::create) {
    private val lock = Any()
    private var generation = 0L
    private val active = mutableMapOf<Long, Job>()
    suspend fun signIn(provider: NativeOAuthProvider, storeTokens: (String, String) -> Unit = publish): Unit = coroutineScope {
        val operation = currentCoroutineContext().job
        val admitted: Long
        val retired: List<Job>
        synchronized(lock) { admitted = ++generation; retired = active.values.toList(); active[admitted] = operation }
        retired.forEach { it.cancel() }
        try {
            val material = challenge()
            val location = prepare(provider, material)
            ensureActive()
            val callback = browser(location, material.state)
            ensureActive()
            val tokens = exchange(callback, material)
            ensureActive()
            withContext(Dispatchers.IO) {
                synchronized(lock) {
                    operation.ensureActive()
                    if (generation != admitted) throw CancellationException("Sign-in retired")
                }
                operation.ensureActive()
                storeTokens(tokens.first, tokens.second)
            }
        } finally { synchronized(lock) { active.remove(admitted) } }
    }
    fun clear() { clearIf { true } }
    fun clearIf(retire: () -> Boolean): Boolean {
        val retired: List<Job>
        val applied = synchronized(lock) {
            val result = retire()
            retired = if (result) { ++generation; active.values.toList() } else emptyList()
            result
        }
        retired.forEach { it.cancel() }
        return applied
    }
}
