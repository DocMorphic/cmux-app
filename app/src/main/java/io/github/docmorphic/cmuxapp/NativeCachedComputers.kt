package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Cached presentation identity deliberately cannot be passed to a connector. */
internal data class NativeComputerDisplayOwner(val login: String, val user: String, val team: String)
internal data class NativeCachedComputers(
    val owner: NativeComputerDisplayOwner,
    val macs: List<NativeCredentialStore.PairedMac>,
    val versions: Map<NativeMacIdentity, String?>,
    val tailscaleRoutes: Map<NativeMacIdentity, String>
) {
    fun warnings(policy: NativeMacCompatibilityPolicy) = versions.mapNotNull { (identity, version) ->
        policy.violation(identity.buildTag, version)?.let { identity to it }
    }.toMap()

    companion object {
        const val ENVIRONMENT = "https://api.stack-auth.com/api/v1/#${NativeAccount.PROJECT_ID}"
        fun project(state: JSONObject?, login: String?, account: NativeAccountTeamsState,
                    environment: String = ENVIRONMENT): NativeCachedComputers? =
            runCatching { projectChecked(state, login, account, environment) }.getOrNull()

        private fun projectChecked(state: JSONObject?, login: String?, account: NativeAccountTeamsState,
                                   environment: String): NativeCachedComputers? {
            if (state == null || login == null || !account.cached || account.scope != null) return null
            val profile = NativeAccountProfileCache({ state }, { error("Display only") }).read(login, environment) ?: return null
            if (profile.userId != account.userId || profile.selectedTeamId != account.selectedTeamId || profile.teams != account.teams) return null
            val user = profile.userId ?: return null
            val team = profile.selectedTeamId ?: return null
            val owner = NativeComputerDisplayOwner(login, user, team)
            val grants = TailscaleGrantStore({ state }, { error("Display only") })
            val rows = NativeComputerVisibility.saved(state).filter {
                NativeMacBuildAudience.consumer.allowsSavedTag(it.instanceTag) &&
                    NativePairingRecords.owner(it, grants) == (user to team)
            }
            val identities = rows.map { NativeMacIdentity(canonicalMacDeviceId(it.deviceId), it.instanceTag) }.toSet()
            val routes = identities.mapNotNull { identity ->
                grants.displayRoutes(owner, identity).firstOrNull()?.let { route ->
                    identity to "${if (':' in route.host) "[${route.host}]" else route.host}:${route.port}"
                }
            }.toMap()
            return NativeCachedComputers(owner, rows, NativeMacVersionHistory.readDisplay(state, owner).filterKeys { it in identities }, routes)
        }
    }
}
