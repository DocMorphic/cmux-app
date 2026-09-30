package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.mutableStateOf

/** Retained presentation data only: no Activity, view, socket or input lease. */
internal class NativePaneSelection {
    val workspaces = mutableStateOf<List<NativeWorkspace>>(emptyList())
    val groups = mutableStateOf<List<NativeGroup>>(emptyList())
    val taskGroupsLoaded = mutableStateOf(false)
    val workspace = mutableStateOf<NativeWorkspace?>(null)
    val terminal = mutableStateOf<NativeTerminal?>(null)
    val browser = mutableStateOf<NativeBrowser?>(null)
    val surface = mutableStateOf<NativeSurface?>(null)
    val changesWorkspace = mutableStateOf<NativeWorkspace?>(null)
}

/** One foreground selection, retained across Activity recreation but never transferred to another owner. */
internal class NativePaneNavigation {
    private data class Owner(val login: String, val code: String, val device: String,
        val build: String?, val user: String?, val team: String?)
    private var owner: Owner? = null
    private var selection = NativePaneSelection()

    fun select(login: String?, mac: NativeCredentialStore.PairedMac?, team: NativeTeamScope?): NativePaneSelection {
        val next = if (login != null && mac != null) Owner(login, mac.code, canonicalMacDeviceId(mac.deviceId),
            mac.instanceTag?.trim()?.takeIf(String::isNotEmpty), team?.userId ?: mac.accountUserId,
            team?.teamId ?: mac.accountTeamId) else null
        if (owner != next) { owner = next; selection = NativePaneSelection() }
        return selection
    }

    fun clear() { owner = null; selection = NativePaneSelection() }
}
