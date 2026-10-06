package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal sealed interface PhoneHelperHttpResult {
    data class Success(val body: JSONObject) : PhoneHelperHttpResult
    data class Retry(val notBeforeMillis: Long) : PhoneHelperHttpResult
    data class Rejected(val status: Int) : PhoneHelperHttpResult
    data object Retired : PhoneHelperHttpResult
}

/** Exact offered endpoint; never sends an account bearer token, cookies, redirects or automatic retries. */
internal class PhoneHelperHttp(endpoint: String, private val permits: () -> Boolean,
    base: OkHttpClient = OkHttpClient(), private val now: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val endpoint = endpoint.toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
            it.encodedPath == "/v1/push/enroll" && it.toString() == endpoint)
    }
    private val client = base.newBuilder().apply { interceptors().clear(); networkInterceptors().clear() }
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(12, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private var retryAt = 0L
    private fun gate(): PhoneHelperHttpResult? = synchronized(lock) {
        when {
            closed || !runCatching(permits).getOrDefault(false) -> PhoneHelperHttpResult.Retired
            now() < retryAt -> PhoneHelperHttpResult.Retry(retryAt)
            else -> null
        }
    }
    suspend fun send(step: String, payload: JSONObject): PhoneHelperHttpResult {
        require(step in setOf("begin", "finish", "maintain.begin", "maintain.finish"))
        gate()?.let { return it }
        val bytes = JSONObject().put("step", step).put("request", payload).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BODY)
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                if (gate() != null) throw IOException("Helper request retired")
                sink.write(bytes)
            }
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build())
            val rejected = synchronized(lock) {
                gate() ?: if (calls.size >= 4) PhoneHelperHttpResult.Retry(now() + 1000) else { calls += call; null }
            }
            if (rejected != null) { continuation.resumeWith(Result.success(rejected)); return@suspendCancellableCoroutine }
            continuation.invokeOnCancellation { call.cancel(); synchronized(lock) { calls -= call } }
            call.enqueue(object : Callback {
                fun complete(result: PhoneHelperHttpResult) {
                    synchronized(lock) { calls -= call }
                    continuation.resumeWith(Result.success(gate() ?: result))
                }
                override fun onFailure(call: Call, e: IOException) = complete(PhoneHelperHttpResult.Retry(now() + 5000))
                override fun onResponse(call: Call, response: Response) {
                    val result = try { response.use { value ->
                        when {
                            value.code == 408 || value.code == 429 || value.code >= 500 -> {
                                val deadline = PhoneReplyRelay.retryDeadline(value.header("Retry-After") ?: "5", now())
                                synchronized(lock) { retryAt = maxOf(retryAt, deadline) }
                                PhoneHelperHttpResult.Retry(deadline)
                            }
                            value.code != 200 -> PhoneHelperHttpResult.Rejected(value.code)
                            else -> {
                                val received = value.body ?: throw IOException("Missing helper response")
                                require(received.contentLength() <= MAX_BODY)
                                val type = received.contentType()
                                require(type?.type == "application" && type.subtype == "json" && (type.charset() == null || type.charset() == Charsets.UTF_8))
                                val source = received.source()
                                require(!source.request(MAX_BODY.toLong() + 1))
                                val bytes = source.readByteArray()
                                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                                PhoneHelperHttpResult.Success(MobileJson.objectValue(text, requireComplete = true))
                            }
                        }
                    } } catch (_: IOException) { PhoneHelperHttpResult.Retry(now() + 5000) }
                    catch (_: Exception) { PhoneHelperHttpResult.Rejected(200) }
                    complete(result)
                }
            })
        }
    }
    override fun close() {
        val pending = synchronized(lock) { closed = true; calls.toList().also { calls.clear() } }
        pending.forEach(Call::cancel)
    }
    companion object { const val MAX_BODY = 32_768 }
}
