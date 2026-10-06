package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.UUID

/** An installed Firebase client identity, never a service-account credential or a server URL. */
internal data class PhoneFcmProject(val project: String, val application: String, val sender: String) {
    init { require(listOf(project, application, sender).all { it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl) }) }
    fun json() = JSONObject().put("project", project).put("application", application).put("sender", sender)
    companion object {
        fun parse(value: JSONObject) = PhoneFcmProject(value.getString("project"), value.getString("application"), value.getString("sender"))
    }
}
internal data class PhoneFcmTokenGrant(val login: String, val project: PhoneFcmProject, val epoch: String)
internal data class PhoneFcmTokenSnapshot(val grant: PhoneFcmTokenGrant, val token: String, val revision: String)

/** Stored in a separate Keystore-encrypted record so logout cannot erase a pending provider deletion. */
internal class PhoneFcmTokenState(private val state: JSONObject) {
    private val root get() = state.optJSONObject(KEY)
    val project get() = root?.getJSONObject("project")?.let(PhoneFcmProject::parse)
    val deletion get() = root?.optString("deletion")?.takeIf(String::isNotBlank)
    val grant: PhoneFcmTokenGrant? get() = root?.let { value ->
        value.optString("login").takeIf(String::isNotBlank)?.let {
            PhoneFcmTokenGrant(it, checkNotNull(project), value.getString("epoch"))
        }
    }
    val snapshot: PhoneFcmTokenSnapshot? get() = grant?.let { owner ->
        root?.optString("token")?.takeIf(String::isNotBlank)?.let { PhoneFcmTokenSnapshot(owner, it, root!!.getString("revision")) }
    }
    val hasWork get() = grant != null || deletion != null

    /** Called only after explicit push consent; ordinary background alerts do not grant FCM consent. */
    fun authorize(login: String, project: PhoneFcmProject): PhoneFcmTokenGrant {
        require(login.isNotBlank() && login.length <= 1024)
        grant?.takeIf { it.login == login && it.project == project }?.let { return it }
        revoke()
        check(this.project == null || this.project == project) { "Previous push project cleanup is incomplete" }
        val value = root ?: JSONObject().also { state.put(KEY, it) }
        value.put("project", project.json()).put("login", login).put("epoch", UUID.randomUUID().toString())
        return checkNotNull(grant)
    }
    fun revoke() {
        val value = root ?: return
        value.remove("login"); value.remove("epoch"); value.remove("token"); value.remove("revision")
        if (value.optBoolean("touched") && deletion == null) value.put("deletion", UUID.randomUUID().toString())
        if (deletion == null) state.remove(KEY)
    }
    fun reconcile(login: String?, allowed: Boolean, installed: PhoneFcmProject?) {
        grant?.let { if (!allowed || it.login != login || it.project != installed) revoke() }
    }
    /** Persist BEFORE asking the SDK: process death can happen after it creates a token. */
    fun beginFetch(owner: PhoneFcmTokenGrant): Boolean {
        if (grant != owner || deletion != null) return false
        root!!.put("touched", true)
        return true
    }
    fun accept(owner: PhoneFcmTokenGrant, token: String): Boolean {
        require(validToken(token))
        if (grant != owner || deletion != null) return false
        val value = root!!
        if (value.optString("token") != token) value.put("revision", UUID.randomUUID().toString())
        value.put("token", token)
        return true
    }
    /** Callback payloads are not trusted as current: fence an in-flight fetch and re-read the SDK. */
    fun invalidate() {
        val value = root ?: return
        if (grant == null) return
        value.put("epoch", UUID.randomUUID().toString())
        value.remove("token"); value.remove("revision")
    }
    fun deleted(id: String): Boolean {
        if (deletion != id) return false
        root!!.remove("deletion"); root!!.remove("touched")
        if (grant == null) state.remove(KEY)
        return true
    }
    companion object {
        const val KEY = "fcm_token_lifecycle"
        fun validToken(value: String) = value.isNotEmpty() && value.length <= 4096 && value.none { it.isWhitespace() || it.isISOControl() }
    }
}

internal interface PhoneFcmTokenProvider {
    val project: PhoneFcmProject
    suspend fun token(): String
    suspend fun delete()
}

/** Caller serializes SDK operations, including non-cancellable SDK Task completion. */
internal class PhoneFcmTokenReconciler(
    private val transaction: ((PhoneFcmTokenState) -> Unit) -> Unit,
    private val login: () -> String?, private val allowed: () -> Boolean,
    private val provider: PhoneFcmTokenProvider?
) {
    /** false means settled or awaiting configuration; true requests another bounded worker pass. */
    suspend fun runPass(): Boolean {
        repeat(3) {
            var owner: PhoneFcmTokenGrant? = null; var deletion: String? = null; var project: PhoneFcmProject? = null
            transaction { state ->
                state.reconcile(login(), allowed(), provider?.project)
                owner = state.grant; deletion = state.deletion; project = state.project
            }
            val sdk = provider ?: return false
            if (project != sdk.project) return false // Never delete a different project's token.
            val retiring = deletion
            if (retiring != null) {
                sdk.delete()
                transaction { it.deleted(retiring) }
            } else {
                val fetching = owner ?: return false
                var started = false
                transaction { state ->
                    state.reconcile(login(), allowed(), sdk.project)
                    started = state.beginFetch(fetching)
                }
                if (!started) return@repeat
                val token = sdk.token()
                var accepted = false
                transaction { state ->
                    state.reconcile(login(), allowed(), sdk.project)
                    accepted = state.accept(fetching, token)
                }
                if (accepted) return false
            }
        }
        return true
    }
}
