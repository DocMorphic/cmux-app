package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CloudAccountScope(val login: String, val user: String, val team: String?, val generation: Long)
internal class CloudCredentials(val scope: CloudAccountScope, val accessToken: String, val refreshToken: String) {
    init { require(accessToken.isNotBlank() && refreshToken.isNotBlank()) }
    override fun toString() = "CloudCredentials(redacted)"
}
internal class CloudApiFailure(val status: Int, val action: String?, message: String?) : IOException(
    message?.takeIf { it.isNotBlank() }?.take(2048) ?: "Cloud request failed ($status)") {
    val unauthorized get() = status == 401
}
internal class CloudNotSignedIn : IOException("Sign in to cmux to use Cloud")
internal interface CloudMachinesService : AutoCloseable {
    suspend fun catalog(): CloudMachineCatalog
    suspend fun create(options: CloudMachineCreateOptions, idempotencyKey: String): CloudMachine
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun delete(id: String)
}

/** Pure request contract. Constructing operations never sends or provisions anything. */
internal class CloudApiRequests(baseUrl: String = "https://cmux.com") {
    private val origin = baseUrl.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null && it.encodedPath == "/")
        require(it.isHttps || it.host in setOf("localhost", "127.0.0.1", "::1"))
    }
    internal class Operation(val method: String, val segments: List<String>, val body: String? = null,
        val deadlineSeconds: Long = 20, val idempotencyKey: String? = null) {
        override fun toString() = "CloudOperation($method, redacted)"
    }
    fun list() = Operation("GET", listOf("api", "vm"))
    fun create(options: CloudMachineCreateOptions, key: String): Operation {
        require(key.isNotBlank() && key.none { it == '\r' || it == '\n' }) { "Missing Cloud creation identity" }
        val body = JSONObject().put("kind", options.kind.wire)
        options.provider?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("provider", it) }
        options.image?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("image", it) }
        if (options.persistentHome) body.put("persistentHome", true)
        if (options.perMachineHome) body.put("perMachineHome", true)
        options.memoryMb?.let { require(it > 0); body.put("memoryMb", it) }
        return Operation("POST", listOf("api", "vm"), body.toString(), 16 * 60, key.trim())
    }
    fun pause(id: String) = Operation("POST", machinePath(id) + "pause")
    fun resume(id: String) = Operation("POST", machinePath(id) + "resume", deadlineSeconds = 16 * 60)
    fun delete(id: String) = Operation("DELETE", machinePath(id))
    fun enroll(publicKey: String, deviceId: String, fingerprint: String, purpose: CloudTunnelPurpose, name: String?): Operation {
        require(publicKey.isNotBlank() && deviceId.isNotBlank() && fingerprint.isNotBlank())
        val body = JSONObject().put("clientPublicKey", publicKey).put("deviceId", deviceId)
            .put("deviceFingerprint", fingerprint).put("tunnelPurpose", purpose.wire)
        name?.takeIf { it.isNotBlank() }?.let { body.put("deviceName", it) }
        return Operation("POST", listOf("api", "vm", "tunnel"), body.toString())
    }
    fun revoke(fingerprint: String, purpose: CloudTunnelPurpose): Operation {
        require(fingerprint.isNotBlank())
        return Operation("DELETE", listOf("api", "vm", "tunnel"), JSONObject()
            .put("deviceFingerprint", fingerprint).put("tunnelPurpose", purpose.wire).toString())
    }
    fun attach(id: String, fingerprint: String, capabilities: List<String> = emptyList()): Operation {
        require(fingerprint.isNotBlank())
        val body = JSONObject().put("transport", "cmux-remote").put("deviceFingerprint", fingerprint)
        val admitted = capabilities.map { it.trim() }.filter { it.matches(Regex("[a-z0-9-]{1,64}")) }.distinct().take(16)
        if (admitted.isNotEmpty()) body.put("clientCapabilities", org.json.JSONArray(admitted))
        return Operation("POST", machinePath(id) + "attach-endpoint", body.toString(), 90)
    }
    fun approve(id: String, invitationId: String): Operation {
        require(invitationId.isNotBlank())
        return Operation("POST", machinePath(id) + listOf("cmux-remote", "approve"), JSONObject().put("invitationId", invitationId).toString())
    }
    fun request(operation: Operation, credentials: CloudCredentials): Request {
        val url = origin.newBuilder().apply { operation.segments.forEach { addPathSegment(it) } }.build()
        val body = operation.body?.toRequestBody("application/json".toMediaType())
            ?: if (operation.method == "POST") ByteArray(0).toRequestBody(null) else null
        return Request.Builder().url(url).method(operation.method, body)
            .header("Authorization", "Bearer ${credentials.accessToken}")
            .header("X-Stack-Refresh-Token", credentials.refreshToken).header("Accept", "application/json")
            .apply {
                credentials.scope.team?.takeIf { it.isNotEmpty() }?.let { header("X-Cmux-Team-Id", it) }
                operation.idempotencyKey?.let { header("Idempotency-Key", it) }
            }.build()
    }
    private fun machinePath(id: String): List<String> {
        val value = id.trim()
        require(value.isNotEmpty() && value != "." && value != "..") { "Invalid Cloud machine identity" }
        return listOf("api", "vm", value)
    }
}

/** One admitted account/team owner. Requests never retry, follow redirects or borrow global cookies. */
internal class CloudApi(
    private val owner: CloudAccountScope,
    private val credentials: suspend () -> CloudCredentials?,
    private val isCurrent: (CloudAccountScope) -> Boolean,
    private val requests: CloudApiRequests = CloudApiRequests(),
    http: OkHttpClient = OkHttpClient()
) : CloudMachinesService {
    private val http = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(16, TimeUnit.MINUTES).writeTimeout(20, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private fun admitted() {
        if (synchronized(lock) { closed } || !isCurrent(owner)) throw CancellationException("Cloud account changed")
    }
    override suspend fun catalog() = CloudResponseDecoding.catalog(json(send(requests.list())))
    override suspend fun create(options: CloudMachineCreateOptions, idempotencyKey: String) =
        CloudResponseDecoding.machine(json(send(requests.create(options, idempotencyKey))))
    override suspend fun pause(id: String) { send(requests.pause(id)) }
    override suspend fun resume(id: String) { send(requests.resume(id)) }
    override suspend fun delete(id: String) { send(requests.delete(id)) }
    suspend fun enroll(publicKey: String, deviceId: String, fingerprint: String, purpose: CloudTunnelPurpose, name: String?) =
        CloudResponseDecoding.enrollment(json(send(requests.enroll(publicKey, deviceId, fingerprint, purpose, name))))
    suspend fun revoke(fingerprint: String, purpose: CloudTunnelPurpose) { send(requests.revoke(fingerprint, purpose)) }
    suspend fun attach(id: String, fingerprint: String, capabilities: List<String> = emptyList()) =
        CloudResponseDecoding.attach(json(send(requests.attach(id, fingerprint, capabilities))))
    suspend fun approve(id: String, invitationId: String) = CloudResponseDecoding.approved(json(send(requests.approve(id, invitationId))))

    private suspend fun send(operation: CloudApiRequests.Operation): ByteArray {
        admitted()
        val snapshot = try { credentials() } catch (failure: Exception) {
            admitted()
            throw failure
        }
        admitted()
        if (snapshot == null) throw CloudNotSignedIn()
        if (snapshot.scope != owner) throw CancellationException("Cloud account changed")
        val call = http.newCall(requests.request(operation, snapshot))
        call.timeout().timeout(operation.deadlineSeconds, TimeUnit.SECONDS)
        synchronized(lock) { if (closed) throw CancellationException("Cloud connection closed"); calls += call }
        try {
            admitted()
            val bytes = suspendCancellableCoroutine<ByteArray> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val data = response.use {
                                val source = requireNotNull(it.body).source()
                                // Read at most one byte beyond the cap, including unknown-length/chunked bodies.
                                require(!source.request(MAX_RESPONSE + 1)) { "Cloud response is too large" }
                                val body = source.readByteArray()
                                if (!it.isSuccessful) {
                                    val envelope = runCatching { json(body) }.getOrNull()
                                    throw CloudApiFailure(it.code, envelope?.opt("action") as? String,
                                        (envelope?.opt("message") as? String) ?: (envelope?.opt("error") as? String))
                                }
                                body
                            }
                            continuation.resume(data)
                        } catch (failure: Exception) { continuation.resumeWithException(failure) }
                    }
                })
            }
            admitted()
            return bytes
        } catch (failure: Exception) {
            admitted() // An old account's HTTP failure must not affect the replacement session either.
            throw failure
        } finally { synchronized(lock) { calls -= call } }
    }
    override fun close() {
        val active = synchronized(lock) { closed = true; calls.toList().also { calls.clear() } }
        active.forEach { it.cancel() }
    }
    companion object {
        private const val MAX_RESPONSE = 2L * 1024 * 1024
        private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    }
}
