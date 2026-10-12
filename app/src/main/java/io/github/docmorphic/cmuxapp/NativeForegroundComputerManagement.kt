package io.github.docmorphic.cmuxapp

import java.util.concurrent.atomic.AtomicBoolean

/** Minted from a live admitted record. It cannot admit a transport or target another Mac. */
internal class NativeForegroundComputerManagement internal constructor(val team: NativeTeamScope,
    val mac: NativeCredentialStore.PairedMac, private val rootCurrent: () -> Boolean,
    private val memberAndRecordCurrent: () -> Boolean) {
    fun available() = rootCurrent() && memberAndRecordCurrent()
    fun routeProbe(detailCurrent: () -> Boolean): NativeForegroundRouteProbe {
        check(available() && detailCurrent()) { "Computer owner changed. Reopen Computer Details." }
        return NativeForegroundRouteProbe({ available() && detailCurrent() }, memberAndRecordCurrent)
    }
    fun beginRevoke(): NativeForegroundComputerRevoke {
        check(available()) { "Computer owner changed. Reopen Computer Details." }
        val target = checkNotNull(NativeComputerTarget.from(mac, team)) { "Computer identity is unavailable" }
        return NativeForegroundComputerRevoke(team, target, memberAndRecordCurrent)
    }
}

/** One route save may finish after it retires its old root grant. No further dialing/RPC is allowed. */
internal class NativeForegroundRouteProbe(private val rootCurrent: () -> Boolean,
    private val memberAndRecordCurrent: () -> Boolean) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val committing = AtomicBoolean(false)
    fun canProbe() = !closed.get() && !committing.get() && rootCurrent()
    fun current() = !closed.get() && memberAndRecordCurrent() && (committing.get() || rootCurrent())
    fun beginCommit() {
        check(canProbe()) { "Computer route owner changed" }
        check(committing.compareAndSet(false, true)) { "Computer route commit already started" }
    }
    override fun close() { closed.set(true) }
}

/** One explicitly confirmed target survives stopping its root while account/record validity remains. */
internal class NativeForegroundComputerRevoke internal constructor(val team: NativeTeamScope,
    val target: NativeComputerTarget, private val permitted: () -> Boolean) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val confirmed = AtomicBoolean(false)
    fun current() = !closed.get() && permitted()
    fun begin(): Boolean {
        check(current()) { "Computer removal account or pairing changed" }
        if (confirmed.get()) return false
        check(running.compareAndSet(false, true)) { "Computer removal is already in progress" }
        return true
    }
    fun acknowledge() { check(current()); confirmed.set(true) }
    fun finish() { running.set(false) }
    override fun close() { closed.set(true) }
}
