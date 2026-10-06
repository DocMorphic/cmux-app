package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public Android release metadata. This client never shares account cookies or authentication. */
internal class NativeWhatsNewFeed(endpoint: String = URL) : AutoCloseable {
    private val url = endpoint.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.fragment == null)
        require(it.isHttps || (it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1")))
    }
    // Include the complete endpoint: two repositories on the same public host must not share cache.
    val cacheIdentity = "feed-" + MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val closed = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): String = suspendCancellableCoroutine { continuation ->
        if (closed.get()) {
            continuation.resumeWithException(IOException("Notice feed closed"))
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "cmux-app-android-notices").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (it.code != 200) throw IOException("Notice feed unavailable")
                        val body = it.body ?: throw IOException("Empty notice feed")
                        if (body.contentLength() > MAX_BYTES) throw IOException("Notice feed too large")
                        val buffer = Buffer()
                        val source = body.source()
                        while (buffer.size <= MAX_BYTES) {
                            val count = source.read(buffer, minOf(8192L, MAX_BYTES + 1 - buffer.size))
                            if (count < 0) break
                        }
                        if (buffer.size > MAX_BYTES) throw IOException("Notice feed too large")
                        buffer.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
        // Covers close racing with enqueue; the owner never leaves an orphan fetch running.
        if (closed.get()) call.cancel()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Closing idle TLS sockets may write close_notify. ViewModel retirement is
            // on Main, so drain this owned pool on its worker before shutting it down.
            val worker = client.dispatcher.executorService
            worker.execute {
                try {
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                } finally { worker.shutdown() }
            }
        }
    }
    companion object {
        const val URL = "https://raw.githubusercontent.com/DocMorphic/cmux-app/main/distribution/android-notices.json"
        const val MAX_BYTES = 1_048_576L
    }
}
