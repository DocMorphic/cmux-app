package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.saveable.Saver
import org.json.JSONObject

/** Saved state identifies a waiter, never a request to replay after process death. */
internal class NativeCreationNavigation(saved: String? = null) {
    private var pendingId: String? = null
    private var login: String? = null
    private var context: List<Any?>? = null
    init {
        if (saved != null) runCatching {
            require(saved.length <= 8192)
            val value = JSONObject(saved); require(value.getInt("version") == 1)
            if (!value.isNull("id")) {
                pendingId = value.getString("id").also { require(it.length in 1..128) }
                login = value.getString("login").also { require(it.length in 1..4096) }
            }
        }.onFailure { leave() }
    }
    fun begin(id: String, login: String, navigation: List<Any?>) {
        pendingId = id; this.login = login; context = navigation.toList()
    }
    private fun leave() { pendingId = null; login = null; context = null }
    fun reconcile(login: String?, state: NativeCreationState, navigation: List<Any?>,
        admitted: (NativeCreationRequest) -> Boolean, completed: (String) -> Unit,
        open: (NativeCreationRequest, NativeCreatedWorkspace) -> Unit): String? {
        val id = pendingId ?: return null
        if (this.login != login || (context != null && context != navigation)) {
            leave(); completed(id); return null
        }
        if (context == null) context = navigation.toList()
        if (state.request?.id != id) {
            leave()
            return "Creation may have completed. Check this Mac's workspaces before trying again."
        }
        if (!admitted(checkNotNull(state.request))) { leave(); completed(id); return null }
        if (state is NativeCreationState.Running) return null
        leave(); completed(id)
        return when (state) {
            is NativeCreationState.Ready -> { state.destination?.let { open(state.request, it) }; null }
            is NativeCreationState.Failed -> state.message
            else -> null
        }
    }
    fun save(): String = JSONObject().put("version", 1).put("id", pendingId ?: JSONObject.NULL)
        .put("login", login ?: JSONObject.NULL).toString()
    companion object {
        val saver = Saver<NativeCreationNavigation, String>(save = { it.save() }, restore = { NativeCreationNavigation(it) })
    }
}
