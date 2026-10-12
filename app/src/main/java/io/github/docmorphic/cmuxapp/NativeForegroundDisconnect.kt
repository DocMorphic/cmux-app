package io.github.docmorphic.cmuxapp

internal enum class NativeForegroundDisconnect { IGNORE, HOLD, RETRY, SELECTED_SCOPE }

/** Recovery metadata identifies a lost handle; it never authorizes another connection. */
internal fun nativeForegroundDisconnect(owner: NativeConnectionRecoveryOwner, admittedClient: Any,
    currentClient: Any?, login: String?, code: String, connectedCode: String?, admittedGeneration: Int,
    currentGeneration: Int, selected: NativeTeamScope?, failure: Throwable): NativeForegroundDisconnect {
    if (currentClient !== admittedClient || login != owner.login || code != owner.code || connectedCode != owner.code ||
        currentGeneration != admittedGeneration) return NativeForegroundDisconnect.IGNORE
    if (!owner.sameIdentity(owner.copy(team = selected))) return NativeForegroundDisconnect.SELECTED_SCOPE
    return if (nativeAutomaticReconnectStops(failure)) NativeForegroundDisconnect.HOLD else NativeForegroundDisconnect.RETRY
}
