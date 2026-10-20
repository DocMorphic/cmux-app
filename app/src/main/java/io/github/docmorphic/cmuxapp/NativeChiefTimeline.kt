package io.github.docmorphic.cmuxapp

import java.time.Duration
import java.time.Instant

/** Chief's subset of ConversationStore ordering and ConversationRunPlan. */
internal data class NativeChiefTimelineItem(val key: String, val message: NativeChiefMessage? = null,
    val outgoing: NativeChiefOutgoing? = null, val localSenderId: String = "me") {
    init { require((message == null) != (outgoing == null)) }
    val seq get() = message?.seq
    val at: Instant get() = message?.at ?: checkNotNull(outgoing).at
    val senderId get() = message?.authorId ?: localSenderId
    val fromMe get() = message?.authorKind == "human" || outgoing != null
    val failed get() = outgoing?.delivery in setOf(NativeChiefDelivery.UNCONFIRMED, NativeChiefDelivery.REJECTED)
}

internal data class NativeChiefTimelineRow(val item: NativeChiefTimelineItem, val timestamp: Boolean,
    val firstInRun: Boolean, val lastInRun: Boolean, val quote: NativeChiefMessage?)

internal object NativeChiefTimeline {
    fun items(state: NativeChiefTranscriptState): List<NativeChiefTimelineItem> {
        val sender = state.messages.lastOrNull { it.authorKind == "human" }?.authorId ?: "me"
        val acknowledged = state.messages.sortedBy { it.seq }.map { message ->
            val clientId = if (message.authorKind == "human") state.localClientIds[message.id] ?: message.clientMessageId else null
            NativeChiefTimelineItem(clientId?.let { "c:$it" } ?: "s:${message.id}", message = message)
        }.toMutableList()
        val local = state.outgoing.map { NativeChiefTimelineItem("c:${it.clientMessageId}", outgoing = it, localSenderId = sender) }
        local.filter { it.failed }.sortedBy { it.at }.asReversed().forEach { failed ->
            val anchor = failed.outgoing?.failedAnchorSeq
            val index = if (anchor != null) acknowledged.indexOfFirst { (it.seq ?: 0uL) > anchor }
                else acknowledged.indexOfFirst { it.at > failed.at }
            acknowledged.add(if (index < 0) acknowledged.size else index, failed)
        }
        return acknowledged + local.filterNot { it.failed }.sortedBy { it.at }
    }

    fun rows(state: NativeChiefTranscriptState): List<NativeChiefTimelineRow> {
        val items = items(state)
        val byId = state.messages.associateBy { it.id }
        fun gap(a: NativeChiefTimelineItem, b: NativeChiefTimelineItem) = Duration.between(a.at, b.at)
        fun sameRun(a: NativeChiefTimelineItem, b: NativeChiefTimelineItem) = a.senderId == b.senderId &&
            gap(a, b) < Duration.ofMinutes(5) && b.message?.threadRoot == null
        val chief = state.messages.firstOrNull { it.authorKind == "chief" }?.authorId ?: "agent_chief"
        return items.mapIndexed { index, item ->
            val previous = items.getOrNull(index - 1); val next = items.getOrNull(index + 1)
            val timestamp = previous == null || gap(previous, item) >= Duration.ofHours(1)
            val first = timestamp || previous == null || !sameRun(previous, item)
            val last = when {
                next == null -> !(state.working && item.senderId == chief)
                gap(item, next) >= Duration.ofHours(1) -> true
                else -> !sameRun(item, next)
            } || item.failed
            NativeChiefTimelineRow(item, timestamp, first, last, item.message?.threadRoot?.let(byId::get))
        }
    }
}
