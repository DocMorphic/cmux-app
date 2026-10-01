package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Bounded byte framing; UTF-8 is decoded only after the entire line arrives. */
internal class SshCmuxLines(private val limit: Int = 16 * 1024 * 1024) {
    private val buffer = ByteArrayOutputStream()
    private var failed = false
    fun feed(bytes: ByteArray): List<JSONObject> {
        check(!failed) { "cmux-tui framing failed" }
        try {
            val result = mutableListOf<JSONObject>()
            var start = 0
            for (i in bytes.indices) if (bytes[i] == 10.toByte()) {
                append(bytes, start, i - start)
                if (buffer.size() > 0) {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    val text = decoder.decode(ByteBuffer.wrap(buffer.toByteArray())).toString()
                    boundNesting(text)
                    val parser = JSONTokener(text)
                    val objectValue = parser.nextValue() as? JSONObject ?: error("cmux-tui line is not an object")
                    check(parser.nextClean() == '\u0000') { "Trailing cmux-tui JSON content" }
                    result += objectValue
                }
                buffer.reset(); start = i + 1
            }
            append(bytes, start, bytes.size - start)
            return result
        } catch (failure: Exception) { failed = true; buffer.reset(); throw failure }
    }
    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        check(length <= limit - buffer.size()) { "cmux-tui line exceeded its limit" }
        buffer.write(bytes, offset, length)
    }
    private fun boundNesting(text: String) {
        // Bound before JSONTokener recurses, not only after building a typed
        // inventory. Brackets in quoted titles/data do not count as nesting.
        var depth = 0; var quoted = false; var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; check(depth <= 128) { "cmux-tui JSON nesting exceeded its limit" } }
                '}', ']' -> { depth--; check(depth >= 0) { "Invalid cmux-tui JSON nesting" } }
            }
        }
        check(depth == 0 && !quoted) { "Incomplete cmux-tui JSON" }
    }
}

internal data class SshCmuxServer(val version: String, val protocol: Int, val session: String,
    val pid: Long, val generation: String?, val capabilities: Set<String>)
internal class SshCmuxFailure(val operation: String, message: String, val code: String? = null) : IOException(message)
internal sealed interface SshCmuxEvent {
    data class Snapshot(val bytes: ByteArray, val columns: Int, val rows: Int, val resized: Boolean) : SshCmuxEvent
    data class Output(val bytes: ByteArray) : SshCmuxEvent
    data class Colors(val values: JSONObject) : SshCmuxEvent
    data class Ended(val disconnected: Boolean) : SshCmuxEvent
}
internal class SshCmuxAttachment internal constructor(val surface: Int, internal val events: (SshCmuxEvent) -> Unit) {
    var lease: String? = null; internal set
    internal var seeded = false
    internal var ended = false
    internal var detaching = false
    internal val operations = Mutex()
}

/** Single-dispatcher owner, Main in production. No mutation/input retries.
 * String IDs correlate replies even if the caller cancels or replies reorder.
 * Unknown fields/events are additive; malformed data retires the relay. */
internal class SshCmuxControl(private val pipe: SshExecPipe, lifetime: CoroutineScope,
    private val timeoutMillis: Long = 30000, private val pendingLimit: Int = 128,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job)
    private val lines = SshCmuxLines()
    private data class Pending(val result: CompletableDeferred<JSONObject>, var timeout: Job? = null)
    private val pending = mutableMapOf<String, Pending>()
    private val writes = Channel<ByteArray>(128)
    private var queuedBytes = 0
    private var nextID = 0L
    private val attachments = mutableMapOf<Int, SshCmuxAttachment>()
    var server: SshCmuxServer? = null; private set
    var closed = false; private set
    var onEvent: ((JSONObject) -> Unit)? = null
    init {
        require(timeoutMillis > 0 && pendingLimit > 0)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { finish() } }
        scope.launch {
            try { for (bytes in writes) { queuedBytes -= bytes.size; try { pipe.write(bytes) } finally { bytes.fill(0) } } }
            catch (_: Exception) { finish() }
        }
        scope.launch {
            try { pipe.output.collect { bytes -> lines.feed(bytes).forEach(::route) } }
            catch (_: Exception) { /* Invalid/failed relay data retires every waiter. */ }
            finally { finish() }
        }
    }
    suspend fun handshake(session: String? = null): SshCmuxServer {
        check(server == null)
        try {
            val data = request("identify")
            check(data.getString("app") == "cmux-tui") { "Unexpected cmux-tui server identity" }
            val caps = data.optJSONArray("capabilities") ?: JSONArray()
            val info = SshCmuxServer(data.getString("version"), integer(data, "protocol", Int.MAX_VALUE.toLong()).toInt(), data.getString("session"),
                integer(data, "pid", Long.MAX_VALUE), data.opt("generation") as? String, (0 until caps.length()).map { caps.getString(it) }.toSet())
            check(info.protocol >= 11) { "cmux-tui control protocol 11 or newer is required" }
            check(info.pid > 0 && (session == null || info.session == session)) { "cmux-tui session identity changed" }
            for (cap in listOf("workspace-registry-v1", "attach-initial-size"))
                check(cap in info.capabilities) { "cmux-tui is missing $cap" }
            val offered = listOf("view-attachment-lease-v1", "view-attachment-detach-v1").filter { it in info.capabilities }
            request("set-client-info", JSONObject().put("name", "cmux-android").put("kind", "android").put("capabilities", JSONArray(offered)))
            server = info; return info
        } catch (failure: Exception) { finish(); throw failure }
    }
    suspend fun request(command: String, params: JSONObject = JSONObject()): JSONObject {
        require(!params.has("id") && !params.has("cmd"))
        val response = exchange(JSONObject(params.toString()).put("cmd", command))
        if (response.opt("ok") != true) throw SshCmuxFailure(command, response.opt("error") as? String ?: "cmux-tui command failed", response.opt("error_code") as? String)
        return response.optJSONObject("data") ?: JSONObject()
    }
    suspend fun requestV2(operation: String, params: JSONObject, idempotencyKey: String? = null): Any {
        val request = JSONObject().put("protocol", "cmux.protocol/2").put("type", "request").put("operation", operation).put("params", params)
        idempotencyKey?.let { request.put("idempotency_key", it) }
        val response = exchange(request)
        if (response.opt("ok") != true) {
            val error = response.optJSONObject("error")
            throw SshCmuxFailure(operation, error?.opt("message") as? String ?: "cmux-tui resource operation failed", error?.opt("code") as? String)
        }
        return checkNotNull(response.opt("result")) { "cmux-tui result missing" }
    }
    private suspend fun exchange(objectValue: JSONObject): JSONObject {
        check(!closed && job.isActive) { "cmux-tui relay ended" }
        check(nextID < Long.MAX_VALUE)
        val id = "r${++nextID}"
        val bytes = (objectValue.put("id", id).toString() + "\n").toByteArray(Charsets.UTF_8)
        if (pending.size >= pendingLimit || bytes.size > 1024 * 1024 - queuedBytes) {
            bytes.fill(0); finish(); throw IOException("cmux-tui request queue exceeded its limit")
        }
        val item = Pending(CompletableDeferred())
        pending[id] = item
        item.timeout = scope.launch { delay(timeoutMillis); finish() }
        queuedBytes += bytes.size
        if (!writes.trySend(bytes).isSuccess) { queuedBytes -= bytes.size; bytes.fill(0); finish() }
        return try { item.result.await() }
        finally { pending.remove(id)?.timeout?.cancel() }
    }
    suspend fun attach(surface: Int, columns: Int, rows: Int, events: (SshCmuxEvent) -> Unit): SshCmuxAttachment {
        require(surface >= 0); size(columns, rows)
        check(server != null && surface !in attachments)
        val attachment = SshCmuxAttachment(surface, events)
        attachments[surface] = attachment // Initial replay precedes the reply.
        try {
            val result = request("attach-surface", JSONObject().put("surface", surface).put("mode", "bytes").put("cols", columns).put("rows", rows))
            attachment.lease = result.opt("lease") as? String
            live(attachment); check(attachment.seeded) { "cmux-tui attach did not send its initial state" }
            request("set-client-sizing", JSONObject().put("surface", surface).put("enabled", true).put("exclusive", true))
            live(attachment); return attachment
        } catch (failure: Exception) {
            // A canceled/failed attach may already own remote geometry. Closing
            // the relay is the cleanup fence when no usable lease was returned.
            finish(); throw failure
        }
    }
    suspend fun send(attachment: SshCmuxAttachment, bytes: ByteArray) = attachment.operations.withLock {
        live(attachment); require(bytes.size <= 256 * 1024)
        request("send", JSONObject().put("surface", attachment.surface).put("bytes", Base64.getEncoder().encodeToString(bytes)))
        Unit
    }
    suspend fun resize(attachment: SshCmuxAttachment, columns: Int, rows: Int): String = attachment.operations.withLock {
        resizeLocked(attachment, columns, rows)
    }
    private suspend fun resizeLocked(attachment: SshCmuxAttachment, columns: Int, rows: Int): String {
        live(attachment); size(columns, rows)
        val params = JSONObject().put("surface", attachment.surface).put("cols", columns).put("rows", rows)
        val lease = attachment.lease
        if (lease == null) return if (request("resize-surface", params).opt("accepted") == true) "applied" else "passive"
        return outcome(request("resize-attached-view", params.put("lease", lease)))
    }
    suspend fun releaseGeometry(attachment: SshCmuxAttachment): Boolean = attachment.operations.withLock {
        live(attachment)
        val lease = attachment.lease ?: return@withLock false
        outcome(request("release-attached-view-size", JSONObject().put("surface", attachment.surface).put("lease", lease)))
        true
    }
    suspend fun claimGeometry(attachment: SshCmuxAttachment, columns: Int, rows: Int) = attachment.operations.withLock {
        check(resizeLocked(attachment, columns, rows) != "superseded") { "cmux-tui view lease expired" }
        request("set-client-sizing", JSONObject().put("surface", attachment.surface).put("enabled", true).put("exclusive", true))
        check(resizeLocked(attachment, columns, rows) != "superseded") { "cmux-tui view lease expired" }
    }
    suspend fun detach(attachment: SshCmuxAttachment) = attachment.operations.withLock {
        if (attachments[attachment.surface] !== attachment) return@withLock
        if (attachment.lease == null || "view-attachment-detach-v1" !in checkNotNull(server).capabilities) { finish(); return@withLock }
        attachment.detaching = true
        try {
            outcome(request("detach-attached-view", JSONObject().put("surface", attachment.surface).put("lease", attachment.lease)))
            attachments.remove(attachment.surface); end(attachment, false)
        } catch (failure: Exception) { finish(); throw failure }
    }
    suspend fun idlePolicy(surface: Int, seconds: Long?): Boolean {
        require(surface >= 0 && (seconds == null || seconds in 1..315360000))
        if ("terminal-idle-close-v1" !in checkNotNull(server).capabilities) return false
        val params = JSONObject().put("surface", surface)
        seconds?.let { params.put("idle_close_seconds", it) }
        request("set-terminal-idle-policy", params); return true
    }
    private fun live(attachment: SshCmuxAttachment) {
        check(!closed && job.isActive && !attachment.ended && !attachment.detaching && attachments[attachment.surface] === attachment) { "cmux-tui attachment ended" }
    }
    private fun outcome(result: JSONObject) = result.getString("outcome").also { check(it in setOf("applied", "passive", "superseded")) }
    private fun size(columns: Int, rows: Int) { require(columns in 1..1000 && rows in 1..1000) }
    private fun route(value: JSONObject) {
        val id = value.opt("id") as? String
        if (id != null) { pending.remove(id)?.let { it.timeout?.cancel(); it.result.complete(value) }; return }
        val event = value.opt("event") as? String ?: return
        // Never coerce fractional, string or overflowing IDs into another live
        // surface. JSONObject.getInt/toInt otherwise silently truncate them.
        val attachment = if (value.has("surface") && !value.isNull("surface"))
            attachments[integer(value, "surface", Int.MAX_VALUE.toLong()).toInt()] else null
        if (attachment != null && !attachment.ended) {
            fun bytes(field: String) = Base64.getDecoder().decode(value.getString(field))
            when (event) {
                "vt-state", "resized" -> {
                    val columns = integer(value, "cols", 1000).toInt(); val rows = integer(value, "rows", 1000).toInt(); size(columns, rows)
                    val data = bytes(if (event == "resized" && value.has("replay")) "replay" else "data")
                    attachment.seeded = true
                    attachment.events(SshCmuxEvent.Snapshot(data, columns, rows, event == "resized"))
                }
                "output" -> { check(attachment.seeded); attachment.events(SshCmuxEvent.Output(bytes("data"))) }
                "colors-changed" -> attachment.events(SshCmuxEvent.Colors(value))
                "detached" -> {
                    if (!attachment.detaching) attachments.remove(attachment.surface)
                    end(attachment, false)
                }
            }
            value.optJSONObject("colors")?.let { attachment.events(SshCmuxEvent.Colors(it)) }
        }
        onEvent?.invoke(value)
    }
    private fun integer(value: JSONObject, field: String, maximum: Long): Long {
        val raw = value.get(field)
        check(raw is Int || raw is Long) { "Invalid cmux-tui $field" }
        return (raw as Number).toLong().also { check(it in 0..maximum) { "Invalid cmux-tui $field" } }
    }
    private fun end(attachment: SshCmuxAttachment, disconnected: Boolean) {
        if (!attachment.ended) { attachment.ended = true; runCatching { attachment.events(SshCmuxEvent.Ended(disconnected)) } }
    }
    private fun finish() {
        if (closed) return
        closed = true; job.cancel(); writes.close(); runCatching { pipe.close() }
        while (true) { val bytes = writes.tryReceive().getOrNull() ?: break; bytes.fill(0) }; queuedBytes = 0
        val requests = pending.values.toList(); pending.clear()
        for (item in requests) { item.timeout?.cancel(); item.result.completeExceptionally(IOException("cmux-tui relay ended; delivery was not confirmed")) }
        val streams = attachments.values.toList(); attachments.clear(); streams.forEach { end(it, true) }
        runCatching { onEvent?.invoke(JSONObject().put("event", "disconnected")) }
    }
    override fun close() = finish()
}
