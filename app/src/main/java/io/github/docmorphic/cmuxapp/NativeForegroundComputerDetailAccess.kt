package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.sync.Mutex

/** Detail reads/mutations stay on the exact existing foreground; this object cannot dial. */
internal class NativeForegroundComputerDetailAccess(val team: NativeTeamScope, val mac: NativeCredentialStore.PairedMac,
    val client: MobileRpcClient, val management: NativeForegroundComputerManagement,
    private val permits: () -> Boolean) {
    val target = checkNotNull(NativeComputerTarget.from(mac, team))
    private val powerGate = Mutex()
    fun current() = !client.isClosed && permits() && management.available()
    suspend fun check(): NativeConnectionReport {
        kotlin.check(current()) { "Computer detail owner changed" }
        return NativeConnectionCheck.run(client, mac, isCurrent = ::current)
    }
    fun power(): NativeMacPowerSession? = if (!current()) null else NativeMacPowerSession(client, target, ::current,
        powerGate, closeClientOnExit = false)
}
