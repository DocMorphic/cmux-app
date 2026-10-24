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
) {
    private fun fail(reason: NativeSignInError): Nothing = throw NativeSignInFailure(reason)
    private fun changed(): Nothing = fail(NativeSignInError.UNAUTHORIZED)
    private fun transitioning(): Nothing = fail(NativeSignInError.NETWORK_ERROR)
    suspend fun session(): NativeAuthenticatedSessionSnapshot {
        currentCoroutineContext().ensureActive()
        // Complete stored pairs authenticate server-side even when access needs refreshing.
        // Do not make a connection queue behind read-only launch/foreground validation.
        if (!storedCaptureBlocked()) {
            val stored = try { read() } catch (_: NativeCredentialReadException) { null }
            if (stored?.owner != null && !stored.pending && !stored.access.isNullOrBlank() && !stored.refresh.isNullOrBlank()) {
                val after = try { read() } catch (_: NativeCredentialReadException) { null }
                if (!storedCaptureBlocked() && after?.owner == stored.owner && !after.pending && after.refresh == stored.refresh) {
                    currentCoroutineContext().ensureActive()
                    return NativeAuthenticatedSessionSnapshot(stored.owner,stored.access,stored.refresh)
                }
            }
        }
        awaitBootstrapped()
        currentCoroutineContext().ensureActive()
        if (transitionActive()) transitioning()
        val initial = read()
        if (initial.pending) transitioning()
        val owner = initial.owner ?: changed()
        repeat(3) {
            val before = read()
            if (before.pending) transitioning()
            if (before.owner != owner) changed()
            val refresh = before.refresh?.takeIf(String::isNotBlank) ?: changed()
            val access = accessToken()?.takeIf(String::isNotBlank)
            currentCoroutineContext().ensureActive()
            val after = read()
            if (transitionActive() || after.pending) transitioning()
            if (after.owner != owner) changed()
            if (access == null) {
                if (!after.refresh.isNullOrBlank()) transitioning() else changed()
            }
            if (after.refresh == refresh) return NativeAuthenticatedSessionSnapshot(owner,access,refresh)
        }
        transitioning()
    }
    /** Browser authentication that accepts a refresh token alone uses this separate contract. */
    suspend fun refresh(): NativeAuthenticatedRefreshSnapshot {
        awaitBootstrapped()
        currentCoroutineContext().ensureActive()
        val before = read()
        val owner = before.owner ?: changed()
        if (transitionActive() || before.pending) changed()
        val refresh = before.refresh?.takeIf(String::isNotBlank) ?: changed()
        val after = read()
        if (transitionActive() || after.pending || after.owner != owner || after.refresh != refresh) changed()
        return NativeAuthenticatedRefreshSnapshot(owner,refresh)
    }
    /** Token rotation within this identity does not retire the operation that asked. */
    fun isCurrent(owner: NativeAuthenticatedOwner): Boolean =
        !transitionActive() && runCatching { read().let { !it.pending && it.owner == owner } }.getOrDefault(false)
}
