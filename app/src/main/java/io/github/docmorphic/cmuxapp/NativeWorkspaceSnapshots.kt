package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal class NativeWorkspaceSnapshotSuperseded : java.io.IOException("Workspace refresh was superseded. Refresh again.")

/** Shared by foreground and feed clients. All ordering operations run on their owning UI scope. */
internal class NativeWorkspaceSnapshots(private val login: () -> String? = { "standalone" }) {
    private data class Owner(val login: String?, val user: String?, val team: String?, val device: String,
        val build: String?, val anonymousOrigin: String?)
    internal class Order(val live: () -> Boolean) {
        var retired = false
        var epoch = 0L
        var issued = 0L
        var applied = 0L
        var mutations = 0
        var latest: NativeWorkspaceSnapshot? = null
        val changes = MutableStateFlow(0L)
        fun change() { epoch++; changes.value++ }
        fun current() = !retired && live()
    }
    internal data class Ticket(val order: Order, val epoch: Long, val sequence: Long) {
        fun current() = order.current() && order.mutations == 0 && order.epoch == epoch && sequence >= order.applied
    }
    private val owners = mutableMapOf<Owner, Order>()
    private fun owner(mac: NativeCredentialStore.PairedMac) = Owner(login(), mac.accountUserId, mac.accountTeamId,
        canonicalMacDeviceId(mac.deviceId), mac.instanceTag?.trim()?.takeIf(String::isNotEmpty), mac.origin.takeIf { mac.deviceId.isBlank() })
    private fun order(mac: NativeCredentialStore.PairedMac): Order {
        val owner = owner(mac)
        return owners.getOrPut(owner) { Order { login() == owner.login } }
    }
    fun retain(macs: List<NativeCredentialStore.PairedMac>) {
        val allowed = macs.map(::owner).toSet()
        owners.keys.toList().filterNot(allowed::contains).forEach { key ->
            owners.remove(key)?.let { it.retired = true; it.change() }
        }
    }
    fun clear() { owners.values.forEach { it.retired = true; it.change() }; owners.clear() }
    fun hasMutation(mac: NativeCredentialStore.PairedMac) = owners[owner(mac)]?.let { it.current() && it.mutations > 0 } == true
    fun latest(mac: NativeCredentialStore.PairedMac): NativeWorkspaceSnapshot? = owners[owner(mac)]?.latest?.takeIf { it.isCurrent() }
    fun createdWorkspace(mac: NativeCredentialStore.PairedMac, created: NativeWorkspace): NativeWorkspace {
        val latest = latest(mac) ?: return created
        return latest.workspaces.singleOrNull { it.id == created.id }
            ?: throw java.io.IOException("The created workspace is no longer available on ${mac.name}.")
    }

    /** Neither a failed mutation nor cancellation proves that the Mac made no change. */
    suspend fun <T> mutate(mac: NativeCredentialStore.PairedMac, operation: suspend () -> T): T {
        val order = order(mac)
        check(order.current()) { "Workspace account changed" }
        order.mutations++; order.change()
        try { return operation() }
        finally { order.mutations--; order.change() }
    }

    /** Only reads are retried. Mutation results keep their existing at-most-once delivery policy. */
    suspend fun read(mac: NativeCredentialStore.PairedMac, client: MobileRpcClient): NativeWorkspaceSnapshot =
        read(mac) { client.workspaces() }

    internal suspend fun read(mac: NativeCredentialStore.PairedMac, fetch: suspend () -> JSONObject): NativeWorkspaceSnapshot = try { withTimeout(30_000) {
        val order = order(mac)
        repeat(3) {
            order.changes.first { !order.current() || order.mutations == 0 }
            if (!order.current()) throw NativeWorkspaceSnapshotSuperseded()
            val ticket = Ticket(order, order.epoch, ++order.issued)
            val value = fetch()
            val workspaces = parseAuthoritativeWorkspaces(value)
            if (ticket.current()) return@withTimeout NativeWorkspaceSnapshot(ticket, value, workspaces)
        }
        throw NativeWorkspaceSnapshotSuperseded()
    } } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw java.io.IOException("Workspace refresh timed out. Try refreshing again.", failure)
    }
}

internal class NativeWorkspaceSnapshot internal constructor(private val ticket: NativeWorkspaceSnapshots.Ticket,
    val value: JSONObject, val workspaces: List<NativeWorkspace>) {
    /** Claim immediately before publishing, after every suspend point and owner check. */
    fun accept(): Boolean {
        if (!ticket.current()) return false
        ticket.order.applied = ticket.sequence
        ticket.order.latest = this
        return true
    }
    fun isCurrent() = ticket.current()
    fun requireCurrent() { if (!ticket.current()) throw NativeWorkspaceSnapshotSuperseded() }
}
