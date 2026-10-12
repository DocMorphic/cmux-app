package io.github.docmorphic.cmuxapp

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/** Activity recreation retains account-scoped snapshots; process death refetches from the Mac. */
internal class NativeFeedSession(
    private val connector: NativeConnector, private val account: NativeAccount, private val store: NativeCredentialStore,
    private val teams: NativeAccountTeams? = null,
    reserveForeground: (NativeTeamContinuation, MobileRpcClient) -> NativeForegroundTransport? = { _, _ -> null }
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var feedMacs = emptyList<NativeCredentialStore.PairedMac>()
    private var routeKeys = emptyMap<String, String>()
    private var localRouteKeys = emptyMap<NativeMacIdentity, String>()
    private val browserHolds = mutableMapOf<Any, String>()
    private val sidebarHolds = mutableMapOf<Any, Boolean>()
    val sidebarHistory = NativeSidebarHistory()
    val ticketPairing = NativeTicketPairing()
    val sidebarSalt = java.util.UUID.randomUUID().toString()
    private var viewModelCleared = false
    val terminalSizing = NativeTerminalSizingSession()
    val macColorSlots = NativeMacColorSlots()
    val macSwitchRecovery = NativeMacSwitchRecovery()
    val foregroundRecovery = NativeConnectionRecoveryGate()
    val foregroundRetryState = mutableIntStateOf(0)
    private val foregroundAuthority = teams?.let { NativeForegroundSessionAuthority(it, store::visiblePairedMacs, reserveForeground) }
    private val foregroundWorkspaceProjection = kotlinx.coroutines.flow.MutableStateFlow<NativeFeedSource?>(null)
    init { if (teams != null) scope.launch { teams.state.collect {
        foregroundAuthority?.reconcile()
        foregroundWorkspaceProjection.value?.let { if (!allowsForegroundMac(it.mac)) foregroundWorkspaceProjection.value = null }
    } } }
    init { if (teams != null) scope.launch { store.revisions.collect {
        foregroundAuthority?.reconcile()
        foregroundWorkspaceProjection.value?.let { if (!allowsForegroundMac(it.mac)) foregroundWorkspaceProjection.value = null }
    } } }
    fun admitForeground(team: NativeTeamScope?, mac: NativeCredentialStore.PairedMac, client: MobileRpcClient) {
        if (team != null) foregroundAuthority?.admit(team, mac, client)
    }
    fun foregroundScope(code: String, client: MobileRpcClient?) = foregroundAuthority?.scopeFor(code, client)
    fun foregroundScope(mac: NativeCredentialStore.PairedMac, client: MobileRpcClient?) = foregroundAuthority?.scopeFor(mac, client)
    fun allowsForegroundMac(mac: NativeCredentialStore.PairedMac) = foregroundAuthority?.scopeFor(mac) != null
    fun releaseForeground(client: MobileRpcClient) { foregroundAuthority?.release(client) }
    private var screenConnection: NativeScreenConnectionState? = null
    private var foregroundSizing: Job? = null
    private var foregroundArtifactWatcher: Job? = null
    private var foregroundWorkspaceAccess: Triple<NativeCredentialStore.PairedMac, MobileRpcClient, NativeForegroundWorkspaceAccess>? = null
    fun connectionState(initialCode: String): NativeScreenConnectionState = screenConnection
        ?: NativeScreenConnectionState(initialCode, ::changeForegroundClient).also { screenConnection = it }
    private fun changeForegroundClient(previous: MobileRpcClient?, next: MobileRpcClient?) {
        foregroundWorkspaceAccess = null
        foregroundWorkspaceProjection.value = null
        foregroundSizing?.cancel(); foregroundSizing = null
        foregroundArtifactWatcher?.cancel(); foregroundArtifactWatcher = null
        previous?.let { releaseForeground(it); terminalSizing.unbind(it); terminalInputs.detach(it); it.close() }
        val active = next ?: return
        foregroundArtifactWatcher = scope.launch {
            active.disconnected.first()
            if (screenConnection?.client === active) foregroundWorkspaceProjection.value = null
            panelPreview?.let { if (!it.current()) dismissPanel(it) else if (!it.access.current()) it.preview.connectionLost() }
            filesSheet?.let { if (!it.current()) dismissFiles(it) else if (!it.access.current()) it.connectionLost() }
        }
        val subscription = terminalSizing.subscription(active) ?: return
        foregroundSizing = scope.launch {
            try { active.useEventSession { wire ->
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { runCatching { wire.unsubscribe(subscription) } } }
            } } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!active.isClosed) active.retireForOwnerFailure(failure)
            }
        }
    }
    fun canRetainForeground(code: String, client: MobileRpcClient): Boolean = if (foregroundAuthority != null)
        foregroundAuthority.scopeFor(code, client) != null
    else account.isSignedIn() && store.visiblePairedMacs().any { it.code == code && connector.allowsSaved(it) }
    private fun canRetainForeground(mac: NativeCredentialStore.PairedMac, client: MobileRpcClient): Boolean =
        if (foregroundAuthority != null) foregroundAuthority.scopeFor(mac, client) != null
        else account.isSignedIn() && NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) && connector.allowsSaved(mac)
    val workspaceSnapshots = NativeWorkspaceSnapshots(store::taskSession)
    val coordinator = NativeFeedCoordinator(scope, connect = { mac ->
        connector.connectSaved(mac, account).also { client ->
            val owner = nativeTerminalInputOwner(mac, store.taskSession())
            client.terminalTrafficAllowed = { surface -> owner != null && allowsTerminalInput(owner) &&
                terminalSizing.allowsTraffic(owner, surface) }
        }
    }, isAllowed = { mac -> account.isSignedIn() && store.visiblePairedMacs().contains(mac) &&
        connector.allowsSaved(mac) }, workspaceSnapshots = workspaceSnapshots, onVerified = ::recordMacSeen,
        refreshIdentity = connector::refreshSavedIdentity)

    fun recordMacSeen(mac: NativeCredentialStore.PairedMac) {
        val login = store.taskSession()
        val time = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try { store.recordMacSeen(login, mac, time) { connector.allowsSaved(mac) } }
            catch (failure: Exception) { currentCoroutineContext().ensureActive() }
        }
    }

    val workspaceMoves = NativeWorkspaceMoves(scope, coordinator, foregroundWorkspaceProjection) { mac, id, intent, base, canSend ->
        val active = foregroundWorkspace(mac)
        if (active != null) active.moveWorkspace(id, intent, base, canSend)
        else coordinator.moveWorkspace(mac, id, intent, base, canSend)
        Unit
    }
    fun allowsCreation(request: NativeCreationRequest): Boolean = !viewModelCleared && account.isSignedIn() &&
        store.taskSession() == request.login && NativeComputerMenuPairing.isCurrent(request.mac, store.visiblePairedMacs()) &&
        ((request.team != null && foregroundAuthority?.scopeFor(request.mac) == request.team) ||
            ((teams == null || request.team?.let(teams::isCurrent) == true) && connector.allowsSaved(request.mac)))
    val workspaceCreation = NativeWorkspaceCreationCoordinator(scope, ::allowsCreation, ::artifactHold, create = { request ->
        val canSend = { allowsCreation(request) }
        when {
            request.workspaceId != null -> workspaceAction(request.mac, request.workspaceId, "terminal.create", canSend = canSend)
            else -> foregroundWorkspace(request.mac)?.createWorkspace(request.groupId, canSend)
                ?: if (request.groupId != null) coordinator.createWorkspaceInGroup(request.mac, request.groupId, canSend)
                else coordinator.createWorkspace(request.mac, canSend)
        }
    }, latest = workspaceSnapshots::createdWorkspace)
    private fun foregroundWorkspace(mac: NativeCredentialStore.PairedMac): NativeForegroundWorkspaceAccess? {
        val state = screenConnection ?: return null
        val client = state.client ?: return null
        fun live() = !viewModelCleared && !client.isClosed && state.client === client && state.connectionReady && state.connectedCode == mac.code &&
            state.code == mac.code && canRetainForeground(mac, client)
        if (!live()) return null
        foregroundWorkspaceAccess?.takeIf { it.second === client && NativeComputerMenuPairing.isCurrent(mac, listOf(it.first)) }?.let { return it.third }
        return NativeForegroundWorkspaceAccess(scope, mac, client, workspaceSnapshots, { state.hostCapabilities }, ::live,
            onSnapshot = { updateForegroundWorkspaceSource(mac) })
            .also { foregroundWorkspaceAccess = Triple(mac, client, it) }
    }
    fun updateForegroundWorkspaceSource(mac: NativeCredentialStore.PairedMac?) {
        if (mac == null || foregroundWorkspace(mac) == null) { foregroundWorkspaceProjection.value = null; return }
        val next = workspaceSource(mac)
        if (next != null) foregroundWorkspaceProjection.value = next
        else if (foregroundWorkspaceProjection.value?.mac?.let { NativeComputerMenuPairing.isCurrent(mac, listOf(it)) } != true)
            foregroundWorkspaceProjection.value = null
    }
    fun workspaceSource(mac: NativeCredentialStore.PairedMac): NativeFeedSource? {
        if (foregroundWorkspace(mac) == null) return coordinator.sources.value[mac.origin]?.takeIf { it.mac == mac }
        val state = checkNotNull(screenConnection)
        val snapshot = workspaceSnapshots.latest(mac) ?: return foregroundWorkspaceProjection.value
            ?.takeIf { NativeComputerMenuPairing.isCurrent(mac, listOf(it.mac)) }
            ?.copy(mac = mac, capabilities = state.hostCapabilities, macMutationTicket = state.client?.macMutationTicket())
        // Presentation can retain the last accepted rows while a write is in flight. The mutation
        // adapter independently requires a fresh snapshot before admitting a subsequent write.
        return NativeFeedSource(mac, workspaces = snapshot.workspaces, groups = parseGroups(snapshot.value),
            capabilities = state.hostCapabilities, availability = NativeFeedAvailability.CONNECTED,
            hasWorkspaceSnapshot = true, macMutationTicket = state.client?.macMutationTicket())
    }
    suspend fun workspaceAction(mac: NativeCredentialStore.PairedMac, workspaceId: String, action: String,
        title: String? = null, canSend: () -> Boolean = { true }) =
        foregroundWorkspace(mac)?.workspaceAction(workspaceId, action, title, canSend)
            ?: coordinator.workspaceAction(mac, workspaceId, action, title, canSend)
    suspend fun createGroup(mac: NativeCredentialStore.PairedMac, canSend: () -> Boolean = { true }) =
        foregroundWorkspace(mac)?.createGroup(canSend) ?: coordinator.createGroup(mac, canSend)
    suspend fun groupAction(mac: NativeCredentialStore.PairedMac, groupId: String, action: String,
        title: String? = null, canSend: () -> Boolean = { true }) =
        foregroundWorkspace(mac)?.groupAction(groupId, action, title, canSend)
            ?: coordinator.groupAction(mac, groupId, action, title, canSend)
    suspend fun customizeWorkspace(mac: NativeCredentialStore.PairedMac, workspaceId: String,
        baseline: WorkspaceCustomizationDraft, submitted: WorkspaceCustomizationDraft, canSend: () -> Boolean = { true }) =
        foregroundWorkspace(mac)?.customizeWorkspace(workspaceId, baseline, submitted, canSend)
            ?: coordinator.customizeWorkspace(mac, workspaceId, baseline, submitted, canSend)
    suspend fun createTask(mac: NativeCredentialStore.PairedMac, client: MobileRpcClient,
        params: org.json.JSONObject, canSend: () -> Boolean): org.json.JSONObject {
        check(screenConnection?.client === client) { "Task connection changed" }
        return checkNotNull(foregroundWorkspace(mac)) { "Task connection is no longer available" }.createTask(params, canSend)
    }
    val taskModels = TaskModelRepository(scope)
    private val taskModelPrefetch = TaskModelPrefetch(scope, taskModels)
    init { scope.launch { coordinator.taskModelIdentities.collect { reconcileTaskModelPrefetch() } } }
    private fun reconcileTaskModelPrefetch() {
        val visible = if (foreground && !viewModelCleared && account.isSignedIn()) store.visiblePairedMacs().toSet() else emptySet()
        val wanted = feedMacs.filter {
            it in visible && connector.allowsSaved(it)
        }
        taskModelPrefetch.update(wanted.map(coordinator::taskModelTarget))
    }
    val localBrowsers = LocalBrowserNavigation(scope)
    private fun browserAccess(mac: NativeCredentialStore.PairedMac, permits: () -> Boolean): MacBrowserAccess {
        val state = screenConnection
        val client = state?.client
        if (state != null && client != null && state.connectionReady && state.connectedCode == mac.code &&
            state.code == mac.code && canRetainForeground(mac, client)) {
            return NativeForegroundBrowserAccess(scope, client, { state.hostCapabilities }) {
                permits() && state.client === client && state.connectionReady && state.connectedCode == mac.code &&
                    state.code == mac.code && canRetainForeground(mac, client)
            }
        }
        return coordinator.browserAccess(mac, permits)
    }
    val browserNetworks = NativeBrowserNetworks(scope, ::browserAccess, allows = { mac, login ->
        account.isSignedIn() && store.taskSession() == login && NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) &&
            (connector.allowsSaved(mac) || allowsForegroundMac(mac))
    }, isCurrent = { owner -> teams?.isCurrent(owner) ?: true }, continues = { mac, owner ->
        foregroundAuthority?.scopeFor(mac) == owner
    })
    val workspaceTabs = NativeWorkspaceTabNavigation(store)
    val terminalStartup = NativeTerminalStartup()
    val paneNavigation = NativePaneNavigation()
    private fun foregroundArtifactConnection(mac: NativeCredentialStore.PairedMac): NativeForegroundArtifactAccess? {
        val state = screenConnection ?: return null
        val client = state.client?.takeUnless { it.isClosed } ?: return null
        val login = store.taskSession() ?: return null
        val capabilities = ArtifactCapabilities.read(state.hostCapabilities)
        fun cached() = !viewModelCleared && account.isSignedIn() && store.taskSession() == login && state.code == mac.code &&
            NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) &&
            ArtifactCapabilities.read(state.hostCapabilities) == capabilities &&
            coordinator.sources.value[mac.origin]?.artifactCacheDenied != true &&
            (connector.allowsSaved(mac) || foregroundScope(mac, client) != null)
        fun live() = cached() && state.client === client && state.connectionReady && state.connectedCode == mac.code &&
            canRetainForeground(mac, client)
        if (!live()) return null
        return NativeForegroundArtifactAccess(scope, client, ::live, ::cached)
    }
    private fun foregroundPanelAccess(mac: NativeCredentialStore.PairedMac, target: NativePanelTarget): PanelArtifactAccess? {
        if ("panel.artifact.v1" !in screenConnection?.hostCapabilities.orEmpty()) return null
        return foregroundArtifactConnection(mac)?.panel(target) {
            workspaceSnapshots.latest(mac)?.workspaces?.singleOrNull { it.id == target.workspace }?.macSurfaces
                ?.singleOrNull { it.id == target.surface }?.let { NativePanelTarget.from(target.workspace, it) == target } == true
        }
    }
    private fun foregroundTerminalAccess(mac: NativeCredentialStore.PairedMac,
        terminal: ArtifactAuthorization.Terminal): TerminalArtifactAccess? {
        val capabilities = ArtifactCapabilities.read(screenConnection?.hostCapabilities.orEmpty())
        return foregroundArtifactConnection(mac)?.terminal(terminal, capabilities) {
            workspaceSnapshots.latest(mac)?.workspaces?.singleOrNull { it.id == terminal.workspaceId }
                ?.terminals?.singleOrNull { it.id == terminal.surfaceId } != null
        }
    }
    fun artifactSource(mac: NativeCredentialStore.PairedMac): NativeFeedSource? {
        coordinator.sources.value[mac.origin]?.takeIf { it.artifactCacheDenied }?.let { return it }
        val state = screenConnection
        val snapshot = workspaceSnapshots.latest(mac)
        if (state != null && state.code == mac.code && snapshot != null &&
            (allowsForegroundMac(mac) || connector.allowsSaved(mac))) {
            val live = state.client?.let { !it.isClosed && state.connectionReady && state.connectedCode == mac.code &&
                canRetainForeground(mac, it) } == true
            if (live) return NativeFeedSource(mac, workspaces = snapshot.workspaces, capabilities = state.hostCapabilities,
                hasWorkspaceSnapshot = true, availability = NativeFeedAvailability.CONNECTED, panelCacheToken = state.client)
        }
        return coordinator.sources.value[mac.origin]
    }
    private fun artifactHold(mac: NativeCredentialStore.PairedMac): AutoCloseable =
        if (allowsForegroundMac(mac) || screenConnection?.client?.let { canRetainForeground(mac, it) } == true)
            AutoCloseable {} else holdBrowser(mac)
    var panelPreview by mutableStateOf<NativePanelPresentation?>(null)
        private set
    fun openPanel(login: String, key: NativeWorkspaceTabKey, mac: NativeCredentialStore.PairedMac, target: NativePanelTarget) {
        panelPreview?.let { if (it.matches(login, key, mac, target) && it.current()) return }
        val access = foregroundPanelAccess(mac, target) ?: coordinator.panelArtifactAccess(mac, target) {
            !viewModelCleared && account.isSignedIn() && store.taskSession() == login
        } ?: return
        dismissPanel()
        panelPreview = NativePanelPresentation(scope, login, key, mac, target, access, artifactHold(mac))
    }
    fun dismissPanel(expected: NativePanelPresentation? = panelPreview) {
        if (panelPreview !== expected) return
        val previous = panelPreview; panelPreview = null; previous?.close()
    }
    fun retryPanel(expected: NativePanelPresentation) {
        if (panelPreview !== expected || !expected.current() || expected.preview.state.value.error == null) return
        if (expected.access.current()) { expected.preview.retry(); return }
        val access = foregroundPanelAccess(expected.mac, expected.target) ?: coordinator.panelArtifactAccess(expected.mac, expected.target) {
            !viewModelCleared && account.isSignedIn() && store.taskSession() == expected.login
        }
        if (access == null) { expected.preview.retryUnavailable(); return }
        // Acquire the replacement's hold before releasing the old one, so retry cannot pause its own feed.
        val replacement = NativePanelPresentation(scope, expected.login, expected.key, expected.mac, expected.target,
            access, artifactHold(expected.mac))
        dismissPanel(expected)
        panelPreview = replacement
    }
    var filesSheet by mutableStateOf<TerminalFilesPresentation?>(null)
        private set
    fun openFiles(login: String, key: NativeWorkspaceTabKey, mac: NativeCredentialStore.PairedMac,
                  terminal: ArtifactAuthorization.Terminal, navigation: TerminalFilesState) {
        filesSheet?.let { if (it.matches(login, key, terminal.surfaceId) && it.mac == mac && it.current()) return }
        val access = foregroundTerminalAccess(mac, terminal) ?: coordinator.terminalArtifactAccess(mac, terminal) {
            !viewModelCleared && account.isSignedIn() && store.taskSession() == login
        } ?: return
        dismissFiles()
        filesSheet = TerminalFilesPresentation(scope, login, key, mac, terminal, navigation, access, artifactHold(mac))
    }
    fun recoverFiles(expected: TerminalFilesPresentation) {
        if (filesSheet !== expected || !expected.current() || expected.access.current()) return
        expected.connectionLost()
        val next = foregroundTerminalAccess(expected.mac, expected.terminal) ?: coordinator.terminalArtifactAccess(expected.mac, expected.terminal) {
            !viewModelCleared && account.isSignedIn() && store.taskSession() == expected.login
        } ?: return
        expected.replaceConnection(next)
    }
    fun dismissFiles(expected: TerminalFilesPresentation? = filesSheet) {
        if (filesSheet !== expected) return
        val previous = filesSheet; filesSheet = null; previous?.close()
    }
    var changesSheet by mutableStateOf<WorkspaceChangesPresentation?>(null)
        private set
    fun openChanges(mac: NativeCredentialStore.PairedMac, workspace: NativeWorkspace) {
        val login = checkNotNull(store.taskSession()) { "Sign in to view changes" }
        val access = coordinator.changesAccess(mac, workspace) {
            !viewModelCleared && account.isSignedIn() && store.taskSession() == login
        }
        check(access.current()) { "Changes workspace is no longer available" }
        changesSheet?.close()
        changesSheet = WorkspaceChangesPresentation(scope, access)
    }
    fun dismissChanges(expected: WorkspaceChangesPresentation? = changesSheet) {
        if (changesSheet !== expected) return
        changesSheet?.close(); changesSheet = null
    }
    val terminalInputs = NativeTerminalInputSession(scope)
    fun configureFeed(macs: List<NativeCredentialStore.PairedMac>, routes: Map<String, String>,
        localRoutes: Map<NativeMacIdentity, String>, active: Boolean) {
        feedMacs = macs; routeKeys = routes; localRouteKeys = localRoutes; foreground = active
        reconcileFeed()
    }
    fun leaveMainScreen() { foreground = false; reconcileFeed() }
    private fun reconcileFeed() {
        coordinator.retainMacs(feedMacs)
        val wanted = if (foreground || sidebarHolds.values.any { it }) feedMacs else feedMacs.filter { it.origin in browserHolds.values }
        if (wanted.isEmpty()) coordinator.pause() else coordinator.updateMacs(wanted, routeKeys, localRouteKeys)
        reconcileTaskModelPrefetch()
    }
    fun holdBrowser(mac: NativeCredentialStore.PairedMac): AutoCloseable {
        check(account.isSignedIn() && NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) &&
            (connector.allowsSaved(mac) || allowsForegroundMac(mac)))
        val token = Any(); browserHolds[token] = mac.origin; reconcileFeed()
        return AutoCloseable {
            if (browserHolds.remove(token) != null) {
                if (viewModelCleared && browserHolds.isEmpty() && sidebarHolds.isEmpty()) dispose() else reconcileFeed()
            }
        }
    }
    fun allowsTerminalInput(owner: TerminalInputSender.Owner): Boolean = account.isSignedIn() &&
        store.taskSession() == owner.login && store.visiblePairedMacs().any { mac ->
            nativeTerminalInputOwner(mac, owner.login) == owner && (connector.allowsSaved(mac) || allowsForegroundMac(mac))
        }

    fun holdSidebar(isCurrent: () -> Boolean): RoutedSidebarLease {
        check(isCurrent()) { "Sidebar account changed" }
        val token = Any(); sidebarHolds[token] = false
        return RoutedSidebarLease({ active ->
            if (token in sidebarHolds) { sidebarHolds[token] = active && isCurrent(); reconcileFeed() }
        }, {
            if (sidebarHolds.remove(token) != null) {
                if (viewModelCleared && browserHolds.isEmpty() && sidebarHolds.isEmpty()) dispose() else reconcileFeed()
            }
        })
    }

    var projection by mutableStateOf(NativeFeedProjection())
    fun clear() { screenConnection?.close(); foregroundAuthority?.clear(); foregroundRecovery.clear(); foregroundRetryState.intValue = 0; workspaceCreation.clear(); ticketPairing.clear(); dismissPanel(); dismissFiles(); dismissChanges(); sidebarHistory.clear(); browserHolds.clear(); sidebarHolds.clear(); feedMacs = emptyList(); foreground = false; macColorSlots.clear(); macSwitchRecovery.clear(); browserNetworks.clear(); terminalInputs.clear(); terminalSizing.clear(); paneNavigation.clear(); workspaceSnapshots.clear(); terminalStartup.clear(); workspaceTabs.clear(); localBrowsers.clear(); taskModelPrefetch.update(emptyList()); taskModels.clear(); workspaceMoves.clear(); coordinator.close(); projection = NativeFeedProjection() }
    private fun dispose() { clear(); terminalInputs.close(); scope.cancel() }
    override fun onCleared() { screenConnection?.close(); foregroundAuthority?.clear(); ticketPairing.clear(); viewModelCleared = true; dismissPanel(); dismissFiles(); dismissChanges(); foreground = false; if (browserHolds.isEmpty() && sidebarHolds.isEmpty()) dispose() else reconcileFeed() }

    class Factory(private val connector: NativeConnector, private val account: NativeAccount,
        private val store: NativeCredentialStore, private val teams: NativeAccountTeams? = null,
        private val reserveForeground: (NativeTeamContinuation, MobileRpcClient) -> NativeForegroundTransport? = { _, _ -> null }) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeFeedSession::class.java)
            return NativeFeedSession(connector, account, store, teams, reserveForeground) as T
        }
    }
}
