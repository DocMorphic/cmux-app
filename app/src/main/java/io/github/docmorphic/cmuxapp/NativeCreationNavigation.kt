package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

internal enum class NativeCreationNotice(val message: String) {
    LOST_RESULT("Creation may have completed. Check your workspaces before trying again."),
    NOT_CONFIRMED("Creation was not confirmed. Check your workspaces before trying again.")
}

/** Saved state identifies a waiter, never a request to replay after process death. */
internal class NativeCreationNavigation(saved: String? = null) {
    private data class Owner(val login: String, val user: String?, val team: String?)
    private fun owner(login: String?, team: NativeTeamScope?) = login?.let { Owner(it, team?.userId, team?.teamId) }
    private var pendingId: String? = null
    private var pendingOwner: Owner? = null
    private var context: List<Any?>? = null
    private var notice by mutableStateOf<NativeCreationNotice?>(null)
    private var noticeOwner: Owner? = null
    init {
        if (saved != null) runCatching {
            require(saved.length <= 32768)
            val value = JSONObject(saved); require(value.getInt("version") == 1)
            fun optional(key: String) = if (value.isNull(key)) null else value.getString(key).also { require(it.length in 1..4096) }
            fun readOwner(prefix: String): Owner {
                val user = optional(prefix + "user"); val team = optional(prefix + "team")
                require((user == null) == (team == null))
                return Owner(checkNotNull(optional(prefix + "login")), user, team)
            }
            if (!value.isNull("id")) {
                pendingId = value.getString("id").also { require(it.length in 1..128) }
                pendingOwner = readOwner("")
            }
            if (!value.isNull("notice")) {
                notice = NativeCreationNotice.valueOf(value.getString("notice"))
                noticeOwner = readOwner("notice_")
            }
        }.onFailure { leave(); dismissNotice() }
    }
    fun begin(id: String, login: String, navigation: List<Any?>, team: NativeTeamScope? = null) {
        dismissNotice()
        pendingId = id; pendingOwner = owner(login, team); context = navigation.toList()
    }
    fun noticeFor(login: String?, team: NativeTeamScope? = null) = notice.takeIf { noticeOwner == owner(login, team) }
    fun dismissNotice() { notice = null; noticeOwner = null }
    private fun showNotice(owner: Owner?, value: NativeCreationNotice): String? {
        if (owner == null) return null
        noticeOwner = owner; notice = value
        return value.message
    }
    private fun leave() { pendingId = null; pendingOwner = null; context = null }
    fun reconcile(login: String?, state: NativeCreationState, navigation: List<Any?>,
        admitted: (NativeCreationRequest) -> Boolean, completed: (String) -> Unit,
        team: NativeTeamScope? = null, open: (NativeCreationRequest, NativeCreatedWorkspace) -> Unit): String? {
        val owner = owner(login, team)
        if (noticeOwner != null && noticeOwner != owner) dismissNotice()
        val id = pendingId ?: return null
        if (pendingOwner != owner || (context != null && context != navigation)) {
            leave(); completed(id); return null
        }
        if (context == null) context = navigation.toList()
        if (state.request?.id != id) {
            leave()
            return showNotice(owner, NativeCreationNotice.LOST_RESULT)
        }
        if (!admitted(checkNotNull(state.request))) { leave(); completed(id); return null }
        if (state is NativeCreationState.Running) return null
        leave(); completed(id)
        return when (state) {
            is NativeCreationState.Ready -> { state.destination?.let { open(state.request, it) }; null }
            is NativeCreationState.Failed -> showNotice(owner, NativeCreationNotice.NOT_CONFIRMED)
            else -> null
        }
    }
    fun save(): String = JSONObject().put("version", 1).put("id", pendingId ?: JSONObject.NULL)
        .put("login", pendingOwner?.login ?: JSONObject.NULL).put("user", pendingOwner?.user ?: JSONObject.NULL)
        .put("team", pendingOwner?.team ?: JSONObject.NULL).put("notice", notice?.name ?: JSONObject.NULL)
        .put("notice_login", noticeOwner?.login ?: JSONObject.NULL).put("notice_user", noticeOwner?.user ?: JSONObject.NULL)
        .put("notice_team", noticeOwner?.team ?: JSONObject.NULL).toString()
    companion object {
        val saver = Saver<NativeCreationNavigation, String>(save = { it.save() }, restore = { NativeCreationNavigation(it) })
    }
}
