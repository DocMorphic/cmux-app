package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Save only the draft and target identity; full messages and credentials never enter the Bundle. */
internal data class AgentFeedModal(
    val scope: String, val key: String, val deviceId: String, val instanceTag: String?,
    val workstream: String, val requestId: String?, val kind: AgentFeedKind,
    val workspaceId: String?, val surfaceId: String?, val mode: String,
    val draft: String = "", val expanded: Boolean = false, val raw: Boolean = false
) {
    fun matches(entry: NativeAgentFeedEntry): Boolean = matches(entry.key, entry.item)
    fun matches(entry: AgentFeedUiEntry): Boolean = entry.owner == AgentFeedUiOwner(deviceId, instanceTag) && matches(entry.key, entry.item)
    private fun matches(entryKey: String, item: NativeAgentFeedItem): Boolean = entryKey == key && item.let {
        it.workstream == workstream && it.requestId == requestId && it.kind == kind &&
            it.workspaceId == workspaceId && it.surfaceId == surfaceId && when (mode) {
                "terminal" -> it.supportsTerminalReply && it.replyText == null
                "revise" -> it.kind == AgentFeedKind.PLAN && it.needsInput
                "read" -> true
                else -> false
            }
    }
    fun status(currentScope: String, snapshot: AgentFeedUiSnapshot): AgentFeedModalStatus {
        val owner = AgentFeedUiOwner(deviceId, instanceTag)
        if (scope != currentScope || owner !in snapshot.allowedOwners) return AgentFeedModalStatus.GONE
        snapshot.entries.singleOrNull { it.key == key }?.let {
            return if (matches(it)) AgentFeedModalStatus.READY else AgentFeedModalStatus.GONE
        }
        return if (owner in snapshot.loadedOwners) AgentFeedModalStatus.GONE else AgentFeedModalStatus.WAITING
    }
    fun status(currentScope: String, allowed: Collection<NativeCredentialStore.PairedMac>,
        sources: Collection<NativeFeedSource>, entries: Collection<NativeAgentFeedEntry>): AgentFeedModalStatus {
        if (scope != currentScope || allowed.none { owns(it) }) return AgentFeedModalStatus.GONE
        entries.singleOrNull { it.key == key }?.let { return if (matches(it)) AgentFeedModalStatus.READY else AgentFeedModalStatus.GONE }
        // A fresh authorized snapshot is authoritative about a removed event. Cold startup
        // and temporarily disconnected owners can still be loading their first snapshot.
        return if (sources.any { owns(it.mac) && it.agentFeed.snapshot != null }) AgentFeedModalStatus.GONE else AgentFeedModalStatus.WAITING
    }
    private fun owns(mac: NativeCredentialStore.PairedMac) = mac.deviceId == deviceId && mac.instanceTag == instanceTag
    fun encode() = JSONObject().put("scope", scope).put("key", key).put("device", deviceId).put("instance", instanceTag)
        .put("workstream", workstream).put("request", requestId).put("kind", kind.name)
        .put("workspace", workspaceId).put("surface", surfaceId).put("mode", mode)
        .put("draft", draft).put("expanded", expanded).put("raw", raw).toString()
    companion object {
        fun from(scope: String, entry: AgentFeedUiEntry, mode: String) = AgentFeedModal(scope, entry.key,
            entry.owner.deviceId, entry.owner.instanceTag, entry.item.workstream, entry.item.requestId,
            entry.item.kind, entry.item.workspaceId, entry.item.surfaceId, mode,
            draft = if (mode == "terminal") entry.failure?.draft.orEmpty() else "")
        fun from(scope: String, entry: NativeAgentFeedEntry, mode: String) = AgentFeedModal(scope, entry.key,
            entry.source.mac.deviceId, entry.source.mac.instanceTag, entry.item.workstream, entry.item.requestId,
            entry.item.kind, entry.item.workspaceId, entry.item.surfaceId, mode,
            draft = if (mode == "terminal") entry.source.agentFeed.failures[entry.item.id]?.draft.orEmpty() else "")
        fun decode(raw: String): AgentFeedModal? = runCatching {
            val value = JSONObject(raw)
            fun optional(key: String) = if (value.isNull(key)) null else value.getString(key)
            AgentFeedModal(value.getString("scope"), value.getString("key"), value.getString("device"), optional("instance"),
                value.getString("workstream"), optional("request"), AgentFeedKind.valueOf(value.getString("kind")),
                optional("workspace"), optional("surface"), value.getString("mode"), value.getString("draft"),
                value.getBoolean("expanded"), value.getBoolean("raw")).also {
                require(it.mode in setOf("terminal", "revise", "read") && it.scope.isNotBlank() && it.key.isNotBlank())
            }
        }.getOrNull()
    }
}
internal enum class AgentFeedModalStatus { READY, WAITING, GONE }

internal val agentFeedPlanModes = listOf("manual" to "Approve (manual edits)", "autoAccept" to "Approve, auto-accept edits",
    "bypassPermissions" to "Approve, bypass permissions", "ultraplan" to "Approve as ultraplan")
internal fun NativeAgentFeedItem.planApproval() = AgentFeedDecision("exit_plan",
    defaultMode ?: "manual")
