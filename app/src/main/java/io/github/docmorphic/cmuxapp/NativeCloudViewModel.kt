package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File

/** Retained account owner. A foreground shell with machines holds the tunnel across tab changes. */
internal class NativeCloudViewModel(context: Context, account: NativeAccount,
    private val store: NativeCredentialStore, private val teams: NativeAccountTeams,
    private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val draftRepository = TerminalDraftRepository.get(context.applicationContext)
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
    private val mutableCreation = MutableStateFlow<CloudWorkspaceCreation?>(null)
    val creation = mutableCreation.asStateFlow()
    private val mutableVisibility = MutableStateFlow<CloudMachineVisibility?>(null)
    val visibility = mutableVisibility.asStateFlow()
    private val mutableNavigationFailure = MutableStateFlow<String?>(null)
    val navigationFailure = mutableNavigationFailure.asStateFlow()
    private var pendingRestore = CloudScreenCheckpoint.decode(savedState.get<String>(CloudScreenCheckpoint.KEY))
    private var navigationRevision = 0L
    private var owner: NativeTeamScope? = null
    private var foreground = false
    private var catalogObserver: Job? = null
    init {
        val application = context.applicationContext
        val root = File(application.noBackupFilesDir, "cloud-creates")
        scope.launch {
            combine(teams.state, store.revisions) { state, _ -> state.scope?.takeIf(teams::isCurrent) }.collect { next ->
                if (next == owner) return@collect
                pendingRestore = CloudScreenCheckpoint.decode(savedState.get<String>(CloudScreenCheckpoint.KEY))
                if ((next != null && pendingRestore?.matches(next) == false) || store.taskSession() == null) cancelRestoration()
                mutableNavigationFailure.value = null
                catalogObserver?.cancel(); catalogObserver = null
                mutableVisibility.value?.close(); mutableVisibility.value = null
                mutableCreation.value?.close(); mutableCreation.value = null; navigationRevision++
                hosts.values.forEach { it.close() }; hosts.clear(); mutableRoute.value = null
                mutableWorkspaces.value?.close(); mutableWorkspaces.value = null
                mutableTunnel.value?.close(); mutableTunnel.value = null
                mutable.value?.close(); mutable.value = null; owner = next
                if (next != null) {
                    val capture = CloudAccountScope(next.login, next.userId, next.teamId, next.generation)
                    val preferences = application.getSharedPreferences("cloud_machine_visibility", Context.MODE_PRIVATE)
                    val visibility = CloudMachineVisibility(capture,
                        { key -> preferences.getStringSet(key, emptySet()).orEmpty().toSet() },
                        { key, ids -> check(preferences.edit().putStringSet(key, ids).commit()) { "Could not save Cloud computer visibility" } },
                        { teams.isCurrent(next) })
                    mutableVisibility.value = visibility
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
                    val creation = CloudWorkspaceCreation(scope, workspaces) { machineId, workspaceId ->
                        val session = checkNotNull(runtime.resource()?.connections?.connection(machineId)) { "Cloud tunnel is reconnecting" }.awaitSession()
                        val bytes = withContext(Dispatchers.IO) {
                            check(teams.isCurrent(next)) { "Cloud account changed" }
                            session.catalog(if (workspaceId == null) CloudCatalogOperation.CREATE_WORKSPACE else CloudCatalogOperation.CREATE_TERMINAL,
                                workspace = workspaceId)
                        }
                        CloudWorkspaceDecoding.created(bytes, terminal = workspaceId != null)
                    }
                    mutableCreation.value = creation
                    controller.setForeground(foreground)
                    mutableTunnel.value = runtime
                    mutable.value = controller
                    mutableWorkspaces.value = workspaces
                    catalogObserver = scope.launch {
                        combine(controller.state, runtime.state, workspaces.state) { machines, tunnel, rows -> Triple(machines, tunnel, rows) }
                            .collect { (update, tunnel, rows) ->
                            visibility.reconcile(update.catalog.machines.map { it.id }.toSet(), update.phase == CloudCatalogPhase.LOADED)
                            runtime.setWanted(foreground && update.catalog.machines.isNotEmpty())
                            workspaces.setMachines(update.catalog.machines)
                            workspaces.setAvailable(foreground && tunnel.phase == CloudTunnelPhase.READY)
                            hosts.keys.toList().filter { id -> update.catalog.machines.none { it.id == id } }.forEach { id ->
                                hosts.remove(id)?.close()
                                if (mutableRoute.value?.host?.machineId == id) { mutableRoute.value = null; cancelRestoration() }
                            }
                            hosts.forEach { (id, host) -> host.reconcile(rows[id], foreground && mutableRoute.value?.host === host, tunnel.phase == CloudTunnelPhase.READY) }
                            mutableRoute.value?.takeIf { it.catalogOwner === workspaces }?.let { route ->
                                rows[route.host.machineId]?.takeIf { it.authoritative }?.rows?.singleOrNull { it.key == route.workspaceId }?.let { row ->
                                    val previous = route.host.selected.value
                                    if (previous == null || row.workspace.terminals.none { it.id == previous.id }) {
                                        if (previous != null) route.host.leave()
                                        val remembered = store.lastWorkspaceTab(next.login, cloudWorkspaceTabKey(next, row))
                                        val terminal = cloudWorkspaceTerminal(row, true, remembered)
                                        terminal?.let { route.host.select(row.workspace, it) }
                                        rememberDestination(next, row, terminal, recordTab = previous == null)
                                    }
                                }
                            }
                            pendingRestore?.let { saved ->
                                when (val decision = saved.resolve(next, foreground, visibility.hidden.value, update, rows)) {
                                    CloudRestoreDecision.Wait -> Unit
                                    CloudRestoreDecision.Discard -> cancelRestoration()
                                    is CloudRestoreDecision.Open -> {
                                        pendingRestore = null
                                        try { openWorkspace(decision.row, decision.terminal?.id, workspaces) }
                                        catch (_: Exception) { cancelRestoration(); mutableNavigationFailure.value = "Could not restore the Cloud terminal. Open its workspace to try again." }
                                    }
                                }
                            }
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
        hosts.forEach { (id, host) -> host.reconcile(mutableWorkspaces.value?.state?.value?.get(id), value && mutableRoute.value?.host === host,
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
        mutableCreation.value?.clearFailure()
        mutableTunnel.value?.resource()?.connections?.retire(setOf(machineId))
        mutableTunnel.value?.retry()
        mutableWorkspaces.value?.refresh(machineId)
        hosts[machineId]?.replay()
    }
    fun openWorkspace(row: CloudWorkspaceRow, terminalId: String? = null, expected: CloudWorkspaceController) {
        check(row.machine.id !in mutableVisibility.value?.hidden?.value.orEmpty()) { "This Cloud computer is hidden on this phone" }
        check(mutableWorkspaces.value === expected) { "Cloud workspace account changed" }
        val owner = owner ?: return
        check(teams.isCurrent(owner)) { "Cloud account changed" }
        val snapshot = mutableWorkspaces.value?.state?.value?.get(row.machine.id) ?: return
        val current = snapshot.rows.singleOrNull { it.key == row.key } ?: return
        val host = hosts.getOrPut(row.machine.id) {
            CloudTerminalHost(scope, row.machine.id, { teams.isCurrent(owner) }, {
                NativeCloudTerminalLink(checkNotNull(connection(row.machine.id)) { "Cloud tunnel is reconnecting" }.awaitSession())
            }, SshComposerPool(draftRepository.drafts, { terminal -> cloudDraftTarget(owner, row.machine.id, terminal) },
                draftRepository::persistNow, { teams.isCurrent(owner) }))
        }
        mutableRoute.value?.host?.takeUnless { it === host }?.let { previous ->
            previous.reconcile(mutableWorkspaces.value?.state?.value?.get(previous.machineId), false, false)
        }
        host.reconcile(snapshot, foreground, mutableTunnel.value?.state?.value?.phase == CloudTunnelPhase.READY)
        val remembered = store.lastWorkspaceTab(owner.login, cloudWorkspaceTabKey(owner, current))
        val terminal = if (terminalId == null) cloudWorkspaceTerminal(current, snapshot.authoritative, remembered)
            else current.workspace.terminals.singleOrNull { it.id == terminalId }
        if (terminalId != null && terminal == null) return
        if (terminal != null) host.select(current.workspace, terminal) else host.leave()
        pendingRestore = null
        rememberDestination(owner, current, terminal, recordTab = terminalId != null || snapshot.authoritative,
            waitingTerminal = if (terminal == null) remembered?.takeIf { it.kind == NativeWorkspaceTabKind.TERMINAL }?.id else null)
        navigationRevision++
        mutableRoute.value = CloudWorkspaceRoute(host, current.key, expected)
    }
    fun createWorkspace(machineId: String, expected: CloudWorkspaceController, workspaceId: String? = null): Boolean {
        if (machineId in mutableVisibility.value?.hidden?.value.orEmpty() || mutableWorkspaces.value !== expected || owner?.let(teams::isCurrent) != true) return false
        val navigation = navigationRevision
        return mutableCreation.value?.request(machineId, workspaceId) { created ->
            if (navigationRevision == navigation && mutableWorkspaces.value === expected && foreground &&
                created.machineId !in mutableVisibility.value?.hidden?.value.orEmpty()) {
                expected.state.value[created.machineId]?.rows?.singleOrNull { it.remoteId == created.workspaceId }?.let { row ->
                    openWorkspace(row, created.terminalId?.let { CloudAddress(created.machineId, it).identifier }, expected)
                }
            }
        } == true
    }
    fun setHidden(machineId: String, hidden: Boolean, expected: CloudMachineVisibility): Boolean {
        if (mutableVisibility.value !== expected || !expected.setHidden(machineId, hidden)) return false
        if (hidden) {
            if (mutableRoute.value?.host?.machineId == machineId) leaveWorkspace()
            hosts.remove(machineId)?.close()
        }
        return true
    }
    private fun rememberDestination(owner: NativeTeamScope, row: CloudWorkspaceRow, terminal: NativeTerminal?,
        recordTab: Boolean, waitingTerminal: String? = null) {
        if (!teams.isCurrent(owner)) return
        if (recordTab && terminal != null) try {
            store.rememberWorkspaceTab(owner.login, cloudWorkspaceTabKey(owner, row), NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, terminal.id))
        } catch (_: Exception) { mutableNavigationFailure.value = "Could not save the selected Cloud terminal on this phone." }
        savedState[CloudScreenCheckpoint.KEY] = CloudScreenCheckpoint(owner.login, owner.userId, owner.teamId,
            row.machine.id, row.key, terminal?.id ?: waitingTerminal).encode()
    }
    fun cancelRestoration() { pendingRestore = null; savedState.remove<String>(CloudScreenCheckpoint.KEY) }
    fun leaveWorkspace() { cancelRestoration(); navigationRevision++; mutableRoute.value?.host?.leave(); mutableRoute.value = null }
    override fun onCleared() {
        mutableVisibility.value?.close(); mutableVisibility.value = null
        mutableCreation.value?.close(); mutableCreation.value = null; navigationRevision++
        catalogObserver?.cancel(); mutableWorkspaces.value?.close(); mutableWorkspaces.value = null
        hosts.values.forEach { it.close() }; hosts.clear(); mutableRoute.value = null
        mutableTunnel.value?.close(); mutableTunnel.value = null
        mutable.value?.close(); mutable.value = null; scope.cancel()
    }
    class Factory(private val context: Context, private val account: NativeAccount,
        private val store: NativeCredentialStore, private val teams: NativeAccountTeams) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass == NativeCloudViewModel::class.java)
            @Suppress("UNCHECKED_CAST") return NativeCloudViewModel(context, account, store, teams, extras.createSavedStateHandle()) as T
        }
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeCloudViewModel::class.java)
            @Suppress("UNCHECKED_CAST") return NativeCloudViewModel(context, account, store, teams) as T
        }
    }
}
