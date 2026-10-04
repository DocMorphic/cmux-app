package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.saveable.listSaver
import org.json.JSONArray
import java.util.UUID

/** Presentation scope only: these keys never become RPC destinations. */
internal fun workspaceMacFilterId(device: String, tag: String?): String? = device.takeIf { it.isNotBlank() }
    ?.let { JSONArray(listOf("mac", canonicalMacDeviceId(it), tag)).toString() }
internal fun workspaceSshFilterId(host: UUID) = JSONArray(listOf("ssh", host.toString())).toString()
internal data class NativeWorkspaceFilterMachine(val id: String, val name: String, val buildLabel: String? = null)

/** iOS MobileWorkspaceListFilter: read state AND exact computer identity. */
internal data class NativeWorkspaceFilter(val unread: Boolean = false, val machines: Set<String> = emptySet()) {
    val active get() = unread || machines.isNotEmpty()
    fun matches(machine: String?, hasUnread: Boolean) = (!unread || hasUnread) &&
        (machines.isEmpty() || machine != null && machine in machines)
    fun toggle(machine: String) = copy(machines = if (machine in machines) machines - machine else machines + machine)
    fun forMenu(present: Set<String>, singleComputer: Boolean): NativeWorkspaceFilter =
        copy(machines = if (singleComputer || present.size < 2) emptySet() else machines.intersect(present))
    companion object {
        val saver = listSaver<NativeWorkspaceFilter, String>(
            save = { listOf(it.unread.toString()) + it.machines.sorted() },
            restore = { NativeWorkspaceFilter(it.firstOrNull() == "true", it.drop(1).toSet()) })
    }
}
