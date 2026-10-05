package io.github.docmorphic.cmuxapp

/** A saved raw route is not a fallback for a computer with an authenticated native identity. */
internal object NativeLegacyTailscalePolicy {
    fun permits(method: NativeMacConnectionMethod, hasNativeIdentity: Boolean): Boolean = when (method) {
        NativeMacConnectionMethod.DIRECT -> false
        NativeMacConnectionMethod.TAILSCALE -> true
        NativeMacConnectionMethod.IROH -> !hasNativeIdentity
    }

    fun hasNativeIdentity(grant: TailscaleSavedGrant, team: NativeTeamScope,
                          saved: List<NativeCredentialStore.PairedMac>, grants: TailscaleGrantStore): Boolean = saved.any { row ->
        canonicalMacDeviceId(row.deviceId) == grant.device && row.instanceTag == grant.build &&
            PairingCodeParser.parse(row.code).getOrNull() is PairingCode.Iroh &&
            NativePairingRecords.owner(row, grants) == (team.userId to team.teamId)
    }
}
