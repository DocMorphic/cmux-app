package io.github.docmorphic.cmuxapp

/** Memory-only, one-open snapshot, matching the source overlay's populate call. */
internal data class NativeChiefReply(val generation: Long, val rootID: String, val targetID: String,
    val rows: List<NativeChiefTimelineItem>, val releaseOffset: Float = 0f) {
    val hiddenKeys = rows.map { it.key }.toSet()
}

internal object NativeChiefReplyPolicy {
    fun open(state: NativeChiefTranscriptState, rootID: String, targetID: String, generation: Long, releaseOffset: Float = 0f): NativeChiefReply? {
        val items = NativeChiefTimeline.items(state)
        if (items.none { it.messageID == rootID }) return null
        val rows = items.filter { it.messageID == rootID || it.replyToID == rootID }
        if (rows.none { it.messageID == targetID }) return null
        return NativeChiefReply(generation, rootID, targetID, rows.toList(), releaseOffset)
    }
}

internal val NativeChiefTimelineItem.messageID get() = message?.id ?: "local:${checkNotNull(outgoing).clientMessageId}"
internal val NativeChiefTimelineItem.replyToID get() = message?.threadRoot ?: outgoing?.replyToID

/** An optimistic row's display-only quote projection. Never enters read ordering or the wire. */
internal fun NativeChiefTimelineItem.quoteMessage(): NativeChiefMessage = message ?: checkNotNull(outgoing).let {
    NativeChiefMessage(messageID, 0uL, localSenderId, "You", "human", it.text, it.at, emptyList(), emptyList(), it.replyToID, it.clientMessageId)
}
