package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** The fetched identity is distinct from a credential exchange's successful token write. */
internal data class NativeSignInUser(val id: String, val name: String?, val email: String?, val selectedTeam: String?,
    val profileImageUrl:String?=null,val demonstrationContentEnabled:Boolean=false) {
    companion object {
        fun isEligible(value: JSONObject): Boolean {
            // JSONSerialization bridges numeric 0/1 to Bool too; strings do not cast.
            fun flag(key: String): Boolean = when (val raw = value.opt(key)) {
                is Boolean -> raw
                is Number -> raw.toDouble() == 1.0
                else -> false
            }
            return !flag("is_anonymous") && !flag("is_restricted")
        }
        fun decode(value: JSONObject): NativeSignInUser {
            // StackClientApp.getUser(.throw) excludes these users by default.
            if (!isEligible(value))
                throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)
            // Complete SDK User/CurrentUser projections preserve strings and use defaults;
            // publishing an empty account id still cannot produce a connection identity.
            return NativeSignInUser(value.opt("id") as? String ?: "",value.opt("display_name") as? String,
                value.opt("primary_email") as? String,
                value.optJSONObject("selected_team")?.let{it.opt("id") as? String ?: ""},
                value.opt("profile_image_url") as? String,
                value.optJSONObject("client_read_only_metadata")?.opt("cmuxReviewDemoContent") == true)
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
    private val sessionEpoch: () -> Long = { 0 },
    private val fetchUserWithRefresh:(suspend(String,suspend(String?)->String?)->NativeSignInUser)?=null,
    private val fetchPendingUser:(suspend(String,()->Unit)->NativeSignInUser)?=null,
) {
    private class Flow(val id: String, val generation: Long, val operation: Job, var epoch: Long,
        val lease: NativeAuthWork.SignInLease) {
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
        val (flow, previous) = work.afterSignOutCapture { synchronized(flowLock) {
            val previous = active.map { it.operation }
            Flow(UUID.randomUUID().toString(), ++generation, operation, sessionEpoch(), work.beginSignIn()).also { active += it } to previous
        } }
        previous.forEach { it.cancel() }
        var exchangeComplete = false
        try {
            withTimeout(networkTimeoutMillis) { previous.forEach { it.join() } }
            work.quiesce(networkTimeoutMillis)
            synchronized(lock) { checkCurrent(flow) }
            work.run(exchangePhase, exchangeTimeoutMillis, flow.id, NativeAuthWork.Admission.EXCLUSIVE) {
                synchronized(lock) { checkCurrent(flow) }
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
                    flow.epoch = sessionEpoch()
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
            val user = work.run(NativeAuthPhase.USER, networkTimeoutMillis, flow.id) { pendingUser(flow,access,30_000) }
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
                flow.lease.publish()
            } }
            try { work.run(NativeAuthPhase.TEAMS, networkTimeoutMillis, flow.id) { onPublished(flow.id, user) } }
            catch (failure: Exception) { currentCoroutineContext().ensureActive() }
        } catch (failure: Exception) {
            // User-fetch failures retain the SDK's stored credentials for a
            // later restore, but cannot expose an unverified signed-in shell.
            // Cancellation/deadline cleanup is conditional on this receipt.
            if (failure is CancellationException || (failure is NativeSignInFailure && failure.reason == NativeSignInError.TIMED_OUT)) {
                val mustClear = !flow.published && (!exchangeComplete || generation != flow.generation || flow.epoch != sessionEpoch())
                if (mustClear) work.cleanup(currentCoroutineContext(), flow.id) {
                    withContext(Dispatchers.IO) { synchronized(lock) {
                        // A fetchUser deadline alone does not destroy the session.
                        if (!flow.published) clearPending(flow.id)
                    } }
                }
            }
            throw failure
        } finally { flow.lease.finish();synchronized(flowLock) { active -= flow } }
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
        val expectedEpoch = work.afterSignOutCapture { sessionEpoch() }
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
            flow = Flow(id, ++generation, operation, expectedEpoch, work.beginSignIn())
            active += flow
        }
        try {
            work.quiesce(networkTimeoutMillis)
            synchronized(lock) { checkCurrent(flow) }
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
            val user = if(fetchPendingUser!=null)work.run(NativeAuthPhase.USER,networkTimeoutMillis,flow.id) {
                pendingUser(flow,access.orEmpty(),timeoutMillis)
            } else if(fetchUserWithRefresh!=null)work.run(NativeAuthPhase.USER,networkTimeoutMillis,flow.id) {
                val initial=access ?: refreshPendingAccess(flow,null,timeoutMillis) ?: throw NativeSessionUserUnavailable()
                pendingUser(flow,initial,timeoutMillis)
            } else if (access == null) refreshUser() else try {
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
                onVerified(flow.id, user)
                flow.lease.publish()
            } }
        } catch (failure: Exception) {
            if (nativeSignInError(failure) == NativeSignInError.UNAUTHORIZED) withContext(Dispatchers.IO) {
                synchronized(lock) { clearPending(flow.id) }
            }
            throw failure
        } finally { flow.lease.finish();synchronized(flowLock) { active -= flow } }
    }

    private suspend fun pendingUser(flow:Flow,access:String,refreshTimeout:Long):NativeSignInUser {
        val caller=currentCoroutineContext()
        return fetchPendingUser?.invoke(flow.id) {caller.ensureActive();checkCurrent(flow)}
            ?: fetchUserWithRefresh?.invoke(access) {failed->refreshPendingAccess(flow,failed,refreshTimeout)} ?: fetchUser(access)
    }

    /** A pending receipt can refresh itself without admitting its unverified tokens as an account. */
    private suspend fun refreshPendingAccess(flow:Flow,rejected:String?,timeout:Long):String? {
        val exchange=refreshAccess ?: return null
        val caller=currentCoroutineContext()
        val observed:Pair<String,String?> = synchronized(lock) {
            caller.ensureActive();checkCurrent(flow)
            val root=load()
            if(!ownsPending(root,flow.id))throw CancellationException("Account changed")
            val refresh=root?.opt("refresh_token") as? String ?: return null
            val current=root.opt("access_token") as? String
            if(rejected!=null && current!=null && current!=rejected && !nativeAccessTokenIsExpired(current,System.currentTimeMillis()))return current
            refresh to current
        }
        val next=try {work.run(NativeAuthPhase.RESTORE_REFRESH,timeout,flow.id){exchange(observed.first)}}
        catch(failure:Exception) {
            caller.ensureActive();checkCurrent(flow)
            if(failure is NativeSignInFailure && failure.reason==NativeSignInError.UNAUTHORIZED)withContext(Dispatchers.IO) {
                synchronized(lock) {
                    caller.ensureActive();checkCurrent(flow)
                    val root=load()
                    if(ownsPending(root,flow.id) && root?.opt("refresh_token")==observed.first)clearPending(flow.id)
                }
            }
            // APIClient.fetchNewAccessToken returns nil for a failed refresh; its caller
            // then throws the original rejected-access error rather than inventing a new one.
            return null
        }
        return withContext(Dispatchers.IO) {synchronized(lock) {
            caller.ensureActive();checkCurrent(flow)
            update {root->
                caller.ensureActive();checkCurrent(flow)
                if(!ownsPending(root,flow.id) || root.opt("refresh_token")!=observed.first || root.opt("access_token")!=observed.second)
                    throw CancellationException("Account changed")
                root.put("access_token",next)
            }
            flow.access=next;next
        }}
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
        if (flow.generation != generation || flow.epoch != sessionEpoch()) throw CancellationException("Sign-in retired")
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
