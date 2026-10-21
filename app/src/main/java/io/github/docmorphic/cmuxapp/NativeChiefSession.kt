package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID

internal data class NativeChiefHomeState(
    val conversations: List<NativeChiefConversation> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val changeCursor: String? = null
) {
    val pinned get() = conversations.firstOrNull { it.pinned } ?: conversations.firstOrNull()
    fun matching(query: String): List<NativeChiefConversation> {
        val text = NativeChiefTextPolicy.trim(query)
        return conversations.filter {
            text.isEmpty() || it.title.contains(text, ignoreCase = true) || it.preview.contains(text, ignoreCase = true)
        }
    }
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
    val echoClientMessageId: String? = null, val failedAnchorSeq: ULong? = null, val sentAfterSeq: ULong = 0uL, val replyToID: String? = null)
internal data class NativeChiefListState(val muted: Boolean = false, val readReceipts: Boolean = true)

internal data class NativeChiefTranscriptState(
    val messages: List<NativeChiefMessage> = emptyList(), val outgoing: List<NativeChiefOutgoing> = emptyList(),
    val loading: Boolean = true, val loadingOlder: Boolean = false, val hasMore: Boolean = false,
    val historyRetryAttempt: Int = 0, val historyError: String? = null, val readsActive: Boolean = false,
    val working: Boolean = false, val connected: Boolean = false, val error: String? = null, val retired: Boolean = false,
    val hasLoadedInfo: Boolean = false, val listState: NativeChiefListState = NativeChiefListState(),
    val lastReadSeq: ULong = 0uL, val unread: Long = 0, val headSeq: ULong = 0uL,
    val catchUpMarker: ULong? = null, val catchUpCount: Long = 0, val loadingCatchUp: Boolean = false,
    val localSendRevision: Long = 0, val reply: NativeChiefReply? = null,
    val draft: String = "", val draftRevision: Long = 0, val deciding: Set<String> = emptySet(), val committed: Set<String> = emptySet(),
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
    private var replyGeneration = 0L
    private var listStateGeneration = 0L
    val state = mutableState.asStateFlow()
    private var monitor: Job? = null
    private var cursor: String? = null
    private var polledSeq = 0uL
    private var lastMarked = 0uL
    private var initialPage = true
    private var reading = NativeChiefReading()
    private var awaitingReadBaseline = false
    private var sendTail: Deferred<Unit>? = null
    private var olderTask: Job? = null
    private var olderWanted = false
    private var olderGeneration = 0L
    private var readsPaused = false
    private var historyOperation: Deferred<NativeChiefRead>? = null
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
        readsPaused = false
        mutableState.value = state.value.copy(readsActive = true)
        awaitingReadBaseline = true
        monitor = scope.launch {
            var first = true
            while (isActive) {
                try {
                    val result = read(limit = if (first) 50 else 200, after = if (first) null else polledSeq,
                        afterCursor = if (first) null else cursor, wait = !first)
                    val messages = coveringGap(result)
                    merge(messages, updateReading = false)
                    cursor = result.changeCursor ?: cursor
                    polledSeq = maxOf(polledSeq, messages.maxOfOrNull { it.seq } ?: 0uL)
                    val reconnecting = !state.value.connected
                    mutableState.value = state.value.copy(loading = false, connected = true, hasLoadedInfo = true, error = null,
                        hasMore = if (initialPage) result.hasMore else state.value.hasMore, working = result.working,
                        headSeq = result.headSeq ?: maxOf(polledSeq, result.messages.maxOfOrNull { it.seq } ?: 0uL))
                    if (first || reconnecting) reading = reading.copy(pending = 0uL)
                    val serverRead = result.lastReadSeq ?: 0uL
                    // Once pending reads are acknowledged, a server-side Mark
                    // Unread is authoritative and may require the same receipt again.
                    if (serverRead >= reading.pending) lastMarked = minOf(lastMarked, serverRead)
                    reading = reading.accept(NativeChiefReading.Server(serverRead,
                        maxOf(0, result.unreadCount ?: 0), state.value.headSeq))
                    initialPage = false; first = false; awaitingReadBaseline = false
                    refreshReading()
                    if (olderWanted) requestOlder()
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

    private fun merge(incoming: List<NativeChiefMessage>, acknowledgedSend: String? = null, updateReading: Boolean = true,
        allowOlder: Boolean = false) {
        checkOwner()
        val known = state.value.messages.map { it.id }.toSet()
        val oldest = state.value.messages.firstOrNull()?.seq
        val pendingServerIds = state.value.outgoing.mapNotNull { it.serverMessageId }.toSet()
        val pendingClientIds = state.value.outgoing.flatMap { listOfNotNull(it.clientMessageId, it.echoClientMessageId) }.toSet()
        // A newest-page event/action read can contain history above the visible
        // window. Like ingestLive, update known rows without pulling unknown old
        // rows across a gap; only explicit history loading widens the window.
        val messages = incoming.filter { message ->
            message.id !in deletedMessageIds && message.clientMessageId !in deletedClientIds &&
                (allowOlder || oldest == null || message.seq >= oldest || message.id in known ||
                    message.authorKind == "human" && (message.id in pendingServerIds || message.clientMessageId in pendingClientIds))
        }
        var outgoing = state.value.outgoing
        fun pending() = outgoing.filter { acknowledgedSend == null || it.clientMessageId == acknowledgedSend }
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
            val candidate = localClientIds[message.id]?.let { id -> pending().firstOrNull { it.clientMessageId == id } }
                ?: message.clientMessageId?.let { id -> pending().firstOrNull { it.clientMessageId == id || it.echoClientMessageId == id } }
                ?: pending().firstOrNull { it.serverMessageId == message.id }
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
            pending().filter { it.serverMessageId == null && it.delivery !in setOf(NativeChiefDelivery.REJECTED, NativeChiefDelivery.UNCONFIRMED) &&
                it.at >= earliest && message.seq > it.sentAfterSeq && it.text == message.text && message.at >= it.at }.minByOrNull { it.at }
                ?.let { correlate(message, it) }
        }
        // A late older read cannot resurrect a pending card once the Mac returned its receipt.
        val byId = state.value.messages.associateBy { it.id }.toMutableMap()
        messages.filter { acknowledgedSend == null || localClientIds[it.id] == acknowledgedSend }.forEach { next ->
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
        if (updateReading) refreshReading()
    }

    private fun publishReading() {
        mutableState.value = state.value.copy(lastReadSeq = reading.lastReadSeq, unread = reading.unread,
            catchUpMarker = reading.marker, catchUpCount = reading.catchUpCount)
    }
    private fun refreshReading() {
        val update = reading.refresh(state.value.messages, !initialPage && !awaitingReadBaseline, !state.value.hasMore)
        reading = update.state; publishReading()
        update.receipt?.let { seq -> scope.launch {
            try { markRead(seq) } catch (error: Exception) { currentCoroutineContext().ensureActive() }
        } }
    }
    fun setViewing(value: Boolean) {
        checkOwner(); reading = reading.viewing(value)
        if (!value || monitor?.isActive == true) refreshReading() else publishReading()
    }
    fun endVisit() { checkOwner(); reading = reading.endVisit(); publishReading() }
    fun dismissCatchUp() { checkOwner(); reading = reading.dismiss(); publishReading() }
    fun catchUpTarget() = reading.target(state.value.messages, !state.value.hasMore)
    fun observeVisible(messageIds: Set<String>, localSendVisible: Boolean) {
        checkOwner()
        val marker = reading.marker ?: return
        val messages = state.value.messages
        val first = messages.indexOfFirst { it.id in messageIds }
        val target = catchUpTarget()?.let { item -> messages.indexOfFirst { it.id == item.id } }
        if ((first >= 0 && target != null && target >= first) || localSendVisible ||
            messages.any { it.id in messageIds && it.authorKind == "human" && it.seq > marker }) dismissCatchUp()
    }

    /** The UI calls this only when resting at newest, outside a scroll or an open action sheet. */
    fun trimHistoryIfResting(preserving: Set<String> = emptySet()): Boolean {
        checkOwner()
        val snapshot = state.value
        if (snapshot.reply != null) return false
        val items = NativeChiefTimeline.items(snapshot)
        if (items.size <= NativeChiefWindow.trimThreshold) return false
        val held = preserving + snapshot.deciding + snapshot.committed + snapshot.uncertain
        val cut = NativeChiefWindow.cut(items, NativeChiefWindow.keepCount, !initialPage,
            snapshot.loadingOlder || snapshot.loadingCatchUp, reading.marker, reading.unread, reading.lastReadSeq, held)
        if (cut <= 0) return false
        val kept = items.drop(cut).mapNotNull { it.message?.id }.toSet()
        mutableState.value = snapshot.copy(messages = snapshot.messages.filter { it.id in kept }, hasMore = true,
            localClientIds = snapshot.localClientIds.filterKeys { it in kept },
            actionErrors = snapshot.actionErrors.filterKeys { it in kept })
        olderWanted = false
        refreshReading()
        return true
    }

    /** Scroll ticks share one owner-held fetch; leaving the region stops retries, not an in-flight read. */
    fun requestOlder() {
        checkOwner()
        if (readsPaused) return
        olderWanted = true
        if (initialPage || state.value.loadingOlder || state.value.loadingCatchUp || !state.value.hasMore || olderTask?.isActive == true) return
        val generation = ++olderGeneration
        mutableState.value = state.value.copy(loadingOlder = true, historyRetryAttempt = 0)
        olderTask = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                var attempt = 0
                while (isActive) {
                    if (loadOlderPage(50, reserved = true)) { olderWanted = false; return@launch }
                    attempt = (attempt + 1).coerceAtMost(Int.MAX_VALUE - 1)
                    mutableState.value = state.value.copy(historyRetryAttempt = attempt)
                    delay(NativeChiefHistory.backoff(attempt))
                    if (!olderWanted) return@launch
                }
            } finally {
                if (generation == olderGeneration && job.isActive && permits())
                    mutableState.value = state.value.copy(loadingOlder = false, historyRetryAttempt = 0)
            }
        }
    }
    fun olderNoLongerWanted() { checkOwner(); olderWanted = false }
    suspend fun loadOlder() { requestOlder(); olderTask?.join() }
    private suspend fun loadOlderPage(limit: Int, reserved: Boolean = false): Boolean {
        checkOwner()
        if (readsPaused || (!reserved && state.value.loadingOlder) || !state.value.hasMore) return false
        val before = state.value.messages.minOfOrNull { it.seq } ?: return false
        val generation = olderGeneration
        mutableState.value = state.value.copy(loadingOlder = true)
        val operation = scope.async { read(before, limit) }
        historyOperation = operation
        try {
            val result = operation.await()
            if (readsPaused || generation != olderGeneration) return false
            val known = state.value.messages.map { it.id }.toSet()
            merge(result.messages.filter { it.seq < before && it.id !in known }, allowOlder = true)
            mutableState.value = state.value.copy(hasMore = result.hasMore, historyError = null)
            refreshReading()
            return true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive(); checkOwner()
            if (readsPaused || generation != olderGeneration) return false
            mutableState.value = state.value.copy(historyError = e.message ?: "Could not load earlier messages")
            return false
        } finally {
            if (historyOperation === operation) historyOperation = null
            operation.cancel()
            if (!reserved && generation == olderGeneration && job.isActive && permits())
                mutableState.value = state.value.copy(loadingOlder = false)
        }
    }

    suspend fun loadCatchUpTarget(): String? {
        checkOwner()
        if (readsPaused) return null
        val generation = olderGeneration
        val marker = reading.marker?.takeIf { it < ULong.MAX_VALUE } ?: return null
        if (state.value.loadingCatchUp) return null
        mutableState.value = state.value.copy(loadingCatchUp = true)
        try {
            var failures = 0
            while (reading.marker == marker) {
                state.first { !it.loadingOlder }; checkOwner()
                if (readsPaused || generation != olderGeneration) return null
                val oldest = state.value.messages.firstOrNull()?.seq ?: return null
                if (oldest <= marker + 1uL || !state.value.hasMore) return catchUpTarget()?.id
                val distance = oldest - (marker + 1uL)
                val limit = if (distance >= 192uL) 200 else maxOf(50, distance.toInt() + 8)
                val loaded = loadOlderPage(limit)
                if (readsPaused || generation != olderGeneration) return null
                if (loaded) failures = 0 else {
                    if (++failures > 4) return null
                    delay(NativeChiefHistory.backoff(failures))
                }
            }
            return null
        } finally { if (generation == olderGeneration && job.isActive && permits()) mutableState.value = state.value.copy(loadingCatchUp = false) }
    }

    /** Chief inherits ConversationBackend's -32601 list-state rejection. Match the
     * store's optimistic publication/generation-fenced rollback without inventing a host RPC
     * or changing this phone's notification/helper preferences. */
    fun updateDetails(muted: Boolean? = null, readReceipts: Boolean? = null) {
        checkOwner()
        if (!state.value.hasLoadedInfo) return
        val previous = state.value.listState
        val changed = previous.copy(muted = muted ?: previous.muted, readReceipts = readReceipts ?: previous.readReceipts)
        if (changed == previous) return
        val generation = ++listStateGeneration
        mutableState.value = state.value.copy(listState = changed)
        scope.launch {
            // Source Task runs the unsupported backend call after the UI event.
            yield()
            if (!permits()) { close(); return@launch }
            if (generation == listStateGeneration) mutableState.value = state.value.copy(listState = previous)
        }
    }

    fun openReplyThread(rootID: String, targetID: String = rootID, releaseOffset: Float = 0f): Boolean {
        checkOwner()
        val snapshot = NativeChiefReplyPolicy.open(state.value, rootID, targetID, replyGeneration + 1, releaseOffset) ?: return false
        replyGeneration = snapshot.generation
        mutableState.value = state.value.copy(reply = snapshot)
        return true
    }

    fun replyTo(messageID: String, releaseOffset: Float = 0f): Boolean {
        checkOwner()
        val item = NativeChiefTimeline.items(state.value).firstOrNull { it.messageID == messageID } ?: return false
        return openReplyThread(item.replyToID ?: item.messageID, item.messageID, releaseOffset)
    }

    fun closeReplyThread() { checkOwner(); mutableState.value = state.value.copy(reply = null) }

    fun editDraft(text: String): Long {
        checkOwner()
        if (text != state.value.draft) mutableState.value = state.value.copy(draft = text, draftRevision = state.value.draftRevision + 1)
        return state.value.draftRevision
    }

    /** One explicit send, one stable id. An uncertain write is never automatically resent. */
    suspend fun send(capturedDraft: String? = null, capturedRevision: Long? = null): String? {
        currentCoroutineContext().ensureActive()
        val submission = submit(capturedDraft, capturedRevision) ?: return null
        submission.second.await()
        return submission.first
    }

    /** Synchronous UI admission, matching Store.send: retiring the view cannot lose a clicked send. */
    fun submitFromComposer(text: String, revision: Long): String? = submit(text, revision)?.first

    private fun submit(capturedDraft: String?, capturedRevision: Long?): Pair<String, Deferred<Unit>>? {
        checkOwner()
        require((capturedDraft == null) == (capturedRevision == null))
        val draft = NativeChiefComposerPolicy.trim(capturedDraft ?: state.value.draft)
        if (draft.isEmpty()) return null
        val id = newId()
        require(state.value.outgoing.none { it.clientMessageId == id })
        val clear = capturedRevision == null || capturedRevision == state.value.draftRevision
        val replyToID = state.value.reply?.targetID
        mutableState.value = state.value.copy(reply = null, draft = if (clear) "" else state.value.draft,
            draftRevision = if (clear) state.value.draftRevision + 1 else state.value.draftRevision,
            localSendRevision = state.value.localSendRevision + 1,
            outgoing = state.value.outgoing + NativeChiefOutgoing(id, draft, now(),
            sentAfterSeq = maxOf(state.value.headSeq, state.value.messages.maxOfOrNull { it.seq } ?: 0uL), replyToID = replyToID))
        return id to enqueueTransmit(id, draft)
    }

    /** Source Try Again: reserve synchronously, reuse the client id, and move the row to the current tail. */
    suspend fun retry(clientMessageId: String): Boolean {
        currentCoroutineContext().ensureActive()
        checkOwner()
        val failed = state.value.outgoing.singleOrNull { it.clientMessageId == clientMessageId && it.delivery.isFailed } ?: return false
        mutableState.value = state.value.copy(outgoing = state.value.outgoing.map {
            if (it.clientMessageId == clientMessageId) it.copy(at = now(), delivery = NativeChiefDelivery.SENDING,
                error = null, failedAnchorSeq = null,
                sentAfterSeq = maxOf(state.value.headSeq, state.value.messages.maxOfOrNull { message -> message.seq } ?: 0uL)) else it
        })
        enqueueTransmit(clientMessageId, failed.text).await()
        return true
    }

    /** Delete only the failed local row. Known late echoes stay hidden for this owner's retained session. */
    fun discardFailed(clientMessageId: String): Boolean {
        checkOwner()
        if (state.value.outgoing.none { it.clientMessageId == clientMessageId && it.delivery.isFailed }) return false
        return deleteLocally(setOf("c:$clientMessageId"))
    }

    /** Source More/Delete: remove only the captured stable rows for this owner.
     * Tombstones survive pause/history reads. Queued sends lose their row before
     * transmit's admission check; an already-started request is not cancelled. */
    fun deleteLocally(rowIds: Set<String>): Boolean {
        checkOwner()
        val doomed = NativeChiefTimeline.items(state.value).filter { it.key in rowIds }
        if (doomed.isEmpty()) return false
        for (item in doomed) {
            item.message?.let { message ->
                deletedMessageIds += message.id
                message.clientMessageId?.let { deletedClientIds += it }
                state.value.localClientIds[message.id]?.let { deletedClientIds += it }
            }
            item.outgoing?.let { outgoing ->
                deletedClientIds += outgoing.clientMessageId
                outgoing.echoClientMessageId?.let { deletedClientIds += it }
                outgoing.serverMessageId?.let { deletedMessageIds += it }
            }
        }
        state.value.localClientIds.filterValues { it in deletedClientIds }.keys.forEach { deletedMessageIds += it }
        mutableState.value = state.value.copy(
            messages = state.value.messages.filterNot { it.id in deletedMessageIds || it.clientMessageId in deletedClientIds },
            outgoing = state.value.outgoing.filterNot { it.clientMessageId in deletedClientIds })
        return true
    }

    /** Like the source store's sendTail, the retained session owns FIFO work, including its acknowledgment read.
     * Leaving a view may cancel its waiter, but only retiring this owner cancels admitted sends. */
    private fun enqueueTransmit(id: String, text: String): Deferred<Unit> {
        val previous = sendTail
        val next = scope.async(start = CoroutineStart.LAZY) {
            previous?.join()
            checkOwner()
            // A queued retry can receive the original send's exact echo before its turn.
            if (state.value.outgoing.none { it.clientMessageId == id }) return@async
            transmit(id, text)
        }
        sendTail = next
        next.start()
        return next
    }

    private suspend fun transmit(id: String, text: String) {
        try {
            val sent = owned { client.send(text, conversationId, clientMessageId = id) }
            check(sent.conversation == conversationId) { "Chief returned another conversation" }
            // Deletion can happen while send awaits the host. Retain canonical
            // identifiers from its ack even if its local row has already gone,
            // so a later history page without a client id cannot resurrect it.
            if (id in deletedClientIds) {
                sent.messageId?.let { deletedMessageIds += it }
                sent.clientMessageId?.let { deletedClientIds += it }
                mutableState.value = state.value.copy(messages = state.value.messages.filterNot {
                    it.id in deletedMessageIds || it.clientMessageId in deletedClientIds
                })
            }
            mutableState.value = state.value.copy(outgoing = state.value.outgoing.map {
                if (it.clientMessageId == id) it.copy(serverMessageId = sent.messageId, replyToID = null,
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
        reading = reading.copy(lastReadSeq = maxOf(reading.lastReadSeq, upToSeq), pending = maxOf(reading.pending, upToSeq))
        refreshReading()
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
                merge(result.messages.filter { it.id == id })
                if (result.messages.none { it.id == id } && message.seq < ULong.MAX_VALUE)
                    merge(read(message.seq + 1uL, 1).messages.filter { it.id == id })
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

    fun pause() {
        readsPaused = true; monitor?.cancel(); monitor = null; ++olderGeneration; olderWanted = false; olderTask?.cancel(); olderTask = null
        historyOperation?.cancel(); historyOperation = null
        mutableState.value = state.value.copy(readsActive = false, loadingOlder = false, loadingCatchUp = false, historyRetryAttempt = 0)
    }
    fun refresh() { checkOwner(); pause(); start() }
    override fun close() {
        val clearedDraftRevision = state.value.draftRevision + 1
        job.cancel(); monitor = null; sendTail = null; olderTask = null; olderWanted = false; historyOperation = null; deletedClientIds.clear(); deletedMessageIds.clear()
        reading = NativeChiefReading(); mutableState.value = NativeChiefTranscriptState(loading = false, draftRevision = clearedDraftRevision, retired = true)
    }
}
