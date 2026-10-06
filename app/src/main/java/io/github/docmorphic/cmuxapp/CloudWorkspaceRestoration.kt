/* Last-tab behavior follows MobileWorkspaceLastTabStore at cmux c2715faa.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import org.json.JSONObject

internal fun cloudWorkspaceTabKey(owner: NativeTeamScope, row: CloudWorkspaceRow) =
    NativeWorkspaceTabKey(owner.userId, owner.teamId, CloudAddress(row.machine.id).identifier, row.key)

/** Incomplete inventories cannot disprove the saved phone selection. */
internal fun cloudWorkspaceTerminal(row: CloudWorkspaceRow, authoritative: Boolean, remembered: NativeWorkspaceTab?): NativeTerminal? {
    if (remembered?.kind == NativeWorkspaceTabKind.TERMINAL) {
        row.workspace.terminals.singleOrNull { it.id == remembered.id }?.let { return it }
        if (!authoritative) return null
    }
    return row.workspace.terminals.firstOrNull()
}

/** Android activity checkpoint. Contains destination IDs only, never input, credentials or create requests. */
internal data class CloudScreenCheckpoint(val login: String, val user: String, val team: String?,
    val machine: String, val workspace: String, val terminal: String?) {
    fun matches(owner: NativeTeamScope) = login == owner.login && user == owner.userId && team == owner.teamId
    fun encode() = JSONObject().put("version", 1).put("login", login).put("user", user).put("team", team ?: JSONObject.NULL)
        .put("machine", machine).put("workspace", workspace).put("terminal", terminal ?: JSONObject.NULL).toString()
    companion object {
        const val KEY = "cloud_destination_v1"
        fun decode(raw: String?): CloudScreenCheckpoint? = runCatching {
            require(raw != null && raw.length <= 32768)
            val value = JSONObject(raw); require(value.opt("version") == 1)
            fun text(key: String): String = (value.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 }
                ?: error("Invalid Cloud destination")
            fun optional(key: String) = if (value.has(key) && value.isNull(key)) null else text(key)
            val saved = CloudScreenCheckpoint(text("login"), text("user"), optional("team"), text("machine"), text("workspace"), optional("terminal"))
            val workspace = checkNotNull(CloudAddress.parse(saved.workspace))
            require(workspace.machineId == saved.machine && workspace.component != null)
            saved.terminal?.let { terminal ->
                val address = checkNotNull(CloudAddress.parse(terminal))
                require(address.machineId == saved.machine && address.component != null)
            }
            saved
        }.getOrNull()
    }
}

internal sealed interface CloudRestoreDecision {
    data object Wait : CloudRestoreDecision
    data object Discard : CloudRestoreDecision
    data class Open(val row: CloudWorkspaceRow, val terminal: NativeTerminal?) : CloudRestoreDecision
}

internal fun CloudScreenCheckpoint.resolve(owner: NativeTeamScope, foreground: Boolean, hidden: Set<String>,
    machines: CloudMachinesState, snapshots: Map<String, CloudWorkspaceSnapshot>): CloudRestoreDecision {
    if (!matches(owner) || machine in hidden) return CloudRestoreDecision.Discard
    if (!foreground) return CloudRestoreDecision.Wait
    val machineState = machines.catalog.machines.singleOrNull { it.id == machine }
        ?: return if (machines.phase == CloudCatalogPhase.LOADED) CloudRestoreDecision.Discard else CloudRestoreDecision.Wait
    if (machineState.lifecycle != CloudMachineLifecycle.RUNNING) return CloudRestoreDecision.Wait
    val snapshot = snapshots[machine]?.takeIf { it.machine.id == machine && it.authoritative && it.availability == NativeFeedAvailability.CONNECTED }
        ?: return CloudRestoreDecision.Wait
    val row = snapshot.rows.singleOrNull { it.key == workspace } ?: return CloudRestoreDecision.Discard
    return CloudRestoreDecision.Open(row, cloudWorkspaceTerminal(row, true, terminal?.let { NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, it) }))
}
