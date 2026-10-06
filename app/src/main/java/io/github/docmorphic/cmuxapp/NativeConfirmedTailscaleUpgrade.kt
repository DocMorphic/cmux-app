package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** A confirmed, authenticated legacy upgrade waits for the saved-record transaction. */
internal class NativeConfirmedTailscaleUpgrade(
    val owner: NativeTeamScope,
    private val expected: NativeCredentialStore.PairedMac,
    private val previous: TailscaleSavedGrant,
    private val replacement: TailscaleSavedGrant,
    private val requireCurrent: () -> Unit,
    private val activate: () -> Unit
) {
    private val consumed = AtomicBoolean()

    fun commit(update: ((JSONObject) -> Unit) -> Unit, incoming: NativeCredentialStore.PairedMac,
               ticket: MobileAttachTicket? = null, email: String? = null): NativeCredentialStore.PairedMac {
        requireCurrent()
        check(consumed.compareAndSet(false, true)) { "This pairing attempt was already saved" }
        require(incoming.code == expected.code && canonicalMacDeviceId(incoming.deviceId) == replacement.device &&
            incoming.instanceTag == replacement.build && expected.instanceTag == null && previous.build == null &&
            replacement.build != null && previous.source == replacement.source && previous.device == replacement.device)
        var result: NativeCredentialStore.PairedMac? = null
        update { state ->
            val grants = TailscaleGrantStore({ state }, { it(state) })
            check(grants.find(owner, previous.source) == previous &&
                NativePairingRecords.owner(expected, grants) == (owner.userId to owner.teamId)) {
                "The previous pairing authorization changed. Pair this Mac again."
            }
            // Resolve the old row's ownership before replacing its untagged grant.
            // Both changes are saved together, including ticket replacement if present.
            val row = NativePairingPersistence.remember(state, incoming, owner, expected, preferIncomingRoute = ticket != null)
            grants.save(owner, replacement) { true }
            result = if (ticket == null) row else NativeAttachTicketStore.install(state, owner, row, ticket, email)
        }
        activate()
        requireCurrent()
        return checkNotNull(result)
    }

    override fun toString() = "ConfirmedTailscaleUpgrade(redacted)"
}
