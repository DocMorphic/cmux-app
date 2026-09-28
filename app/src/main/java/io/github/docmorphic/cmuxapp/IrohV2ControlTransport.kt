package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class IrohV2ServerFailure(val code: String, val retryable: Boolean, val retryAfterMs: Long? = null) : IOException(code)
internal class IrohV2HttpFailure(val status: Int, val retryAfter: String?) : IOException("Iroh service HTTP $status")
internal class IrohV2Unavailable(cause: Throwable? = null) : IOException("Iroh control transport unavailable", cause)

internal object IrohV2Wire {
    const val MAX_REPLY = 2 * 1024 * 1024
    const val MAX_REQUEST = 16 * 1024
    val requestResponses = mapOf(
        "device.register.v1" to "device.registered.v1", "challenge.request.v1" to "challenge.result.v1",
        "ticket.request.v1" to "ticket.result.v1", "relay.request.v1" to "relay.result.v1",
        "directory.request.v1" to "directory.result.v1", "device.metadata.v1" to "operation.completed.v1",
        "device.revoke.v1" to "operation.completed.v1", "permission.update.v1" to "operation.completed.v1",
        "preferences.update.v1" to "operation.completed.v1", "session.goodbye.v1" to "operation.completed.v1")

    fun serverFailure(value: JSONObject): IrohV2ServerFailure? {
        if (value.optString("schemaId") != "error.v1") return null
        val code = value.get("code") as? String ?: throw IOException("Invalid Iroh error code")
        val retryable = value.get("retryable") as? Boolean ?: throw IOException("Invalid Iroh retry flag")
        return IrohV2ServerFailure(code, retryable,
            if (value.has("retryAfterMs")) integer(value, "retryAfterMs") else null)
    }
    fun integer(value: JSONObject, key: String): Long {
        val number = value.get(key)
        if (number !is Int && number !is Long) throw IOException("Invalid Iroh integer")
        return (number as Number).toLong().also { if (it !in 0..9007199254740991L) throw IOException("Unsafe Iroh integer") }
    }
    fun validateReply(value: JSONObject, id: String, schema: String): JSONObject {
        if (value.optString("requestId") != id) throw IOException("Iroh reply identity mismatch")
        serverFailure(value)?.let { throw it }
        if (value.optString("schemaId") != schema) throw IOException("Unexpected Iroh response schema")
        return value
    }
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
}

/** A single V2 socket incarnation. Correlation and receipts never cross a replacement socket. */
internal class IrohV2ControlSocket private constructor(private val timeoutMs: Long) : AutoCloseable {
    private data class Pending(val schema: String, val answer: CompletableDeferred<JSONObject>)
    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()
    private val eventsChannel = Channel<JSONObject>(64)
    val events = eventsChannel.receiveAsFlow()
    private var socket: WebSocket? = null
    private var failure: Throwable? = null

    suspend fun request(body: JSONObject): JSONObject {
        val id = body.getString("requestId")
        val expected = IrohV2Wire.requestResponses[body.getString("schemaId")] ?: throw IOException("Unsupported Iroh request")
        val bytes = IrohV2SigningCodec.encode(body)
        require(bytes.size <= IrohV2Wire.MAX_REQUEST) { "Iroh request too large" }
        val answer = CompletableDeferred<JSONObject>()
        synchronized(lock) {
            failure?.let { throw it }
            check(!pending.containsKey(id)) { "Duplicate Iroh request ID" }
            pending[id] = Pending(expected, answer)
            if (socket?.send(bytes.toString(Charsets.UTF_8)) != true) {
                pending.remove(id)
                throw IOException("Iroh socket unavailable")
            }
        }
        return try { withTimeout(timeoutMs) { answer.await() }.also { synchronized(lock) { failure?.let { throw it } } } }
        finally { synchronized(lock) { pending.remove(id) } }
    }

    private fun receive(webSocket: WebSocket, bytes: ByteArray) {
        try {
            if (bytes.size > IrohV2Wire.MAX_REPLY) throw IOException("Iroh response too large")
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            synchronized(lock) {
                if (failure != null) return
                value.optJSONObject("deliveryReceipt")?.let { receipt ->
                    val token = receipt.getString("token")
                    if (!token.matches(Regex("[A-Za-z0-9_-]{22}"))) throw IOException("Invalid Iroh receipt")
                    val acknowledgement = JSONObject().put("schemaId", "session.ack.v1")
                        .put("requestId", UUID.randomUUID().toString()).put("sequence", IrohV2Wire.integer(receipt, "sequence"))
                        .put("token", token)
                    if (!webSocket.send(acknowledgement.toString())) throw IOException("Iroh receipt queue unavailable")
                }
                when (value.getString("schemaId")) {
                    "directory.changed.v1", "device.revoked.v1" -> {
                        // Never drop authority changes. Saturation terminates this incarnation.
                        if (eventsChannel.trySend(value).isFailure) throw IOException("Iroh event consumer stalled")
                    }
                    else -> {
                        val id = value.getString("requestId")
                        val waiter = pending.remove(id)
                        if (waiter != null) {
                            try { waiter.answer.complete(IrohV2Wire.validateReply(value, id, waiter.schema)) }
                            catch (error: Exception) { waiter.answer.completeExceptionally(error) }
                        }
                        // Replies to acknowledgements, cancelled or timed-out calls cannot mutate state.
                        IrohV2Wire.serverFailure(value)?.let { error ->
                            if (error.code in setOf("device_revoked", "team_access_revoked")) throw error
                        }
                    }
                }
            }
        } catch (error: Exception) { terminate(error) }
    }

    private fun terminate(error: Throwable) {
        val old = synchronized(lock) {
            if (failure != null) return
            failure = error
            pending.values.forEach { it.answer.completeExceptionally(error) }
            pending.clear()
            eventsChannel.close(error)
            socket.also { socket = null }
        }
        old?.cancel()
    }

    override fun close() = terminate(IOException("Iroh control session closed"))

    companion object {
        suspend fun open(client: OkHttpClient, request: Request, requestId: String,
                         timeoutMs: Long = 15_000): Pair<IrohV2ControlSocket, JSONObject> {
            val session = IrohV2ControlSocket(timeoutMs)
            val ready = CompletableDeferred<JSONObject>()
            session.pending[requestId] = Pending("session.ready.v1", ready)
            val socket = IrohV2Wire.client(client).newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > IrohV2Wire.MAX_REPLY) session.terminate(IOException("Iroh response too large"))
                    else session.receive(webSocket, text.toByteArray(Charsets.UTF_8))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size > IrohV2Wire.MAX_REPLY) session.terminate(IOException("Iroh response too large"))
                    else session.receive(webSocket, bytes.toByteArray())
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    session.terminate(response?.let { IrohV2HttpFailure(it.code, it.header("Retry-After")) } ?: IrohV2Unavailable(t))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    session.terminate(if (code == 1008 && reason in setOf("device_revoked", "team_access_revoked"))
                        IrohV2ServerFailure(reason, false) else IrohV2Unavailable())
                }
            })
            synchronized(session.lock) {
                if (session.failure == null) session.socket = socket else socket.cancel()
            }
            try { return session to withTimeout(timeoutMs) { ready.await() } }
            catch (error: Throwable) { session.terminate(error); throw error }
        }
    }
}

/** Signed-request headers/body are prepared by the control service; transport never retries mutations. */
internal class IrohV2ControlHttp(base: OkHttpClient) : AutoCloseable {
    private val client = IrohV2Wire.client(base).newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()

    suspend fun exchange(request: Request, requestId: String, expectedSchema: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        synchronized(lock) {
            if (closed) { continuation.resumeWith(Result.failure(IOException("Iroh HTTP transport closed"))); return@suspendCancellableCoroutine }
            calls.add(call)
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                synchronized(lock) { calls.remove(call) }
                continuation.resumeWith(Result.failure(IrohV2Unavailable(error)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = it.body ?: throw IOException("Missing Iroh response")
                        if (body.contentLength() > IrohV2Wire.MAX_REPLY) throw IOException("Iroh response too large")
                        val input = body.byteStream()
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > IrohV2Wire.MAX_REPLY) throw IOException("Iroh response too large")
                            bytes.write(buffer, 0, count)
                        }
                        val value = runCatching { JSONObject(bytes.toString("UTF-8")) }.getOrNull()
                        if (value?.optString("schemaId") == "error.v1")
                            IrohV2Wire.validateReply(value, requestId, expectedSchema)
                        if (!it.isSuccessful) throw IrohV2HttpFailure(it.code, it.header("Retry-After"))
                        IrohV2Wire.validateReply(value ?: throw IOException("Invalid Iroh response"), requestId, expectedSchema)
                    }
                }
                val currentResult = synchronized(lock) {
                    calls.remove(call)
                    if (closed) Result.failure<JSONObject>(IOException("Iroh HTTP transport closed")) else result
                }
                continuation.resumeWith(currentResult)
            }
        })
    }

    override fun close() {
        val pending = synchronized(lock) { closed = true; calls.toList().also { calls.clear() } }
        pending.forEach { it.cancel() }
    }
}
