package io.github.docmorphic.cmuxapp

/** ConversationStore's loaded-window cut, applied to the same acknowledged/local timeline. */
internal object NativeChiefWindow {
    const val trimThreshold = 1500
    const val keepCount = 1000

    fun cut(items: List<NativeChiefTimelineItem>, keep: Int, loaded: Boolean, paging: Boolean,
        marker: ULong?, unread: Long, lastRead: ULong, preserving: Set<String> = emptySet()): Int {
        if (!loaded || paging || keep <= 0) return 0
        var acknowledged = 0
        var cut = items.size
        for (index in items.indices.reversed()) if (items[index].seq != null) {
            if (++acknowledged == keep) { cut = index; break }
        }
        if (acknowledged != keep || cut <= 0) return 0
        listOfNotNull(marker, lastRead.takeIf { unread > 0 }).forEach { floor ->
            (0 until cut).firstOrNull { (items[it].seq ?: 0uL) > floor }?.let { cut = maxOf(0, it - 1) }
        }
        (0 until cut).firstOrNull { index ->
            val item = items[index]
            item.key in preserving || item.message?.id in preserving ||
                item.outgoing?.clientMessageId?.let { "local:$it" in preserving } == true
        }?.let { cut = it }
        val indexes = items.mapIndexedNotNull { index, item -> item.message?.id?.let { it to index } }.toMap()
        var scanned = items.size
        while (scanned > cut) {
            val start = cut
            for (index in start until scanned) {
                val target = items[index].message?.threadRoot?.let(indexes::get)
                if (target != null && target < cut) cut = target
            }
            scanned = start
        }
        (0 until cut).firstOrNull { items[it].seq == null }?.let { cut = it }
        return cut
    }
}
