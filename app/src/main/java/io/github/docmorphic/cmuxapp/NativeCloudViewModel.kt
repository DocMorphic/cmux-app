package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File

/** Retained account owner. A foreground shell with machines holds the tunnel across tab changes. */
internal class NativeCloudViewModel(context: Context, account: NativeAccount,
    store: NativeCredentialStore, private val teams: NativeAccountTeams) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<CloudMachinesController?>(null)
    val controller = mutable.asStateFlow()
    private val mutableTunnel = MutableStateFlow<CloudTunnelController<NativeCloudTunnelResource>?>(null)
    val tunnel = mutableTunnel.asStateFlow()
    private val mutableWorkspaces = MutableStateFlow<CloudWorkspaceController?>(null)
    val workspaces = mutableWorkspaces.asStateFlow()
    private var owner: NativeTeamScope? = null
    private var foreground = false
    private var catalogObserver: Job? = null
    init {
        val application = context.applicationContext
        val root = File(application.noBackupFilesDir, "cloud-creates")
        scope.launch {
            combine(teams.state, store.revisions) { state, _ -> state.scope?.takeIf(teams::isCurrent) }.collect { next ->
                if (next == owner) return@collect
                catalogObserver?.cancel(); catalogObserver = null
                mutableWorkspaces.value?.close(); mutableWorkspaces.value = null
                mutableTunnel.value?.close(); mutableTunnel.value = null
                mutable.value?.close(); mutable.value = null; owner = next
                if (next != null) {
                    val capture = CloudAccountScope(next.login, next.userId, next.teamId, next.generation)
                    val api = nativeCloudApi(account, teams, next)
                    val runtime = CloudTunnelController(scope, { teams.isCurrent(next) },
                        { startNativeCloudTunnelResource(application, scope, api, teams, next) },
                        NativeCloudTunnelResource::retire)
                    val controller = CloudMachinesController(scope, api,
                        CloudCreateFileJournal(root, capture), { teams.isCurrent(next) },
                        retireConnections = { runtime.resource()?.connections?.retire(it) })
                    val workspaces = CloudWorkspaceController(scope, { teams.isCurrent(next) }) { machineId ->
                        val link = runtime.resource()?.connections?.connection(machineId)
                            ?: throw IllegalStateException("Cloud tunnel is reconnecting")
                        link.awaitSession().loadWorkspaceCatalog()
                    }
                    controller.setForeground(foreground)
                    mutableTunnel.value = runtime
                    mutable.value = controller
                    mutableWorkspaces.value = workspaces
                    catalogObserver = scope.launch {
                        combine(controller.state, runtime.state) { machines, tunnel -> machines to tunnel }.collect { (update, tunnel) ->
                            runtime.setWanted(foreground && update.catalog.machines.isNotEmpty())
                            workspaces.setMachines(update.catalog.machines)
                            workspaces.setAvailable(foreground && tunnel.phase == CloudTunnelPhase.READY)
                        }
                    }
                    if (foreground) controller.refresh()
                }
            }
        }
    }
    fun activate() {
        mutable.value?.takeIf { foreground && it.state.value.phase == CloudCatalogPhase.IDLE }?.refresh()
    }
    fun setForeground(value: Boolean) {
        foreground = value
        mutable.value?.setForeground(value)
        if (!value) mutableWorkspaces.value?.setAvailable(false)
        mutableTunnel.value?.setWanted(value && mutable.value?.state?.value?.catalog?.machines?.isNotEmpty() == true)
        if (value) activate()
    }
    fun connection(machineId: String): CloudMachineHandshake<CloudNativeSession>? {
        val machine = mutable.value?.state?.value?.catalog?.machines?.singleOrNull { it.id == machineId } ?: return null
        if (machine.lifecycle != CloudMachineLifecycle.RUNNING) return null
        return mutableTunnel.value?.resource()?.connections?.connection(machineId)
    }
    fun retryConnection(machineId: String) {
        mutableTunnel.value?.resource()?.connections?.retire(setOf(machineId))
        mutableTunnel.value?.retry()
        mutableWorkspaces.value?.refresh(machineId)
    }
    override fun onCleared() {
        catalogObserver?.cancel(); mutableWorkspaces.value?.close(); mutableWorkspaces.value = null
        mutableTunnel.value?.close(); mutableTunnel.value = null
        mutable.value?.close(); mutable.value = null; scope.cancel()
    }
    class Factory(private val context: Context, private val account: NativeAccount,
        private val store: NativeCredentialStore, private val teams: NativeAccountTeams) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeCloudViewModel::class.java)
            @Suppress("UNCHECKED_CAST") return NativeCloudViewModel(context, account, store, teams) as T
        }
    }
}
