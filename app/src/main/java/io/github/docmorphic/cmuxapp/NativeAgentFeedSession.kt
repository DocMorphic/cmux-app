package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class AgentFeedDelivery { NOT_SENT, UNCONFIRMED }
internal data class AgentFeedFailure(val message: String, val delivery: AgentFeedDelivery, val draft: String? = null)
internal data class NativeAgentFeedState(
    val snapshot: NativeAgentFeedSnapshot? = null, val loading: Boolean = false, val error: String? = null,
    val pending: Set<String> = emptySet(), val failures: Map<String, AgentFeedFailure> = emptyMap()
)

/** One verified Mac connection owns this session, including every read and user-initiated action. */
internal class NativeAgentFeedSession(
    parent: CoroutineScope,
    private val admitted: () -> Boolean,
    private val request: suspend (String, JSONObject) -> JSONObject,
    initial: NativeAgentFeedSnapshot? = null,
    private val fatal: (Exception) -> Unit = {}
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val refresh = NativeFeedRefresh()
    private val revision = NativeFeedRevision().also { if (initial != null) it.accept(initial.revision) }
    private val mutableState = MutableStateFlow(NativeAgentFeedState(snapshot = initial))
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    private val localDecisions = mutableMapOf<String, AgentFeedDecision>()
    private val localReplies = mutableMapOf<String, String>()

    fun start() {
        if (monitor != null) return
        monitor = scope.launch { refresh.run { fetch() } }
    }
    fun refresh() = refresh.request()
    fun changed(payload: JSONObject) {
        val raw = payload.opt("revision") as? Number
        if (raw == null || raw.toLong() < 0 || revision.observe(raw.toLong()) || state.value.error != null) refresh.request()
    }
    private fun checkOwner() { check(job.isActive && admitted()) { "Feed computer is no longer connected" } }
    private fun failed(error: Exception) {
        if (error is MobileRpcException && error.code in setOf("unauthorized", "forbidden", "permission_denied", "team_access_revoked")) {
            mutableState.value = NativeAgentFeedState(error = "Feed access is no longer authorized")
            fatal(error)
        }
    }
    private suspend fun fetch(): Boolean {
        checkOwner()
        mutableState.value = state.value.copy(loading = true)
        try {
            val snapshot = NativeAgentFeedWire.decode(request("feed.list", JSONObject()))
            currentCoroutineContext().ensureActive(); checkOwner()
            // Consult the live watermark after the RPC: an event can arrive during this read.
            if (!revision.accept(snapshot.revision)) return false
            val ids = snapshot.items.map { it.id }.toSet()
            localReplies.keys.retainAll(ids)
            localDecisions.keys.retainAll(snapshot.items.mapNotNull { it.requestId }.toSet())
            val projected = snapshot.copy(items = snapshot.items.map { row ->
                val decision = localDecisions[row.requestId]
                row.copy(status = if (decision != null && row.status == AgentFeedStatus.PENDING) AgentFeedStatus.RESOLVED else row.status,
                    decision = if (row.status == AgentFeedStatus.PENDING) decision ?: row.decision else row.decision,
                    replyText = row.replyText ?: localReplies[row.id])
            })
            mutableState.value = state.value.copy(snapshot = projected, error = null,
                failures = state.value.failures.filterKeys { it in ids })
            return true
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            checkOwner()
            mutableState.value = state.value.copy(error = "Could not refresh agent activity")
            failed(error)
            // A subsequent event, explicit refresh or the bounded refresh timer retries.
            return true
        } finally { mutableState.value = state.value.copy(loading = false) }
    }

    private suspend fun <T> owned(action: suspend () -> T): T {
        checkOwner()
        val operation = scope.async { checkOwner(); action() }
        return try { operation.await() } finally { operation.cancel() }
    }
    private fun live(item: NativeAgentFeedItem): NativeAgentFeedItem =
        state.value.snapshot?.items?.singleOrNull { it.id == item.id && it.workstream == item.workstream && it.requestId == item.requestId }
            ?: error("This Feed item is no longer available")

    suspend fun decide(item: NativeAgentFeedItem, decision: AgentFeedDecision): Boolean = owned {
        val row = live(item)
        check(row.needsInput) { "This request is no longer pending" }
        val method = when (row.kind) {
            AgentFeedKind.PERMISSION -> {
                require(decision.kind == "permission" && decision.mode in setOf("once", "always", "deny", "all", "bypass"))
                "feed.permission.reply"
            }
            AgentFeedKind.PLAN -> {
                require(decision.kind == "exit_plan" && decision.mode in setOf("manual", "autoAccept", "bypassPermissions", "ultraplan", "revise", "deny"))
                require(decision.mode != "revise" || !decision.feedback.isNullOrBlank())
                "feed.exit_plan.reply"
            }
            AgentFeedKind.QUESTION -> {
                require(decision.kind == "question" && decision.selections.isNotEmpty() && decision.selections.all { it.isNotBlank() })
                require(row.questions.isEmpty() || decision.selections.size == row.questions.size)
                "feed.question.reply"
            }
            else -> error("Unsupported Feed action")
        }
        val params = JSONObject().put("request_id", row.requestId)
        decision.mode?.let { params.put("mode", it) }
        if (decision.selections.isNotEmpty()) params.put("selections", JSONArray(decision.selections))
        decision.feedback?.takeIf { it.isNotEmpty() }?.let { params.put("feedback", it) }
        send(row, null) {
            request(method, params)
            currentCoroutineContext().ensureActive(); checkOwner()
            localDecisions[checkNotNull(row.requestId)] = decision
            mutableState.value = state.value.copy(snapshot = state.value.snapshot?.let { snapshot ->
                snapshot.copy(items = snapshot.items.map { if (it.requestId == row.requestId && it.status == AgentFeedStatus.PENDING)
                    it.copy(status = AgentFeedStatus.RESOLVED, decision = decision) else it })
            })
        }
    }
    suspend fun terminalReply(item: NativeAgentFeedItem, text: String): Boolean = owned {
        val row = live(item); val trimmed = text.trim()
        require(trimmed.isNotEmpty() && row.supportsTerminalReply && row.replyText == null && row.id !in localReplies)
        send(row, trimmed) {
            val result = request("mobile.terminal.paste", JSONObject().put("workspace_id", row.workspaceId)
                .put("surface_id", row.surfaceId).put("text", trimmed).put("submit_key", "return").put("feed_event_id", row.id))
            currentCoroutineContext().ensureActive(); checkOwner()
            check(result.opt("submitted") == true) { "Text may have reached the terminal, but submission was not confirmed" }
            localReplies[row.id] = trimmed
            mutableState.value = state.value.copy(snapshot = state.value.snapshot?.let { snapshot ->
                snapshot.copy(items = snapshot.items.map { if (it.id == row.id) it.copy(replyText = trimmed) else it })
            })
        }
    }
    private suspend fun send(row: NativeAgentFeedItem, draft: String?, action: suspend () -> Unit): Boolean {
        // Two rows may refer to one request. Reserve all aliases before suspending.
        val related = state.value.snapshot?.items.orEmpty().filter {
            it.id == row.id || row.requestId != null && it.requestId == row.requestId
        }.map { it.id }.toSet()
        if (related.any { it in state.value.pending }) return false
        checkOwner()
        mutableState.value = state.value.copy(pending = state.value.pending + related, failures = state.value.failures - related)
        try {
            action()
            refresh.request()
            return true
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            checkOwner()
            mutableState.value = state.value.copy(failures = state.value.failures +
                (row.id to AgentFeedFailure("Delivery could not be confirmed. Check the terminal before trying again.", AgentFeedDelivery.UNCONFIRMED, draft)))
            failed(error)
            refresh.request()
            return false
        } finally { mutableState.value = state.value.copy(pending = state.value.pending - related) }
    }
    suspend fun fullText(item: NativeAgentFeedItem): String = owned {
        live(item)
        val result = StringBuilder(); var byteCount = 0; var offset = 0; var version: Double? = null
        while (true) {
            currentCoroutineContext().ensureActive(); checkOwner()
            val params = JSONObject().put("item_id", item.id).put("offset", offset)
            version?.let { params.put("version", it) }
            val page = request("feed.text", params)
            currentCoroutineContext().ensureActive(); checkOwner()
            val text = page.opt("text") as? String ?: error("Invalid Feed text")
            val nextVersion = (page.opt("version") as? Number)?.toDouble() ?: error("Invalid Feed version")
            val size = text.toByteArray(Charsets.UTF_8).size
            check(nextVersion.isFinite() && (version == null || version == nextVersion) && size <= 16_384 && byteCount + size <= 8_388_608) {
                "Feed message changed or exceeded its size limit"
            }
            result.append(text); byteCount += size; version = nextVersion
            if (!page.has("next_offset") || page.isNull("next_offset")) break
            val next = page.opt("next_offset") as? Number ?: error("Invalid Feed text offset")
            check(next.toDouble() == (offset + size).toDouble() && next.toLong() > offset) { "Invalid Feed text offset" }
            offset = next.toInt()
        }
        result.toString()
    }
    override fun close() { job.cancel(); refresh.close() }
}
