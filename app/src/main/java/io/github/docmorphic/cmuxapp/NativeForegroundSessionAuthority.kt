package io.github.docmorphic.cmuxapp

import java.util.concurrent.atomic.AtomicReference

/** Captures the verified foreground's original scope without extending admission to another Mac. */
internal class NativeForegroundSessionAuthority(
    private val teams: NativeAccountTeams,
    private val pairings: () -> List<NativeCredentialStore.PairedMac>
) {
    private class Binding(val proof: NativeTeamContinuation, val mac: NativeCredentialStore.PairedMac,
                          val client: MobileRpcClient)
    // No second monitor around calls into the shared account/credential lock.
    // Store emissions can reconcile synchronously while that lock is held.
    private val binding = AtomicReference<Binding?>(null)

    /** Caller has completed host identity, ticket and workspace verification on this exact handle. */
    fun admit(team: NativeTeamScope, mac: NativeCredentialStore.PairedMac, client: MobileRpcClient) {
        val previous = binding.get()
        val proof = checkNotNull(teams.captureContinuation(team)) { "Account or team changed during connection" }
        check(!client.isClosed && mac.accountUserId == team.userId && mac.accountTeamId == team.teamId &&
            NativeComputerMenuPairing.isCurrent(mac, pairings())) { "The admitted Mac identity changed" }
        check(teams.isCurrent(team) && teams.allowsContinuation(proof) &&
            binding.compareAndSet(previous, Binding(proof, mac, client))) { "Foreground connection changed" }
    }

    fun scopeFor(code: String, client: MobileRpcClient?): NativeTeamScope? {
        val captured = binding.get() ?: return null
        if (captured.client !== client || captured.mac.code != code) return null
        if (client.isClosed || !teams.allowsContinuation(captured.proof) ||
            !NativeComputerMenuPairing.isCurrent(captured.mac, pairings())) {
            binding.compareAndSet(captured, null)
            return null
        }
        return captured.proof.admittedScope.takeIf { binding.get() === captured }
    }

    /** An old composition's disposal cannot erase the replacement foreground binding. */
    fun release(client: MobileRpcClient) {
        val captured = binding.get() ?: return
        if (captured.client === client) binding.compareAndSet(captured, null)
    }

    fun reconcile() {
        val captured = binding.get() ?: return
        if (captured.client.isClosed || !teams.allowsContinuation(captured.proof) ||
            !NativeComputerMenuPairing.isCurrent(captured.mac, pairings())) binding.compareAndSet(captured, null)
    }

    fun clear() { binding.set(null) }
}
