package io.github.docmorphic.cmuxapp

/** Display identity only; never used to authorize or route a connection. */
internal fun nativeMacColorIdentity(device: String, build: String?) =
    NativeMacIdentity(canonicalMacDeviceId(device), build?.trim()?.takeIf(String::isNotEmpty))

internal val NativeMacIdentity.colorSeed: String get() = deviceId + (buildTag?.let { "\u001f$it" } ?: "")
internal val NativeCredentialStore.PairedMac.colorIdentity: NativeMacIdentity
    get() = nativeMacColorIdentity(deviceId, instanceTag)
internal val IrohV2Computer.colorIdentity: NativeMacIdentity
    get() = nativeMacColorIdentity(deviceId, buildTag)

/** Activity-owner lifetime, matching iOS's additive app-instance assignments. */
internal class NativeMacColorSlots {
    private data class Scope(val login: String, val user: String?, val team: String?)
    private var scope: Scope? = null
    private var slots = emptyMap<NativeMacIdentity, Int>()

    fun select(login: String?, team: NativeTeamScope?, saved: List<NativeCredentialStore.PairedMac>,
        discovered: List<IrohV2Computer>, foreground: NativeMacIdentity? = null): Map<NativeMacIdentity, Int> {
        if (login == null || (team != null && team.login != login)) { clear(); return emptyMap() }
        val next = Scope(login, team?.userId, team?.teamId)
        val identities = (saved.map { it.colorIdentity } + discovered.map { it.colorIdentity })
            .filter { it.deviceId.isNotBlank() }.distinct()
        if (scope != next) {
            // A team transition may retain one still-admitted foreground instance;
            // other old-team assignments must not leak into the new team's table.
            slots = if (scope?.login == next.login && scope?.user == next.user && foreground in identities)
                slots.filterKeys { it == foreground } else emptyMap()
            scope = next
        }
        val additions = identities.filter { it !in slots }
            .sortedWith(compareBy<NativeMacIdentity> { it.deviceId }.thenBy { it.buildTag.orEmpty() })
        if (additions.isNotEmpty()) {
            var index = (slots.values.maxOrNull() ?: -1) + 1
            slots = slots + additions.associateWith { index++ }
        }
        return slots
    }

    fun clear() { scope = null; slots = emptyMap() }
}
