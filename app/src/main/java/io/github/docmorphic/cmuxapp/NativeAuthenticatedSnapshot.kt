package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Account incarnation plus the shared encrypted-document writer epoch. No credentials. */
internal data class NativeAuthenticatedOwner(val identity: NativeAuthIdentity, val login: String, val epoch: Long) {
    override fun toString() = "NativeAuthenticatedOwner(redacted)"
}
internal class NativeAuthenticatedSessionSnapshot(val owner: NativeAuthenticatedOwner, val accessToken: String, val refreshToken: String) {
    override fun toString() = "NativeAuthenticatedSessionSnapshot(redacted)"
}
internal class NativeAuthenticatedRefreshSnapshot(val owner: NativeAuthenticatedOwner, val refreshToken: String) {
    override fun toString() = "NativeAuthenticatedRefreshSnapshot(redacted)"
}
/** One atomic storage/model observation; absence and unavailable storage remain distinct. */
internal class NativeStoredAuthSession(val owner: NativeAuthenticatedOwner?, val access: String?, val refresh: String?,
    val pending: Boolean = false) {
    override fun toString() = "NativeStoredAuthSession(redacted)"
}

/** Original AuthCoordinator token-snapshot policy, with storage and phase ownership injected. */
internal class NativeAuthenticatedSnapshots(
    private val read: () -> NativeStoredAuthSession,
    private val storedCaptureBlocked: () -> Boolean,
    private val transitionActive: () -> Boolean,
    private val awaitBootstrapped: suspend () -> Unit,
    private val accessToken: suspend () -> String?,
    private val publishedOwner: () -> NativeAuthenticatedOwner?,
    private val pendingRecovery: () -> Boolean = { false },
    private val work: NativeAuthWork = NativeAuthWork(),
    private val captureTimeoutMillis: Long = 60_000,
    private val availability:()->Boolean = { try {read();true} catch(_:NativeCredentialReadException){false} },
) {
    private fun fail(reason: NativeSignInError): Nothing = throw NativeSignInFailure(reason)
    private fun changed(): Nothing = fail(NativeSignInError.UNAUTHORIZED)
    private fun transitioning(): Nothing = fail(NativeSignInError.NETWORK_ERROR)
    // The SDK storage boundary stays strict. The application coordinator observes unavailable
    // token reads as nil and classifies them using availability captured before the operation.
    private fun tokens(): NativeStoredAuthSession = try { read() }
        catch (_: NativeCredentialReadException) { NativeStoredAuthSession(publishedOwner(),null,null) }
    private fun empty(available:Boolean):Nothing = if(available)changed() else transitioning()
    private fun owner():NativeAuthenticatedOwner = publishedOwner()?.takeIf{it.identity.userId.isNotEmpty()} ?: changed()
    suspend fun session(): NativeAuthenticatedSessionSnapshot {
        currentCoroutineContext().ensureActive()
        // Complete stored pairs authenticate server-side even when access needs refreshing.
        // Do not make a connection queue behind read-only launch/foreground validation.
        if (!storedCaptureBlocked()) {
            val stored = try { read() } catch (_: NativeCredentialReadException) { null }
            if (stored?.owner != null && stored.owner.identity.userId.isNotEmpty() && !stored.pending && !stored.access.isNullOrBlank() && !stored.refresh.isNullOrBlank()) {
                val after = try { read() } catch (_: NativeCredentialReadException) { null }
                if (!storedCaptureBlocked() && publishedOwner()==stored.owner && after?.owner == stored.owner && !after.pending && after.refresh == stored.refresh) {
                    currentCoroutineContext().ensureActive()
                    return NativeAuthenticatedSessionSnapshot(stored.owner,stored.access,stored.refresh)
                }
            }
        }
        awaitBootstrapped()
        currentCoroutineContext().ensureActive()
        // Android's private orphan-exchange recovery remains retryable without exposing its pair.
        if(pendingRecovery())transitioning()
        val owner = owner()
        if (transitionActive()) transitioning()
        val pair=work.run(NativeAuthPhase.REFRESH,captureTimeoutMillis,owner.login) { coherent(owner) }
        if(publishedOwner()!=owner)changed()
        if(transitionActive() || pendingRecovery())transitioning()
        return NativeAuthenticatedSessionSnapshot(owner,pair.first,pair.second)
    }
    private suspend fun coherent(owner:NativeAuthenticatedOwner):Pair<String,String> {
        val available=availability()
        currentCoroutineContext().ensureActive()
        repeat(3) {
            val before = tokens()
            currentCoroutineContext().ensureActive()
            if (before.pending) transitioning()
            val refresh = before.refresh?.takeIf(String::isNotBlank) ?: empty(available)
            if (before.owner != owner) changed()
            val access = try {accessToken()?.takeIf(String::isNotBlank)} catch(_:NativeCredentialReadException){null}
            currentCoroutineContext().ensureActive()
            val after = tokens()
            if (after.pending) transitioning()
            if (access == null) {
                if (!after.refresh.isNullOrBlank()) transitioning() else empty(available)
            }
            if (after.owner != owner) changed()
            if (after.refresh == refresh) return access to refresh
        }
        transitioning()
    }
    /** Browser authentication that accepts a refresh token alone uses this separate contract. */
    suspend fun refresh(): NativeAuthenticatedRefreshSnapshot {
        awaitBootstrapped()
        currentCoroutineContext().ensureActive()
        val owner = owner()
        if (transitionActive() || pendingRecovery()) changed()
        val available=availability()
        val before = tokens()
        if (before.pending) changed()
        val refresh = before.refresh?.takeIf(String::isNotBlank) ?: empty(available)
        if (before.owner != owner || publishedOwner()!=owner || transitionActive()) changed()
        currentCoroutineContext().ensureActive()
        return NativeAuthenticatedRefreshSnapshot(owner,refresh)
    }
    /** Token rotation within this identity does not retire the operation that asked. */
    fun isCurrent(owner: NativeAuthenticatedOwner): Boolean =
        !transitionActive() && publishedOwner()==owner
}
