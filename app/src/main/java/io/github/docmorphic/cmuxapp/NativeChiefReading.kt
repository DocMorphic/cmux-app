package io.github.docmorphic.cmuxapp

/** Chief's direct-conversation subset of ConversationStore read state and catch-up. */
internal data class NativeChiefReading(
    val lastReadSeq: ULong = 0uL, val unread: Long = 0,
    val marker: ULong? = null, val catchUpCount: Long = 0,
    val viewing: Boolean = false, val captured: Boolean = false,
    val pending: ULong = 0uL, val server: Server? = null
) {
    data class Server(val lastReadSeq: ULong, val unread: Long, val headSeq: ULong)
    data class Update(val state: NativeChiefReading, val receipt: ULong? = null)

    fun accept(server: Server) = if (server.lastReadSeq >= pending)
        copy(server = server, pending = 0uL, lastReadSeq = server.lastReadSeq) else copy(server = server)

    fun refresh(messages: List<NativeChiefMessage>, loaded: Boolean, exhausted: Boolean): Update {
        var next = this
        val marksRead = viewing && loaded
        var counted = false
        if (!marksRead || (!captured && server != null)) {
            next = next.copy(unread = next.count(messages, loaded, exhausted)); counted = true
        }
        var receipt: ULong? = null
        if (marksRead) {
            if (!next.captured && next.server != null) {
                next = next.copy(captured = true)
                if (next.unread > 0 && next.marker == null) next = next.copy(marker = next.lastReadSeq, catchUpCount = next.unread)
            }
            messages.lastOrNull { it.authorKind != "human" }?.seq?.let { newest ->
                if (newest > next.lastReadSeq) {
                    next = next.copy(lastReadSeq = newest, pending = maxOf(next.pending, newest))
                    receipt = newest
                    next = next.copy(unread = next.count(messages, loaded, exhausted)); counted = true
                }
            }
            if (!counted) next = next.copy(unread = next.count(messages, loaded, exhausted))
        }
        return Update(next, receipt)
    }

    private fun count(messages: List<NativeChiefMessage>, loaded: Boolean, exhausted: Boolean): Long {
        val baseline = server ?: return 0
        fun incoming(after: ULong) = messages.count { it.seq > after && it.authorKind != "human" }.toLong()
        val oldest = if (loaded) messages.firstOrNull()?.seq else null
        if (oldest != null && (oldest == 0uL || lastReadSeq >= oldest - 1uL || exhausted)) return incoming(lastReadSeq)
        val base = if (baseline.lastReadSeq == lastReadSeq) baseline.unread else 0L
        val later = if (loaded) incoming(maxOf(baseline.headSeq, lastReadSeq)) else 0L
        return if (base > Long.MAX_VALUE - later) Long.MAX_VALUE else base + later
    }

    fun target(messages: List<NativeChiefMessage>, exhausted: Boolean): NativeChiefMessage? {
        val floor = marker ?: return null
        val oldest = messages.firstOrNull()?.seq ?: return null
        if (floor == ULong.MAX_VALUE || (oldest > floor + 1uL && !exhausted)) return null
        return messages.firstOrNull { it.seq > floor && it.authorKind != "human" }
    }

    fun viewing(value: Boolean) = copy(viewing = value, captured = if (value) captured else false)
    fun dismiss() = copy(marker = null, catchUpCount = 0)
    fun endVisit() = dismiss().copy(viewing = false, captured = false)
}
