package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** One panel's source-style optimistic dialog claim and restoration. No entered
 * text, username or password is retained here. The presentation owns edits. */
internal class BrowserDialogState(private val panel: String) {
    var pending: JSONObject? by mutableStateOf(null)
        private set
    private var lastResolvedId: String? = null
    private var closed = false

    fun install(value: JSONObject?) {
        if (closed || value == null || value.optString("panel_id") != panel) return
        val id = value.optString("dialog_id")
        if (id.isEmpty() || id == lastResolvedId) return
        lastResolvedId = null
        pending = JSONObject(value.toString())
    }

    fun resolve(id: String) {
        if (closed || id.isEmpty()) return
        lastResolvedId = id
        if (pending?.optString("dialog_id") == id) pending = null
    }

    fun claim(id: String): JSONObject? {
        if (closed || pending?.optString("dialog_id") != id) return null
        return pending.also { pending = null }
    }

    fun restore(value: JSONObject): Boolean {
        if (closed || pending != null || value.optString("dialog_id") == lastResolvedId) return false
        install(value)
        return pending != null
    }

    fun close() { closed = true; pending = null }
}

/** BrowserStreamDialogCard.submit at the pinned source. JSON transport escapes
 * the embedded NUL; it remains the username/password boundary on the Mac. */
internal fun browserDialogResponseText(value: JSONObject, button: JSONObject,
    text: String, username: String, password: String): String? = when {
    button.optString("role") == "cancel" -> null
    value.optString("kind") == "http_basic_authentication" -> username + '\u0000' + password
    value.optJSONObject("text_field") != null -> text
    else -> null
}
