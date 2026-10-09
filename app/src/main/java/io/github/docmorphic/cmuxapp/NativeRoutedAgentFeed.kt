package io.github.docmorphic.cmuxapp

/** Main-process Feed authority for a browser presentation. Nothing resolves a browser-supplied RPC target. */
internal class NativeRoutedAgentFeed(
    private val input: () -> NativeSidebarInput?, private val identity: (List<Any?>) -> String,
    private val computer: (String) -> String,
    private val session: (NativeCredentialStore.PairedMac) -> NativeAgentFeedSession?,
    private val read: (NativeAgentFeedEntry, Boolean?) -> Unit,
    private val refresh: suspend () -> Unit, private val navigate: (NativeSidebarTarget) -> Unit
) {
    private val stopReasons = NativeAgentFeedStopReasonCache()
    private fun owner(mac: NativeCredentialStore.PairedMac) = AgentFeedUiOwner(identity(listOf("feed-owner", mac.deviceId, mac.instanceTag)), null)
    private fun key(entry: NativeAgentFeedEntry) = identity(listOf("feed-entry", entry.source.mac.origin, entry.source.mac.code,
        entry.key, entry.item.workstream, entry.item.requestId, entry.item.kind.name, entry.item.workspaceId,
        entry.item.surfaceId, entry.item.questions.toString()))
    fun snapshot(query: RoutedSidebarQuery): AgentFeedUiSnapshot {
        val value = checkNotNull(input()) { "Sidebar account changed" }
        val selected = query.computer?.let { key -> value.computers.singleOrNull { computer(it.id) == key }?.id }
        val sources = value.sources.filter { (query.computer == null || selected != null) &&
            (selected == null || workspaceMacFilterId(it.mac.deviceId, it.mac.instanceTag) == selected) }
        val native = aggregateNativeAgentFeed(sources, stopReasons)
        val result = agentFeedUiSnapshot(sources, native, value.sources.map { it.mac }, value.agentReadState, value.appearances::name)
        return result.copy(entries = native.zip(result.entries).map { (entry, row) -> row.copy(key = key(entry), owner = owner(entry.source.mac)) },
            allowedOwners = value.sources.map { owner(it.mac) }.toSet(), loadedOwners = sources.filter { it.agentFeed.snapshot != null }.map { owner(it.mac) }.toSet())
    }
    private fun live(key: String): NativeAgentFeedEntry {
        val value = checkNotNull(input()) { "Sidebar account changed" }
        return aggregateNativeAgentFeed(value.sources, stopReasons).singleOrNull { key(it) == key } ?: error("This Feed item changed. Refresh the Feed.")
    }
    suspend fun action(command: RoutedAgentFeedCommand, canSend: () -> Boolean): Boolean {
        check(canSend() && input() != null) { "Feed is no longer visible" }
        if (command.verb == RoutedAgentFeedVerb.REFRESH) { refresh(); return true }
        val row = live(checkNotNull(command.key))
        if (command.verb == RoutedAgentFeedVerb.READ) { read(row, command.needsInput); return true }
        val active = checkNotNull(session(row.source.mac)) { "Connect to this computer to answer" }
        val sent = when (command.verb) {
            RoutedAgentFeedVerb.DECIDE -> active.decide(row.item, checkNotNull(command.decision), canSend)
            RoutedAgentFeedVerb.REPLY -> active.terminalReply(row.item, checkNotNull(command.text), canSend)
            else -> error("Unsupported Feed action")
        }
        if (sent && canSend()) read(row, null)
        return sent
    }
    suspend fun text(key: String, canRead: () -> Boolean): String {
        check(canRead()); val row = live(key)
        return checkNotNull(session(row.source.mac)) { "Connect to this computer to read the message" }.fullText(row.item, canRead)
    }
    fun resolve(key: String, tab: Boolean): (() -> Unit)? {
        fun target() = runCatching {
            val row = live(key)
            check(row.source.availability == NativeFeedAvailability.CONNECTED)
            val workspace = row.source.workspaces.singleOrNull { it.id == row.item.workspaceId }
            check(workspace != null && (!tab || workspace.terminals.any { it.id == row.item.surfaceId }))
            NativeSidebarTarget.Agent(row, tab)
        }.getOrNull()
        target() ?: return null
        return { val current = checkNotNull(target()) { "This Feed destination is no longer available" }; read(current.entry, null); navigate(current) }
    }
}
