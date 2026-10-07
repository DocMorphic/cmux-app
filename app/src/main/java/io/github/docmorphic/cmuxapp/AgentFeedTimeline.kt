package io.github.docmorphic.cmuxapp

/** Renderable Feed data only. A browser projection can use opaque owner/entry identifiers. */
internal data class AgentFeedUiOwner(val deviceId: String, val instanceTag: String?)
internal data class AgentFeedUiEntry(
    val key: String, val owner: AgentFeedUiOwner, val item: NativeAgentFeedItem,
    val computerName: String, val connected: Boolean, val pending: Boolean,
    val failure: AgentFeedFailure?, val needsInput: Boolean
)
internal data class AgentFeedUiSnapshot(
    val entries: List<AgentFeedUiEntry>, val allowedOwners: Set<AgentFeedUiOwner>,
    val loadedOwners: Set<AgentFeedUiOwner>, val connected: Boolean,
    val updating: Boolean, val unsupported: Boolean, val hasSources: Boolean,
    val refreshFailed: Boolean
)

/** Implementations resolve live ownership and authorize every operation; UI IDs are not authority. */
internal interface AgentFeedTimelineActions {
    suspend fun decide(entry: AgentFeedUiEntry, decision: AgentFeedDecision): Boolean
    suspend fun reply(entry: AgentFeedUiEntry, text: String): Boolean
    suspend fun fullText(entry: AgentFeedUiEntry): String
    fun read(entry: AgentFeedUiEntry, needsInput: Boolean? = null)
    fun open(entry: AgentFeedUiEntry, tab: Boolean)
}

internal fun NativeCredentialStore.PairedMac.agentFeedUiOwner() = AgentFeedUiOwner(deviceId, instanceTag)
internal fun AgentFeedUiEntry.matchesIdentity(current: NativeAgentFeedEntry): Boolean =
    current.key == key && current.source.mac.agentFeedUiOwner() == owner &&
        current.item.workstream == item.workstream && current.item.requestId == item.requestId &&
        current.item.kind == item.kind && current.item.workspaceId == item.workspaceId &&
        current.item.surfaceId == item.surfaceId && current.item.questions == item.questions

internal fun NativeAgentFeedEntry.ui(computerName: String, needsInput: Boolean) = AgentFeedUiEntry(
    key, source.mac.agentFeedUiOwner(), item, computerName,
    source.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY in source.capabilities,
    item.id in source.agentFeed.pending, source.agentFeed.failures[item.id], needsInput
)
internal fun agentFeedUiSnapshot(sources: Collection<NativeFeedSource>, entries: List<NativeAgentFeedEntry>,
    allowedMacs: Collection<NativeCredentialStore.PairedMac>, readState: NativeAgentFeedReadState,
    computerName: (NativeCredentialStore.PairedMac) -> String) = AgentFeedUiSnapshot(
    entries.map { it.ui(computerName(it.source.mac), readState.needsInput(it)) },
    allowedMacs.map { it.agentFeedUiOwner() }.toSet(),
    sources.filter { it.agentFeed.snapshot != null }.map { it.mac.agentFeedUiOwner() }.toSet(),
    sources.any { it.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY in it.capabilities },
    sources.any { it.agentFeed.loading },
    sources.isNotEmpty() && sources.all { it.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY !in it.capabilities },
    sources.isNotEmpty(), sources.any { it.agentFeed.error != null }
)
