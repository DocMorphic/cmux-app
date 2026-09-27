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
    connector: NativeConnector, account: NativeAccount, store: NativeCredentialStore
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val coordinator = NativeFeedCoordinator(scope, connect = { mac ->
        connector.connect(PairingCodeParser.parse(mac.code).getOrThrow() as PairingCode.Tailscale, account)
    }, isAllowed = { mac -> account.isSignedIn() && store.pairedMacs().contains(mac) })

    val workspaceMoves = NativeWorkspaceMoves(scope, coordinator)
    val taskModels = TaskModelRepository()

    var projection by mutableStateOf(NativeFeedProjection())
    fun clear() { taskModels.clear(); workspaceMoves.clear(); coordinator.close(); projection = NativeFeedProjection() }
    override fun onCleared() { clear(); scope.cancel() }

    class Factory(private val connector: NativeConnector, private val account: NativeAccount,
        private val store: NativeCredentialStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeFeedSession::class.java)
            return NativeFeedSession(connector, account, store) as T
        }
    }
}
