package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A current saved row supplies identity; independently authenticated grants supply destinations. */
internal object NativeSavedTailscaleRoutes {
    fun candidates(mac: NativeCredentialStore.PairedMac, team: NativeTeamScope,
        grants: TailscaleGrantStore): List<TailscaleSavedGrant> {
        val pairing = PairingCodeParser.parse(mac.code).getOrNull() as? PairingCode.Tailscale ?: return emptyList()
        if (NativePairingRecords.owner(mac, grants) != (team.userId to team.teamId)) return emptyList()
        val target = NativeComputerTarget.from(mac, team)
        return if (target != null && mac.accountUserId == team.userId && mac.accountTeamId == team.teamId && mac.stableOrigin != null)
            grants.computer(team, target)
        else listOfNotNull(grants.find(team, TailscaleGrantStore.source(pairing))?.takeIf {
            it.device == canonicalMacDeviceId(mac.deviceId) && it.build == mac.instanceTag
        })
    }
}

internal class NativeSavedTailscaleRouteAdmission(val mac: NativeCredentialStore.PairedMac,
    val grant: TailscaleSavedGrant, private val current: () -> Boolean) {
    fun requireCurrent() = check(current()) { "Saved computer or route changed. Reconnect to the Mac." }
    fun requireBinding(pairing: PairingCode.Tailscale, owner: NativeTeamScope) {
        requireCurrent()
        check(coversPrimaryTicket(pairing) || (mac.accountUserId == owner.userId && mac.accountTeamId == owner.teamId &&
            mac.stableOrigin != null && grant.build != null && NativeComputerTarget.from(mac, owner)?.buildTag == grant.build)) { "A replacement route requires an owned Mac identity" }
        check(PairingCodeParser.parse(mac.code).getOrNull() == pairing && grant.user == owner.userId &&
            grant.team == owner.teamId && grant.device == canonicalMacDeviceId(mac.deviceId) &&
            grant.build == (mac.instanceTag ?: NativeComputerTarget.from(mac, owner)?.buildTag)) {
            "Saved destination does not match this computer"
        }
    }
    fun coversPrimaryTicket(pairing: PairingCode.Tailscale) = grant.source == TailscaleGrantStore.source(pairing) && grant.build == mac.instanceTag
    override fun toString() = "SavedTailscaleRouteAdmission(redacted)"
}

/** Retry another captured destination only for a transport failure, never an identity or account denial. */
internal suspend fun connectSavedTailscaleRoutes(pairing: PairingCode.Tailscale,
    candidates: List<TailscaleSavedGrant>, ticket: NativeSavedTicketAdmission?,
    admission: (TailscaleSavedGrant) -> NativeSavedTailscaleRouteAdmission,
    connect: suspend (NativeSavedTailscaleRouteAdmission, NativeSavedTicketAdmission?) -> MobileRpcClient): MobileRpcClient {
    check(candidates.isNotEmpty()) { "Add a Tailscale connection in Computer Details first." }
    var last: java.io.IOException? = null
    for (grant in candidates) {
        currentCoroutineContext().ensureActive()
        val captured = admission(grant)
        captured.requireCurrent()
        try { return connect(captured, ticket?.takeIf { captured.coversPrimaryTicket(pairing) }) }
        catch (failure: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            captured.requireCurrent()
            if (failure is TailscaleReadinessException) throw failure
            last = failure
        }
    }
    throw checkNotNull(last)
}
