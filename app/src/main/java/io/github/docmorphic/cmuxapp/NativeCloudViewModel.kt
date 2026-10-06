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
    private val hosts = mutableMapOf<String, CloudTerminalHost>()
    private val mutableRoute = MutableStateFlow<CloudWorkspaceRoute?>(null)
    val route = mutableRoute.asStateFlow()
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
                hosts.values.forEach { it.close() }; hosts.clear(); mutableRoute.value = null
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
                        val pool = runtime.resource()?.connections
                        val link = pool?.connection(machineId)
                            ?: throw IllegalStateException("Cloud tunnel is reconnecting")
                        try { link.awaitSession().loadWorkspaceCatalog() }
                        catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) { pool.retire(machineId, link); throw failure }
                    }
                    controller.setForeground(foreground)
                    mutableTunnel.value = runtime
                    mutable.value = controller
                    mutableWorkspaces.value = workspaces
                    catalogObserver = scope.launch {
                        combine(controller.state, runtime.state, workspaces.state) { machines, tunnel, rows -> Triple(machines, tunnel, rows) }
                            .collect { (update, tunnel, rows) ->
                            runtime.setWanted(foreground && update.catalog.machines.isNotEmpty())
                            workspaces.setMachines(update.catalog.machines)
                            workspaces.setAvailable(foreground && tunnel.phase == CloudTunnelPhase.READY)
                            hosts.keys.toList().filter { id -> update.catalog.machines.none { it.id == id } }.forEach { id ->
                                hosts.remove(id)?.close()
                                if (mutableRoute.value?.host?.machineId == id) mutableRoute.value = null
                            }
                            hosts.forEach { (id, host) -> host.reconcile(rows[id], foreground, tunnel.phase == CloudTunnelPhase.READY) }
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
        hosts.forEach { (id, host) -> host.reconcile(mutableWorkspaces.value?.state?.value?.get(id), value,
            mutableTunnel.value?.state?.value?.phase == CloudTunnelPhase.READY) }
        mutableTunnel.value?.setWanted(value && mutable.value?.state?.value?.catalog?.machines?.isNotEmpty() == true)
        if (value) activate()
    }
    fun connection(machineId: String): CloudMachineHandshake<CloudNativeSession>? {
        val machine = mutable.value?.state?.value?.catalog?.machines?.singleOrNull { it.id == machineId } ?: return null
        if (machine.lifecycle != CloudMachineLifecycle.RUNNING) return null
        return mutableTunnel.value?.resource()?.connections?.connection(machineId)
    }
    fun retryConnection(machineId: String, expected: CloudWorkspaceController) {
        if (mutableWorkspaces.value !== expected || owner?.let(teams::isCurrent) != true) return
        mutableTunnel.value?.resource()?.connections?.retire(setOf(machineId))
        mutableTunnel.value?.retry()
        mutableWorkspaces.value?.refresh(machineId)
        hosts[machineId]?.replay()
    }
    fun openWorkspace(row: CloudWorkspaceRow, terminalId: String? = null, expected: CloudWorkspaceController) {
        check(mutableWorkspaces.value === expected) { "Cloud workspace account changed" }
        val owner = owner ?: return
        check(teams.isCurrent(owner)) { "Cloud account changed" }
        val snapshot = mutableWorkspaces.value?.state?.value?.get(row.machine.id) ?: return
        val current = snapshot.rows.singleOrNull { it.key == row.key } ?: return
        val host = hosts.getOrPut(row.machine.id) {
            CloudTerminalHost(scope, row.machine.id, { teams.isCurrent(owner) }) {
                NativeCloudTerminalLink(checkNotNull(connection(row.machine.id)) { "Cloud tunnel is reconnecting" }.awaitSession())
            }
        }
        host.reconcile(snapshot, foreground, mutableTunnel.value?.state?.value?.phase == CloudTunnelPhase.READY)
        val terminal = if (terminalId == null) current.workspace.terminals.firstOrNull()
            else current.workspace.terminals.singleOrNull { it.id == terminalId }
        if (terminalId != null && terminal == null) return
        if (terminal != null) host.select(current.workspace, terminal) else host.leave()
        mutableRoute.value = CloudWorkspaceRoute(host, current.key, expected)
    }
    fun leaveWorkspace() { mutableRoute.value?.host?.leave(); mutableRoute.value = null }
    override fun onCleared() {
        catalogObserver?.cancel(); mutableWorkspaces.value?.close(); mutableWorkspaces.value = null
        hosts.values.forEach { it.close() }; hosts.clear(); mutableRoute.value = null
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
