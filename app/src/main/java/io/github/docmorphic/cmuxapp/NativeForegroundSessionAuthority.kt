package io.github.docmorphic.cmuxapp

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Captures the verified foreground's original scope without extending admission to another Mac. */
internal class NativeForegroundSessionAuthority(
    private val teams: NativeAccountTeams,
    private val pairings: () -> List<NativeCredentialStore.PairedMac>,
    private val reserveTransport: (NativeTeamContinuation, MobileRpcClient) -> NativeForegroundTransport?
) {
    constructor(teams: NativeAccountTeams, pairings: () -> List<NativeCredentialStore.PairedMac>) :
        this(teams, pairings, { _, _ -> null })
    private class Binding(val proof: NativeTeamContinuation, val mac: NativeCredentialStore.PairedMac,
                          val client: MobileRpcClient, val transport: NativeForegroundTransport?) {
        val closed = AtomicBoolean(false)
        fun close() { if (closed.compareAndSet(false, true)) transport?.close() }
    }
    // No second monitor around calls into the shared account/credential lock.
    // Store emissions can reconcile synchronously while that lock is held.
    private val binding = AtomicReference<Binding?>(null)

    /** Caller has completed host identity, ticket and workspace verification on this exact handle. */
    fun admit(team: NativeTeamScope, mac: NativeCredentialStore.PairedMac, client: MobileRpcClient) {
        val previous = binding.get()
        val proof = checkNotNull(teams.captureContinuation(team)) { "Account or team changed during connection" }
        check(!client.isClosed && mac.accountUserId == team.userId && mac.accountTeamId == team.teamId &&
            NativeComputerMenuPairing.isCurrent(mac, pairings())) { "The admitted Mac identity changed" }
        val candidate = Binding(proof, mac, client, reserveTransport(proof, client))
        if (!teams.isCurrent(team) || !teams.allowsContinuation(proof) || !binding.compareAndSet(previous, candidate)) {
            candidate.close()
            error("Foreground connection changed")
        }
        try { candidate.transport?.activate { binding.get() === candidate && !candidate.closed.get() } }
        catch (failure: Throwable) {
            if (!binding.compareAndSet(candidate, previous?.takeUnless { it.closed.get() })) previous?.close()
            candidate.close()
            throw failure
        }
        previous?.close()
    }

    fun scopeFor(code: String, client: MobileRpcClient?): NativeTeamScope? {
        val captured = binding.get() ?: return null
        if (captured.client !== client || captured.mac.code != code) return null
        if (captured.closed.get() || client.isClosed || !teams.allowsContinuation(captured.proof) ||
            !NativeComputerMenuPairing.isCurrent(captured.mac, pairings())) {
            if (binding.compareAndSet(captured, null)) captured.close()
            return null
        }
        return captured.proof.admittedScope.takeIf { binding.get() === captured }
    }

    /** Exact saved identity, including route/ticket revision; a same-code sibling cannot borrow it. */
    fun scopeFor(mac: NativeCredentialStore.PairedMac, client: MobileRpcClient? = binding.get()?.client): NativeTeamScope? {
        val captured = binding.get() ?: return null
        if (captured.client !== client || !NativeComputerMenuPairing.isCurrent(captured.mac, listOf(mac))) return null
        return scopeFor(mac.code, client).takeIf { binding.get() === captured }
    }

    /** An old composition's disposal cannot erase the replacement foreground binding. */
    fun release(client: MobileRpcClient) {
        val captured = binding.get() ?: return
        if (captured.client === client && binding.compareAndSet(captured, null)) captured.close()
    }

    fun reconcile() {
        val captured = binding.get() ?: return
        if (captured.closed.get() || captured.client.isClosed || !teams.allowsContinuation(captured.proof) ||
            !NativeComputerMenuPairing.isCurrent(captured.mac, pairings())) {
            if (binding.compareAndSet(captured, null)) captured.close()
        }
    }

    fun clear() { binding.getAndSet(null)?.close() }
}
