package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

internal class NativeDeletionCredentials(val access: String, val refresh: String) {
    override fun toString() = "NativeDeletionCredentials(redacted)"
}

internal enum class NativeAccountDeletionResult {
    PROCESSING, COMPLETED, CLEANUP_INCOMPLETE, UNAUTHORIZED, PARTIAL, CONNECTION, TIMED_OUT, UNKNOWN, REJECTED, STORAGE;
    val signsOut get() = this in setOf(COMPLETED, CLEANUP_INCOMPLETE, UNAUTHORIZED)
    val title get() = if (this == CLEANUP_INCOMPLETE) "Account Deleted" else "Couldn't Delete Account"
    val message get() = when (this) {
        CLEANUP_INCOMPLETE -> "Your account sign-in was deleted, but some cmux cleanup did not finish. You will be signed out. Contact support if cmux data is still visible."
        UNAUTHORIZED -> "Your session is no longer valid. You will be signed out on this device. Sign in again if the account still exists."
        PARTIAL -> "Account deletion is incomplete. Some cmux data may already have been deleted. Try Delete Account again to complete deletion."
        CONNECTION -> "Could not reach the server. Check your internet connection and try again."
        TIMED_OUT -> "Account deletion timed out. We couldn't confirm whether it finished. Check your connection before trying Delete Account again."
        UNKNOWN -> "We couldn't confirm whether account deletion finished. Wait a moment, then try Delete Account again."
        STORAGE -> "Could not save the deletion request on this device. No deletion request was sent. Free up storage and try again."
        else -> "Try again later or contact support."
    }
}

/** One explicitly confirmed DELETE. Never redirects, retries or falls back to another account. */
internal class NativeAccountDeletionClient(
    private val credentials: suspend () -> NativeDeletionCredentials?,
    private val isCurrent: () -> Boolean,
    origin: HttpUrl = "https://cmux.com/".toHttpUrl(),
    base: OkHttpClient = OkHttpClient(),
    timeoutMillis: Long = 60_000
) {
    private val endpoint: HttpUrl
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    init {
        require(origin.isHttps || origin.host in setOf("127.0.0.1", "localhost", "::1"))
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.query == null && origin.fragment == null)
        require(origin.encodedPath == "/")
        endpoint = checkNotNull(origin.resolve("api/account"))
    }
    suspend fun delete(): NativeAccountDeletionResult {
        checkCurrent()
        val tokens = credentials() ?: return NativeAccountDeletionResult.UNAUTHORIZED
        checkCurrent()
        if (!valid(tokens.access) || !valid(tokens.refresh)) return NativeAccountDeletionResult.UNAUTHORIZED
        val request = Request.Builder().url(endpoint).delete()
            .header("Authorization", "Bearer ${tokens.access}")
            .header("X-Stack-Refresh-Token", tokens.refresh).header("Accept", "application/json").build()
        val started = AtomicBoolean(false)
        val call = client.newBuilder().eventListener(object : EventListener() {
            override fun requestHeadersStart(call: Call) { started.set(true) }
        }).build().newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            if (!isCurrent()) { continuation.cancel(CancellationException("Account changed")); return@suspendCancellableCoroutine }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val result = when {
                        e is InterruptedIOException -> NativeAccountDeletionResult.TIMED_OUT
                        !started.get() && (e is UnknownHostException || e is ConnectException || e is SSLException) -> NativeAccountDeletionResult.CONNECTION
                        else -> NativeAccountDeletionResult.UNKNOWN
                    }
                    complete(result)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try { response.use {
                        val source = it.body?.source()
                        if ((it.body?.contentLength() ?: 0) > MAX_BODY || source?.request(MAX_BODY + 1L) == true)
                            NativeAccountDeletionResult.UNKNOWN
                        else parse(it.code, source?.readUtf8().orEmpty())
                    } } catch (_: IOException) { NativeAccountDeletionResult.UNKNOWN }
                    complete(result)
                }
                private fun complete(result: NativeAccountDeletionResult) {
                    if (isCurrent()) continuation.resumeWith(Result.success(result))
                    else continuation.cancel(CancellationException("Account changed"))
                }
            })
        }
    }
    private fun checkCurrent() { if (!isCurrent()) throw CancellationException("Account changed") }
    private fun valid(value: String) = value.isNotBlank() && value.length <= 8192 && value.none { it == '\r' || it == '\n' }
    private fun parse(status: Int, body: String): NativeAccountDeletionResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return when {
            status in 200..299 -> when {
                json?.opt("deletionPending") == true -> NativeAccountDeletionResult.UNKNOWN
                json?.opt("cleanupIncomplete") == true -> NativeAccountDeletionResult.CLEANUP_INCOMPLETE
                else -> NativeAccountDeletionResult.COMPLETED
            }
            status == 401 -> NativeAccountDeletionResult.UNAUTHORIZED
            json?.optString("error") in setOf("account_delete_retryable", "account_stack_delete_failed_after_data_delete") -> NativeAccountDeletionResult.PARTIAL
            json?.optString("error") == "account_delete_failed" -> NativeAccountDeletionResult.REJECTED
            status == 408 || status >= 500 -> NativeAccountDeletionResult.UNKNOWN
            else -> NativeAccountDeletionResult.REJECTED
        }
    }
    private companion object { const val MAX_BODY = 64 * 1024 }
}
