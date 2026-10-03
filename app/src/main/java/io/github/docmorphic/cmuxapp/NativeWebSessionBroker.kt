package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Sensitive values intentionally have no generated data-class toString/copy/component methods. */
internal class NativeWebSessionSnapshot(val login: String, val accessToken: String, val refreshToken: String) {
    override fun toString() = "NativeWebSessionSnapshot(redacted)"
}

/** The native-to-web handoff is an out-of-band cookie exchange, never a WebView navigation. */
internal class NativeWebSessionBroker(
    private val policy: WhatsNewWebPolicy, private val projectId: String,
    private val snapshot: suspend () -> NativeWebSessionSnapshot?,
    private val isCurrent: (NativeWebSessionSnapshot) -> Boolean,
    timeoutMillis: Long = 30_000
) {
    // Deliberately independent of account clients: no shared cookies, cache, interceptors or automatic retries.
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()

    suspend fun cookies(destination: String): List<Cookie> {
        if (!policy.allows(destination)) return emptyList()
        val target = destination.toHttpUrlOrNull() ?: return emptyList()
        if (target.encodedPath == HANDOFF_PATH) return emptyList()
        try {
            val session = snapshot() ?: return emptyList()
            currentCoroutineContext().ensureActive()
            if (session.accessToken.isBlank() || session.refreshToken.isBlank() || !isCurrent(session)) return emptyList()
            val endpoint = target.newBuilder().encodedPath(HANDOFF_PATH).query(null).fragment(null).build()
            val after = target.encodedPath + (target.encodedQuery?.let { "?$it" } ?: "")
            val body = FormBody.Builder().add("access_token", session.accessToken)
                .add("refresh_token", session.refreshToken).add("after", after).build()
            val request = Request.Builder().url(endpoint).post(body)
                .header("X-Cmux-App-Session-Handoff", "1").header("X-Cmux-App-Session-Response", "cookies").build()
            val response = execute(request)
            response.use {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(session)) return emptyList()
                return acceptedCookies(it, target, projectId)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); return emptyList() }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                // A response can race cancellation after dispatch; never leave its socket/body open.
                continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
            }
        })
    }

    companion object {
        const val HANDOFF_PATH = "/handler/app-session-handoff"
        internal fun acceptedCookies(response: Response, target: HttpUrl, projectId: String): List<Cookie> {
            if (response.code != 204 || response.header("X-Cmux-App-Session-Handoff") != "ready" ||
                !sameOrigin(response.request.url, target)) return emptyList()
            val cookies = Cookie.parseAll(target, response.headers).filter { cookie ->
                cookie.domain == target.host && stackCookie(cookie.name, projectId) && cookie.expiresAt > System.currentTimeMillis()
            }
            val names = cookies.map { it.name }.toSet()
            val hasAccess = PREFIXES.any { prefix -> ACCESS.any { "$prefix$it" in names } }
            val hasRefresh = PREFIXES.any { prefix -> REFRESH.any { base ->
                val name = "$prefix$base-$projectId"
                names.any { it == name || it.startsWith("$name--") }
            } }
            return if (hasAccess && hasRefresh) cookies else emptyList()
        }
        private val PREFIXES = listOf("", "__Host-", "__Secure-")
        private val ACCESS = listOf("stack-access", "hexclave-access")
        private val REFRESH = listOf("stack-refresh", "hexclave-refresh")
        private fun stackCookie(name: String, projectId: String) = PREFIXES.any { prefix ->
            (ACCESS + REFRESH).any { name == "$prefix$it" } || REFRESH.any { base ->
                val scoped = "$prefix$base-$projectId"
                name == scoped || name.startsWith("$scoped--")
            }
        }
        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
