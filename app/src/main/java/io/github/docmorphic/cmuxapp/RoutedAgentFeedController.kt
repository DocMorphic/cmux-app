package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun emptyAgentFeedUi() = AgentFeedUiSnapshot(emptyList(), emptySet(), emptySet(), false, false, false, false, false)
internal data class RoutedAgentFeedUi(val snapshot: AgentFeedUiSnapshot = emptyAgentFeedUi(), val loading: Boolean = true, val error: String? = null, val hasSnapshot: Boolean = false)
internal class RoutedAgentFeedController(private val scope: CoroutineScope,
    private val fetch: suspend (RoutedSidebarQuery) -> AgentFeedUiSnapshot,
    private val action: suspend (RoutedAgentFeedCommand) -> Boolean,
    private val text: suspend (String) -> String,
    private val select: suspend (String, Boolean) -> String) {
    private val mutable = MutableStateFlow(RoutedAgentFeedUi())
    val state = mutable.asStateFlow()
    private var ready = false; private var foreground = false; private var visible = false
    private var query = RoutedSidebarQuery(feed = true)
    private var job: Job? = null
    private fun active() = ready && foreground && visible && query.feed
    fun configure(available: Boolean, active: Boolean) {
        if (ready == available && foreground == active) return
        ready = available; foreground = active
        if (!ready) mutable.value = RoutedAgentFeedUi()
        restart()
    }
    fun visible(value: Boolean, query: RoutedSidebarQuery = this.query) {
        if (visible == value && this.query == query) return
        val changedComputer = this.query.computer != query.computer
        visible = value; this.query = query
        if (changedComputer) mutable.value = RoutedAgentFeedUi()
        restart()
    }
    private fun restart() {
        job?.cancel(); job = null
        if (!active()) return
        job = scope.launch {
            while (isActive && active()) {
                try {
                    val snapshot = fetch(query)
                    currentCoroutineContext().ensureActive()
                    mutable.value = RoutedAgentFeedUi(snapshot, false, hasSnapshot = true)
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    mutable.value = state.value.copy(loading = false, error = failure.message ?: "Could not load Feed")
                }
                delay(1_500)
            }
        }
    }
    private fun requireEntry(entry: AgentFeedUiEntry) {
        check(active() && state.value.snapshot.entries.any { it.key == entry.key && it.owner == entry.owner }) { "Feed item is no longer visible" }
    }
    suspend fun decide(entry: AgentFeedUiEntry, decision: AgentFeedDecision): Boolean {
        requireEntry(entry)
        return action(RoutedAgentFeedCommand(entry.key, RoutedAgentFeedVerb.DECIDE, decision = decision)).also { restart() }
    }
    suspend fun reply(entry: AgentFeedUiEntry, value: String): Boolean {
        requireEntry(entry)
        return action(RoutedAgentFeedCommand(entry.key, RoutedAgentFeedVerb.REPLY, text = value)).also { restart() }
    }
    suspend fun fullText(entry: AgentFeedUiEntry): String { requireEntry(entry); return text(entry.key) }
    fun read(entry: AgentFeedUiEntry, needs: Boolean?) { launch {
        requireEntry(entry); action(RoutedAgentFeedCommand(entry.key, RoutedAgentFeedVerb.READ, needsInput = needs)); restart()
    } }
    fun open(entry: AgentFeedUiEntry, tab: Boolean, navigate: (String) -> Unit) { launch {
        requireEntry(entry); val ticket = select(entry.key, tab); requireEntry(entry); navigate(ticket)
    } }
    fun refresh() { launch { check(active()); action(RoutedAgentFeedCommand(null, RoutedAgentFeedVerb.REFRESH)); restart() } }
    private fun launch(block: suspend () -> Unit) { val origin = query; scope.launch {
        try { block() } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (active() && query == origin) mutable.value = state.value.copy(error = failure.message ?: "Feed action failed")
        }
    } }
}
