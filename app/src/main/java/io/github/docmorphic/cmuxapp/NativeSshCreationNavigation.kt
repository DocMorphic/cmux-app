package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import org.json.JSONArray
import org.json.JSONObject

internal data class SshCreatedWorkspaceRoute(val login: String, val host: SshHostRecord, val target: SshWorkspaceTarget,
    val rememberedTab: NativeWorkspaceTab? = null)

/** Saves destinations and a waiter ID only. It never stores or restores a create command. */
internal class NativeSshCreationNavigation(saved: String? = null) {
    var route by mutableStateOf<SshCreatedWorkspaceRoute?>(null)
        private set
    private var pendingLogin: String? = null
    private var pendingId: String? = null
    private var context: List<Any?>? = null
    init {
        if (saved != null) runCatching {
            require(saved.length <= 32768)
            val value = JSONObject(saved); require(value.getInt("version") == 1)
            fun optional(key: String) = if (value.isNull(key)) null else value.getString(key).also { require(it.isNotBlank()) }
            pendingLogin = optional("pendingLogin"); pendingId = optional("pendingId")
            require((pendingLogin == null) == (pendingId == null))
            if (!value.isNull("route")) {
                val r = value.getJSONObject("route"); val host = r.getJSONArray("host")
                require(host.length() == 10)
                val record = checkNotNull(SshHostEditSaver.restore((0 until 10).map(host::getString)))
                route = SshCreatedWorkspaceRoute(r.getString("login").also { require(it.isNotBlank()) }, record,
                    checkNotNull(SshWorkspaceTarget.decode(r.getString("target"))),
                    r.optJSONObject("remembered")?.let { tab -> NativeWorkspaceTabKind.entries.firstOrNull { it.wire == tab.optString("kind") }
                        ?.let { NativeWorkspaceTab(it, tab.getString("id")) } })
            }
        }.onFailure { route = null; pendingLogin = null; pendingId = null }
    }
    fun begin(login: String, coordinator: SshWorkspaceCreationCoordinator, host: SshHostRecord,
        kind: SshWorkspaceKind, navigation: List<Any?>): Boolean {
        val id = coordinator.begin(host, kind) ?: return false
        route = null; pendingLogin = login; pendingId = id; context = navigation.toList()
        return true
    }
    fun open(login: String, host: SshHostRecord, target: SshWorkspaceTarget, rememberedTab: NativeWorkspaceTab? = null) {
        leave(); route = SshCreatedWorkspaceRoute(login, host, target, rememberedTab)
    }
    fun leave() { route = null; pendingLogin = null; pendingId = null; context = null }

    /** Called from the UI owner after observing the retained coordinator's state. */
    fun reconcile(login: String?, state: SshWorkspaceCreationState?, navigation: List<Any?>,
        hostCurrent: (SshHostRecord) -> Boolean, completed: (String) -> Unit = {}): String? {
        if (route?.login != null && route?.login != login) route = null
        if (route != null && state != null && !hostCurrent(checkNotNull(route).host)) route = null
        val id = pendingId ?: return null
        if (pendingLogin != login || (context != null && context != navigation)) {
            pendingId = null; pendingLogin = null; context = null
            return null
        }
        if (context == null) context = navigation.toList() // Activity restoration resumes the same waiter.
        if (state == null) return null // The login resource is still loading.
        if (state.request?.id != id) {
            pendingId = null; pendingLogin = null; context = null
            return "Workspace creation may have completed. Check the SSH computer before trying again."
        }
        if (state is SshWorkspaceCreationState.Running) return null
        pendingId = null; pendingLogin = null; context = null
        return when (state) {
            is SshWorkspaceCreationState.Ready -> {
                if (hostCurrent(state.request.host) && login != null)
                    route = SshCreatedWorkspaceRoute(login, state.request.host, state.target)
                completed(id); null
            }
            is SshWorkspaceCreationState.Failed -> { completed(id); state.message }
            else -> null
        }
    }
    fun save(): String = JSONObject().put("version", 1)
        .put("pendingLogin", pendingLogin ?: JSONObject.NULL).put("pendingId", pendingId ?: JSONObject.NULL)
        .put("route", route?.let { r -> JSONObject().put("login", r.login).put("target", r.target.encode())
            .put("remembered", r.rememberedTab?.let { JSONObject().put("kind", it.kind.wire).put("id", it.id) } ?: JSONObject.NULL)
            .put("host", JSONArray(listOf(r.host.id.toString(), r.host.name, r.host.endpoint.host,
                r.host.endpoint.port.toString(), r.host.endpoint.username, r.host.keyId?.toString().orEmpty(),
                r.host.jumpHostId?.toString().orEmpty(), r.host.idleClose.name, r.host.createdAtMillis.toString(),
                r.host.autoConnectPaused.toString()))) } ?: JSONObject.NULL).toString()
    companion object {
        val saver = Saver<NativeSshCreationNavigation, String>(save = { it.save() }, restore = { NativeSshCreationNavigation(it) })
    }
}
