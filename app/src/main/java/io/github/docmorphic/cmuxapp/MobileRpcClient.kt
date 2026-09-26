package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * The control channel of cmux's mobile RPC protocol. The caller must supply a
 * route from a scanned cmux pairing code and a current same-account Stack token.
 * Requests are never replayed after a timeout: terminal input is not idempotent.
 */
class MobileRpcClient(
    private val route: PairingCode.Route,
    private val accessToken: suspend () -> String?,
    private val attachToken: String? = null
) : AutoCloseable {
    data class Event(val topic: String, val payload: JSONObject, val streamId: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val stateLock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val eventsMutable = MutableSharedFlow<Event>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = eventsMutable.asSharedFlow()
    private val disconnectedMutable = MutableSharedFlow<Throwable>(replay = 1)
    val disconnected = disconnectedMutable.asSharedFlow()

    private var socket: Socket? = null
    private var reader: Job? = null
    private var closed = false

    suspend fun connect() = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            check(!closed) { "Connection has been closed" }
            if (socket?.isConnected == true && socket?.isClosed == false) return@withContext
        }
        val candidate = Socket()
        try {
            candidate.connect(InetSocketAddress(route.host, route.port), 15_000)
            candidate.tcpNoDelay = true
            synchronized(stateLock) {
                check(!closed) { "Connection has been closed" }
                check(socket == null) { "Connection already active" }
                socket = candidate
                reader = scope.launch { readLoop(candidate) }
            }
        } catch (error: Exception) {
            candidate.close()
            throw error
        }
    }

    suspend fun request(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = 15_000
    ): JSONObject {
        require(method.isNotBlank())
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("id", id).put("method", method).put("params", params)
        val token = if (method == "mobile.host.status") {
            runCatching { accessToken()?.trim() }.getOrNull()
        } else {
            accessToken()?.trim().also {
                require(!it.isNullOrEmpty()) { "Sign in to cmux with the same account as your Mac" }
            }
        }
        if (!token.isNullOrEmpty()) {
            val auth = JSONObject().put("stack_access_token", token)
            if (method != "mobile.host.status" && !attachToken.isNullOrBlank()) auth.put("attach_token", attachToken)
            body.put("auth", auth)
        }
        val answer = CompletableDeferred<JSONObject>()
        synchronized(stateLock) {
            check(!closed && socket != null) { "Not connected to cmux" }
            pending[id] = answer
        }
        try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val active = synchronized(stateLock) { socket } ?: error("Connection closed")
                    active.getOutputStream().write(MobileFrameCodec.encode(body.toString().toByteArray(Charsets.UTF_8)))
                    active.getOutputStream().flush()
                }
            }
            return withTimeout(timeoutMillis) { answer.await() }
        } finally {
            synchronized(stateLock) { pending.remove(id) }
        }
    }

    suspend fun hostStatus(): JSONObject = request("mobile.host.status")
    suspend fun workspaces(): JSONObject = request("mobile.workspace.list")
    suspend fun notifications(): JSONObject = request("notification.feed.list")
    suspend fun markNotificationRead(id: String): JSONObject = request(
        "notification.feed.mark_read",
        JSONObject().put("notification_ids", org.json.JSONArray().put(id))
    )
    suspend fun workspaceAction(
        workspaceId: String,
        windowId: String?,
        action: String,
        title: String? = null
    ): JSONObject {
        require(action in setOf("rename", "pin", "unpin", "mark_read", "mark_unread"))
        val params = JSONObject().put("workspace_id", workspaceId)
            .put("client_id", clientId).put("action", action)
        if (!windowId.isNullOrBlank()) params.put("window_id", windowId)
        if (action == "rename") params.put("title", title?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Enter a workspace title"))
        return request("workspace.action", params)
    }

    suspend fun closeWorkspace(workspaceId: String, windowId: String?): JSONObject {
        val params = JSONObject().put("workspace_id", workspaceId).put("client_id", clientId)
        if (!windowId.isNullOrBlank()) params.put("window_id", windowId)
        return request("workspace.close", params)
    }
    suspend fun replay(workspaceId: String, surfaceId: String, columns: Int, rows: Int): JSONObject =
        request("mobile.terminal.replay", JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId)
            .put("client_id", clientId)
            .put("viewport_columns", columns)
            .put("viewport_rows", rows)
            .put("anchor", "screen"))

    suspend fun input(workspaceId: String, surfaceId: String, text: String): JSONObject =
        request("terminal.input", JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId)
            .put("client_id", clientId)
            .put("text", text))

    private val clientId = UUID.randomUUID().toString()

    suspend fun subscribe(topics: List<String>, streamId: String = UUID.randomUUID().toString()): JSONObject =
        request("mobile.events.subscribe", JSONObject()
            .put("stream_id", streamId)
            .put("topics", org.json.JSONArray(topics)))

    private fun readLoop(active: Socket) {
        val decoder = MobileFrameDecoder()
        val bytes = ByteArray(64 * 1024)
        var failure: Throwable = EOFException("cmux disconnected")
        try {
            val input = active.getInputStream()
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                for (frame in decoder.feed(bytes.copyOf(count))) dispatch(JSONObject(String(frame, Charsets.UTF_8)))
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            synchronized(stateLock) {
                if (socket === active) socket = null
                pending.values.forEach { it.completeExceptionally(failure) }
                pending.clear()
            }
            if (!closed) disconnectedMutable.tryEmit(failure)
            active.close()
        }
    }

    private fun dispatch(envelope: JSONObject) {
        if (envelope.optString("kind") == "event") {
            val topic = envelope.optString("topic")
            if (topic.isNotBlank()) eventsMutable.tryEmit(Event(
                topic,
                envelope.optJSONObject("payload") ?: JSONObject(),
                envelope.optString("stream_id").takeIf { it.isNotBlank() }
            ))
            return
        }
        val id = envelope.optString("id")
        val waiter = synchronized(stateLock) { pending[id] } ?: return
        if (envelope.optBoolean("ok")) {
            waiter.complete(envelope.optJSONObject("result") ?: JSONObject())
        } else {
            val message = envelope.optJSONObject("error")?.optString("message")
                .orEmpty().ifBlank { "cmux RPC request failed" }
            waiter.completeExceptionally(IllegalStateException(message))
        }
    }

    override fun close() {
        val active = synchronized(stateLock) {
            if (closed) return
            closed = true
            socket.also { socket = null }
        }
        active?.close()
        scope.cancel()
    }
}
