package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Drafts and acknowledgements are scoped to the original Mac and terminal. */
class TerminalDrafts(saved: JSONArray? = null) {
    data class Target(val pairing: String, val workspace: String, val surface: String)
    data class Draft(
        val text: String = "", val revision: Long = 0,
        val operation: String? = null, val error: String? = null,
        val attachments: List<ComposerAttachment> = emptyList()
    )
    data class Send(val target: Target, val text: String, val revision: Long, val operation: String,
        val attachments: List<ComposerAttachment>)

    private val mutable = MutableStateFlow(readSaved(saved))
    val state = mutable.asStateFlow()
    @Volatile var generation: Long = 0; private set

    @Synchronized fun attach(target: Target, attachment: ComposerAttachment, expectedGeneration: Long) {
        check(generation == expectedGeneration) { "Account changed while opening the attachment" }
        require(ComposerAttachment.read(attachment.json()) == attachment) { "Invalid attachment" }
        val old = mutable.value[target] ?: Draft()
        require(old.attachments.none { it.id == attachment.id }) { "Attachment already staged" }
        require(old.attachments.size < 10) { "Each terminal can hold up to 10 attachments" }
        require(old.attachments.sumOf { it.size.toLong() } + attachment.size <= ComposerAttachment.TERMINAL_BYTES) {
            "Attachments for this terminal must fit within 32 MiB"
        }
        val all = mutable.value.values.flatMap { it.attachments }
        require(all.size < 20 && all.sumOf { it.size.toLong() } + attachment.size <= ComposerAttachment.TOTAL_BYTES) {
            "Remove some pending attachments before adding more"
        }
        put(target, old.copy(attachments = old.attachments + attachment))
    }

    @Synchronized fun removeAttachment(target: Target, id: String) {
        val old = mutable.value[target] ?: return
        put(target, old.copy(attachments = old.attachments.filterNot { it.id == id }))
    }

    @Synchronized fun ownsAttachment(target: Target, attachment: ComposerAttachment, expectedGeneration: Long): Boolean =
        generation == expectedGeneration && mutable.value[target]?.attachments?.contains(attachment) == true

    @Synchronized fun contains(send: Send, attachment: ComposerAttachment): Boolean =
        mutable.value[send.target]?.let { it.operation == send.operation && attachment in it.attachments } == true

    @Synchronized fun acknowledgeImage(send: Send, attachment: ComposerAttachment) {
        if (contains(send, attachment)) removeAttachment(send.target, attachment.id)
    }

    @Synchronized fun edit(target: Target, text: String) {
        val old = mutable.value[target] ?: Draft()
        if (old.text != text) put(target, old.copy(text = text, revision = old.revision + 1))
    }

    /** Reserves the send synchronously so double taps and IME actions cannot race. */
    @Synchronized fun begin(target: Target): Send? {
        val draft = mutable.value[target] ?: return null
        if ((draft.text.isEmpty() && draft.attachments.isEmpty()) || draft.operation != null) return null
        val operation = UUID.randomUUID().toString()
        put(target, draft.copy(operation = operation, error = null))
        return Send(target, draft.text, draft.revision, operation, draft.attachments)
    }

    @Synchronized fun finish(send: Send, error: String? = null, deliveredFiles: Set<String> = emptySet()) {
        val current = mutable.value[send.target] ?: return
        if (current.operation != send.operation) return
        val clear = error == null && current.revision == send.revision
        put(send.target, current.copy(text = if (clear) "" else current.text,
            operation = null, error = error, attachments = if (error == null)
                current.attachments.filterNot { it.id in deliveredFiles } else current.attachments))
    }

    @Synchronized fun clear() { generation++; mutable.value = emptyMap() }

    @Synchronized fun discard(target: Target) { mutable.value = mutable.value - target }

    @Synchronized fun saved(): JSONArray = JSONArray().also { array ->
        mutable.value.forEach { (target, draft) ->
            if (draft.attachments.isNotEmpty() || draft.text.isNotEmpty() || draft.operation != null || draft.error != null) {
                array.put(JSONObject().put("pairing", target.pairing)
                    .put("workspace", target.workspace).put("surface", target.surface)
                    .put("text", draft.text)
                    .put("attachments", JSONArray(draft.attachments.map { it.json() }))
                    .put("delivery_unconfirmed", draft.operation != null || draft.error != null))
            }
        }
    }

    private fun put(target: Target, draft: Draft) {
        mutable.value = if (draft.attachments.isEmpty() && draft.text.isEmpty() && draft.operation == null && draft.error == null)
            mutable.value - target else mutable.value + (target to draft)
    }

    private fun readSaved(array: JSONArray?): Map<Target, Draft> = buildMap {
        if (array == null) return@buildMap
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val target = Target(item.optString("pairing"), item.optString("workspace"), item.optString("surface"))
            if (target.pairing.isBlank() || target.workspace.isBlank() || target.surface.isBlank()) continue
            val attachments = item.optJSONArray("attachments")?.let { values ->
                (0 until values.length()).mapNotNull { values.optJSONObject(it)?.let(ComposerAttachment::read) }
            }.orEmpty()
            put(target, Draft(text = item.optString("text"), attachments = attachments, error =
                if (item.optBoolean("delivery_unconfirmed")) DELIVERY_UNCONFIRMED else null))
        }
    }

    companion object {
        const val DELIVERY_UNCONFIRMED = "Delivery was not confirmed. Check the terminal before sending again."
    }
}
