package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope

/** Retained by NativeFeedSession, never by a View or Activity. */
internal class NativeChiefComputerSession(parent: CoroutineScope, client: NativeChiefApi,
    private val permits: () -> Boolean) : AutoCloseable {
    val home = NativeChiefHomeSession(parent, client, ::current)
    private val transcripts = mutableMapOf<String, NativeChiefTranscriptSession>()
    private val scope = parent
    private val api = client
    private var closed = false
    private var active = false
    var selected by mutableStateOf<NativeChiefConversation?>(null)
        private set
    var query by mutableStateOf("")
        private set
    fun search(text: String) { if (current()) query = text }
    fun current() = !closed && permits()
    fun transcript(): NativeChiefTranscriptSession? = selected?.let { transcripts[it.id] }
    fun open(conversation: NativeChiefConversation) {
        if (!current()) { close(); return }
        // Select only a row supplied by this Mac's current Home snapshot.
        val row = home.state.value.conversations.singleOrNull { it.id == conversation.id } ?: return
        transcript()?.let { it.endVisit(); it.pause() }
        selected = row
        transcripts.getOrPut(row.id) { NativeChiefTranscriptSession(scope, api, row.id, ::current) }
        if (active) transcript()?.let { it.setViewing(true); it.start() }
    }
    fun back() { transcript()?.let { it.endVisit(); it.pause() }; selected = null }
    fun setActive(value: Boolean) {
        if (!current()) { close(); return }
        active = value
        if (value) { home.start(); transcript()?.let { it.setViewing(true); it.start() } }
        else { home.pause(); transcripts.values.forEach { it.setViewing(false); it.pause() } }
    }
    override fun close() {
        if (closed) return
        closed = true; active = false; home.close(); transcripts.values.forEach { it.close() }; transcripts.clear(); selected = null; query = ""
    }
}
