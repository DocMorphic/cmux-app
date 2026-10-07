package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal data class NativeAgentFeedEntry(val source: NativeFeedSource, val item: NativeAgentFeedItem) {
    val key: String get() = JSONArray(listOf(source.mac.deviceId, source.mac.instanceTag, item.id)).toString()
    val turnKey: String get() = JSONArray(listOf("turn", source.mac.deviceId, source.mac.instanceTag,
        item.workstream, item.source, ((item.createdAt - 978307200) / 180).toLong())).toString()
}
internal data class NativeAgentFeedReadState(val baseline: Double, val read: List<String> = emptyList(),
    val overrides: Map<String, Boolean> = emptyMap()) {
    fun needsInput(entry: NativeAgentFeedEntry): Boolean = overrides[entry.key] ?: (entry.item.needsInput ||
        entry.item.createdAt > baseline && entry.key !in read && (entry.item.kind != AgentFeedKind.STOP || entry.turnKey !in read))
    fun interacted(entry: NativeAgentFeedEntry) = copy(read = (read + entry.key +
        if (entry.item.kind == AgentFeedKind.STOP) listOf(entry.turnKey) else emptyList()).distinct().takeLast(1500))
    fun triage(entry: NativeAgentFeedEntry, needsInput: Boolean): NativeAgentFeedReadState {
        val next = if (needsInput) this else interacted(entry)
        return next.copy(overrides = if (needsInput == entry.item.needsInput) next.overrides - entry.key
            else next.overrides + (entry.key to needsInput))
    }
    fun encode(): String = JSONObject().put("baseline", baseline).put("read", JSONArray(read)).toString()
    companion object {
        fun decode(raw: String?, now: Double): NativeAgentFeedReadState = runCatching {
            val value = JSONObject(checkNotNull(raw)); val baseline = value.getDouble("baseline")
            require(baseline.isFinite())
            val keys = value.getJSONArray("read")
            NativeAgentFeedReadState(baseline, (0 until keys.length()).map { keys.getString(it) }.distinct().takeLast(1500))
        }.getOrElse { NativeAgentFeedReadState(now) }
    }
}

/** Retained snapshots remain intact; aggregate and deduplicate only the displayed history. */
internal fun aggregateNativeAgentFeed(sources: Collection<NativeFeedSource>): List<NativeAgentFeedEntry> {
    val ordered = sources.flatMap { source -> source.agentFeed.snapshot?.items.orEmpty().map { NativeAgentFeedEntry(source, it) } }
        .sortedWith(compareByDescending<NativeAgentFeedEntry> { it.item.createdAt }.thenBy { it.key })
    val result = mutableListOf<NativeAgentFeedEntry>()
    val exact = mutableMapOf<List<String?>, Int>(); val turns = mutableMapOf<List<String?>, Int>()
    ordered.forEach { entry ->
        val row = entry.item
        if (row.kind != AgentFeedKind.STOP) { result += entry; return@forEach }
        val turn = listOf(entry.source.mac.deviceId, entry.source.mac.instanceTag, row.workstream, row.source)
        val key = turn + row.reason
        val exactIndex = exact[key]?.takeIf { abs(result[it].item.createdAt - row.createdAt) <= 2 }
        val turnIndex = turns[turn]?.takeIf { index ->
            val previous = result[index].item
            abs(previous.createdAt - row.createdAt) <= 120 && agentFeedSameTurnReason(previous.reason, row.reason)
        }
        val index = exactIndex ?: turnIndex
        if (index != null) {
            val previous = result[index]
            val richer = if (exactIndex == null && row.reason.orEmpty().length > previous.item.reason.orEmpty().length) entry else previous
            result[index] = richer.copy(item = richer.item.copy(replyText = previous.item.replyText ?: row.replyText))
        } else { exact[key] = result.size; turns[turn] = result.size; result += entry }
    }
    return result.take(400)
}
internal fun agentFeedSameTurnReason(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    fun collapsed(value: String) = value.trim().replace(Regex("\\s+"), " ")
    val first = collapsed(a); val second = collapsed(b)
    if (first.isEmpty() || second.isEmpty()) return false
    if (first == second) return true
    val (shorter, longer) = if (first.length <= second.length) first to second else second to first
    return shorter.endsWith("…") && longer.startsWith(shorter.dropLast(1))
}
