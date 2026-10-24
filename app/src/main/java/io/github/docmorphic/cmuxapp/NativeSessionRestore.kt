package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cached presentation and a live credential verdict are separate source states. */
internal data class NativeAuthState(val authenticated: Boolean = false, val user: NativeSignInUser? = null,
    val restoring: Boolean = false) {
    companion object {
        fun primed(hasTokens: Boolean, cachedUser: NativeSignInUser?) =
            NativeAuthState(hasTokens && cachedUser != null, cachedUser, hasTokens)
    }
}

/** Credential-free published account incarnation. Profile edits and same-user revalidation keep it stable. */
internal data class NativeAuthIdentity(val generation:Long,val userId:String) {
    override fun toString()="NativeAuthIdentity(generation=$generation, user=<redacted>)"
}

/** SDK getUser(.throw) collapses most request failures into USER_NOT_SIGNED_IN. */
internal class NativeSessionUserUnavailable : IllegalStateException("The saved account could not be verified")

/** Source launch/foreground validation policy. Storage and backend effects remain injected. */
internal class NativeSessionRestore(
    hasTokensHint: Boolean, cachedUser: NativeSignInUser?,
    private val setHint: (Boolean) -> Unit,
    private val loadCachedUser: () -> NativeSignInUser?,
    private val storageAvailable: suspend () -> Boolean,
    private val hasStoredTokens: suspend () -> Boolean,
    private val currentUser: suspend () -> NativeSignInUser?,
    private val refreshToken: suspend () -> String?,
    private val clearPersisted: suspend (String?) -> Boolean,
    private val saveCachedUser: suspend (NativeSignInUser) -> Unit,
    private val refreshTeams: suspend (NativeSignInUser) -> Unit,
    private val postSignIn: suspend () -> Unit = {},
    private val work: NativeAuthWork = NativeAuthWork(),
    private val restoreTimeoutMillis: Long = 8_000,
    private val networkTimeoutMillis: Long = 60_000,
    private val onCleared: () -> Unit = {},
    private val externalEpoch: () -> Long = { 0 },
    private val lock: Any = Any(),
    private val restoreDispatcher:CoroutineDispatcher?=null,
) {
    @Volatile private var generation = 0L
    internal val generationSnapshot get()=generation
    val sessionGeneration get()=synchronized(lock){generation}
    @Volatile internal var recoveryObserver:NativeSessionRecoveryObserver?=null
    private var credentialWrites = 0L
    private var hint = hasTokensHint
    private var validation: CompletableDeferred<Unit>? = null
    private var bootstrap: Job? = null
    private var initialValidationAttempted=false
    val needsIdentityValidation get()=synchronized(lock) { !initialValidationAttempted || verifiedSessionGeneration!=generation || publishedIdentity.value!=verifiedIdentity }
    private val mutable = MutableStateFlow(NativeAuthState.primed(hasTokensHint, cachedUser))
    val state = mutable.asStateFlow()
    private val publishedIdentity=MutableStateFlow(identityFrom(mutable.value))
    private var verifiedIdentity:NativeAuthIdentity?=null
    private var verifiedSessionGeneration:Long?=null
    val identities=publishedIdentity.asStateFlow()
    private fun identityFrom(value:NativeAuthState)=value.user?.id?.takeIf { value.authenticated && it.isNotEmpty() }
        ?.let { NativeAuthIdentity(generation,it) }
    fun identity():NativeAuthIdentity?=synchronized(lock) { publishedIdentity.value }
    fun isIdentityCurrent(identity:NativeAuthIdentity)=synchronized(lock) { publishedIdentity.value==identity }
    internal fun verifiedUser():NativeSignInUser?=synchronized(lock) {
        mutable.value.user?.takeIf{mutable.value.authenticated && verifiedSessionGeneration==generation && publishedIdentity.value==verifiedIdentity}
    }
    private val checking = MutableStateFlow(false)
    val revalidating = checking.asStateFlow()
    private data class Context(val generation: Long, val writes: Long, val external: Long)
    private fun capture() = synchronized(lock) { Context(generation, credentialWrites, externalEpoch()) }
    private fun current(context: Context) = synchronized(lock) { generation == context.generation && externalEpoch() == context.external }
    private fun clearIsCurrent(context: Context) = synchronized(lock) {
        generation == context.generation && credentialWrites == context.writes && externalEpoch() == context.external
    }
    fun noteCredentialWrite() = synchronized(lock) { credentialWrites++ }
    fun start(scope: CoroutineScope) = synchronized(lock) {
        if (bootstrap == null) bootstrap = scope.launch(start = CoroutineStart.LAZY) { checkExisting() }.also { it.start() }
    }
    suspend fun awaitBootstrapped() { synchronized(lock) { bootstrap }?.join() }
    /** Private background account owners have no Activity bootstrap; join or run the same validation. */
    suspend fun restoreIdentityIfNeeded():NativeSignInUser? {
        if(identity()==null || needsIdentityValidation)checkExisting()
        return verifiedUser()
    }
    suspend fun revalidate() {
        if (synchronized(lock) { !mutable.value.authenticated && !mutable.value.restoring && !hint }) return
        checkExisting()
    }
    /** Recovery admission is checked atomically before a new validation; existing runs still coalesce. */
    internal suspend fun revalidateIf(admit:()->Boolean) {
        if(synchronized(lock){!mutable.value.authenticated && !mutable.value.restoring && !hint})return
        checkExisting(admit)
    }
    fun clear(clearCachedUser: Boolean = true) { clearIfCurrent(null, clearCachedUser) }
    private fun clearIfCurrent(context: Context?, clearCachedUser: Boolean = true) {
        val cleared = synchronized(lock) {
            if (context != null && !clearIsCurrent(context)) false else {
                generation++; hint = false; verifiedIdentity=null;verifiedSessionGeneration=null;publishedIdentity.value=null;mutable.value = NativeAuthState()
                runCatching { setHint(false) }; recoveryObserver?.cleared();true
            }
        }
        if (cleared && clearCachedUser) runCatching(onCleared)
    }
    /** Called only after the interactive completion has committed its verified user. */
    fun publishedSignIn(user: NativeSignInUser) = synchronized(lock) {
        recoveryObserver?.signedIn();publish(user, signIn = true);Unit
    }
    private fun publish(user: NativeSignInUser, signIn: Boolean, context: Context? = null): Pair<Long, Boolean>? {
        val publication = synchronized(lock) {
            if (context != null && !current(context)) return null
            val transition = signIn || !mutable.value.authenticated || mutable.value.user?.id != user.id
            if (transition) generation++
            initialValidationAttempted=true
            hint = true
            val value=NativeAuthState(true,user,false)
            verifiedSessionGeneration=generation;verifiedIdentity=identityFrom(value);publishedIdentity.value=verifiedIdentity;mutable.value=value
            runCatching { setHint(true) }
            generation to transition
        }
        return publication
    }
    private fun preserve(context: Context) {
        val cached = state.value.user ?: runCatching(loadCachedUser).getOrNull()
        val preserved=synchronized(lock) {
            if (generation != context.generation || externalEpoch() != context.external) false else {
                hint = true
                val value=NativeAuthState(cached != null,cached,false)
                publishedIdentity.value=identityFrom(value);mutable.value=value
                runCatching { setHint(true) };true
            }
        }
        // Both the hint and snapshot were committed under the session monitor.
        if(preserved)recoveryObserver?.preserved(context.generation)
    }
    private suspend fun checkExisting(admit:()->Boolean={true}) {
        if(restoreDispatcher==null)checkExistingOwned(admit)
        else withContext(restoreDispatcher){checkExistingOwned(admit)}
    }
    private suspend fun checkExistingOwned(admit:()->Boolean) {
        val (done, leader) = synchronized(lock) {
            validation?.let { it to false } ?: run {
                if(!admit())return
                CompletableDeferred<Unit>().also { validation = it; checking.value = true } to true
            }
        }
        // The source's checked-continuation waiters complete with the owning run.
        if (!leader) { withContext(NonCancellable) { done.await() }; return }
        work.restoreSession(restoreDispatcher,onFinished={
            try {recoveryObserver?.validationCompleted()} finally {
                synchronized(lock) { initialValidationAttempted=true;validation = null; checking.value = false; done.complete(Unit) }
            }
        }) {body(retryStorage=true)}
    }
    private suspend fun body(retryStorage: Boolean) {
        val context = capture()
        val cachedUser = runCatching(loadCachedUser).getOrNull()
        val available = storageAvailable()
        if (!current(context)) return
        val tokens = try {
            work.run(NativeAuthPhase.VALIDATE_SESSION, restoreTimeoutMillis) { hasStoredTokens() }
        } catch (_: Exception) { if (current(context)) preserve(context); return }
        if (!current(context)) return
        if (!tokens) {
            if (!available) {
                preserve(context)
                if (retryStorage && storageAvailable() && current(context)) body(retryStorage = false)
            } else clearIfCurrent(context)
            return
        }
        synchronized(lock) {
            if (current(context)) {
                hint = true
                if (mutable.value.user == null && cachedUser != null) mutable.value = mutable.value.copy(user = cachedUser)
                runCatching { setHint(true) }
            }
        }
        try {
            val user = work.run(NativeAuthPhase.VALIDATE_SESSION, restoreTimeoutMillis) { currentUser() }
            if (!current(context)) return
            if (user == null) {
                val expected = work.run(NativeAuthPhase.VALIDATE_SESSION, restoreTimeoutMillis) { refreshToken() }
                if (current(context)) clearStale(context, expected)
                return
            }
            val (publishedGeneration, transition) = publish(user, signIn = false, context = context) ?: return
            runCatching { saveCachedUser(user) }
            if (synchronized(lock) { generation != publishedGeneration } || externalEpoch() != context.external) return
            try { work.run(NativeAuthPhase.TEAMS, networkTimeoutMillis) { refreshTeams(user) } }
            catch (_: Exception) { /* Membership failure cannot undo a published user. */ }
            if (synchronized(lock) { generation != publishedGeneration } || externalEpoch() != context.external || !transition) return
            try { work.run(NativeAuthPhase.POST_SIGN_IN, networkTimeoutMillis) { postSignIn() } }
            catch (_: Exception) { /* Source post-sign-in effects are fallible. */ }
        } catch (failure: Exception) {
            if (!current(context)) return
            if (failure is CancellationException) return
            if (failure is NativeSignInFailure && failure.reason == NativeSignInError.TIMED_OUT) { preserve(context); return }
            val surviving = try {
                work.run(NativeAuthPhase.VALIDATE_SESSION, restoreTimeoutMillis) { refreshToken() }
            } catch (_: Exception) { if (current(context)) preserve(context); return }
            if (!current(context)) return
            if (surviving == null || failure is NativeSignInFailure && failure.reason == NativeSignInError.UNAUTHORIZED)
                clearStale(context, surviving)
            else {recoveryObserver?.validationFailed(context.generation,failure);preserve(context)}
        }
    }
    private suspend fun clearStale(context: Context, expectedRefresh: String?) {
        if (!clearIsCurrent(context)) return
        if (!clearPersisted(expectedRefresh)) return
        clearIfCurrent(context)
    }
}
