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
        val operation: String? = null, val error: String? = null
    )
    data class Send(val target: Target, val text: String, val revision: Long, val operation: String)

    private val mutable = MutableStateFlow(readSaved(saved))
    val state = mutable.asStateFlow()

    @Synchronized fun edit(target: Target, text: String) {
        val old = mutable.value[target] ?: Draft()
        if (old.text != text) put(target, old.copy(text = text, revision = old.revision + 1))
    }

    /** Reserves the send synchronously so double taps and IME actions cannot race. */
    @Synchronized fun begin(target: Target): Send? {
        val draft = mutable.value[target] ?: return null
        if (draft.text.isEmpty() || draft.operation != null) return null
        val operation = UUID.randomUUID().toString()
        put(target, draft.copy(operation = operation, error = null))
        return Send(target, draft.text, draft.revision, operation)
    }

    @Synchronized fun finish(send: Send, error: String? = null) {
        val current = mutable.value[send.target] ?: return
        if (current.operation != send.operation) return
        val clear = error == null && current.revision == send.revision
        put(send.target, current.copy(text = if (clear) "" else current.text,
            operation = null, error = error))
    }

    @Synchronized fun clear() { mutable.value = emptyMap() }

    @Synchronized fun saved(): JSONArray = JSONArray().also { array ->
        mutable.value.forEach { (target, draft) ->
            if (draft.text.isNotEmpty() || draft.operation != null || draft.error != null) {
                array.put(JSONObject().put("pairing", target.pairing)
                    .put("workspace", target.workspace).put("surface", target.surface)
                    .put("text", draft.text)
                    .put("delivery_unconfirmed", draft.operation != null || draft.error != null))
            }
        }
    }

    private fun put(target: Target, draft: Draft) {
        mutable.value = if (draft.text.isEmpty() && draft.operation == null && draft.error == null)
            mutable.value - target else mutable.value + (target to draft)
    }

    private fun readSaved(array: JSONArray?): Map<Target, Draft> = buildMap {
        if (array == null) return@buildMap
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val target = Target(item.optString("pairing"), item.optString("workspace"), item.optString("surface"))
            if (target.pairing.isBlank() || target.workspace.isBlank() || target.surface.isBlank()) continue
            put(target, Draft(text = item.optString("text"), error =
                if (item.optBoolean("delivery_unconfirmed")) DELIVERY_UNCONFIRMED else null))
        }
    }

    companion object {
        const val DELIVERY_UNCONFIRMED = "Delivery was not confirmed. Check the terminal before sending again."
    }
}
