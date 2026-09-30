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

/** Activity recreation retains account-scoped snapshots; process death refetches from the Mac. */
internal class NativeFeedSession(
    private val connector: NativeConnector, private val account: NativeAccount, private val store: NativeCredentialStore
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val workspaceSnapshots = NativeWorkspaceSnapshots(store::taskSession)
    val coordinator = NativeFeedCoordinator(scope, connect = { mac ->
        connector.connectSaved(mac, account)
    }, isAllowed = { mac -> account.isSignedIn() && store.pairedMacs().contains(mac) &&
        connector.allowsSaved(mac) }, workspaceSnapshots = workspaceSnapshots)

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
    fun allowsTerminalInput(owner: TerminalInputSender.Owner): Boolean = account.isSignedIn() &&
        store.taskSession() == owner.login && store.pairedMacs().any { mac ->
            canonicalMacDeviceId(mac.deviceId) == owner.device && mac.instanceTag?.trim()?.takeIf(String::isNotEmpty) == owner.build &&
                (mac.accountUserId == null || mac.accountUserId == owner.user) &&
                (mac.accountTeamId == null || mac.accountTeamId == owner.team) && connector.allowsSaved(mac)
        }

    var projection by mutableStateOf(NativeFeedProjection())
    fun clear() { browserNetworks.clear(); terminalInputs.clear(); paneNavigation.clear(); workspaceSnapshots.clear(); terminalStartup.clear(); workspaceTabs.clear(); localBrowsers.clear(); taskModels.clear(); workspaceMoves.clear(); coordinator.close(); projection = NativeFeedProjection() }
    override fun onCleared() { clear(); terminalInputs.close(); scope.cancel() }

    class Factory(private val connector: NativeConnector, private val account: NativeAccount,
        private val store: NativeCredentialStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeFeedSession::class.java)
            return NativeFeedSession(connector, account, store) as T
        }
    }
}
