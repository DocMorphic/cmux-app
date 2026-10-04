package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal enum class NativeFeedbackCompletion { SUCCESS, FAILURE }
internal data class NativeFeedbackState(val owner: String? = null, val id: String? = null, val email: String = "",
    val message: String = "", val sending: Boolean = false, val error: String? = null, val receipt: String? = null,
    val completion: NativeFeedbackCompletion? = null, val completionId: Long = 0)

/** Retains a live request across view recreation; saved state can restore a draft, never replay a POST. */
internal class NativeFeedbackController(private val scope: CoroutineScope, restored: String? = null,
    private val save: (String) -> Unit = {}) {
    private val mutable = MutableStateFlow(restore(restored))
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private fun update(value: NativeFeedbackState) {
        mutable.value = value
        save(JSONObject().put("version", 1).put("owner", value.owner).put("id", value.id)
            .put("email", value.email).put("message", value.message).put("sending", value.sending)
            .put("error", value.error).put("receipt", value.receipt).toString())
    }
    fun bind(owner: String?) {
        if (state.value.owner == owner) return
        generation++; update(NativeFeedbackState(owner)); request?.cancel(); request = null
    }
    fun open(owner: String?, email: String) {
        generation++; update(NativeFeedbackState(owner, UUID.randomUUID().toString(), email.take(321)))
        request?.cancel(); request = null
    }
    fun edit(id: String, email: String? = null, message: String? = null) {
        val value = state.value
        if (value.id != id || value.sending) return
        update(value.copy(email = email?.take(321) ?: value.email, message = message?.take(16_384) ?: value.message))
    }
    fun dismiss(id: String) {
        if (state.value.id != id) return
        generation++; update(NativeFeedbackState(state.value.owner)); request?.cancel(); request = null
    }
    fun acknowledge(receipt: String) {
        if (state.value.receipt == receipt) update(state.value.copy(receipt = null))
    }
    /** A live completion is consumed once across hosts/recreation; saved outcomes never buzz on restore. */
    fun takeCompletion(owner: String?): NativeFeedbackCompletion? {
        val value = state.value
        if (value.owner != owner) return null
        val result = value.completion ?: return null
        mutable.value = value.copy(completion = null)
        return result
    }
    fun send(id: String, stamp: NativeFeedbackStamp, submit: suspend (String, String, NativeFeedbackStamp) -> Unit) {
        val draft = state.value
        if (draft.id != id || draft.sending || !NativeFeedbackClient.valid(draft.email, draft.message)) return
        val ticket = ++generation
        fun current() = generation == ticket && state.value.id == id && state.value.owner == draft.owner
        update(draft.copy(sending = true, error = null, completion = null))
        request = scope.launch {
            try {
                submit(draft.email, draft.message, stamp)
                currentCoroutineContext().ensureActive()
                if (current()) update(NativeFeedbackState(draft.owner, receipt = id, completion = NativeFeedbackCompletion.SUCCESS, completionId = ticket))
            } catch (error: Exception) {
                if (current()) update(state.value.copy(sending = false,
                    error = if (error is CancellationException) INTERRUPTED else error.message ?: "Could not send feedback.",
                    completion = if (error is CancellationException) null else NativeFeedbackCompletion.FAILURE, completionId = ticket))
                if (error is CancellationException) throw error
            }
        }
    }
    companion object {
        const val INTERRUPTED = "Could not confirm whether feedback was sent. Your draft is preserved; review it before trying again."
        private fun restore(raw: String?): NativeFeedbackState = runCatching {
            if (raw == null || raw.length > 100_000) return NativeFeedbackState()
            val json = JSONObject(raw); require(json.getInt("version") == 1)
            fun text(key: String) = json.opt(key) as? String
            val owner = text("owner")?.take(512)
            val id = text("id")?.take(64)
            NativeFeedbackState(owner, id, text("email").orEmpty().take(321), text("message").orEmpty().take(16_384),
                error = if (id != null && json.optBoolean("sending")) INTERRUPTED else text("error")?.take(2000),
                receipt = text("receipt")?.take(64))
        }.getOrElse { NativeFeedbackState() }
    }
}
