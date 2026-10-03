package io.github.docmorphic.cmuxapp

/** Retained connection-switch intent. No client, credential, Activity or input is retained here. */
internal class NativeMacSwitchRecovery {
    data class Owner(val login: String, val team: NativeTeamScope?)
    data class Baseline(val mac: NativeCredentialStore.PairedMac, val selection: String)
    data class Attempt(val id: Long, val owner: Owner, val target: String, val baseline: Baseline?)
    private var owner: Owner? = null
    private var live: NativeCredentialStore.PairedMac? = null
    private var pending: Attempt? = null
    private var nextId = 0L

    fun reconcile(next: Owner?) {
        if (owner != next) { clear(); owner = next }
    }
    fun connected(next: Owner, mac: NativeCredentialStore.PairedMac) {
        reconcile(next); live = mac
        if (pending?.target == mac.code) pending = null
    }
    fun begin(next: Owner, target: String, connectedCode: String?, selection: String,
        savedFallback: NativeCredentialStore.PairedMac? = null) {
        reconcile(next)
        if (target == connectedCode) { pending = null; return }
        // Two picker taps can arrive before Compose retires the original client.
        // Preserve the first filter snapshot as well as the original connection.
        val baseline = pending?.baseline
            ?: live?.takeIf { it.code == connectedCode }?.let { Baseline(it, selection) }
            ?: savedFallback?.takeIf { it.code != target }?.let { Baseline(it, selection) }
        pending = Attempt(++nextId, next, target, baseline)
    }
    /** Non-picker navigation supersedes a pending switch, including returning to Computers. */
    fun entering(next: Owner?, code: String): Attempt? {
        reconcile(next)
        if (pending?.target != code) pending = null
        return pending
    }
    fun failed(attempt: Attempt?, current: Owner?, code: String,
        allowed: (NativeCredentialStore.PairedMac) -> Boolean): Baseline? {
        if (attempt == null || pending != attempt || owner != current || attempt.owner != current || attempt.target != code) return null
        pending = null
        val baseline = attempt.baseline?.takeIf { it.mac.code != code && allowed(it.mac) } ?: return null
        // A newer choice during restoration may still fall back to the same Mac.
        // Failure of this restoration cannot loop because its target is the baseline.
        pending = Attempt(++nextId, attempt.owner, baseline.mac.code, baseline)
        return baseline
    }
    fun retarget(attempt: Attempt?, current: Owner?, code: String) {
        if (attempt != null && pending == attempt && owner == current && attempt.owner == current)
            pending = attempt.copy(target = code)
    }
    fun cancelAndRestore(current: Owner?, code: String, selection: String,
        allowed: (NativeCredentialStore.PairedMac) -> Boolean): Baseline? {
        val attempt = pending
        if (attempt == null || owner != current || attempt.owner != current || attempt.target != code) return null
        pending = null
        val baseline = attempt.baseline?.takeIf { allowed(it.mac) }?.copy(selection = selection) ?: return null
        pending = Attempt(++nextId, attempt.owner, baseline.mac.code, baseline)
        return baseline
    }
    fun cancel() { pending = null }
    fun clear() { owner = null; live = null; pending = null }
}
