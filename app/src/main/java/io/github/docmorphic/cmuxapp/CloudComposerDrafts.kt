package io.github.docmorphic.cmuxapp

import org.json.JSONArray

/** Terminal IDs remain stable when moved between workspaces; account and machine ownership remain explicit. */
internal fun cloudDraftTarget(owner: NativeTeamScope, machineId: String, terminalId: String): TerminalDrafts.Target {
    val address = checkNotNull(CloudAddress.parse(terminalId))
    require(address.machineId == machineId && address.component != null)
    return TerminalDrafts.Target(JSONArray(listOf("cloud", "https://cmux.com", owner.login, owner.userId, owner.teamId)).toString(),
        CloudAddress(machineId).identifier, terminalId)
}
