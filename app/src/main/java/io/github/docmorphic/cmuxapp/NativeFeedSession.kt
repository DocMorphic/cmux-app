package io.github.docmorphic.cmuxapp

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Activity recreation retains account-scoped snapshots; process death refetches from the Mac. */
internal class NativeFeedSession(
    private val connector: NativeConnector, private val account: NativeAccount, private val store: NativeCredentialStore
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var feedMacs = emptyList<NativeCredentialStore.PairedMac>()
    private var routeKeys = emptyMap<String, String>()
    private var localRouteKeys = emptyMap<NativeMacIdentity, String>()
    private val browserHolds = mutableMapOf<Any, String>()
    private var viewModelCleared = false
    val terminalSizing = NativeTerminalSizingSession()
    val macColorSlots = NativeMacColorSlots()
    val macSwitchRecovery = NativeMacSwitchRecovery()
    val workspaceSnapshots = NativeWorkspaceSnapshots(store::taskSession)
    val coordinator = NativeFeedCoordinator(scope, connect = { mac ->
        connector.connectSaved(mac, account).also { client ->
            val owner = nativeTerminalInputOwner(mac, store.taskSession())
            client.terminalTrafficAllowed = { surface -> owner != null && allowsTerminalInput(owner) &&
                terminalSizing.allowsTraffic(owner, surface) }
        }
    }, isAllowed = { mac -> account.isSignedIn() && store.pairedMacs().contains(mac) &&
        connector.allowsSaved(mac) }, workspaceSnapshots = workspaceSnapshots, onVerified = ::recordMacSeen)

    fun recordMacSeen(mac: NativeCredentialStore.PairedMac) {
        val login = store.taskSession()
        val time = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try { store.recordMacSeen(login, mac, time) { connector.allowsSaved(mac) } }
            catch (failure: Exception) { currentCoroutineContext().ensureActive() }
        }
    }

    val workspaceMoves = NativeWorkspaceMoves(scope, coordinator)
    val taskModels = TaskModelRepository()
    val localBrowsers = LocalBrowserNavigation(scope)
    val browserNetworks = NativeBrowserNetworks(scope, coordinator::browserAccess) { mac, login ->
        account.isSignedIn() && store.taskSession() == login && store.pairedMacs().contains(mac) && connector.allowsSaved(mac)
    }
    val workspaceTabs = NativeWorkspaceTabNavigation(store)
    val terminalStartup = NativeTerminalStartup()
    val paneNavigation = NativePaneNavigation()
    val terminalInputs = NativeTerminalInputSession(scope)
    fun configureFeed(macs: List<NativeCredentialStore.PairedMac>, routes: Map<String, String>,
        localRoutes: Map<NativeMacIdentity, String>, active: Boolean) {
        feedMacs = macs; routeKeys = routes; localRouteKeys = localRoutes; foreground = active
        reconcileFeed()
    }
    fun leaveMainScreen() { foreground = false; reconcileFeed() }
    private fun reconcileFeed() {
        val wanted = if (foreground) feedMacs else feedMacs.filter { it.origin in browserHolds.values }
        if (wanted.isEmpty()) coordinator.pause() else coordinator.updateMacs(wanted, routeKeys, localRouteKeys)
    }
    fun holdBrowser(mac: NativeCredentialStore.PairedMac): AutoCloseable {
        check(account.isSignedIn() && store.pairedMacs().contains(mac) && connector.allowsSaved(mac))
        val token = Any(); browserHolds[token] = mac.origin; reconcileFeed()
        return AutoCloseable {
            if (browserHolds.remove(token) != null) {
                if (viewModelCleared && browserHolds.isEmpty()) dispose() else reconcileFeed()
            }
        }
    }
    fun allowsTerminalInput(owner: TerminalInputSender.Owner): Boolean = account.isSignedIn() &&
        store.taskSession() == owner.login && store.pairedMacs().any { mac ->
            canonicalMacDeviceId(mac.deviceId) == owner.device && mac.instanceTag?.trim()?.takeIf(String::isNotEmpty) == owner.build &&
                (mac.accountUserId == null || mac.accountUserId == owner.user) &&
                (mac.accountTeamId == null || mac.accountTeamId == owner.team) && connector.allowsSaved(mac)
        }

    var projection by mutableStateOf(NativeFeedProjection())
    fun clear() { browserHolds.clear(); feedMacs = emptyList(); foreground = false; macColorSlots.clear(); macSwitchRecovery.clear(); browserNetworks.clear(); terminalInputs.clear(); terminalSizing.clear(); paneNavigation.clear(); workspaceSnapshots.clear(); terminalStartup.clear(); workspaceTabs.clear(); localBrowsers.clear(); taskModels.clear(); workspaceMoves.clear(); coordinator.close(); projection = NativeFeedProjection() }
    private fun dispose() { clear(); terminalInputs.close(); scope.cancel() }
    override fun onCleared() { viewModelCleared = true; foreground = false; if (browserHolds.isEmpty()) dispose() else reconcileFeed() }

    class Factory(private val connector: NativeConnector, private val account: NativeAccount,
        private val store: NativeCredentialStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeFeedSession::class.java)
            return NativeFeedSession(connector, account, store) as T
        }
    }
}
