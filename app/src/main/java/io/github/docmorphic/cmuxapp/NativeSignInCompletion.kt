package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** The fetched identity is distinct from a credential exchange's successful token write. */
internal data class NativeSignInUser(val id: String, val name: String?, val email: String?, val selectedTeam: String?) {
    companion object {
        fun decode(value: JSONObject): NativeSignInUser {
            // JSONSerialization bridges numeric 0/1 to Bool too; strings do not cast.
            fun flag(key: String): Boolean = when (val raw = value.opt(key)) {
                is Boolean -> raw
                is Number -> raw.toDouble() == 1.0
                else -> false
            }
            // StackClientApp.getUser(.throw) excludes these users by default.
            if (flag("is_anonymous") || flag("is_restricted"))
                throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
            fun text(value: Any?, limit: Int): String? {
                if (value == null || value === JSONObject.NULL) return null
                if (value !is String || value.length > limit) throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
                return value.trim().takeIf(String::isNotEmpty)
            }
            val id = text(value.opt("id"), 128) ?: throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
            val selected = value.opt("selected_team")
            if (selected != null && selected !== JSONObject.NULL && selected !is JSONObject)
                throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
            return NativeSignInUser(id, text(value.opt("display_name"), 512), text(value.opt("primary_email"), 512),
                (selected as? JSONObject)?.let { text(it.opt("id"), 128) })
        }
    }
}

/**
 * Shared email/OAuth completion. The receipt identifies the actual store writer,
 * including before UI publication; a newer attempt that has not written cannot
 * steal ownership, and cleanup never clears a newer writer's credentials.
 */
internal class NativeSignInCompletion(
    private val load: () -> JSONObject?,
    private val update: ((JSONObject) -> Unit) -> Unit,
    private val fetchUser: suspend (String) -> NativeSignInUser,
    private val environment: String,
    private val lock: Any = Any(),
    private val networkTimeoutMillis: Long = 60_000,
    private val onPublished: suspend (String, NativeSignInUser) -> Unit = { _, _ -> },
    private val refreshAccess: (suspend (String) -> String)? = null,
    private val work: NativeAuthWork = NativeAuthWork(),
    private val onCredentialsWritten: () -> Unit = {},
    private val onVerified: (String, NativeSignInUser) -> Unit = { _, _ -> },
) {
    private class Flow(val id: String, val generation: Long, val operation: Job) {
        var access: String? = null
        @Volatile var published = false
    }
    // Deadline/error delivery must never wait behind a blocked credential write.
    private val flowLock = Any()
    @Volatile private var generation = 0L
    private val active = mutableSetOf<Flow>()

    suspend fun signIn(exchangeTimeoutMillis: Long = networkTimeoutMillis, exchangePhase: NativeAuthPhase = NativeAuthPhase.EMAIL_EXCHANGE,
        exchange: suspend ((String, String) -> Unit) -> Unit): Unit = coroutineScope {
        val operation = currentCoroutineContext().job
        val (flow, previous) = synchronized(flowLock) {
            val previous = active.map { it.operation }
            Flow(UUID.randomUUID().toString(), ++generation, operation).also { active += it } to previous
        }
        previous.forEach { it.cancel() }
        var exchangeComplete = false
        try {
            withTimeout(networkTimeoutMillis) { previous.forEach { it.join() } }
            work.quiesce(networkTimeoutMillis)
            work.run(exchangePhase, exchangeTimeoutMillis, flow.id, NativeAuthWork.Admission.EXCLUSIVE) {
                val writer = currentCoroutineContext().job
                try { exchange { access, refresh -> synchronized(lock) {
                    writer.ensureActive()
                    checkCurrent(flow)
                    update { root ->
                        root.put("access_token", access).put("refresh_token", refresh).put("task_session", flow.id)
                            .put(PENDING, flow.id)
                        root.remove("task_drafts")
                    }
                    flow.access = access
                    onCredentialsWritten()
                } } }
                finally {
                    if (!writer.isActive) withContext(NonCancellable + Dispatchers.IO) {
                        synchronized(lock) { if (!flow.published) clearPending(flow.id) }
                    }
                }
            }
            exchangeComplete = true
            checkCurrent(flow)
            val access = checkNotNull(flow.access) { "Sign-in returned without credentials" }
            val user = work.run(NativeAuthPhase.USER, networkTimeoutMillis, flow.id) { fetchUser(access) }
            withContext(Dispatchers.IO) { synchronized(lock) {
                checkCurrent(flow)
                update { root ->
                    if (!ownsPending(root, flow.id)) throw CancellationException("Account changed")
                    NativeAccountProfileCache.writeVerifiedUser(root, flow.id, environment, user)
                    root.remove(PENDING)
                }
                // Once the verified user is published, cancellation of the form
                // or a tolerated team-refresh failure must not undo the login.
                flow.published = true
                onVerified(flow.id, user)
            } }
            try { work.run(NativeAuthPhase.TEAMS, networkTimeoutMillis, flow.id) { onPublished(flow.id, user) } }
            catch (failure: Exception) { currentCoroutineContext().ensureActive() }
        } catch (failure: Exception) {
            // User-fetch failures retain the SDK's stored credentials for a
            // later restore, but cannot expose an unverified signed-in shell.
            // Cancellation/deadline cleanup is conditional on this receipt.
            if (failure is CancellationException || (failure is NativeSignInFailure && failure.reason == NativeSignInError.TIMED_OUT)) {
                val mustClear = !flow.published && (!exchangeComplete || generation != flow.generation)
                if (mustClear) work.cleanup(currentCoroutineContext(), flow.id) {
                    withContext(Dispatchers.IO) { synchronized(lock) {
                        // A fetchUser deadline alone does not destroy the session.
                        if (!flow.published) clearPending(flow.id)
                    } }
                }
            }
            throw failure
        } finally { synchronized(flowLock) { active -= flow } }
    }

    fun hasLivePending(receipt: String): Boolean = synchronized(flowLock) {
        active.any { it.id == receipt && !it.published }
    }

    /** Resume only an orphaned pending exchange, never overlap a live sign-in. */
    suspend fun restorePending(timeoutMillis: Long = 8_000): Unit = coroutineScope {
        val operation = currentCoroutineContext().job
        val flow: Flow
        val access: String?
        val refresh: String?
        val expectedGeneration = generation
        val root = load() ?: return@coroutineScope
        synchronized(flowLock) {
            if (generation != expectedGeneration) return@coroutineScope
            if (active.isNotEmpty()) return@coroutineScope
            val id = root.opt(PENDING) as? String ?: return@coroutineScope
            if (!ownsPending(root, id)) return@coroutineScope
            access = (root.opt("access_token") as? String)?.takeIf { it.isNotBlank() }
            refresh = (root.opt("refresh_token") as? String)?.takeIf { it.isNotBlank() }
            if (access == null && (refresh == null || refreshAccess == null)) return@coroutineScope
            flow = Flow(id, ++generation, operation)
            active += flow
        }
        try {
            work.quiesce(networkTimeoutMillis)
            suspend fun refreshUser(): NativeSignInUser {
                val exchange = refreshAccess ?: throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
                val credential = refresh ?: throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
                val next = work.run(NativeAuthPhase.RESTORE_REFRESH, timeoutMillis, flow.id) { exchange(credential) }
                withContext(Dispatchers.IO) { synchronized(lock) {
                    checkCurrent(flow)
                    update { root ->
                        if (!ownsPending(root, flow.id) || root.opt("refresh_token") != credential)
                            throw CancellationException("Account changed")
                        root.put("access_token", next)
                    }
                } }
                return work.run(NativeAuthPhase.USER, networkTimeoutMillis, flow.id) { fetchUser(next) }
            }
            val user = if (access == null) refreshUser() else try {
                work.run(NativeAuthPhase.USER, networkTimeoutMillis, flow.id) { fetchUser(access) }
            } catch (failure: Exception) {
                if (nativeSignInError(failure) != NativeSignInError.UNAUTHORIZED || refreshAccess == null || refresh == null) throw failure
                refreshUser()
            }
            withContext(Dispatchers.IO) { synchronized(lock) {
                checkCurrent(flow)
                update { root ->
                    if (!ownsPending(root, flow.id)) throw CancellationException("Account changed")
                    NativeAccountProfileCache.writeVerifiedUser(root, flow.id, environment, user)
                    root.remove(PENDING)
                }
                flow.published = true
            } }
        } catch (failure: Exception) {
            if (nativeSignInError(failure) == NativeSignInError.UNAUTHORIZED) withContext(Dispatchers.IO) {
                synchronized(lock) { clearPending(flow.id) }
            }
            throw failure
        } finally { synchronized(flowLock) { active -= flow } }
    }

    /** Form retirement must not wait for credential IO. */
    fun cancelPending() {
        val flows = synchronized(flowLock) { ++generation; active.filterNot { it.published } }
        work.cancelOwners(flows.map { it.id }.toSet())
        flows.forEach { it.operation.cancel() }
    }

    /** Conditional session retirement is atomic with credential writers. */
    fun clearIf(includePublished: Boolean = false, retire: () -> Boolean): Boolean {
        val retired: List<Job>
        val owners: Set<String>
        val applied = synchronized(lock) {
            val result = retire()
            val flows = synchronized(flowLock) {
                if (result) { ++generation; active.filter { includePublished || !it.published } } else emptyList()
            }
            retired = flows.map { it.operation }; owners = flows.map { it.id }.toSet()
            result
        }
        if (applied && includePublished) work.cancelAll() else if (applied) work.cancelOwners(owners)
        retired.forEach { it.cancel() }
        return applied
    }

    private fun checkCurrent(flow: Flow) {
        flow.operation.ensureActive()
        if (flow.generation != generation) throw CancellationException("Sign-in retired")
    }
    private fun clearPending(id: String) {
        if (!ownsPending(load(), id)) return
        update { root -> if (ownsPending(root, id)) {
            root.put("access_token", "").put("refresh_token", "")
            root.remove("task_session"); root.remove("task_drafts"); root.remove(PENDING)
            NativeAccountProfileCache.prune(root)
        } }
    }
    companion object {
        const val PENDING = "sign_in_pending"
        fun isPending(root: JSONObject?): Boolean = root?.has(PENDING) == true
        private fun ownsPending(root: JSONObject?, id: String) = !id.isBlank() &&
            root?.opt("task_session") == id && root.opt(PENDING) == id
    }
}
