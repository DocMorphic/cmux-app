package io.github.docmorphic.cmuxapp

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** cmux's consent-image URL, address, MIME and size policy; DNS results are used by the TLS connection. */
internal object MarkdownImagePolicy {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun url(raw: String): HttpUrl? {
        if (raw.length > 16_384 || runCatching { URI(raw).rawUserInfo != null }.getOrDefault(true)) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        val host = url.host.trimEnd('.').lowercase()
        if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
            if (!runCatching { allowed(InetAddress.getByName(host)) }.getOrDefault(false)) return null
        }
        return url.takeIf { it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            host.isNotEmpty() && host != "localhost" && !host.endsWith(".localhost") && host != "local" && !host.endsWith(".local") }
    }
    fun allowed(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 100 && b in 64..127 ||
                a == 169 && b == 254 || a == 172 && b in 16..31 || a == 192 && b in listOf(0, 168) || a == 198 && b in 18..19)
        }
        if (bytes.size != 16 || bytes.take(12).all { it == 0 }) return false
        if (bytes.take(10).all { it == 0 } && bytes[10] == 255 && bytes[11] == 255)
            return allowed(InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
        return !((bytes[0] and 254) == 252 || bytes[0] == 254 && (bytes[1] and 192) in listOf(128, 192) || bytes[0] == 255)
    }
    fun redirect(initial: HttpUrl, target: HttpUrl, depth: Int): Boolean = depth <= 3 && url(target.toString()) != null &&
        initial.host.trimEnd('.').equals(target.host.trimEnd('.'), true)
    fun mime(value: String?): String? = when (val mime = value?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpg" -> "image/jpeg"
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/svg+xml" -> mime
        else -> null
    }
    fun read(input: InputStream, contentLength: Long, limit: Int = MAX_BYTES): ByteArray {
        require(contentLength <= limit) { "Image exceeds size limit" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= limit - output.size()) { "Image exceeds size limit" }
            output.write(buffer, 0, count)
        }
        if (contentLength >= 0) require(output.size().toLong() == contentLength) { "Incomplete image" }
        return output.toByteArray()
    }
}

internal class MarkdownRemoteImages : AutoCloseable {
    data class Image(val bytes: ByteArray, val mime: String)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var closed = false
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = InetAddress.getAllByName(hostname).toList()
                if (addresses.isEmpty() || addresses.any { !MarkdownImagePolicy.allowed(it) }) throw UnknownHostException("Image host is not public")
                return addresses
            }
        }).build()
    fun fetch(raw: String): Image? {
        val initial = MarkdownImagePolicy.url(raw) ?: return null
        var url = initial
        for (depth in 0..3) {
            if (closed || !MarkdownImagePolicy.redirect(initial, url, depth)) return null
            val call = client.newCall(Request.Builder().url(url)
                .header("Accept", "image/png,image/jpeg,image/gif,image/webp,image/avif,image/svg+xml")
                .header("User-Agent", "cmux-markdown-image-loader").build())
            calls.add(call)
            try {
                if (closed) { call.cancel(); return null }
                call.execute().use { response ->
                    if (response.code in 300..399) {
                        url = url.resolve(response.header("Location") ?: return null) ?: return null
                    } else {
                        if (!response.isSuccessful) return null
                        val mime = MarkdownImagePolicy.mime(response.header("Content-Type")) ?: return null
                        val body = response.body ?: return null
                        val bytes = body.byteStream().use { MarkdownImagePolicy.read(it, body.contentLength()) }
                        return if (closed) null else Image(bytes, mime)
                    }
                }
            } catch (_: Exception) { return null }
            finally { calls.remove(call) }
        }
        return null
    }
    override fun close() { closed = true; calls.forEach { it.cancel() }; calls.clear(); client.connectionPool.evictAll() }
}
