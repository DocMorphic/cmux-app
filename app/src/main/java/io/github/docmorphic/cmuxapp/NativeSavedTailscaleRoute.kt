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
        val original = grants.find(team, TailscaleGrantStore.source(pairing))?.takeIf {
            it.device == canonicalMacDeviceId(mac.deviceId) && it.build == mac.instanceTag
        }
        return if (target != null && mac.accountUserId == team.userId && mac.accountTeamId == team.teamId && mac.stableOrigin != null)
            (grants.computer(team, target) + listOfNotNull(original?.takeIf { mac.instanceTag == null })).distinctBy { it.route }
        else listOfNotNull(original)
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
            (coversPrimaryTicket(pairing) || grant.build == (mac.instanceTag ?: NativeComputerTarget.from(mac, owner)?.buildTag))) {
            "Saved destination does not match this computer"
        }
    }
    /** A legacy grant pins its original numeric route/device, not an inferred sibling build. */
    fun verifyHost(status: org.json.JSONObject, owner: NativeTeamScope): String? {
        mac.requireMatchingHost(status)
        val raw = status.opt("mac_instance_tag")
        val build = if (raw == null || raw === org.json.JSONObject.NULL) null else {
            require(raw is String) { "The Mac returned an invalid build identity" }
            raw.takeIf { it.isNotEmpty() }?.also { require(it.isNotBlank() && it == it.trim() && it.length <= 64 && it.none(Char::isISOControl)) }
        }
        val target = NativeComputerTarget.from(mac, owner)
        check(target == null || target.buildTag == build) { "This route reaches a different cmux installation." }
        if (grant.matches(status)) return null
        val pairing = PairingCodeParser.parse(mac.code).getOrThrow() as PairingCode.Tailscale
        check(mac.instanceTag == null && grant.build == null && coversPrimaryTicket(pairing) && build != null &&
            canonicalMacDeviceId(status.optString("mac_device_id")) == grant.device) {
            "This route reaches a different Mac or cmux installation. Pair the intended Mac again."
        }
        return build
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
