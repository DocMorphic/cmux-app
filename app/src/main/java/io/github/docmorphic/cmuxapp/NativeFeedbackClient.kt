package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NativeFeedbackStamp(val version: String, val build: String, val bundle: String,
    val buildType: String, val os: String, val model: String, val locale: String)

/** Public iOS feedback contract. No credentials, terminal content, attachments or automatic retries. */
internal class NativeFeedbackClient(origin: HttpUrl = "https://cmux.com/".toHttpUrl(), base: OkHttpClient = OkHttpClient(),
    timeoutMillis: Long = 30_000) {
    private val endpoint: HttpUrl
    private val client = base.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    init {
        require(origin.isHttps || origin.host in setOf("127.0.0.1", "localhost", "::1"))
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.query == null && origin.fragment == null && origin.encodedPath == "/")
        endpoint = checkNotNull(origin.resolve("api/feedback"))
    }
    suspend fun submit(email: String, message: String, stamp: NativeFeedbackStamp) {
        require(valid(email, message)) { "Enter a valid email and a message of up to 4,000 characters." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("email", email.trim()).addFormDataPart("message", message.trim())
            .addFormDataPart("appVersion", stamp.version).addFormDataPart("appBuild", stamp.build)
            .addFormDataPart("bundleIdentifier", stamp.bundle).addFormDataPart("buildType", stamp.buildType)
            .addFormDataPart("osVersion", stamp.os).addFormDataPart("hardwareModel", stamp.model)
            .addFormDataPart("locale", stamp.locale).build()
        val call = client.newCall(Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build())
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(IOException("Could not confirm feedback was sent. Check your connection and try again later.", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (it.isSuccessful) continuation.resume(Unit)
                        else continuation.resumeWithException(IOException(when (it.code) {
                            400 -> "Check your email and message, then try again."
                            429 -> "Too many feedback requests. Try again later."
                            else -> "Could not send feedback (${it.code}). Try again later."
                        }))
                    }
                }
            })
        }
    }
    companion object {
        fun valid(email: String, message: String): Boolean {
            val address = email.trim()
            return address.length <= 320 && address.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) &&
                message.trim().isNotEmpty() && message.length <= 4_000
        }
    }
}
