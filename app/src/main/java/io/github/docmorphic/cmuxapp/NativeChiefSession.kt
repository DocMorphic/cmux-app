package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

internal data class NativeChiefHomeState(
    val conversations: List<NativeChiefConversation> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val changeCursor: String? = null
) {
    val pinned get() = conversations.firstOrNull { it.pinned } ?: conversations.firstOrNull()
    fun matching(query: String) = conversations.filter { query.trim().let { text ->
        text.isEmpty() || it.title.contains(text, ignoreCase = true) || it.preview.contains(text, ignoreCase = true)
    } }
}

/** Main-dispatcher confined. A retained owner supplies the exact same authority as the RPC client. */
internal class NativeChiefHomeSession(parent: CoroutineScope, private val client: NativeChiefApi,
    private val permits: () -> Boolean) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutableState = MutableStateFlow(NativeChiefHomeState())
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    private fun checkOwner() { if (!job.isActive || !permits()) { close(); error("Chief computer or account changed") } }
    private suspend fun <T> owned(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); checkOwner()
        val operation = scope.async { checkOwner(); action().also { currentCoroutineContext().ensureActive(); checkOwner() } }
        return try { operation.await() } finally { operation.cancel() }
    }

    fun start() {
        checkOwner()
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            var failures = 0
            while (isActive) {
                try {
                    val cursor = state.value.changeCursor
                    val snapshot = owned { client.conversations(cursor, cursor != null) }
                    var rows = snapshot.conversations
                    var startupFailure: String? = null
                    if (cursor == null && rows.isEmpty()) {
                        val status = try { owned { client.status() } }
                        catch (e: Exception) { currentCoroutineContext().ensureActive(); checkOwner(); null }
                        if (!status?.startFailure.isNullOrEmpty()) startupFailure = status?.startFailure
                        else if (status?.started == true && status.defaultConversation.isNotEmpty()) rows = listOf(
                            NativeChiefConversation(status.defaultConversation, "Chief", "", 0, true, false, Instant.ofEpochSecond(-62135769600)))
                    }
                    mutableState.value = NativeChiefHomeState(rows.sortedWith(compareByDescending<NativeChiefConversation> { it.pinned }
                        .thenByDescending { it.updatedAt }), false, startupFailure, snapshot.changeCursor ?: cursor)
                    if (state.value.changeCursor == null) {
                        failures = (failures + 1).coerceAtMost(10)
                        delay(if (failures == 1) 1_000 else (failures * 2_000).toLong().coerceAtMost(30_000))
                    } else failures = 0
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive(); checkOwner()
                    mutableState.value = state.value.copy(loading = false, error = e.message ?: "Could not refresh Chief")
                    failures = (failures + 1).coerceAtMost(10)
                    delay(if (failures == 1) 1_000 else (failures * 2_000).toLong().coerceAtMost(30_000))
                }
            }
        }
    }
    fun pause() { monitor?.cancel(); monitor = null }
    fun refresh() { checkOwner(); pause(); mutableState.value = state.value.copy(changeCursor = null, loading = true); start() }
    override fun close() { job.cancel(); monitor = null; mutableState.value = NativeChiefHomeState(loading = false) }
}

internal enum class NativeChiefDelivery { SENDING, QUEUED, SENT, UNCONFIRMED, REJECTED }
internal val NativeChiefDelivery.isFailed: Boolean
    get() = this == NativeChiefDelivery.UNCONFIRMED || this == NativeChiefDelivery.REJECTED
internal data class NativeChiefOutgoing(val clientMessageId: String, val text: String, val at: Instant,
    val delivery: NativeChiefDelivery = NativeChiefDelivery.SENDING, val serverMessageId: String? = null, val error: String? = null,
    val echoClientMessageId: String? = null, val failedAnchorSeq: ULong? = null, val sentAfterSeq: ULong = 0uL)
internal data class NativeChiefTranscriptState(
    val messages: List<NativeChiefMessage> = emptyList(), val outgoing: List<NativeChiefOutgoing> = emptyList(),
    val loading: Boolean = true, val loadingOlder: Boolean = false, val hasMore: Boolean = false,
    val working: Boolean = false, val connected: Boolean = false, val error: String? = null,
    val lastReadSeq: ULong = 0uL, val unread: Long = 0, val headSeq: ULong = 0uL,
    val draft: String = "", val deciding: Set<String> = emptySet(), val committed: Set<String> = emptySet(),
    val uncertain: Set<String> = emptySet(), val actionErrors: Map<String, String> = emptyMap(),
    val localClientIds: Map<String, String> = emptyMap()
)

/** Event-backed transcript. Pausing cancels reads, preserving drafts/history; retiring clears all content. */
internal class NativeChiefTranscriptSession(parent: CoroutineScope, private val client: NativeChiefApi,
    val conversationId: String, private val permits: () -> Boolean,
    private val now: () -> Instant = Instant::now, private val newId: () -> String = { UUID.randomUUID().toString() }) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutableState = MutableStateFlow(NativeChiefTranscriptState())
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    private var cursor: String? = null
    private var polledSeq = 0uL
    private var lastMarked = 0uL
    private var initialPage = true
    private val deletedClientIds = mutableSetOf<String>()
    private val deletedMessageIds = mutableSetOf<String>()
    private fun checkOwner() { if (!job.isActive || !permits()) { close(); error("Chief computer or account changed") } }
    private suspend fun <T> owned(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive(); checkOwner()
        val operation = scope.async { checkOwner(); action().also { currentCoroutineContext().ensureActive(); checkOwner() } }
        return try { operation.await() } finally { operation.cancel() }
    }
    private suspend fun read(before: ULong? = null, limit: Int = 200, after: ULong? = null,
        afterCursor: String? = null, wait: Boolean = false): NativeChiefRead = owned {
        client.read(conversationId, before, limit, after, afterCursor, wait)
    }.also { check(it.conversation == conversationId) { "Chief returned another conversation" } }

    fun start() {
        checkOwner()
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            var first = true
            while (isActive) {
                try {
                    val result = read(after = if (first) null else polledSeq, afterCursor = if (first) null else cursor, wait = !first)
                    val messages = coveringGap(result)
                    merge(messages)
                    cursor = result.changeCursor ?: cursor
                    polledSeq = maxOf(polledSeq, messages.maxOfOrNull { it.seq } ?: 0uL)
                    mutableState.value = state.value.copy(loading = false, connected = true, error = null,
                        hasMore = if (initialPage) result.hasMore else state.value.hasMore, working = result.working,
                        lastReadSeq = result.lastReadSeq ?: 0uL, unread = maxOf(0, result.unreadCount ?: 0),
                        headSeq = result.headSeq ?: maxOf(polledSeq, result.messages.maxOfOrNull { it.seq } ?: 0uL))
                    initialPage = false; first = false
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive(); checkOwner()
                    mutableState.value = state.value.copy(loading = false, connected = false, error = e.message ?: "Chief disconnected")
                    delay(3_000)
                }
            }
        }
    }

    private suspend fun coveringGap(result: NativeChiefRead): List<NativeChiefMessage> {
        val first = result.messages.minOfOrNull { it.seq } ?: return result.messages
        if (polledSeq == 0uL || polledSeq == ULong.MAX_VALUE || first <= polledSeq || first - polledSeq <= 1uL) return result.messages
        val pages = mutableListOf(result.messages)
        var before = first; var more = result.hasMore
        while (more && before > polledSeq + 1uL) {
            val page = read(before)
            if (page.messages.isEmpty()) break
            val next = page.messages.minOf { it.seq }
            check(next < before && page.messages.all { it.seq < before }) { "Chief history did not advance" }
            pages.add(0, page.messages); before = next; more = page.hasMore
        }
        return pages.flatten().associateBy { it.id }.values.sortedBy { it.seq }
    }

    private fun merge(incoming: List<NativeChiefMessage>, acknowledgedSend: String? = null) {
        checkOwner()
        val messages = incoming.filterNot { it.id in deletedMessageIds || it.clientMessageId in deletedClientIds }
        var outgoing = state.value.outgoing
        val localClientIds = state.value.localClientIds.toMutableMap()
        val earliest = now().minusSeconds(300)
        val humans = messages.filter { it.authorKind == "human" }
        fun correlate(message: NativeChiefMessage, candidate: NativeChiefOutgoing) {
            localClientIds[message.id] = candidate.clientMessageId
            outgoing = outgoing.filterNot { it.clientMessageId == candidate.clientMessageId }
        }
        // Resolve exact ids across the whole page before trying text. Otherwise
        // an older identical row can steal a send whose exact echo is later in it.
        humans.forEach { message ->
            val candidate = localClientIds[message.id]?.let { id -> outgoing.firstOrNull { it.clientMessageId == id } }
                ?: message.clientMessageId?.let { id -> outgoing.firstOrNull { it.clientMessageId == id || it.echoClientMessageId == id } }
                ?: outgoing.firstOrNull { it.serverMessageId == message.id }
            if (candidate != null) correlate(message, candidate)
        }
        // The accepted send's immediate read has the source's bounded clock-skew
        // fallback. Poll/history correlation remains stricter (at >= startedAt).
        outgoing.singleOrNull { it.clientMessageId == acknowledgedSend }?.let { pending ->
            humans.filter { it.id !in localClientIds && it.clientMessageId == null &&
                it.seq > pending.sentAfterSeq && it.text == pending.text && it.at >= pending.at.minusSeconds(5) }.maxByOrNull { it.at }
                ?.let { correlate(it, pending) }
        }
        humans.filter { it.id !in localClientIds && it.clientMessageId == null }.forEach { message ->
            outgoing.filter { it.serverMessageId == null && it.delivery !in setOf(NativeChiefDelivery.REJECTED, NativeChiefDelivery.UNCONFIRMED) &&
                it.at >= earliest && message.seq > it.sentAfterSeq && it.text == message.text && message.at >= it.at }.minByOrNull { it.at }
                ?.let { correlate(message, it) }
        }
        // A late older read cannot resurrect a pending card once the Mac returned its receipt.
        val byId = state.value.messages.associateBy { it.id }.toMutableMap()
        messages.forEach { next ->
            val previous = byId[next.id]
            byId[next.id] = if (previous == null) next else next.copy(partPayloads = next.partPayloads.map { part ->
                previous.partPayloads.firstOrNull { old ->
                    when (part.kind) {
                        "approval" -> old.kind == part.kind && old.approval?.request == part.approval?.request &&
                            old.approval?.decided != null && part.approval?.decided == null
                        "question" -> old.kind == part.kind && old.question?.id == part.question?.id &&
                            old.question?.state != null && old.question.state != NativeChiefQuestion.State.PENDING &&
                            part.question?.state == NativeChiefQuestion.State.PENDING
                        else -> false
                    }
                } ?: part
            })
        }
        val sorted = byId.values.sortedWith(compareBy<NativeChiefMessage> { it.seq }.thenBy { it.id })
        val settled = sorted.filter { NativeChiefPresentation.pendingPart(it) == null }.map { it.id }.toSet()
        mutableState.value = state.value.copy(messages = sorted, outgoing = outgoing,
            localClientIds = localClientIds, committed = state.value.committed - settled, uncertain = state.value.uncertain - settled)
    }

    suspend fun loadOlder() {
        checkOwner()
        if (state.value.loadingOlder || !state.value.hasMore) return
        val before = state.value.messages.minOfOrNull { it.seq } ?: return
        mutableState.value = state.value.copy(loadingOlder = true)
        try {
            val result = read(before)
            check(result.messages.all { it.seq < before }) { "Chief history did not advance" }
            merge(result.messages)
            mutableState.value = state.value.copy(hasMore = result.hasMore && result.messages.isNotEmpty(), error = null)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive(); checkOwner()
            mutableState.value = state.value.copy(error = e.message ?: "Could not load earlier messages")
        } finally { if (job.isActive && permits()) mutableState.value = state.value.copy(loadingOlder = false) }
    }

    fun editDraft(text: String) { checkOwner(); mutableState.value = state.value.copy(draft = text) }

    /** One explicit send, one stable id. An uncertain write is never automatically resent. */
    suspend fun send(): String? {
        checkOwner()
        val draft = state.value.draft
        if (draft.isBlank()) return null
        val id = newId()
        require(state.value.outgoing.none { it.clientMessageId == id })
        mutableState.value = state.value.copy(draft = "", outgoing = state.value.outgoing + NativeChiefOutgoing(id, draft, now(),
            sentAfterSeq = maxOf(state.value.headSeq, state.value.messages.maxOfOrNull { it.seq } ?: 0uL)))
        transmit(id, draft)
        return id
    }

    /** Source Try Again: reserve synchronously, reuse the client id, and move the row to the current tail. */
    suspend fun retry(clientMessageId: String): Boolean {
        checkOwner()
        val failed = state.value.outgoing.singleOrNull { it.clientMessageId == clientMessageId && it.delivery.isFailed } ?: return false
        mutableState.value = state.value.copy(outgoing = state.value.outgoing.map {
            if (it.clientMessageId == clientMessageId) it.copy(at = now(), delivery = NativeChiefDelivery.SENDING,
                error = null, failedAnchorSeq = null,
                sentAfterSeq = maxOf(state.value.headSeq, state.value.messages.maxOfOrNull { message -> message.seq } ?: 0uL)) else it
        })
        transmit(clientMessageId, failed.text)
        return true
    }

    /** Delete only the failed local row. Known late echoes stay hidden for this owner's retained session. */
    fun discardFailed(clientMessageId: String): Boolean {
        checkOwner()
        val failed = state.value.outgoing.singleOrNull { it.clientMessageId == clientMessageId && it.delivery.isFailed } ?: return false
        deletedClientIds += failed.clientMessageId
        failed.echoClientMessageId?.let { deletedClientIds += it }
        failed.serverMessageId?.let { deletedMessageIds += it }
        state.value.localClientIds.filterValues { it == clientMessageId }.keys.forEach { deletedMessageIds += it }
        mutableState.value = state.value.copy(outgoing = state.value.outgoing.filterNot { it.clientMessageId == clientMessageId })
        return true
    }

    private suspend fun transmit(id: String, text: String) {
        try {
            val sent = owned { client.send(text, conversationId, clientMessageId = id) }
            check(sent.conversation == conversationId) { "Chief returned another conversation" }
            mutableState.value = state.value.copy(outgoing = state.value.outgoing.map {
                if (it.clientMessageId == id) it.copy(serverMessageId = sent.messageId,
                    echoClientMessageId = sent.clientMessageId,
                    delivery = if (sent.queued) NativeChiefDelivery.QUEUED else NativeChiefDelivery.SENT) else it
            })
            // An event read can precede the acknowledgment that supplies its
            // canonical id. Correlate that already-retained echo immediately.
            merge(state.value.messages)
            try { merge(read().messages, acknowledgedSend = id) }
            catch (e: Exception) { currentCoroutineContext().ensureActive(); checkOwner() }
        } catch (e: Exception) {
            if (job.isActive && permits()) mutableState.value = state.value.copy(outgoing = state.value.outgoing.map {
                if (it.clientMessageId == id) it.copy(delivery = if (e is MobileRpcException && e.fromHostResponse)
                    NativeChiefDelivery.REJECTED else NativeChiefDelivery.UNCONFIRMED,
                    failedAnchorSeq = state.value.messages.maxOfOrNull { message -> message.seq } ?: 0uL,
                    error = e.message ?: "Delivery could not be confirmed. Check the conversation before sending again.") else it
            })
            currentCoroutineContext().ensureActive(); checkOwner()
        }
    }

    suspend fun markRead(upToSeq: ULong = state.value.headSeq) {
        checkOwner()
        if (upToSeq == 0uL || upToSeq <= lastMarked) return
        try {
            val marked = owned { client.markRead(conversationId, upToSeq) }
            check(marked.conversation == conversationId) { "Chief returned another conversation" }
            if (marked.marked) lastMarked = maxOf(lastMarked, marked.upToSeq)
        } catch (e: Exception) { currentCoroutineContext().ensureActive(); checkOwner() }
    }

    suspend fun approve(messageId: String, choice: String): Boolean {
        checkOwner()
        val message = state.value.messages.single { it.id == messageId }
        val approval = NativeChiefPresentation.pendingPart(message)?.takeIf { it.kind == "approval" }?.approval
            ?: error("That approval is no longer waiting")
        require(choice in approval.options && !choice.startsWith("always:")) { "That approval option is no longer available" }
        return decide(message) {
            val result = owned { client.approve(conversationId, approval.request, choice) }
            check(result.conversation == conversationId && result.request == approval.request && result.decided) { "Chief did not confirm this approval" }
        }
    }

    suspend fun answer(messageId: String, form: NativeChiefQuestionForm): Boolean {
        checkOwner()
        val message = state.value.messages.single { it.id == messageId }
        val question = NativeChiefPresentation.pendingPart(message)?.takeIf { it.kind == "question" }?.question
            ?: error("That Chief question is no longer waiting")
        require(form.question == question && form.canSubmit) { "Choose an answer for each question before submitting" }
        return decide(message) {
            val result = owned { client.answer(conversationId, messageId, form.choices, form.text) }
            check(result.conversation == conversationId && result.message == messageId) { "Chief did not confirm this question" }
        }
    }

    private suspend fun decide(message: NativeChiefMessage, action: suspend () -> Unit): Boolean {
        val id = message.id
        if (id in state.value.deciding || id in state.value.committed) return false
        // A user may explicitly retry a failed answer, as in the source sheet.
        // Polling and refresh never retry it, and a confirmed commit remains reserved.
        mutableState.value = state.value.copy(deciding = state.value.deciding + id, uncertain = state.value.uncertain - id,
            actionErrors = state.value.actionErrors - id)
        try {
            action()
            mutableState.value = state.value.copy(committed = state.value.committed + id)
            // The action is committed. A failed follow-up read must not invite a duplicate answer.
            try {
                val result = read()
                merge(result.messages)
                if (result.messages.none { it.id == id } && message.seq < ULong.MAX_VALUE) merge(read(message.seq + 1uL, 1).messages)
            } catch (e: Exception) { currentCoroutineContext().ensureActive(); checkOwner() }
            return true
        } catch (e: Exception) {
            if (job.isActive && permits()) mutableState.value = state.value.copy(
                uncertain = if (e is MobileRpcException && e.fromHostResponse) state.value.uncertain else state.value.uncertain + id,
                actionErrors = state.value.actionErrors + (id to (e.message ?: "The answer could not be confirmed. Refresh the conversation.")))
            currentCoroutineContext().ensureActive(); checkOwner(); return false
        } finally { if (job.isActive && permits()) mutableState.value = state.value.copy(deciding = state.value.deciding - id) }
    }

    suspend fun stop(): Boolean {
        checkOwner()
        if ("stop" in state.value.deciding) return false
        mutableState.value = state.value.copy(deciding = state.value.deciding + "stop")
        try {
            val result = owned { client.stop(conversationId) }
            check(result.conversation == conversationId) { "Chief returned another conversation" }
            if (result.stopped) mutableState.value = state.value.copy(working = false)
            return result.stopped
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive(); checkOwner()
            mutableState.value = state.value.copy(error = e.message ?: "Stop could not be confirmed"); return false
        } finally { if (job.isActive && permits()) mutableState.value = state.value.copy(deciding = state.value.deciding - "stop") }
    }

    fun pause() { monitor?.cancel(); monitor = null }
    fun refresh() { checkOwner(); pause(); start() }
    override fun close() {
        job.cancel(); monitor = null; deletedClientIds.clear(); deletedMessageIds.clear()
        mutableState.value = NativeChiefTranscriptState(loading = false)
    }
}
