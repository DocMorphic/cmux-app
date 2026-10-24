package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Retain the typed refusal needed by the original email-verification policy. */
internal open class NativeAccountRequestFailure(val code: String, val requiresEmailVerification: Boolean,
    message: String) : IOException(message)

internal fun nativeAccountResponse(status: Int, knownError: String?, raw: String): JSONObject {
    if (status == 429) throw NativeAccountRequestFailure("RATE_LIMITED", false, "Too many attempts. Please wait a moment and try again.")
    if (status !in 200..299 || !knownError.isNullOrBlank()) {
        val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val code = (knownError ?: (body.opt("code") as? String).orEmpty()).uppercase(Locale.ROOT)
        val detail = body.optJSONObject("details")?.opt("would_work_if_email_was_verified")
        val unverified = code == "USER_EMAIL_ALREADY_EXISTS" && when (detail) {
            is Boolean -> detail
            is Number -> detail.toDouble() != 0.0
            else -> false
        }
        throw NativeAccountRequestFailure(code, unverified,
            (body.opt("message") as? String)?.takeIf { it.isNotBlank() } ?: "Account request failed: HTTP $status")
    }
    return JSONObject(raw.ifBlank { "{}" })
}

internal enum class NativeSignInRecoveryError { PENDING, RATE_LIMIT, UNAVAILABLE }
internal class NativeSignInRecoveryFailure(val reason: NativeSignInRecoveryError) : IOException("Account recovery unavailable")

/** Same privacy-neutral, unauthenticated cmux endpoints as the original runtime. */
internal class NativeEmailRecoveryClient(private val origin: String = "https://cmux.com",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()) {
    suspend fun verification(email: String) = request("/api/auth/email-verification", email)
    suspend fun billing(email: String) = request("/api/billing/recover", email)
    private suspend fun request(path: String, email: String): Unit = suspendCancellableCoroutine { continuation ->
        val body = JSONObject().put("email", email.trim().lowercase(Locale.ROOT)).toString()
        val call = client.newCall(Request.Builder().url(origin.trimEnd('/') + path)
            .post(body.toRequestBody("application/json".toMediaType())).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(NativeSignInRecoveryFailure(NativeSignInRecoveryError.UNAVAILABLE))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    when (response.code) {
                        in 200..299 -> continuation.resume(Unit)
                        429 -> continuation.resumeWithException(NativeSignInRecoveryFailure(NativeSignInRecoveryError.RATE_LIMIT))
                        else -> continuation.resumeWithException(NativeSignInRecoveryFailure(NativeSignInRecoveryError.UNAVAILABLE))
                    }
                }
            }
        })
    }
}
