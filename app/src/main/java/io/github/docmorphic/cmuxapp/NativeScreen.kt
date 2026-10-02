package io.github.docmorphic.cmuxapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val nativePage = Color(0xFF0B0C0E)
private val nativePanel = Color(0xFF191B1F)
private val nativeAccent = Color(0xFF76B9FF)
private val nativeMuted = Color(0xFF9B9FA8)

@Composable
fun NativeScreen(
    onUseHelper: () -> Unit, incomingCode: String? = null, incomingNotificationRoute: String? = null,
    onNotificationHandled: (String) -> Unit = {}, onPairingHandled: (String) -> Unit = {},
    connector: NativeConnector? = null
) {
    val context = LocalContext.current
    val runtimeOwner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val sharedConnections = remember(runtimeOwner, connector) {
        if (connector == null) ViewModelProvider(runtimeOwner, NativeConnectionsViewModel.Factory(context))
            .get(NativeConnectionsViewModel::class.java).connections else null
    }
    val connection = connector ?: checkNotNull(sharedConnections).connector
    val computerStates = remember(sharedConnections) {
        sharedConnections?.native?.state ?: kotlinx.coroutines.flow.MutableStateFlow(NativeComputersState())
    }
    val computerState by computerStates.collectAsState()
    val focusManager = LocalFocusManager.current
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val displayPreferences = remember(context) {
        context.getSharedPreferences("native_display", android.content.Context.MODE_PRIVATE)
    }
    val toolbarStore = rememberTerminalToolbar(displayPreferences)
    var viewportRequestGeneration by remember { mutableLongStateOf(0L) }
    val store = remember(context, sharedConnections) { sharedConnections?.store ?: NativeCredentialStore(context.applicationContext) }
    val account = remember(store) { sharedConnections?.account ?: NativeAccount(store) }
    val feedSession = remember(runtimeOwner) {
        ViewModelProvider(runtimeOwner, NativeFeedSession.Factory(connection, account, store))
            .get(NativeFeedSession::class.java)
    }
    val terminalInputs = feedSession.terminalInputs
    val scope = rememberCoroutineScope()
    val terminalFocusRequester = remember { FocusRequester() }
    var signedIn by remember { mutableStateOf(account.isSignedIn()) }
    val accountTeams = remember(account, store) { sharedConnections?.teams ?: NativeAccountTeams(account, store) }
    val teamState by accountTeams.state.collectAsState()
    val appearances = nativeMacAppearances(teamState.scope)
    DisposableEffect(accountTeams) { onDispose { if (sharedConnections == null) accountTeams.close() } }
    LaunchedEffect(signedIn, accountTeams) {
        if (!signedIn) accountTeams.clear()
    }
    var code by remember { mutableStateOf(store.load()?.optString("pairing_code").orEmpty()) }
    var pendingPairingCode by rememberSaveable(signedIn) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectionError by remember(code) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var retryDelay by remember { mutableLongStateOf(2_000) }
    var client by remember { mutableStateOf<MobileRpcClient?>(null) }
    var connectionReady by remember { mutableStateOf(false) }
    var hostName by remember(code) { mutableStateOf("cmux") }
    var hostCapabilities by remember(code) { mutableStateOf<Set<String>>(emptySet()) }
    var savedPairedMacs by remember { mutableStateOf(store.pairedMacs()) }
    val pairedMacs = savedPairedMacs.filter {
        connection.allowsSaved(it)
    }
    val machineColorIndices = nativeMacColorIndices(pairedMacs, computerState.computers)
    val paneSelection = feedSession.paneNavigation.select(if (signedIn) store.taskSession() else null,
        pairedMacs.singleOrNull { it.code == code }, teamState.scope)
    var showSettings by rememberSaveable(signedIn) { mutableStateOf(false) }
    var showSshComputers by rememberSaveable(signedIn) { mutableStateOf(false) }
    var showSshKeys by rememberSaveable(signedIn) { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showTaskComposer by rememberSaveable(signedIn) { mutableStateOf(false) }
    var taskDraftId by rememberSaveable(signedIn) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var taskDraftRepository by remember(signedIn) { mutableStateOf<TaskDraftRepository?>(null) }
    var taskDraftLoadError by remember(signedIn) { mutableStateOf<String?>(null) }
    var taskDraftLoadAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(signedIn, taskDraftLoadAttempt) {
        if (!signedIn) { TaskDraftRepository.clearMemory(); return@LaunchedEffect }
        taskDraftLoadError = null
        try {
            taskDraftRepository = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val session = checkNotNull(store.taskSession()) { "Sign in to load task drafts" }
                TaskDraftRepository.get(context, session)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            taskDraftLoadError = "Could not load saved task drafts"
        }
    }
    val emptyTaskDrafts = remember { kotlinx.coroutines.flow.MutableStateFlow<Map<String, TaskDraft>>(emptyMap()) }
    val taskDraftEntries by (taskDraftRepository?.drafts?.state ?: emptyTaskDrafts).collectAsState()
    LaunchedEffect(taskDraftRepository) {
        taskDraftRepository?.saveError?.collect { if (it != null) error = it }
    }
    var showCreateGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var backgroundNotifications by remember { mutableStateOf(NativeNotificationService.isEnabled(context)) }
    var workspaces by paneSelection.workspaces
    var groups by paneSelection.groups
    var taskGroupsLoaded by paneSelection.taskGroupsLoaded
    var collapsedGroups by remember(signedIn) {
        val saved = store.load()?.optJSONObject("collapsed_groups")
        mutableStateOf(saved?.keys()?.asSequence()?.filter { saved.opt(it) is Boolean }
            ?.associateWith { saved.optBoolean(it) }.orEmpty())
    }
    var notifications by remember(code) { mutableStateOf<List<NativeNotification>>(emptyList()) }
    var notificationTab by rememberSaveable(signedIn) { mutableStateOf(false) }
    var searchState by rememberSaveable(signedIn, stateSaver = listSaver(
        save = { state: NativeSearchState -> state.commit().let { listOf(it.workspaceQuery, it.notificationQuery) } },
        restore = { NativeSearchState(workspaceQuery = NativeSearchText.boundQuery(it[0]),
            notificationQuery = NativeSearchText.boundQuery(it[1])) }
    )) { mutableStateOf(NativeSearchState()) }
    val searchScope = if (notificationTab) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES
    val search = searchState.text(searchScope).trim()
    val notificationQuery = searchState.text(NativeSearchScope.NOTIFICATIONS).trim()
    fun finishSearch(cancel: Boolean = false) {
        searchState = if (cancel) searchState.clear(searchScope) else searchState.commit()
        focusManager.clearFocus(); softwareKeyboard?.hide()
    }

    var unreadWorkspacesOnly by rememberSaveable(signedIn) { mutableStateOf(false) }
    var createMenuOpen by remember { mutableStateOf(false) }
    var workspaceFilterMenuOpen by remember { mutableStateOf(false) }
    var computerMenuOpen by remember { mutableStateOf(false) }
    var selectedWorkspace by paneSelection.workspace
    var selectedTerminal by paneSelection.terminal
    var selectedSurface by paneSelection.surface
    val terminalZoom = remember(code, selectedWorkspace?.id, selectedTerminal?.id) { TerminalZoomState() }
    val terminalCells = remember(density, terminalZoom.size) {
        TerminalCellMetrics.fromFontSize(with(density) { terminalZoom.size.sp.toPx() }, with(density) { 2.dp.toPx() })
    }
    var terminalViewportPixels by remember { mutableStateOf(IntSize.Zero) }
    val terminalViewport = TerminalViewport.fit(terminalViewportPixels.width, terminalViewportPixels.height, terminalCells)
    val terminalColumns = terminalViewport?.columns ?: 0
    val terminalRows = terminalViewport?.rows ?: 0
    var selectedBrowser by paneSelection.browser
    var selectedChangesWorkspace by paneSelection.changesWorkspace
    var connectedCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(teamState.scope, signedIn) {
        val pairing = PairingCodeParser.parse(code).getOrNull()
        if (pairing is PairingCode.Iroh && (!signedIn || (teamState.scope != null && !connection.allowsSaved(pairing)))) {
            client?.close(); client = null; code = ""
            selectedTerminal = null; selectedWorkspace = null; selectedSurface = null; selectedBrowser = null
        }
    }
    val notificationDelivery = remember(context) { NativeNotificationDelivery(context.applicationContext) }
    var workspaceRoute by remember { mutableStateOf<NativeWorkspaceRoute?>(null) }
    var inAppNotification by remember { mutableStateOf<NotificationDestination?>(null) }
    val currentIncomingRoute by rememberUpdatedState(incomingNotificationRoute ?: inAppNotification?.routeId)
    val handleNotification by rememberUpdatedState(onNotificationHandled)
    var notificationNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val feedCoordinator = feedSession.coordinator
    val workspaceSnapshots = feedSession.workspaceSnapshots
    fun workspaceOwner(requestedCode: String = code) = store.pairedMacs().singleOrNull {
        it.code == requestedCode && connection.allowsSaved(it)
    } ?: throw java.io.IOException("This workspace's saved Mac is no longer available.")
    val localBrowsers = feedSession.localBrowsers
    DisposableEffect(localBrowsers) { onDispose { localBrowsers.cancelRequest() } }
    val localBrowserState by localBrowsers.state.collectAsState()
    val localBrowser = localBrowserState.local
    val browserLogin = if (signedIn) store.taskSession() else null
    fun inputOwner(mac: NativeCredentialStore.PairedMac, login: String?) = login?.let {
        TerminalInputSender.Owner(it, mac.accountUserId ?: it, mac.accountTeamId,
            canonicalMacDeviceId(mac.deviceId), mac.instanceTag?.trim()?.takeIf(String::isNotEmpty))
    }
    SideEffect { terminalInputs.retainOwner(pairedMacs.singleOrNull { it.code == code }?.let { inputOwner(it, browserLogin) }) }
    fun inputTargets(rows: List<NativeWorkspace>) = rows.flatMap { workspace -> workspace.terminals.filter { it.isReady }
        .map { NativeTerminalInputSession.Target(workspace.id, it.id) } }.toSet()
    val screenResume = rememberNativeScreenResume()
    val changesNavigation = rememberSaveable(saver = ChangesNavigationMemory.saver) { ChangesNavigationMemory() }
    SideEffect {
        changesNavigation.retainLogin(browserLogin)
        if (selectedChangesWorkspace == null && screenResume.pending?.changes != true && workspaceRoute?.changes != true)
            changesNavigation.clear()
    }
    val screenBootCount = rememberNativeScreenBootCount()
    fun requireWorkspaceConnection(active: MobileRpcClient, owner: NativeCredentialStore.PairedMac) {
        check(signedIn && client === active && connectionReady && connectedCode == owner.code && code == owner.code &&
            store.taskSession() == browserLogin && store.pairedMacs().contains(owner) && connection.allowsSaved(owner)) {
            "Workspace connection changed. Reconnect to this Mac."
        }
    }
    var creatingTerminal by remember { mutableStateOf(false) }
    val workspaceTabs = feedSession.workspaceTabs
    val terminalStartup = feedSession.terminalStartup
    val terminalStartupState by terminalStartup.state.collectAsState()
    val pendingWorkspaceTab by workspaceTabs.pending.collectAsState()
    val displayedTab = if (showSettings || showTaskComposer || selectedChangesWorkspace != null || workspaceRoute != null || currentIncomingRoute != null) null
        else workspaceTabDisplay(browserLogin, teamState.scope, pairedMacs, code, selectedWorkspace, selectedTerminal, selectedBrowser, selectedSurface, localBrowser)
    LaunchedEffect(browserLogin, pairedMacs) { workspaceSnapshots.retain(pairedMacs) }
    SideEffect {
        if (screenResume.pending == null) {
            workspaceTabs.observe(browserLogin, displayedTab?.first, displayedTab?.second)
            if (browserLogin != null && displayedTab != null && displayedTab.second == null && selectedWorkspace != null && !creatingTerminal)
                workspaceTabs.awaitDefault(browserLogin, displayedTab.first)
            terminalStartup.observe(displayedTab?.first, selectedTerminal?.id)
        }
        if (workspaceRoute == null && !creatingTerminal && localBrowserState.creating == null) {
            val destination = if (showSettings || showTaskComposer || currentIncomingRoute != null || pendingPairingCode != null) null else
                workspaceTabDisplay(browserLogin, teamState.scope, pairedMacs, code,
                    selectedChangesWorkspace ?: selectedWorkspace, selectedTerminal, selectedBrowser, selectedSurface, localBrowser)
            screenResume.observe(destination?.let { (key, tab) ->
                NativeScreenCheckpoint(checkNotNull(browserLogin), key,
                    if (selectedChangesWorkspace != null) null else workspaceTabs.pending.value?.takeIf { it.key == key }?.tab ?: tab,
                    changes = selectedChangesWorkspace != null,
                    startup = terminalStartup.state.value.pending?.takeIf { it.key == key && selectedChangesWorkspace == null },
                    failure = terminalStartup.state.value.failure?.takeIf { it.key == key && selectedChangesWorkspace == null },
                    bootCount = screenBootCount)
            })
        }
    }
    NativeScreenResumeEffect(screenResume, browserLogin, teamState.scope, savedPairedMacs, pairedMacs,
        admissionReady = connector != null || teamState.scope != null,
        hasRetainedPane = selectedWorkspace != null || localBrowser != null || selectedChangesWorkspace != null,
        newerNavigation = showSettings || showTaskComposer || currentIncomingRoute != null || incomingCode != null || pendingPairingCode != null ||
            (workspaceRoute != null && workspaceRoute?.resume == null)) { route ->
        if (route != null || workspaceRoute?.resume != null) workspaceRoute = route
    }
    fun selectPane(pane: NativeWorkspacePane) {
        terminalStartup.cancelPin()
        selectedTerminal = pane.terminal; selectedBrowser = pane.browser; selectedSurface = pane.surface
        val login = browserLogin ?: return
        workspaceTabDisplay(login, teamState.scope, pairedMacs, code, selectedWorkspace, selectedTerminal, selectedBrowser, selectedSurface, null)
            ?.let { workspaceTabs.explicit(login, it.first, checkNotNull(it.second)) }
    }
    val navigationGeneration = remember { NativeNavigationGeneration() }
    fun browserNavigationContext() = listOf(signedIn, teamState.scope, code, workspaceRoute?.id,
        selectedWorkspace?.id, selectedTerminal?.id, selectedBrowser?.id, selectedSurface?.id,
        selectedChangesWorkspace?.id, showSettings, showTaskComposer, notificationTab, currentIncomingRoute, localBrowser?.key)
    SideEffect {
        navigationGeneration.observe(browserNavigationContext())
        localBrowsers.retain(teamState.scope, signedIn, pairedMacs.map { it.origin }.toSet(), browserLogin)
        feedSession.browserNetworks.retain(browserLogin.takeIf { signedIn }, teamState.scope, pairedMacs)
        localBrowsers.navigationContext(browserNavigationContext())
    }
    fun openNewBrowser(source: NativeFeedSource, workspace: NativeWorkspace) {
        workspaceTabs.cancel()
        val owner = teamState.scope
        val key = localBrowserKey(browserLogin, owner, source.mac, workspace.id) ?: return
        val entryContext = browserNavigationContext()
        focusManager.clearFocus(); softwareKeyboard?.hide()
        localBrowsers.open(key, workspace, selectedTerminal?.id.takeIf { selectedWorkspace?.id == workspace.id && code == source.mac.code },
            LocalBrowserNavigation.canCreate(source.availability == NativeFeedAvailability.CONNECTED, source.capabilities),
            create = { localBrowserCreatedPanel(feedCoordinator.workspaceAction(source.mac, workspace.id, "browser.create"), workspace.id) },
            stillCurrent = { signedIn && store.taskSession() == browserLogin && teamState.scope == owner && browserNavigationContext() == entryContext &&
                store.pairedMacs().contains(source.mac) && connection.allowsSaved(source.mac) &&
                localBrowserWorkspacePresent(feedCoordinator.sources.value[source.mac.origin], workspace.id) },
            onLocal = {
                workspaceRoute = null; inAppNotification = null
                selectedTerminal = null; selectedWorkspace = null; selectedSurface = null; selectedBrowser = null; selectedChangesWorkspace = null
                finishSearch(); showSettings = false; showTaskComposer = false; error = null
            }, onRemote = { panel ->
                inAppNotification = null; workspaceRoute = NativeWorkspaceRoute(source.mac.origin, workspace.id, browserId = panel)
            })
    }
    fun createTerminal(source: NativeFeedSource, workspace: NativeWorkspace) {
        if (creatingTerminal) return
        val entryNavigation = navigationGeneration.observe(browserNavigationContext())
        val entryLogin = browserLogin
        val entryOwner = teamState.scope
        fun stillCurrent() = signedIn && store.taskSession() == entryLogin && teamState.scope == entryOwner &&
            navigationGeneration.matches(entryNavigation, browserNavigationContext()) &&
            store.pairedMacs().contains(source.mac) && connection.allowsSaved(source.mac)
        creatingTerminal = true
        workspaceTabs.cancel()
        scope.launch {
            try {
                if (!stillCurrent()) return@launch
                val response = feedCoordinator.workspaceAction(source.mac, workspace.id, "terminal.create")
                if (!stillCurrent()) return@launch
                val id = response.opt("created_terminal_id") as? String
                check(!id.isNullOrBlank()) { "Mac did not return the created terminal." }
                val returned = parseAuthoritativeWorkspaces(response).singleOrNull { it.id == workspace.id }
                val created = returned?.let { workspaceSnapshots.createdWorkspace(source.mac, it) }
                check(created?.terminals?.any { it.id == id } == true) { "Mac did not return the created terminal workspace." }
                inAppNotification = null; error = null
                workspaceRoute = NativeWorkspaceRoute(source.mac.origin, workspace.id, terminalId = id,
                    createdWorkspace = created, createdAtMillis = android.os.SystemClock.elapsedRealtime())
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (stillCurrent()) error = failure.message
            } finally { creatingTerminal = false }
        }
    }
    val feedSources by feedCoordinator.sources.collectAsState()
    fun workspaceSourceForPane(): NativeFeedSource? = pairedMacs.singleOrNull { it.code == code }?.let { mac ->
        feedSources[mac.origin] ?: NativeFeedSource(mac)
    }
    LaunchedEffect(feedSources) { feedSources.values.forEach(localBrowsers::observeWorkspaces) }
    val workspaceMoves = feedSession.workspaceMoves
    val moveSources by workspaceMoves.sources.collectAsState()
    val moveStatus by workspaceMoves.status.collectAsState()
    LaunchedEffect(moveStatus) { moveStatus.values.mapNotNull { it.error }.firstOrNull()?.let { error = it } }
    var selectedComputerOrigin by rememberSaveable(signedIn) {
        mutableStateOf(store.load()?.optString("computer_selection").orEmpty())
    }
    val selectedComputer = pairedMacs.firstOrNull { it.ownsOrigin(selectedComputerOrigin) }
    val selectedOrigin = selectedComputer?.origin
    fun selectComputer(mac: NativeCredentialStore.PairedMac?) {
        screenResume.cancel()
        selectedComputerOrigin = mac?.origin.orEmpty()
        store.update { it.put("computer_selection", selectedComputerOrigin) }
        if (mac != null) code = mac.code
        workspaceRoute = null
        computerMenuOpen = false
    }
    var computerDetails by remember { mutableStateOf<NativeComputerDetailsPresentation?>(null) }
    val forgetCallbacks = NativeComputerForgetCallbacks(started = { owner, target, rows ->
        if (teamState.scope == owner && NativeComputerForgetLocal.ownsForeground(code, owner, target, rows)) {
            // Stop this foreground handshake/reconnect before remote removal, so it
            // cannot persist the old pairing after the confirmed cleanup commits.
            code = ""; connectionReady = false; client?.close(); client = null; connectedCode = null
            selectedTerminal = null; selectedWorkspace = null; selectedSurface = null; selectedBrowser = null; selectedChangesWorkspace = null
            workspaceRoute = null
        }
    }, finished = { savedPairedMacs = store.pairedMacs() })
    fun newTaskDraft() {
        taskDraftRepository?.templates?.state?.value?.lastOrigin?.let { origin ->
            pairedMacs.firstOrNull { it.ownsOrigin(origin) }?.let(::selectComputer)
        }
        taskDraftId = java.util.UUID.randomUUID().toString()
        showTaskComposer = true
    }
    LaunchedEffect(pairedMacs, selectedComputerOrigin) {
        feedSession.taskModels.retainOrigins(pairedMacs.flatMap { it.origins }.toSet())
        if (selectedComputerOrigin.isNotBlank() && selectedComputer == null) selectComputer(null)
    }
    val canCreateOnCurrentMac = connectionReady && client != null && connectedCode == code &&
        (selectedComputer == null || selectedComputer.code == connectedCode)
    val computerConnections = nativeComputerConnections(pairedMacs, feedSources,
        activeCode = connectedCode.takeIf { connectionReady && client != null && it == code },
        pendingCode = code.takeIf { signedIn && it.isNotBlank() && !connectionReady && busy },
        foregroundWorkspaces = workspaces)
    NativeComputerDetailsPresentationHost(sharedConnections?.native, computerState, computerDetails,
        computerDetails?.target?.let { computerConnections[NativeMacIdentity(it.deviceId, it.buildTag)] } ?: NativeComputerConnection(),
        forgetCallbacks) { computerDetails = null }
    val scopedFeedSources = remember(feedSources, selectedOrigin) {
        feedSources.values.filter { selectedOrigin == null || it.mac.origin == selectedOrigin }
    }
    val feedEntries = remember(feedSources, selectedOrigin, appearances) {
        aggregateNativeFeed(feedSources.values, selectedOrigin, appearances::name)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visibleNotificationMac = if (localBrowser != null)
        pairedMacs.singleOrNull { it.ownsOrigin(localBrowser.key.computerId) }
        else pairedMacs.singleOrNull { it.code == code }
    ObserveNativeNotificationSelection(lifecycle,
        if (displayedTab == null || browserLogin == null || visibleNotificationMac == null ||
            pendingPairingCode != null || screenResume.pending != null || showSshComputers || showLicenses) null
        else NativeNotificationSelection(browserLogin, visibleNotificationMac.origin, displayedTab.first.workspaceId,
            displayedTab.second?.takeIf { it.kind == NativeWorkspaceTabKind.TERMINAL }?.id))
    var feedForeground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle, sharedConnections) {
        val probeOwner = Any()
        sharedConnections?.setProbeActive(probeOwner, feedForeground)
        val observer = LifecycleEventObserver { _, _ ->
            feedForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            sharedConnections?.setProbeActive(probeOwner, feedForeground)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sharedConnections?.setProbeActive(probeOwner, false) }
    }
    LaunchedEffect(signedIn, pairedMacs, feedForeground, computerState.connectionKeys, computerState.localConnectionKeys) {
        if (!signedIn) feedSession.clear()
        else feedSession.configureFeed(pairedMacs, computerState.connectionKeys, computerState.localConnectionKeys, feedForeground)
    }
    DisposableEffect(feedCoordinator) { onDispose { feedSession.leaveMainScreen() } }
    var unreadNotificationsOnly by rememberSaveable(signedIn) { mutableStateOf(false) }
    var notificationFilterMenu by remember { mutableStateOf(false) }
    var confirmReadAll by remember { mutableStateOf(false) }
    var pendingReadAllOrigin by remember { mutableStateOf<String?>(null) }
    var pendingReadAllComputer by remember { mutableStateOf("All Computers") }
    var readAllBusy by remember { mutableStateOf(false) }
    var feedRefreshing by remember { mutableStateOf(false) }
    var changingNotifications by remember { mutableStateOf<Set<String>>(emptySet()) }
    var feedRowWindow by rememberSaveable(notificationQuery, unreadNotificationsOnly, selectedOrigin) { mutableIntStateOf(300) }
    val feedProjection = feedSession.projection
    fun refreshFeed() {
        if (feedRefreshing) return
        scope.launch {
            feedRefreshing = true
            try { feedCoordinator.refresh() } finally { feedRefreshing = false }
        }
    }
    fun setNotificationRead(entry: NativeFeedEntry, read: Boolean) {
        if (entry.id in changingNotifications) return
        changingNotifications += entry.id
        scope.launch {
            try { feedCoordinator.setRead(entry, read); error = null }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message
            } finally { changingNotifications -= entry.id }
        }
    }
    if (confirmReadAll) AlertDialog(onDismissRequest = { confirmReadAll = false },
        title = { Text("Mark all notifications as read?") },
        text = { Text("This marks all notifications for $pendingReadAllComputer as read, including those hidden by search. Offline computers keep their unread notifications.") },
        confirmButton = { TextButton(onClick = {
            confirmReadAll = false; readAllBusy = true
            scope.launch {
                try { feedCoordinator.markAllRead(pendingReadAllOrigin); error = null }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message
                } finally { readAllBusy = false }
            }
        }) { Text("Mark All Read") } },
        dismissButton = { TextButton(onClick = { confirmReadAll = false }) { Text("Cancel") } })

    LaunchedEffect(notificationTab) {
        if (notificationTab) while (true) {
            notificationNow = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val searchLocale = configuration.locales[0]
    val workspaceSources = remember(pairedMacs, feedSources, moveSources, selectedOrigin, connectedCode, client, workspaces, groups, hostCapabilities) {
        pairedMacs.filter { selectedOrigin == null || it.origin == selectedOrigin }.map { mac ->
            val snapshot = moveSources[mac.origin] ?: feedSources[mac.origin]
            if (snapshot?.hasWorkspaceSnapshot == true) snapshot
            else if (client != null && connectedCode == mac.code) NativeFeedSource(mac, workspaces = workspaces,
                groups = groups, capabilities = hostCapabilities, availability = NativeFeedAvailability.CONNECTED,
                hasWorkspaceSnapshot = true)
            else snapshot ?: NativeFeedSource(mac)
        }
    }
    val workspaceSearch = remember(workspaceSources, searchLocale, appearances) {
        NativeSearchIndex(workspaceSources.flatMap { source ->
            val groupNames = source.groups.associate { it.id to it.name }
            source.workspaces.map { workspace -> workspaceSearchId(source, workspace) to
                (listOf(workspace.title, workspace.description, workspace.directory, workspace.preview,
                    source.mac.name, appearances.name(source.mac), groupNames[workspace.groupId]) + workspace.terminals.map { it.title }) }
        }, searchLocale)
    }
    val notificationSearch = remember(feedEntries, searchLocale) {
        NativeSearchIndex(feedEntries.map { it.id to it.searchFields() }, searchLocale, notification = true)
    }
    LaunchedEffect(feedEntries, notificationSearch, notificationQuery, unreadNotificationsOnly, notificationNow, feedRowWindow) {
        feedSession.projection = NativeFeedProjection.build(feedEntries, unreadNotificationsOnly,
            notificationSearch.matches(notificationQuery), java.time.ZoneId.systemDefault(), feedRowWindow, feedProjection)
    }
    LaunchedEffect(notificationTab) { if (notificationTab) refreshFeed() }
    val draftRepository = remember(context) { TerminalDraftRepository.get(context) }
    val drafts = draftRepository.drafts
    val draftStates by drafts.state.collectAsState()
    val draftSaveError by draftRepository.saveError.collectAsState()
    val draftTarget = selectedWorkspace?.let { workspace -> selectedTerminal?.let { terminal ->
        TerminalDrafts.Target(code, workspace.id, terminal.id)
    } }
    val terminalDraft = draftStates[draftTarget] ?: TerminalDrafts.Draft()
    var grid by remember(draftTarget, client) { mutableStateOf<TerminalDisplay>(RenderGrid()) }
    var gridRevision by remember { mutableIntStateOf(0) }
    var replayGeneration by remember { mutableIntStateOf(0) }
    var terminalTransport by remember { mutableStateOf(TerminalTransport.resolve(emptySet())) }
    var scrollPosition by remember { mutableDoubleStateOf(0.0) }
    val scrollViewport = TerminalScrollViewport.at(scrollPosition, grid.historyLineCount, grid.activeScreen)
    val scrollOffset = scrollViewport.rowOffset
    var terminalClick by remember { mutableStateOf<((TerminalGeometry.Cell) -> Unit)?>(null) }
    var terminalScroll by remember { mutableStateOf<((Double, TerminalGeometry.Cell) -> Boolean)?>(null) }
    var cancelQueuedScroll by remember { mutableStateOf<(() -> Unit)?>(null) }
    var scrollInteractionEpoch by remember { mutableIntStateOf(0) }
    val terminalMotion = rememberTerminalScrollMotion(draftTarget, client)
    fun stopTerminalScrolling() { terminalMotion.stop(); scrollInteractionEpoch++; cancelQueuedScroll?.invoke() }
    var textSnapshot by remember(draftTarget, client) { mutableStateOf<TerminalTextSnapshot?>(null) }
    fun openTerminalText() { stopTerminalScrolling(); textSnapshot = TerminalTextSnapshot.capture(grid) }
    textSnapshot?.let { TerminalTextSheet(it) { textSnapshot = null } }
    val filesMemory = rememberSaveable(saver = TerminalFilesMemory.saver) { TerminalFilesMemory() }
    val filesKey = draftTarget?.let { target -> pairedMacs.singleOrNull { it.code == code }?.let {
        workspaceTabKey(browserLogin, teamState.scope, it, target.workspace)
    } }
    val emptyFilesState = remember { TerminalFilesState() }
    val filesState = if (connectionReady && connectedCode == code && browserLogin != null && filesKey != null && draftTarget != null)
        filesMemory.bind(browserLogin, filesKey, draftTarget.surface) else emptyFilesState
    SideEffect {
        filesMemory.retainLogin(browserLogin)
        if (draftTarget == null && screenResume.pending == null && workspaceRoute?.resume == null) filesMemory.clear()
    }
    var showTerminalFiles by filesState::showing
    val terminalArtifactPath = filesState.path
    val artifactRpc = remember(client, hostCapabilities) { client?.let { ArtifactRpc(it, hostCapabilities) } }
    val artifactPreferences = remember(context) { context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE) }
    var folderTapEnabled by remember(artifactPreferences) { mutableStateOf(artifactPreferences.getBoolean("terminal-folder-tap", true)) }
    var showMissingArtifacts by remember(artifactPreferences) { mutableStateOf(artifactPreferences.getBoolean("show-missing-files", false)) }
    DisposableEffect(artifactPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == "show-missing-files") showMissingArtifacts = preferences.getBoolean(key, false)
            if (key == "terminal-folder-tap") folderTapEnabled = preferences.getBoolean(key, true)
        }
        artifactPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { artifactPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val artifactsReady = selectedTerminal?.isReady == true && connectionReady && connectedCode == code && artifactRpc?.capabilities?.terminal == true && draftTarget != null
    val artifactController = remember(artifactRpc, draftTarget, artifactsReady, showMissingArtifacts) {
        if (artifactsReady) TerminalArtifactController(scope, artifactRpc!!,
            ArtifactAuthorization.Terminal(draftTarget!!.workspace, draftTarget.surface), showMissingArtifacts) else null
    }
    DisposableEffect(artifactController) { onDispose { artifactController?.close() } }
    LaunchedEffect(artifactController) {
        val controller = artifactController ?: return@LaunchedEffect
        snapshotFlow {
            val viewport = TerminalScrollViewport.at(scrollPosition, grid.historyLineCount, grid.activeScreen)
            Triple(gridRevision, viewport.rowOffset, viewport.topClipFraction > 0)
        }.collectLatest {
            val viewport = TerminalScrollViewport.at(scrollPosition, grid.historyLineCount, grid.activeScreen)
            controller.observe(RenderGrid.plainText(viewport.lines(grid)))
        }
    }
    val artifactTapController = remember(artifactRpc, draftTarget, grid, artifactsReady, folderTapEnabled) { TerminalArtifactTapController(scope) }
    DisposableEffect(artifactTapController) { onDispose { artifactTapController.close() } }
    val artifactChipCount = artifactController?.count?.collectAsState()?.value
    val artifactRefresh = artifactController?.galleryRefresh?.collectAsState()?.value ?: 0
    if (terminalArtifactPath != null && artifactsReady && artifactRpc != null && draftTarget != null) {
        ArtifactPathSheet(artifactRpc, ArtifactAuthorization.Terminal(draftTarget.workspace, draftTarget.surface), terminalArtifactPath!!, navigation = filesState.direct) {
            filesState.closePath()
        }
    }
    if (showTerminalFiles && artifactsReady && artifactRpc != null && draftTarget != null) {
        ArtifactFilesSheet(artifactRpc, ArtifactAuthorization.Terminal(draftTarget.workspace, draftTarget.surface), artifactRefresh, navigation = filesState.gallery) {
            filesState.closeGallery()
        }
    }

    var showShortcuts by remember(draftTarget, client) { mutableStateOf(false) }
    if (showShortcuts) TerminalToolbarSettings(toolbarStore) { showShortcuts = false }
    var inputModifiers by remember(draftTarget, client) { mutableStateOf(TerminalInputModifiers()) }
    var directTyping by remember(draftTarget) { mutableStateOf(false) }
    var rawKeyboardView by remember(draftTarget) { mutableStateOf<TerminalKeyboardView?>(null) }
    fun openDirectKeyboard() {
        // Hardware keys may arrive before Compose mounts the native editor. The existing
        // grid must own focus immediately; its key handler uses the same ordered input path.
        terminalFocusRequester.requestFocus()
        directTyping = true
        rawKeyboardView?.showKeyboard()
    }
    LaunchedEffect(directTyping, rawKeyboardView) {
        if (directTyping) rawKeyboardView?.let { view ->
            // Let the removed Compose editor finish its IME session before requesting the native editor.
            withFrameNanos { }
            view.showKeyboard()
        }
    }
    val hardwareInput = remember(draftTarget, client) { TerminalHardwareInput() }
    val inputClient = client
    val inputTarget = draftTarget
    var outputInput by remember(inputClient, inputTarget) { mutableStateOf<TerminalOutputLaneOwner?>(null) }
    val nativeInput = remember(inputClient, inputTarget, terminalTransport.mode, connectionReady, selectedTerminal?.isReady) {
        if (inputClient != null && inputTarget != null && selectedTerminal?.isReady == true && connectionReady && terminalTransport.mode == TerminalOutputMode.GRID)
            TerminalInputLaneOwner(scope, onAcknowledgement = { terminalInputs.receive(inputClient, inputTarget.surface, it) }) { use ->
                inputClient.useTerminalInputLane(inputTarget.surface, use)
            }
        else null
    }
    DisposableEffect(nativeInput) {
        val registration = if (nativeInput != null && inputClient != null && inputTarget != null)
            terminalInputs.registerLane(inputClient, inputTarget.workspace, inputTarget.surface, nativeInput.ready, nativeInput::sendIdentified) else null
        onDispose { registration?.close(); nativeInput?.close() }
    }
    val retainedInputQueue = remember(inputClient, inputTarget, hostCapabilities) {
        if (inputClient != null && inputTarget != null) terminalInputs.orderedQueue(inputClient, inputTarget.workspace, inputTarget.surface) else null
    }
    val inputQueue = remember(inputClient, inputTarget, nativeInput, retainedInputQueue) {
        retainedInputQueue ?: TerminalInputQueue(scope) { entry ->
            check(TerminalInputDelivery.CAPABILITY !in hostCapabilities) { "Could not reserve this terminal's input queue" }
            check(inputClient != null && inputTarget != null && client === inputClient &&
                code == inputTarget.pairing && signedIn && selectedTerminal?.id == inputTarget.surface && selectedTerminal?.isReady == true) { "Terminal connection changed" }
            if (entry.paste) inputClient.paste(inputTarget.workspace, inputTarget.surface, entry.text, submit = false)
            else if (nativeInput?.send(entry.text) != true && outputInput?.send(entry.text) != true)
                inputClient.input(inputTarget.workspace, inputTarget.surface, entry.text)
        }
    }
    ObservePhoneReplyDirect(lifecycle) { target ->
        val mac = pairedMacs.singleOrNull { it.ownsOrigin(target.origin) }
        fun admitted() = feedForeground && signedIn && store.taskSession() == target.team.login &&
            teamState.scope?.let { accountTeams.isCurrent(it) && it.login == target.team.login &&
                it.userId == target.team.userId && it.teamId == target.team.teamId } == true &&
            mac != null && store.pairedMacs().contains(mac) && connection.allowsSaved(mac)
        if (mac == null || !admitted()) null
        else if (mac.code == code) {
            val active = client
            val workspace = target.resolve(workspaces)
            fun ready() = admitted() && active != null && client === active && !active.isClosed &&
                connectionReady && connectedCode == mac.code && code == mac.code && target.resolve(workspaces) == workspace
            if (active == null || workspace == null || !ready()) null else {
                val queue = if (inputClient === active && inputTarget?.workspace == workspace && inputTarget?.surface == target.surface)
                    inputQueue else terminalInputs.orderedQueue(active, workspace, target.surface)
                PhoneReplyDirectAttempt(target, ::ready) { text, allowed ->
                    suspend fun paste(): Boolean {
                        if (!allowed()) return false
                        checkPhoneReplyPaste(active.paste(workspace, target.surface, text, submit = true))
                        return true
                    }
                    if (queue != null) queue.performOrdered { paste() } else paste()
                }
            }
        } else feedCoordinator.replyAttempt(mac, target, ::admitted)
    }
    val inputStatus by inputQueue.status.collectAsState()
    DisposableEffect(inputQueue) { onDispose { if (inputQueue !== retainedInputQueue) inputQueue.close() } }
    val deliveryStates by terminalInputs.status.collectAsState()
    val deliveryKey = inputTarget?.let { terminalInputs.key(inputClient, it.workspace, it.surface) }
    val deliveryState = deliveryKey?.let(deliveryStates::get)
    val inputFailure = inputStatus.error ?: deliveryState?.failure?.let {
        if (it == TerminalInputSender.Failure.UNSUPPORTED) "Input support changed on your Mac. Check the terminal before resuming."
        else "Typing paused. Delivery was not confirmed. Check the terminal before resuming."
    }

    fun queueInput(value: String, paste: Boolean = false): Boolean {
        val target = draftTarget ?: return false
        if (client == null || inputFailure != null || selectedTerminal?.isReady != true || drafts.state.value[target]?.operation != null) return false
        stopTerminalScrolling(); scrollPosition = 0.0
        return inputQueue.offer(value, paste)
    }
    fun directText(value: String) {
        val text = value.replace("\r\n", "\r").replace('\n', '\r')
        queueInput(inputModifiers.text(text))
        inputModifiers = inputModifiers.consume()
    }
    fun directDelete(before: Int, after: Int) {
        if (before == 0 && after == 0) return
        queueInput(inputModifiers.special("Backspace").repeat(before) + inputModifiers.special("Delete").repeat(after))
        inputModifiers = inputModifiers.consume()
    }
    fun directHardware(event: AndroidKeyEvent): Boolean {
        val armed = inputModifiers.armed
        val sequence = hardwareInput.sequence(event, grid.applicationCursorKeys,
            armed == TerminalInputModifiers.Key.CONTROL, armed == TerminalInputModifiers.Key.ALT,
            armed == TerminalInputModifiers.Key.SHIFT, armed == TerminalInputModifiers.Key.COMMAND) ?: return false
        if (sequence.isNotEmpty()) {
            queueInput(sequence)
            inputModifiers = inputModifiers.consume()
        }
        return true
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runCatching { NativeNotificationService.setEnabled(context, true) }
            .onSuccess { backgroundNotifications = true; error = null }
            .onFailure { error = it.message }
        else error = "Allow notifications to receive cmux updates in the background"
    }

    LaunchedEffect(signedIn) { if (!signedIn) { drafts.clear(); inAppNotification = null } }

    val attachmentFiles = remember(context) { AttachmentFiles(context.applicationContext) }
    var pickerTarget by remember { mutableStateOf<TerminalDrafts.Target?>(null) }
    var pickerGeneration by remember { mutableLongStateOf(0) }
    var pickerImages by remember { mutableStateOf(false) }
    var preparingAttachments by remember { mutableStateOf(false) }
    var attachmentMenu by remember { mutableStateOf(false) }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pickerTarget
        val generation = pickerGeneration
        val images = pickerImages
        pickerTarget = null
        if (target != null && uris.isNotEmpty()) scope.launch {
            preparingAttachments = true
            try {
                require(uris.size <= 10) { "Choose up to 10 attachments" }
                for (uri in uris) {
                    val prepared = attachmentFiles.prepare(uri, images)
                    check(signedIn && code == target.pairing && drafts.generation == generation &&
                        workspaces.any { it.id == target.workspace && it.terminals.any { terminal -> terminal.id == target.surface } }) {
                        "The attachment target changed. Choose the attachment again."
                    }
                    draftRepository.attach(target, prepared, generation)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not open the attachment"
            } finally { preparingAttachments = false }
        }
    }

    fun acceptTerminalPaste(content: TerminalPasteContent): Boolean {
        inputModifiers = TerminalInputModifiers()
        val target = draftTarget ?: return false
        val active = client ?: return false
        if (preparingAttachments || terminalDraft.operation != null || inputFailure != null) return false
        val generation = drafts.generation
        val items = content.items
        if (items.all { it is TerminalPasteContent.Item.Text }) {
            val accepted = queueInput(items.joinToString("\n") { (it as TerminalPasteContent.Item.Text).value }, paste = true)
            if (accepted) content.close()
            return accepted
        }
        if (items.any { it is TerminalPasteContent.Item.Attachment && !it.image } &&
            ComposerAttachment.FILE_CAPABILITY !in hostCapabilities) {
            error = "Update cmux on your Mac to paste files"
            return false
        }
        val retainedTarget = deliveryKey.takeIf { retainedInputQueue != null }
        fun checkTarget() {
            val admitted = if (retainedTarget != null) terminalInputs.allowsTarget(retainedTarget, target.workspace)
                else signedIn && client === active && code == target.pairing &&
                    workspaces.any { it.id == target.workspace && it.terminals.any { terminal -> terminal.id == target.surface } }
            check(admitted && drafts.generation == generation) {
                "The paste target changed. Paste again in the intended terminal."
            }
        }
        if (directTyping) {
            stopTerminalScrolling(); scrollPosition = 0.0
            val accepted = inputQueue.offerAction(release = content::close) {
                for (item in items) {
                    checkTarget()
                    when (item) {
                        is TerminalPasteContent.Item.Text -> active.paste(target.workspace, target.surface, item.value, submit = false)
                        is TerminalPasteContent.Item.Attachment -> {
                            val prepared = attachmentFiles.prepare(item.uri, item.image)
                            checkTarget()
                            if (prepared.attachment.imageFormat != null) {
                                active.pasteImage(target.workspace, target.surface, prepared.bytes, prepared.attachment.imageFormat)
                            } else {
                                val path = active.uploadAttachment(prepared.attachment, prepared.bytes, ::checkTarget)
                                checkTarget()
                                active.paste(target.workspace, target.surface, ComposerAttachment.withPaths(listOf(path), ""), submit = false)
                            }
                        }
                    }
                }
            }
            if (!accepted) error = "Could not queue the paste. Wait for pending input and try again."
            return accepted
        }
        // Composer paste uses the same encrypted storage and captured target as picked attachments.
        preparingAttachments = true
        scope.launch {
            try {
                for (item in items) {
                    checkTarget()
                    when (item) {
                        is TerminalPasteContent.Item.Text -> drafts.edit(target, (drafts.state.value[target]?.text ?: "") + item.value)
                        is TerminalPasteContent.Item.Attachment -> {
                            val prepared = attachmentFiles.prepare(item.uri, item.image)
                            checkTarget()
                            draftRepository.attach(target, prepared, generation)
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Could not open the pasted attachment"
            } finally { preparingAttachments = false }
        }.invokeOnCompletion { content.close() }
        return true
    }

    fun pasteClipboard() {
        inputModifiers = TerminalInputModifiers()
        rawKeyboardView?.finishComposition()
        try {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return
            val content = TerminalPasteContent.fromClipboard(context, clip)
            var accepted = false
            try { accepted = acceptTerminalPaste(content) }
            finally { if (!accepted) content.close() }
        } catch (failure: Exception) { error = failure.message ?: "Could not read the clipboard" }
    }

    fun sendComposer(submit: Boolean) {
        val target = draftTarget ?: return
        val active = client ?: return
        if (preparingAttachments || inputFailure != null || selectedTerminal?.isReady != true) return
        val send = drafts.begin(target) ?: return
        val supportsFiles = ComposerAttachment.FILE_CAPABILITY in hostCapabilities
        stopTerminalScrolling(); scrollPosition = 0.0
        val retainedKey = deliveryKey.takeIf { retainedInputQueue != null }
        if (retainedKey != null) {
            // The session queue survives rotation; callbacks capture ownership, never UI selection.
            queueTerminalComposer(inputQueue, drafts, send, draftRepository::persistNow) {
                deliverTerminalComposer(active, drafts, send, submit, supportsFiles,
                    read = draftRepository::read, persist = draftRepository::persistNow,
                    isCurrent = { terminalInputs.allowsTarget(retainedKey, target.workspace) },
                    resolveClient = { terminalInputs.clientForTarget(retainedKey, target.workspace) })
            }
            return
        }
        scope.launch {
            try {
                inputQueue.awaitIdle()
                draftRepository.persistNow()
                check(client === active && signedIn && code == target.pairing && selectedTerminal?.id == target.surface && selectedTerminal?.isReady == true) { "Connection changed" }
                val deliveredFiles = deliverTerminalComposer(active, drafts, send, submit, supportsFiles,
                    read = draftRepository::read, persist = draftRepository::persistNow,
                    isCurrent = { client === active && signedIn && code == target.pairing && selectedTerminal?.id == target.surface && selectedTerminal?.isReady == true })
                drafts.finish(send, deliveredFiles = deliveredFiles)
                draftRepository.persistNow()
                if (selectedTerminal?.id == target.surface) scrollPosition = 0.0
            } catch (failure: Exception) {
                drafts.finish(send, TerminalDrafts.DELIVERY_UNCONFIRMED)
                if (failure is CancellationException) throw failure
            }
        }
    }

    fun applyListing(snapshot: NativeWorkspaceSnapshot) {
        if (!snapshot.accept()) throw NativeWorkspaceSnapshotSuperseded()
        val value = snapshot.value
        val owner = pairedMacs.singleOrNull { it.code == code }
        val updated = snapshot.workspaces.map { workspace ->
            val key = owner?.let { workspaceTabKey(browserLogin, teamState.scope, it, workspace.id) }
            if (key != null) workspaceTabs.withDiscoveredBrowsers(key, workspace) else workspace
        }
        workspaces = updated
        client?.let { terminalInputs.updateTargets(it, inputTargets(updated)) }
        if (value.has("groups")) { groups = parseGroups(value); taskGroupsLoaded = true }
        selectedChangesWorkspace = selectedChangesWorkspace?.let { previous -> updated.firstOrNull { it.id == previous.id } }
        selectedWorkspace?.let { previous ->
            val current = updated.firstOrNull { it.id == previous.id }
            selectedWorkspace = current
            val tabKey = owner?.let { workspaceTabKey(browserLogin, teamState.scope, it, previous.id) }
            val previousTerminal = selectedTerminal
            val pane = if (tabKey != null && previousTerminal != null)
                terminalStartup.reconcile(tabKey, current, previousTerminal) else null
            selectedTerminal = if (previousTerminal != null && tabKey != null) pane?.terminal
                else previousTerminal?.let { terminal -> current?.terminals?.firstOrNull { it.id == terminal.id } }
            if (previousTerminal != null && pane?.terminal == null) {
                selectedBrowser = pane?.browser; selectedSurface = pane?.surface
            }
            // Also retire a timeout banner when its terminal becomes ready after fallback.
            if (tabKey != null && previousTerminal == null) terminalStartup.reconcile(tabKey, current, null)
            selectedBrowser = selectedBrowser?.let { browser ->
                current?.browsers?.firstOrNull { it.id == browser.id }
            }
            selectedSurface = selectedSurface?.let { surface -> current?.macSurfaces?.firstOrNull { it.id == surface.id } }
            if (current != null && selectedChangesWorkspace == null && selectedTerminal == null && selectedBrowser == null && selectedSurface == null &&
                workspaceTabs.pending.value?.tab == null && !creatingTerminal) {
                current.defaultPane(tabKey?.let(workspaceTabs::browsers).orEmpty())?.let { fallback ->
                    workspaceTabs.cancel()
                    selectedTerminal = fallback.terminal; selectedBrowser = fallback.browser; selectedSurface = fallback.surface
                }
            }
            // A host refresh may replace the interim pane while a remembered tab is still loading.
            // Only an explicit picker action should cancel that intent.
            workspaceTabs.pending.value?.takeIf { it.key == tabKey && it.login == browserLogin }?.let { ticket ->
                workspaceTabs.refreshInterim(ticket, workspaceTabDisplay(browserLogin, teamState.scope,
                    pairedMacs, code, selectedWorkspace, selectedTerminal, selectedBrowser, selectedSurface, null)?.second)
            }
        }
    }

    fun applyCreatedWorkspace(mac: NativeCredentialStore.PairedMac, result: TaskCreationResult): NativeWorkspace {
        val created = workspaceSnapshots.createdWorkspace(mac, result.created)
        val latest = workspaceSnapshots.latest(mac)
        if (latest != null) applyListing(latest) else workspaces = result.merge(workspaces)
        return created
    }

    fun proposePairing(value: String) {
        PairingCodeParser.parse(value).fold(
            onSuccess = { pairing ->
                if (pairing is PairingCode.Tailscale) {
                    pendingPairingCode = value.trim()
                    error = null
                } else if (pairing is PairingCode.Iroh) {
                    val team = computerState.account
                    val mac = computerState.computers.singleOrNull { it.endpointId == pairing.endpointId &&
                        (pairing.macDeviceId == null || pairing.macDeviceId.equals(it.deviceId, ignoreCase = true)) }
                    if (team != null && mac != null && computerState.ready && connection.allowsSaved(pairing)) {
                        code = PairingCodeParser.computer(mac, team)
                        error = null
                    } else error = "This Mac is not available in your selected team. Check its Mobile settings and refresh Computers."
                }
            },
            onFailure = { error = it.message }
        )
    }

    val handlePairing by rememberUpdatedState(onPairingHandled)
    LaunchedEffect(incomingCode, signedIn, code, pairedMacs, teamState.scope, computerState) {
        val incoming = incomingCode ?: return@LaunchedEffect
        pendingPairingCode = null
        val action = incomingPairingAction(incoming, signedIn,
            alreadySelected = incoming == code && pairedMacs.any { it.code == code }, teamState.scope, computerState)
        when (action) {
            NativePairingLinkAction.Wait -> return@LaunchedEffect
            NativePairingLinkAction.Consumed -> Unit
            NativePairingLinkAction.Confirm -> { pendingPairingCode = incoming; error = null }
            NativePairingLinkAction.Unavailable -> error = "This Mac is not available in your selected team. Check its Mobile settings and refresh Computers."
            is NativePairingLinkAction.Select -> {
                if (connection.allowsSaved(PairingCodeParser.parse(action.code).getOrThrow())) {
                    pendingPairingCode = null; code = action.code; error = null
                } else error = "This Mac is not available in your selected team."
            }
        }
        handlePairing(incoming)
    }
    LaunchedEffect(currentIncomingRoute, showSettings, showTaskComposer) {
        if (currentIncomingRoute != null || showSettings || showTaskComposer) localBrowsers.leave(close = false)
    }
    LaunchedEffect(incomingNotificationRoute) { if (incomingNotificationRoute != null) { pendingPairingCode = null; inAppNotification = null; workspaceRoute = null } }
    val routeClient = client
    val routeConnectedCode = connectedCode
    val routePairingCode = code
    val routeSignedIn = signedIn
    val routeInAppNotification = inAppNotification
    LaunchedEffect(incomingNotificationRoute, routeInAppNotification?.routeId, routeSignedIn,
        routePairingCode, routeConnectedCode, routeClient, teamState.scope, pairedMacs) {
        val routeId = incomingNotificationRoute ?: routeInAppNotification?.routeId ?: return@LaunchedEffect
        val openedFromFeed = incomingNotificationRoute == null
        fun consumeRoute() {
            if (openedFromFeed) { if (inAppNotification?.routeId == routeId) inAppNotification = null }
            else handleNotification(routeId)
        }
        if (!routeSignedIn || currentIncomingRoute != routeId || (connector == null && teamState.scope == null)) return@LaunchedEffect
        val route = if (openedFromFeed) routeInAppNotification else notificationDelivery.destination(routeId)
        val mac = store.pairedMacs().singleOrNull { it.ownsOrigin(route?.origin) && connection.allowsSaved(it) }
        if (route == null || mac == null) {
            error = "This notification's saved Mac is no longer available."
            consumeRoute()
            return@LaunchedEffect
        }
        if (routePairingCode != mac.code) {
            store.update { it.put("pairing_code", mac.code) }
            showSettings = false; selectedWorkspace = null; selectedSurface = null; selectedTerminal = null; selectedBrowser = null
            code = mac.code
            return@LaunchedEffect
        }
        // Use the session captured with the effect keys. Reading mutable client here
        // can run this route twice if a handshake completes before this effect starts.
        val active = routeClient ?: return@LaunchedEffect
        if (routeConnectedCode != mac.code) return@LaunchedEffect
        fun isCurrent() = currentIncomingRoute == routeId && client === active && code == mac.code &&
            signedIn && store.pairedMacs().contains(mac) && connection.allowsSaved(mac)
        try {
            val feed = parseNotifications(active.notifications())
            val listing = workspaceSnapshots.read(mac, active)
            if (!isCurrent()) return@LaunchedEffect
            val notification = feed.firstOrNull { it.id == route.notificationId } ?: route.notification()
            val available = listing.workspaces
            val workspace = notification.destination(available)
            val exactBrowser = workspace?.browsers?.firstOrNull { it.id == notification.surfaceId }
            val surface = workspace?.macSurfaces?.firstOrNull { it.id == notification.surfaceId }
            val terminal = workspace?.terminals?.firstOrNull { it.id == notification.surfaceId }
                ?: if (exactBrowser == null && surface == null) workspace?.terminals?.firstOrNull() else null
            val browser = exactBrowser ?: if (terminal == null && surface == null) workspace?.browsers?.firstOrNull() else null
            check(workspace != null && (terminal != null || browser != null || surface != null)) {
                "This notification's workspace is no longer available."
            }
            val opened = withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (!isCurrent()) false else {
                    applyListing(listing); notifications = feed
                    finishSearch(); notificationTab = openedFromFeed; showSettings = false; showTaskComposer = false
                    showCreateGroup = false; showLicenses = false; selectedChangesWorkspace = null
                    localBrowserKey(browserLogin, teamState.scope, mac, workspace.id)?.let(localBrowsers::selectMacPane)
                    selectedWorkspace = workspace; selectedTerminal = terminal; selectedBrowser = browser; selectedSurface = surface
                    notificationDelivery.cancel(routeId)
                    true
                }
            }
            if (!opened) return@LaunchedEffect
            // Navigation is committed. A failed read acknowledgment never targets a different session.
            active.markNotificationRead(notification.id)
            if (!isCurrent()) return@LaunchedEffect
            notifications = feed.map { if (it.id == notification.id) it.copy(isRead = true) else it }
            scope.launch { feedCoordinator.refresh() }
            error = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (isCurrent()) error = failure.message ?: "Could not open this notification"
        }
        if (isCurrent()) consumeRoute()
    }

    val capturedWorkspaceRoute = workspaceRoute
    LaunchedEffect(capturedWorkspaceRoute?.id, routeSignedIn, routePairingCode, routeConnectedCode, routeClient) {
        val route = capturedWorkspaceRoute ?: return@LaunchedEffect
        if (!routeSignedIn) { screenResume.cancel(); screenResume.complete(); workspaceRoute = null; return@LaunchedEffect }
        val mac = store.pairedMacs().singleOrNull { it.ownsOrigin(route.origin) && connection.allowsSaved(it) }
        if (mac == null || (route.resume != null && !route.resume.matches(store.taskSession(), teamState.scope, mac))) {
            screenResume.cancel()
            error = "This workspace's saved Mac is no longer available."
            workspaceRoute = null
            return@LaunchedEffect
        }
        val browserKey = localBrowserKey(browserLogin, teamState.scope, mac, route.workspaceId)
        val browserWorkspace = workspaceSources.firstOrNull { it.mac.origin == mac.origin }?.workspaces?.firstOrNull { it.id == route.workspaceId }
        val explicitPane = route.terminalId != null || route.browserId != null || route.surfaceId != null || route.changes
        val tabKey = workspaceTabKey(browserLogin, teamState.scope, mac, route.workspaceId)
        val remembered = if (!explicitPane && tabKey != null && browserLogin != null)
            route.resume?.tab ?: workspaceTabs.remembered(browserLogin, tabKey) else null
        if (route.resume == null && browserKey != null && !explicitPane && browserWorkspace != null && localBrowsers.restoreFromMemory(browserKey, browserWorkspace, remembered)) {
            selectedTerminal = null; selectedWorkspace = null; selectedSurface = null; selectedBrowser = null; selectedChangesWorkspace = null
            showSettings = false; showTaskComposer = false; finishSearch(); workspaceRoute = null
            return@LaunchedEffect
        }
        if (browserKey != null && explicitPane) localBrowsers.selectMacPane(browserKey) else localBrowsers.leave(close = false)
        if (routePairingCode != mac.code) {
            store.update { it.put("pairing_code", mac.code) }
            selectedWorkspace = null; selectedSurface = null; selectedTerminal = null; selectedBrowser = null; selectedChangesWorkspace = null
            code = mac.code
            return@LaunchedEffect
        }
        val active = routeClient ?: return@LaunchedEffect
        if (routeConnectedCode != mac.code) return@LaunchedEffect
        fun isCurrent() = workspaceRoute?.id == route.id && signedIn && client === active && code == mac.code &&
            store.pairedMacs().contains(mac) && connection.allowsSaved(mac) &&
            (route.resume == null || (screenResume.pending == route.resume && route.resume.matches(store.taskSession(), teamState.scope, mac)))
        try {
            // Opening a known pane is navigation, not evidence that an inventory is current.
            // An unrelated in-flight mutation must not block that explicit user action.
            val cachedDuringMutation = browserWorkspace?.takeIf { route.createdWorkspace == null && workspaceSnapshots.hasMutation(mac) }
            val listing = if (route.createdWorkspace == null && cachedDuringMutation == null) workspaceSnapshots.read(mac, active) else null
            if (!isCurrent()) return@LaunchedEffect
            val workspace = route.createdWorkspace?.takeIf { it.id == route.workspaceId }?.let { workspaceSnapshots.createdWorkspace(mac, it) }
                ?: cachedDuringMutation
                ?: checkNotNull(listing).workspaces.singleOrNull { it.id == route.workspaceId }
            if (workspace == null) {
                if (listing != null) applyListing(listing)
                screenResume.cancel(); workspaceRoute = null
                error = "This workspace is no longer available on ${mac.name}."
                return@LaunchedEffect
            }
            if (browserKey != null && !explicitPane && localBrowsers.restoreFromMemory(browserKey, workspace, remembered)) {
                if (listing != null) applyListing(listing) else applyCreatedWorkspace(mac, TaskCreationResult(workspace, listOf(workspace))); finishSearch(); showSettings = false; showTaskComposer = false
                selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null; selectedChangesWorkspace = null
                screenResume.complete(); workspaceRoute = null; return@LaunchedEffect
            }
            listing?.requireCurrent()
            route.resume?.let { terminalStartup.restore(it, workspace, screenBootCount) }
            val restoredPin = terminalStartup.state.value.pending?.takeIf { route.resume != null && it.key == tabKey }
            val expiredPin = route.resume?.startup != null && terminalStartup.state.value.failure?.terminalId == route.resume.startup.terminalId
            val choice = when {
                restoredPin != null -> {
                    workspaceTabs.cancel()
                    NativeWorkspaceTabChoice(workspace.explicitPane(terminalId = restoredPin.terminalId))
                }
                expiredPin -> { workspaceTabs.cancel(); NativeWorkspaceTabChoice(workspace.defaultPane()) }
                !explicitPane && tabKey != null && browserLogin != null -> workspaceTabs.open(browserLogin, tabKey, workspace, remembered)
                else -> NativeWorkspaceTabChoice(workspace.paneForRoute(route))
            }
            if (choice.localBrowser && browserKey != null) {
                localBrowsers.restoreRemembered(browserKey, workspace)
                if (listing != null) applyListing(listing) else applyCreatedWorkspace(mac, TaskCreationResult(workspace, listOf(workspace))); finishSearch(); showSettings = false; showTaskComposer = false
                selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null; selectedChangesWorkspace = null
                screenResume.complete(); workspaceRoute = null; return@LaunchedEffect
            }
            val pane = choice.pane
            check(route.changes || pane != null || workspaceTabs.pending.value != null) { "This workspace pane is no longer available." }
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (isCurrent()) {
                    if (listing != null) applyListing(listing) else applyCreatedWorkspace(mac, TaskCreationResult(workspace, listOf(workspace))); finishSearch(); notificationTab = false
                    showSettings = false; showTaskComposer = false
                    selectedWorkspace = workspace; selectedTerminal = pane?.terminal; selectedBrowser = pane?.browser; selectedSurface = pane?.surface
                    selectedChangesWorkspace = if (route.changes) workspace else null
                    if (route.createdWorkspace != null && tabKey != null && pane?.terminal != null) {
                        workspaceTabs.cancel(); terminalStartup.begin(tabKey, pane.terminal, route.createdAtMillis ?: android.os.SystemClock.elapsedRealtime())
                    }
                    error = null; screenResume.complete(); workspaceRoute = null
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (isCurrent()) {
                error = failure.message ?: "Could not open this workspace"
                if (route.resume == null) workspaceRoute = null
            }
        }
    }

    NativeWorkspaceTabRecovery(workspaceTabs, pendingWorkspaceTab, client, connectionReady, hostCapabilities,
        readListing = { active -> workspaceSnapshots.read(workspaceOwner(), active) },
        isCurrent = { val pending = pendingWorkspaceTab
            pending != null && browserLogin == pending.login && store.taskSession() == browserLogin &&
            displayedTab?.first == pending.key && pairedMacs.any { it.code == code && connection.allowsSaved(it) &&
                workspaceTabKey(browserLogin, teamState.scope, it, pending.key.workspaceId) == pending.key } },
        onSnapshot = { listing ->
            applyListing(listing)
            if (selectedTerminal == null && selectedBrowser == null && selectedSurface == null && !creatingTerminal) {
                selectedWorkspace?.defaultPane()?.let { pane ->
                    selectedTerminal = pane.terminal; selectedBrowser = pane.browser; selectedSurface = pane.surface
                }
            }
            workspaceTabDisplay(browserLogin, teamState.scope, pairedMacs, code, selectedWorkspace,
                selectedTerminal, selectedBrowser, selectedSurface, null)?.second
        },
        onChoice = { listing, workspace, choice ->
            applyListing(listing); selectedWorkspace = workspaces.firstOrNull { it.id == workspace.id } ?: workspace
            selectedTerminal = choice.pane?.terminal; selectedBrowser = choice.pane?.browser; selectedSurface = choice.pane?.surface
        }, onMissing = { selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null })

    val discoveryKey = displayedTab?.first.takeIf { selectedWorkspace != null }
    NativeWorkspaceBrowserDiscovery(workspaceTabs, browserLogin, discoveryKey, client,
        connectionReady && connectedCode == code, "browser.stream.v1" in hostCapabilities && pendingWorkspaceTab == null && !creatingTerminal,
        readListing = { active -> workspaceSnapshots.read(workspaceOwner(), active) },
        isCurrent = { connectionReady && connectedCode == code && browserLogin != null && store.taskSession() == browserLogin && discoveryKey != null &&
            displayedTab?.first == discoveryKey && selectedWorkspace?.id == discoveryKey.workspaceId &&
            pairedMacs.any { it.code == code && connection.allowsSaved(it) &&
                workspaceTabKey(browserLogin, teamState.scope, it, discoveryKey.workspaceId) == discoveryKey } },
        onInventory = { listing ->
            val surfaceId = selectedSurface?.id
            applyListing(listing)
            if (surfaceId != null && selectedSurface?.id == surfaceId) {
                selectedWorkspace?.explicitPane(surfaceId = surfaceId)?.let { pane ->
                    selectedTerminal = pane.terminal; selectedBrowser = pane.browser; selectedSurface = pane.surface
                }
            }
        }, onMissing = { selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null })

    NativeTerminalStartupExpiry(terminalStartup, terminalStartupState.pending) {
        val workspace = selectedWorkspace
        val key = workspace?.let { row -> pairedMacs.singleOrNull { it.code == code }?.let {
            workspaceTabKey(browserLogin, teamState.scope, it, row.id)
        } }
        val failure = terminalStartup.state.value.failure
        if (key != null && workspace != null && failure?.key == key && selectedTerminal?.id == failure.terminalId) {
            val pane = terminalStartup.reconcile(key, workspace, selectedTerminal)
            selectedTerminal = pane?.terminal; selectedBrowser = pane?.browser; selectedSurface = pane?.surface
        }
    }

    LaunchedEffect(signedIn, code, retry,
        computerState.connectionKey(PairingCodeParser.parse(code).getOrNull() as? PairingCode.Iroh)) {
        connectionReady = false
        client?.close(); client = null; connectedCode = null
        if (!signedIn || code.isBlank()) return@LaunchedEffect
        val requestedCode = code
        busy = true
        try {
            val pairing = PairingCodeParser.parse(requestedCode).getOrThrow()
            val pairingOwner = if (sharedConnections != null) kotlinx.coroutines.withTimeout(30_000) {
                accountTeams.state.first { it.scope != null || it.error != null }.scope
                    ?: error("Refresh your account teams before connecting.")
            } else null
            val saved = store.pairedMacs().singleOrNull { it.code == requestedCode && connection.allowsSaved(it) }
            if (pairingOwner != null) check(accountTeams.isCurrent(pairingOwner)) { "Account or team changed. Reconnect to the Mac." }
            val active = if (saved != null) connection.connectSaved(saved, account) else connection.connectPairing(pairing, account)
            try {
                val status = active.hostStatus()
                require(status.optString("mac_device_id").isNotBlank()) { "The Mac did not provide its device identity." }
                store.pairedMacs().singleOrNull { it.code == requestedCode && connection.allowsSaved(it) }?.requireMatchingHost(status)
                val displayName = status.optString("mac_display_name").ifBlank { "cmux" }
                val capabilities = status.optJSONArray("capabilities")?.let { values ->
                    (0 until values.length()).mapNotNull { index ->
                        values.optString(index).takeIf { it.isNotBlank() }
                    }.toSet()
                } ?: emptySet()
                val verified = NativeCredentialStore.PairedMac(requestedCode, status.optString("mac_device_id"), displayName,
                    status.optString("mac_instance_tag").takeIf { !status.isNull("mac_instance_tag") && it.isNotBlank() },
                    accountUserId = pairingOwner?.userId, accountTeamId = pairingOwner?.teamId)
                val feed = try { parseNotifications(active.notifications()) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        emptyList()
                    }
                ensureActive()
                if (code != requestedCode || !signedIn) throw CancellationException("Connection changed")
                val listing = workspaceSnapshots.read(saved ?: verified, active)
                ensureActive()
                if (code != requestedCode || !signedIn) throw CancellationException("Connection changed")
                val remembered = if (pairingOwner != null) store.rememberAuthenticatedMac(verified, pairingOwner) {
                    accountTeams.isCurrent(pairingOwner) && signedIn && code == requestedCode
                } else {
                    store.rememberMac(verified.code, verified.deviceId, verified.name, verified.instanceTag)
                    verified
                }
                if (remembered.code != requestedCode) {
                    active.close()
                    savedPairedMacs = store.pairedMacs()
                    code = remembered.code
                    connectionError = null; retryDelay = 2_000; busy = false
                    return@LaunchedEffect
                }
                hostName = displayName; hostCapabilities = capabilities
                terminalTransport = TerminalTransport.resolve(capabilities, status.optString("terminal_fidelity"))
                applyListing(listing); notifications = feed
                inputOwner(remembered, store.taskSession())?.let { owner ->
                    terminalInputs.attach(owner, active, capabilities, inputTargets(workspaces)) { feedSession.allowsTerminalInput(owner) }
                }
                client = active; connectedCode = requestedCode
                connectionReady = true
                savedPairedMacs = store.pairedMacs()
                connectionError = null
                retryDelay = 2_000
            } catch (failure: Throwable) { active.close(); throw failure }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            connectionError = nativeConnectionFailure(failure)
            busy = false
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000)
            retry++
        }
        busy = false
    }

    LaunchedEffect(client) {
        val active = client ?: return@LaunchedEffect
        active.disconnected.collect { failure ->
            connectionReady = false
            connectionError = nativeConnectionFailure(failure)
            delay(2_000)
            retry++
        }
    }

    LaunchedEffect(client) {
        val active = client ?: return@LaunchedEffect
        active.events.collect { event ->
            if (event.topic == "workspace.list.changed" || event.topic == "workspace.updated") {
                val requestedCode = code
                try {
                    val listing = workspaceSnapshots.read(workspaceOwner(requestedCode), active)
                    if (client === active && code == requestedCode && signedIn) { applyListing(listing); connectionError = null }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (failure !is NativeWorkspaceSnapshotSuperseded && client === active && code == requestedCode)
                        connectionError = nativeConnectionFailure(failure)
                }
            }
        }
    }

    LaunchedEffect(client, selectedTerminal, selectedSurface?.id, terminalStartupState.failure,
        pendingWorkspaceTab?.id, hostCapabilities, discoveryKey, creatingTerminal) {
        val active = client ?: return@LaunchedEffect
        // Pane recovery/discovery owns the visible workspace's polling while active.
        if (pendingWorkspaceTab != null || (discoveryKey != null && "browser.stream.v1" in hostCapabilities && !creatingTerminal)) return@LaunchedEffect
        if (selectedTerminal?.isReady == true && selectedSurface == null && terminalStartupState.failure == null) return@LaunchedEffect
        val requestedCode = code
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (client === active && code == requestedCode && signedIn) {
                try {
                    val listing = workspaceSnapshots.read(workspaceOwner(requestedCode), active)
                    if (client !== active || code != requestedCode || !signedIn) return@repeatOnLifecycle
                    connectionError = null; applyListing(listing)
                    val feed = active.notifications()
                    if (client !== active || code != requestedCode || !signedIn) return@repeatOnLifecycle
                    notifications = parseNotifications(feed)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (failure !is NativeWorkspaceSnapshotSuperseded && client === active && code == requestedCode) connectionError = nativeConnectionFailure(failure)
                }
                delay(if (selectedTerminal?.isReady == false || terminalStartupState.failure != null) 2_000 else 5_000)
            }
        }
    }

    LaunchedEffect(client, selectedWorkspace?.id, selectedTerminal?.id, selectedTerminal?.isReady, terminalColumns, terminalRows, terminalCells, terminalTransport) {
        val active = client ?: return@LaunchedEffect
        val workspace = selectedWorkspace ?: return@LaunchedEffect
        val terminal = selectedTerminal?.takeIf { it.isReady } ?: return@LaunchedEffect
        val requestedViewport = terminalViewport ?: return@LaunchedEffect
        val generation = ++replayGeneration
        val viewportGeneration = ++viewportRequestGeneration
        val transport = terminalTransport
        val mirror = TerminalStreamMirror(terminal.id, transport, requestedViewport, ghosttyTerminalFactory(terminalCells))
        val replayRecovery = TerminalReplayRecovery()
        // Keep the last painted frame while this viewport gets a fresh replay.
        // The display state above resets for a different terminal or connection;
        // protocol cursors and parser state always belong to this new mirror.
        scrollPosition = 0.0
        var replayRunning = false
        var replayAgain = false
        var recoveryFailed = false
        var subscriptionReady = false
        var nativeOutput: TerminalOutputLaneOwner? = null
        var inputRegistration: AutoCloseable? = null
        val subscriptionId = java.util.UUID.randomUUID().toString()
        var previousScrollAnchor: TerminalScrollAnchor? = null
        fun publish() {
            val next = mirror.display
            if (next.columns <= 0 || next.rows <= 0) return
            val anchor = (next as? RenderGrid)?.scrollAnchor
            if (anchor != null && previousScrollAnchor?.let { !anchor.sameSpace(it) } == true) terminalMotion.stop()
            if (transport.screenAnchor && anchor != null)
                scrollPosition = anchor.rebase(scrollPosition, previousScrollAnchor, next.historyLineCount)
            else scrollPosition = TerminalScrollViewport.at(scrollPosition, next.historyLineCount, next.activeScreen).position
            previousScrollAnchor = anchor
            grid = next
            gridRevision++
        }
        suspend fun replayTerminal() {
            if (replayRunning || recoveryFailed) return
            nativeOutput?.pause()
            replayRunning = true
            mirror.beginReplay()
            try {
                repeat(3) {
                    replayAgain = false
                    // Replay also reports a viewport. Preserve this generation's
                    // natural dimensions, rather than re-pinning to a host cap.
                    val snapshot = replayRecovery.replay {
                        active.replay(workspace.id, terminal.id, requestedViewport.columns, requestedViewport.rows,
                            viewportGeneration = viewportGeneration, screenAnchor = transport.screenAnchor,
                            maxScrollbackRows = if (mirror.historyLineCount == 0) 10_000 else 0)
                    }
                    if (generation != replayGeneration || client !== active) return
                    val result = mirror.replay(snapshot)
                    publish()
                    if (result != TerminalStreamMirror.Result.REPLAY && !replayAgain) {
                        error = null
                        nativeOutput?.resume()
                        return
                    }
                    mirror.beginReplay()
                }
                error("Terminal output could not be synchronized. Reconnect to retry.")
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                recoveryFailed = true
                error = failure.message ?: "Terminal replay failed"
            } finally { replayRunning = false }
        }
        fun requestReplay() {
            nativeOutput?.pause()
            mirror.beginReplay()
            if (replayRunning) replayAgain = true
            else if (subscriptionReady && !recoveryFailed) launch(start = CoroutineStart.UNDISPATCHED) { replayTerminal() }
        }
        val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
            var lastDelivery: Long? = null
            active.events.collect { event ->
                if (generation != replayGeneration || client !== active) return@collect
                if (event.deliverySequence > 0) {
                    if (lastDelivery?.let { event.deliverySequence != it + 1 } == true) requestReplay()
                    lastDelivery = event.deliverySequence
                }
                if (event.streamId != null && event.streamId != subscriptionId) return@collect
                if (event.topic == "terminal.set_font") {
                    TerminalSetFont.decode(event.payload)?.takeIf { it.matches(workspace.id, terminal.id) }?.let {
                        terminalZoom.apply(it.size)
                    }
                    return@collect
                }
                if (event.topic == "terminal.render_grid") {
                    val frame = event.payload.optJSONObject("render_grid") ?: event.payload
                    if (frame.optString("surface_id") == terminal.id && frame.optBoolean("full", true) &&
                        frame.optInt("columns") > 0 && frame.optInt("rows") > 0) replayRecovery.onFullGrid()
                }
                try {
                    val result = when (event.topic) {
                        "terminal.render_grid" -> mirror.grid(event.payload)
                        "terminal.bytes" -> mirror.bytes(event.payload)
                        else -> TerminalStreamMirror.Result.IGNORED
                    }
                    when (result) {
                        TerminalStreamMirror.Result.APPLIED -> publish()
                        TerminalStreamMirror.Result.REPLAY -> requestReplay()
                        TerminalStreamMirror.Result.IGNORED -> Unit
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message
                    requestReplay()
                }
            }
        }
        fun isCurrent() = generation == replayGeneration && client === active &&
            selectedWorkspace?.id == workspace.id && selectedTerminal?.id == terminal.id
        val scrollQueue = TerminalScrollQueue(this, onFailure = { failure ->
            if (generation == replayGeneration && client === active && selectedWorkspace?.id == workspace.id &&
                selectedTerminal?.id == terminal.id) { terminalMotion.stop(); error = failure.message ?: "Terminal scroll failed" }
        }, canSend = ::isCurrent) { delivery ->
            val interactionEpoch = scrollInteractionEpoch
            val response = inputQueue.performOrdered { active.terminalScroll(workspace.id, terminal.id, delivery) }
            if (generation == replayGeneration && client === active && selectedWorkspace?.id == workspace.id &&
                selectedTerminal?.id == terminal.id && interactionEpoch == scrollInteractionEpoch && response.optJSONObject("render_grid") != null) {
                when (mirror.grid(response)) {
                    TerminalStreamMirror.Result.APPLIED -> publish()
                    TerminalStreamMirror.Result.REPLAY -> requestReplay()
                    TerminalStreamMirror.Result.IGNORED -> Unit
                }
            }
        }
        var viewportAttempted = false
        var subscriptionAttempted = false
        try {
            subscriptionAttempted = true
            // Settle the handshake before cleanup can unsubscribe this unique stream.
            // Otherwise a late server acknowledgement can recreate an abandoned subscription.
            withContext(NonCancellable) {
                active.subscribe(transport.topics, subscriptionId, screenAnchor = transport.screenAnchor)
            }
            kotlin.coroutines.coroutineContext.ensureActive()
            viewportAttempted = true
            runCatching {
                active.reportViewport(workspace.id, terminal.id, requestedViewport, viewportGeneration)
            }.onFailure {
                if (it is CancellationException) throw it
                error = it.message ?: "Terminal resize failed"
            }
            subscriptionReady = true
            replayTerminal()
            if (!recoveryFailed && isCurrent()) {
                if (transport.mode != TerminalOutputMode.GRID) {
                    nativeOutput = TerminalOutputLaneOwner(this, cursor = { mirror.nativeCursor.takeUnless { mirror.replayPending } },
                        useLane = { cursor, use -> active.useTerminalOutputLane(terminal.id, cursor, use) },
                        consume = { frame ->
                            check(isCurrent()) { "Terminal connection changed" }
                            mirror.lane(frame).also { if (it == TerminalStreamMirror.Result.APPLIED) publish() }
                        }, resync = ::requestReplay, onAcknowledgement = { terminalInputs.receive(active, terminal.id, it) })
                    outputInput = nativeOutput
                    nativeOutput?.let { lane ->
                        inputRegistration = terminalInputs.registerLane(active, workspace.id, terminal.id, lane.ready, lane::sendIdentified)
                    }
                    nativeOutput?.resume()
                }
                terminalClick = { cell ->
                    if (isCurrent() && scrollPosition == 0.0) launch {
                        try { if (isCurrent() && inputFailure == null && drafts.state.value[draftTarget]?.operation == null)
                            inputQueue.performOrdered { active.terminalClick(workspace.id, terminal.id, cell) } }
                        catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            if (isCurrent()) error = failure.message ?: "Terminal click failed"
                        }
                    }
                }
                cancelQueuedScroll = { scrollQueue.cancelPending() }
                terminalScroll = { lines, cell ->
                    if (!isCurrent()) false else {
                        val primary = mirror.display.activeScreen == "primary"
                        // Raw-byte mirrors retain their own history; viewport-anchored grids
                        // instead receive the Mac's viewport after the scroll RPC.
                        var moved = false
                        if (primary && (transport.screenAnchor || transport.mode != TerminalOutputMode.GRID)) {
                            val next = TerminalScrollViewport.at(scrollPosition + lines, mirror.historyLineCount).position
                            moved = next != scrollPosition; scrollPosition = next
                        }
                        if (transport.screenAnchor && primary) moved else scrollQueue.offer(lines, cell)
                    }
                }
            }
            eventJob.join()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Terminal subscription failed"
        } finally {
            inputRegistration?.close(); nativeOutput?.close()
            if (outputInput === nativeOutput) outputInput = null
            scrollQueue.close()
            if (generation == replayGeneration) { terminalClick = null; terminalScroll = null; cancelQueuedScroll = null }
            eventJob.cancel()
            mirror.close()
            // An old viewport effect must never clear a newer report on the same surface.
            if (viewportAttempted && (generation == replayGeneration ||
                selectedTerminal?.id != terminal.id || client !== active)) withContext(NonCancellable) {
                runCatching { active.clearViewport(workspace.id, terminal.id, ++viewportRequestGeneration) }
            }
            if (subscriptionAttempted) withContext(NonCancellable) { runCatching { active.unsubscribe(subscriptionId) } }
        }
    }

    // Capture during composition: the effect callback may run after client changes.
    // Reading the mutable state inside it can assign the next session to old cleanup.
    val disposableClient = client
    DisposableEffect(disposableClient) {
        onDispose { disposableClient?.let { terminalInputs.detach(it); it.close() } }
    }
    BackHandler(enabled = signedIn && code.isNotBlank() && searchState.active != null && selectedTerminal == null &&
        selectedBrowser == null && selectedChangesWorkspace == null && !showSettings && !showTaskComposer) { finishSearch(cancel = true) }
    BackHandler(enabled = workspaceRoute != null && selectedTerminal == null && selectedBrowser == null && selectedSurface == null) { screenResume.cancel(); workspaceRoute = null }
    BackHandler(enabled = selectedTerminal != null && selectedSurface == null) { selectedTerminal = null; selectedWorkspace = null; selectedSurface = null }
    BackHandler(enabled = selectedBrowser != null) { selectedBrowser = null; selectedWorkspace = null; selectedSurface = null }
    BackHandler(enabled = showSettings && selectedTerminal == null) { showSettings = false }

    if (showLicenses) OpenSourceLicensesDialog { showLicenses = false }

    NativePairingConfirmation(if (signedIn) pendingPairingCode else null,
        onDismiss = { pendingPairingCode = null },
        onConnect = { proposed ->
            try {
                connection.authorizePairing(PairingCodeParser.parse(proposed).getOrThrow() as PairingCode.Tailscale)
                code = proposed; pendingPairingCode = null; retry++
            } catch (failure: Exception) { error = failure.message; pendingPairingCode = null }
        })
    if (showCreateGroup) AlertDialog(
        onDismissRequest = { showCreateGroup = false },
        title = { Text("New group") },
        text = { OutlinedTextField(newGroupName, { newGroupName = it },
            label = { Text("Name (optional)") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val active = client
            showCreateGroup = false
            if (active != null) scope.launch {
                val requestedCode = code
                try {
                    val owner = workspaceOwner(requestedCode)
                    requireWorkspaceConnection(active, owner)
                    workspaceSnapshots.mutate(owner) { active.createGroup(newGroupName) }
                    val listing = workspaceSnapshots.read(owner, active)
                    if (client === active && code == requestedCode && signedIn) {
                        applyListing(listing); refreshFeed(); newGroupName = ""; error = null
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (client === active && code == requestedCode) error = failure.message
                }
            }
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("Cancel") } }
    )

    if (signedIn) sharedConnections?.ssh?.let { NativeSshPromptHost(it) }

    NativeScreenLayout(Modifier.fillMaxSize().background(nativePage).statusBarsPadding().navigationBarsPadding().imePadding()) {
        LocalBrowserCreationProgress(localBrowserState.creating != null, localBrowsers::cancelRequest)
        if (signedIn && terminalStartupState.failure?.key?.let { it == displayedTab?.first } == true) {
            NativeTerminalCreationRecovery(creatingTerminal, connectionReady && selectedWorkspace != null) {
                selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { createTerminal(it, workspace) } }
            }
        }
        when {
            !signedIn -> NativeSignIn(account::sendCode, account::signIn, onUseHelper,
                onLicenses = { showLicenses = true }, onSignedIn = { signedIn = true; error = null })
            showSshComputers && sharedConnections != null -> NativeSshComputersRoute(sharedConnections.ssh) { showSshComputers = false }
            showSettings && showSshKeys -> NativeSshKeysRoute(store, browserLogin) { showSshKeys = false }
            showSettings -> {
                NativeSettingsLayout(onBack = { showSettings = false }, account = {
                NativeAccountTeamSection(teamState, onRefresh = {
                    scope.launch {
                        try { accountTeams.refresh() }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure }
                    }
                }, onSelect = { id ->
                    scope.launch {
                        try {
                            accountTeams.select(id)
                            client?.close(); client = null; code = ""
                            selectedTerminal = null; selectedWorkspace = null; selectedSurface = null
                        } catch (failure: Exception) { if (failure is CancellationException) throw failure }
                    }
                }, onCreate = { name ->
                    try {
                        accountTeams.create(name)
                        client?.close(); client = null; code = ""
                        selectedTerminal = null; selectedWorkspace = null; selectedSurface = null
                        true
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        accountTeams.state.value.createdTeam != null
                    }
                })
                }, computers = {
                Text("COMPUTERS", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = nativeMuted, fontSize = 11.sp)
                if (sharedConnections != null) TextButton(onClick = { showSshComputers = true }, modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.ssh.computers")) { Text("SSH Computers") }
                NativeSavedComputerRows(pairedMacs, appearances, machineColorIndices, sharedConnections?.native,
                    computerState, computerConnections, forgetCallbacks, { computerDetails = it }) { mac -> code = mac.code; showSettings = false }
                TextButton(onClick = {
                    code = ""; showSettings = false; selectedTerminal = null; selectedWorkspace = null; selectedSurface = null
                }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Find another Mac") }
                if (code.isNotBlank() && (PairingCodeParser.parse(code).getOrNull() !is PairingCode.Iroh ||
                    pairedMacs.none { it.code == code && computerState.account?.let { team -> NativeComputerTarget.from(it, team) } != null })) TextButton(onClick = {
                    runCatching {
                        val owner = teamState.scope
                        if (sharedConnections != null) {
                            checkNotNull(owner) { "Refresh your account teams first" }
                            store.forgetMac(code, owner) { accountTeams.isCurrent(owner) }
                        } else store.forgetMac(code)
                        savedPairedMacs = store.pairedMacs()
                        code = store.load()?.optString("pairing_code").orEmpty()
                        showSettings = false
                    }.onFailure { error = it.message }
                }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Remove local pairing", color = Color(0xFFFF9999)) }
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = {
                    NativeNotificationService.setEnabled(context, false)
                    backgroundNotifications = false
                    drafts.clear()
                    accountTeams.clear(); account.signOut(); TaskDraftRepository.clearAttachments(context); signedIn = false; client?.close(); client = null
                },
                    modifier = Modifier.padding(horizontal = 14.dp)) { Text("Sign out") }
                }, notifications = {
                Text("NOTIFICATIONS", Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    color = nativeMuted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background notifications")
                        Text("Keep a connection to your Mac for agent alerts", color = nativeMuted, fontSize = 12.sp)
                    }
                    Switch(backgroundNotifications, onCheckedChange = { enabled ->
                        if (enabled) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else runCatching { NativeNotificationService.setEnabled(context, true) }
                                .onSuccess { backgroundNotifications = true; error = null }
                                .onFailure { error = it.message }
                        } else runCatching { NativeNotificationService.setEnabled(context, false) }
                            .onSuccess { backgroundNotifications = false; error = null }
                            .onFailure { error = it.message }
                    }, enabled = signedIn && code.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = "Background notifications" })
                }
                }, preferences = {
                TextButton(onClick = { showSshKeys = true }, modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.ssh.keys")) { Text("SSH Keys") }
                NativeTerminalPreferenceSettings(folderTapEnabled, showMissingArtifacts, artifactPreferences)
                TextButton(onClick = { showShortcuts = true }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Terminal Shortcuts") }
                TextButton(onClick = { showLicenses = true }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Open-source licenses") }
                }, connections = {
                NativeNetworkingSettings(sharedConnections?.native, computerState)
                NativeLegacyConnectionCheckSettings(client, pairedMacs, code, connectionReady)
                Text("CONNECTION", Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    color = nativeMuted, fontSize = 11.sp)
                Text("${pairedMacs.firstOrNull { it.code == code }?.let(appearances::name) ?: hostName} · ${if (client != null) "Connected" else "Disconnected"}",
                    Modifier.padding(horizontal = 22.dp), color = nativeMuted, fontSize = 13.sp)
                TextButton(onClick = onUseHelper, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Text("Use existing helper connection", color = nativeMuted)
                }
                NativeLocalResetSection()
                })
            }
            showTaskComposer -> {
                val repository = taskDraftRepository
                val restored = taskDraftEntries[taskDraftId]
                val restoredMac = restored?.let { draft -> pairedMacs.firstOrNull { it.ownsOrigin(draft.origin) } }
                LaunchedEffect(restored?.origin, pairedMacs) {
                    if (restoredMac != null && code != restoredMac.code) selectComputer(restoredMac)
                }
                val taskMac = pairedMacs.firstOrNull { it.code == code }
                val taskOrigin = restored?.origin ?: taskMac?.origin ?: pairingOrigin(code)
                val selectedTaskMac = pairedMacs.firstOrNull { it.ownsOrigin(taskOrigin) }
                val taskCode = selectedTaskMac?.code
                val taskConnected = connectionReady && taskCode != null && connectedCode == taskCode && code == taskCode
                val active = client.takeIf { taskConnected }
                val taskWorkspaces = if (taskMac?.ownsOrigin(taskOrigin) == true) workspaces else emptyList()
                if (repository != null) key(taskDraftId, repository.session) {
                NativeTaskComposerView(active,
                    directories = preferredTaskDirectories(taskWorkspaces, selectedWorkspace?.id),
                    createTask = { parameters ->
                        val target = checkNotNull(selectedTaskMac) { "This Mac is no longer paired" }
                        val taskClient = checkNotNull(active) { "That Mac is not connected" }
                        requireWorkspaceConnection(taskClient, target)
                        workspaceSnapshots.mutate(target) { taskClient.request("workspace.create", parameters, timeoutMillis = 30_000) }
                    },
                    origin = taskOrigin,
                    models = feedSession.taskModels,
                    onCreated = { response ->
                        val result = TaskCreationResult.parse(response)
                        val created = applyCreatedWorkspace(checkNotNull(selectedTaskMac), result)
                        // Like iOS, partial create responses cannot replace group metadata.
                        refreshFeed()
                        showTaskComposer = false
                        selectedSurface = null; selectedBrowser = null; selectedWorkspace = created
                        selectedBrowser = null
                        selectedTerminal = created.terminals.firstOrNull {
                            it.id == response.optString("created_terminal_id")
                        } ?: created.preferredTerminal
                        selectedTerminal?.let { terminal -> selectedTaskMac?.let { mac ->
                            workspaceTabKey(browserLogin, teamState.scope, mac, created.id)?.let { key ->
                                workspaceTabs.cancel(); terminalStartup.begin(key, terminal)
                            }
                        } }
                    }, onBack = { showTaskComposer = false },
                    isCurrent = { signedIn && taskDraftRepository === repository && showTaskComposer && store.taskSession() == browserLogin },
                    savedDrafts = repository.drafts, draftId = taskDraftId,
                    macName = selectedTaskMac?.let(appearances::name) ?: restored?.macName ?: "Choose a Mac",
                    appearances = appearances,
                    hasSelectedMac = selectedTaskMac != null,
                    resolvedMacOrigin = taskMac?.origin.takeIf { selectedTaskMac == null && taskOrigin == pairingOrigin(code) },
                    savedTemplates = repository.templates, persistTemplateChange = repository::updateTemplates,
                    attachmentRepository = repository, supportsAttachments = taskMac?.ownsOrigin(taskOrigin) == true && ComposerAttachment.FILE_CAPABILITY in hostCapabilities,
                    macs = pairedMacs, workspaceGroups = if (taskMac?.ownsOrigin(taskOrigin) == true) groups else emptyList(),
                    supportsGroups = if (taskConnected) "workspace.create_in_group.v1" in hostCapabilities else null,
                    groupsLoaded = taskConnected && taskGroupsLoaded,
                    groupIsCurrent = { group -> group == null || (connectionReady && connectedCode == taskCode && code == taskCode && "workspace.create_in_group.v1" in hostCapabilities &&
                        taskGroupsLoaded && groups.count { it.id == group } == 1) },
                    directoryWorkspaces = taskWorkspaces, selectedWorkspaceId = selectedWorkspace?.id,
                    selectMac = { editor, nextOrigin ->
                        val target = requireNotNull(pairedMacs.singleOrNull { it.ownsOrigin(nextOrigin) }) { "This Mac is no longer paired" }
                        val snapshot = workspaceSources.firstOrNull { it.mac.origin == nextOrigin && it.availability == NativeFeedAvailability.CONNECTED }
                        val currentDraft = checkNotNull(repository.drafts.state.value[editor.id])
                        val templates = repository.templates.state.value
                        val nextDirectory = templates.suggestedDirectory(templates.selected(currentDraft.templateId), nextOrigin,
                            snapshot?.let { preferredTaskDirectories(it.workspaces, null).firstOrNull() })
                        repository.selectMac(editor, nextOrigin, target.name, nextDirectory)
                        check(signedIn && taskDraftRepository === repository && pairedMacs.any { it.ownsOrigin(nextOrigin) }) { "Task account or Mac changed" }
                        selectComputer(target)
                    },
                    persistDrafts = repository::persistNow, flushDrafts = repository::flush,
                    onResumeDraft = { draft ->
                        taskDraftId = draft.id
                        pairedMacs.firstOrNull { it.ownsOrigin(draft.origin) }?.let(::selectComputer)
                    }, onNewDraft = { newTaskDraft() },
                    supportsTaskCreation = if (taskConnected) "workspace.task_create.v1" in hostCapabilities else null,
                    refreshWorkspaces = {
                        val listing = workspaceSnapshots.read(checkNotNull(selectedTaskMac) { "This Mac is no longer paired" },
                            checkNotNull(active) { "That Mac is not connected" })
                        check(signedIn && connectionReady && client === active && connectedCode == taskCode && code == taskCode) {
                            "Connection changed while refreshing workspaces"
                        }
                        applyListing(listing)
                    })
                } else Column(Modifier.fillMaxSize().padding(22.dp)) {
                    TextButton(onClick = { showTaskComposer = false }) { Text("‹  Workspaces") }
                    Text(taskDraftLoadError ?: "Loading saved drafts…")
                    if (taskDraftLoadError != null) TextButton(onClick = { taskDraftLoadAttempt++ }) { Text("Retry") }
                }
            }
            screenResume.pending != null && selectedWorkspace == null && localBrowser == null -> {
                NativeWorkspaceWaitingPane("Restoring workspace…",
                    onBack = { screenResume.cancel(); workspaceRoute = null },
                    connected = connectionReady && error == null, connectionError = error ?: connectionError,
                    onReconnect = { error = null; retryDelay = 2_000; retry++ })
            }
            localBrowser != null && pairedMacs.any { localBrowserKey(browserLogin, teamState.scope, it, localBrowser.key.workspaceId) == localBrowser.key } -> {
                val browserMac = pairedMacs.first { localBrowserKey(browserLogin, teamState.scope, it, localBrowser.key.workspaceId) == localBrowser.key }
                RoutedLocalBrowserWorkspaceView(localBrowser, localBrowsers,
                    workspaceSources.firstOrNull { it.mac.ownsOrigin(localBrowser.key.computerId) }?.workspaces
                        ?.firstOrNull { it.id == localBrowser.key.workspaceId } ?: localBrowser.workspace,
                    network = { feedSession.browserNetworks.network(browserMac) },
                    retainHost = {
                        val held = feedSession.holdBrowser(browserMac)
                        val connectionHandle = try {
                            if (sharedConnections != null) NativeAppConnections.acquire(context.applicationContext) else null
                        } catch (failure: Exception) { held.close(); throw failure }
                        RoutedBrowserHostLease({ held.close(); connectionHandle?.close() },
                            { active -> connectionHandle?.connections?.setProbeActive(held, active) })
                    },
                    onClose = { displayedTab?.first?.let { key -> browserLogin?.let { workspaceTabs.forget(it, key) } } },
                    onRoute = { workspaceRoute = it }, browserModes = true)
            }
            code.isBlank() -> NativeComputerPicker(teamState, computerState, runtime = sharedConnections?.native,
                onSsh = if (sharedConnections != null) ({ showSshComputers = true }) else null,
                colorIndices = machineColorIndices, connections = computerConnections, forgetCallbacks = forgetCallbacks,
                presentDetails = { computerDetails = it },
                hasSavedComputers = pairedMacs.isNotEmpty(),
                onSelect = { mac -> computerState.account?.let { code = PairingCodeParser.computer(mac, it) } },
                onSettings = { workspaceRoute = null; finishSearch(); showSettings = true },
                onRefresh = { scope.launch {
                    try { accountTeams.refresh(); sharedConnections?.native?.refresh() }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure }
                } }, onPairing = ::proposePairing, onNewTask = ::newTaskDraft,
                onUseHelper = onUseHelper, onLicenses = { showLicenses = true }, onError = { error = it })
            selectedWorkspace != null && selectedTerminal == null && selectedBrowser == null && selectedSurface == null && selectedChangesWorkspace == null -> {
                val workspace = selectedWorkspace!!
                val source = workspaceSourceForPane()
                NativeWorkspaceWaitingPane(workspace.title,
                    onBack = { workspaceTabs.cancel(); selectedWorkspace = null },
                    onNewTerminal = if (source != null && connectionReady && !creatingTerminal)
                        ({ createTerminal(source, workspace) }) else null,
                    onNewBrowser = source?.let { ({ openNewBrowser(it, workspace) }) },
                    connected = connectionReady, connectionError = connectionError,
                    onReconnect = { retryDelay = 2_000; retry++ })
            }
            selectedSurface != null && selectedWorkspace != null -> {
                NativeSurfaceView(selectedWorkspace!!, selectedSurface!!, client, hostCapabilities, connectionReady,
                    onBack = { selectedSurface = null; selectedTerminal = null; selectedBrowser = null; selectedWorkspace = null },
                    onSurface = { selectPane(NativeWorkspacePane(surface = it)) },
                    onTerminal = { selectPane(NativeWorkspacePane(terminal = it)) },
                    onBrowser = { selectPane(NativeWorkspacePane(browser = it)) },
                    mutateWorkspace = { active, method, parameters ->
                        val owner = workspaceOwner()
                        requireWorkspaceConnection(active, owner)
                        workspaceSnapshots.mutate(owner) { active.request(method, parameters) }
                        val listing = workspaceSnapshots.read(owner, active)
                        check(client === active && code == owner.code && signedIn) { "Connection changed while refreshing the workspace." }
                        applyListing(listing)
                        listing.value
                    },
                    onNewBrowser = { selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { openNewBrowser(it, workspace) } } })
            }
            selectedTerminal?.isReady == false -> {
                NativeStartingTerminalPane(selectedTerminal!!, selectedWorkspace, workspaces.size,
                    onBack = { selectedTerminal = null; selectedWorkspace = null; selectedSurface = null },
                    onTerminal = { selectPane(NativeWorkspacePane(terminal = it)) },
                    onSurface = { selectPane(NativeWorkspacePane(surface = it)) },
                    onBrowser = { selectPane(NativeWorkspacePane(browser = it)) },
                    onNewBrowser = { selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { openNewBrowser(it, workspace) } } })
            }
            selectedTerminal != null -> {
                val terminal = selectedTerminal!!
                NativeTerminalHeader(terminal, selectedWorkspace, workspaces.size, hostCapabilities, connectionReady, directTyping,
                    onBack = { selectedTerminal = null; selectedWorkspace = null; selectedSurface = null },
                    onSurface = { surface ->
                        rawKeyboardView?.finishComposition(); directTyping = false
                        inputModifiers = TerminalInputModifiers(); stopTerminalScrolling(); softwareKeyboard?.hide()
                        selectPane(NativeWorkspacePane(surface = surface))
                    }, onText = ::openTerminalText, onFiles = {
                        inputModifiers = TerminalInputModifiers(); stopTerminalScrolling(); softwareKeyboard?.hide(); showTerminalFiles = true
                    }, onNewBrowser = {
                        rawKeyboardView?.finishComposition(); directTyping = false; stopTerminalScrolling(); softwareKeyboard?.hide()
                        selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { openNewBrowser(it, workspace) } }
                    }, onKeyboard = {
                        inputModifiers = TerminalInputModifiers()
                        if (directTyping) { rawKeyboardView?.finishComposition(); directTyping = false }
                        else openDirectKeyboard()
                    }, onBrowser = { browser ->
                        rawKeyboardView?.finishComposition(); directTyping = false
                        inputModifiers = TerminalInputModifiers(); stopTerminalScrolling(); softwareKeyboard?.hide()
                        selectPane(NativeWorkspacePane(browser = browser))
                    })
                NativeTerminalTabs(selectedWorkspace?.terminals.orEmpty(), terminal) { selectPane(NativeWorkspacePane(terminal = it)) }
                val currentGrid = grid
                val visibleArtifactScroll by rememberUpdatedState(scrollViewport)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                RenderGridView(currentGrid, terminalCells, gridRevision,
                    Modifier.fillMaxSize().testTag("native-terminal")
                        .onSizeChanged { terminalViewportPixels = it }
                        .focusRequester(terminalFocusRequester)
                        .onPreviewKeyEvent { event -> directHardware(event.nativeKeyEvent) }
                        .focusable()
                        .semantics(mergeDescendants = true) {
                            stateDescription = "Terminal font size ${terminalZoom.size}"
                            onClick("Open keyboard") { openDirectKeyboard(); true }
                            customActions = listOf(CustomAccessibilityAction("View as Text") { openTerminalText(); true })
                        }
                        .terminalPinchZoom(terminalZoom)
                        .pointerInput(terminal.id, currentGrid, terminalCells, artifactRpc, artifactsReady, artifactTapController) {
                            detectTapGestures(onTap = { point ->
                                artifactTapController.invalidate()
                                var handlingArtifact = false
                                TerminalGeometry.fit(size.width.toFloat(), size.height.toFloat(),
                                    currentGrid.columns, currentGrid.rows, terminalCells)?.let { geometry ->
                                    val cell = visibleArtifactScroll.cell(geometry, point.x, point.y)
                                    val path = if (artifactsReady && geometry.contains(point.x, point.y)) TerminalArtifactHitTest.path(
                                        RenderGrid.plainText(visibleArtifactScroll.lines(currentGrid)), cell.column, cell.row, currentGrid.columns) else null
                                    if (path != null) {
                                        handlingArtifact = true
                                        val authorization = ArtifactAuthorization.Terminal(draftTarget!!.workspace, draftTarget.surface)
                                        val columns = currentGrid.columns
                                        val rows = currentGrid.rows
                                        artifactTapController.tap(path, folderTapEnabled,
                                            stat = { candidate ->
                                                val metadata = artifactRpc!!.stat(authorization, candidate)
                                                if (metadata.getBoolean("is_directory")) ArtifactKind.DIRECTORY else ArtifactKind.read(metadata.opt("kind"))
                                            },
                                            stillMatches = {
                                                currentGrid.columns == columns && currentGrid.rows == rows &&
                                                    TerminalArtifactHitTest.path(RenderGrid.plainText(visibleArtifactScroll.lines(currentGrid)), cell.column, cell.row, columns) == path
                                            },
                                            open = {
                                                stopTerminalScrolling(); directTyping = false; softwareKeyboard?.hide()
                                                filesState.openPath(path)
                                            },
                                            focus = { sendClick ->
                                                if (sendClick) terminalClick?.invoke(cell)
                                                openDirectKeyboard()
                                            })
                                    } else terminalClick?.invoke(cell)
                                }
                                if (!handlingArtifact) openDirectKeyboard()
                            }, onLongPress = { artifactTapController.invalidate(); openTerminalText() })
                        }
                        .terminalScrollGestures(terminalMotion,
                            TerminalGeometry.fit(terminalViewportPixels.width.toFloat(), terminalViewportPixels.height.toFloat(),
                                currentGrid.columns, currentGrid.rows, terminalCells),
                            replayGeneration, currentGrid.activeScreen,
                            linePath = !(terminalTransport.screenAnchor && currentGrid.activeScreen == "primary"),
                            enabled = terminalScroll != null,
                            onScroll = { lines, cell -> terminalScroll?.invoke(lines, cell) ?: false }), scrollPosition = scrollPosition)
                if (scrollOffset > 0) Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(nativePanel, RoundedCornerShape(14.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Scrollback · $scrollOffset rows", color = nativeMuted, fontSize = 12.sp)
                    TextButton(onClick = { stopTerminalScrolling(); scrollPosition = 0.0 }) { Text("Latest") }
                }
                artifactChipCount?.takeUnless { terminalZoom.overlayVisible }?.let { count ->
                    TerminalArtifactChip(count, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (scrollOffset > 0) 62.dp else 10.dp)) {
                        inputModifiers = TerminalInputModifiers()
                        stopTerminalScrolling(); softwareKeyboard?.hide(); showTerminalFiles = true
                    }
                }
                TerminalZoomOverlay(terminalZoom, displayPreferences,
                    foreground = runCatching { Color(android.graphics.Color.parseColor(currentGrid.foreground)) }.getOrDefault(Color.White),
                    background = runCatching { Color(android.graphics.Color.parseColor(currentGrid.background)) }.getOrDefault(nativePanel),
                    modifier = Modifier.align(Alignment.Center))
                }
                TerminalToolbarView(toolbarStore.layout, inputModifiers,
                    canInput = selectedTerminal?.isReady == true && connectionReady && client != null && inputFailure == null && terminalDraft.operation == null,
                    filesEnabled = artifactsReady,
                    onModifier = { inputModifiers = inputModifiers.tap(it, android.os.SystemClock.uptimeMillis()) },
                    onButton = { button ->
                        when (button) {
                            TerminalToolbarButton.PASTE -> pasteClipboard()
                            TerminalToolbarButton.FILES -> if (artifactsReady) {
                                inputModifiers = TerminalInputModifiers()
                                stopTerminalScrolling(); softwareKeyboard?.hide(); showTerminalFiles = true
                            }
                            TerminalToolbarButton.ZOOM_IN, TerminalToolbarButton.ZOOM_OUT ->
                                terminalZoom.step(if (button == TerminalToolbarButton.ZOOM_IN) 1 else -1)
                            else -> button.key?.let { key ->
                                rawKeyboardView?.finishComposition()
                                val sequence = inputModifiers.special(key, currentGrid.applicationCursorKeys)
                                inputModifiers = inputModifiers.consume()
                                queueInput(sequence)
                            }
                        }
                    }, onCustom = { action ->
                        rawKeyboardView?.finishComposition()
                        inputModifiers = TerminalInputModifiers()
                        queueInput(action.output)
                    }, onCustomize = {
                        rawKeyboardView?.finishComposition(); inputModifiers = TerminalInputModifiers()
                        softwareKeyboard?.hide(); showShortcuts = true
                    }, insert = if (!directTyping && client != null && terminalDraft.operation == null && !preparingAttachments &&
                        (terminalDraft.text.isNotEmpty() || terminalDraft.attachments.isNotEmpty())) ({ sendComposer(submit = false) }) else null)
                inputFailure?.let { message ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = Color(0xFFFFAAAA), fontSize = 12.sp)
                        TextButton(onClick = {
                            val key = deliveryKey
                            if (key != null && terminalInputs.requiresReconnect(key)) retry++
                            else if (if (key != null && retainedInputQueue != null) terminalInputs.resume(key) else inputQueue.resume()) rawKeyboardView?.showKeyboard()
                        }) { Text(if (deliveryKey?.let(terminalInputs::requiresReconnect) == true) "Reconnect" else "Resume typing") }
                    }
                }
                if (directTyping) {
                    key(draftTarget, client) {
                        AndroidView(factory = { viewContext ->
                            TerminalKeyboardView(viewContext).also { rawKeyboardView = it }
                        }, update = { view ->
                            val enabled = client != null && inputFailure == null && terminalDraft.operation == null
                            val resumed = enabled && !view.isEnabled
                            view.isEnabled = enabled
                            if (resumed) view.restartKeyboard()
                            view.onText = ::directText
                            view.onDelete = ::directDelete
                            view.onKey = ::directHardware
                            view.onPaste = { queueInput(it, paste = true) }
                            view.onContent = ::acceptTerminalPaste
                            view.onContentError = { error = it }
                        }, onRelease = { view -> view.dispose(); if (rawKeyboardView === view) rawKeyboardView = null },
                            modifier = Modifier.fillMaxWidth().height(48.dp).background(nativePanel))
                    }
                } else {
                    if (terminalDraft.attachments.isNotEmpty() || preparingAttachments) {
                        Row(Modifier.fillMaxWidth().background(nativePanel).horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            terminalDraft.attachments.forEach { attachment ->
                                InputChip(selected = false, onClick = {},
                                    label = { Text(attachment.name, maxLines = 1, modifier = Modifier.widthIn(max = 180.dp)) },
                                    leadingIcon = { AttachmentThumbnail(attachment, draftRepository) },
                                    trailingIcon = {
                                        TextButton(onClick = { draftTarget?.let { drafts.removeAttachment(it, attachment.id) } },
                                            modifier = Modifier.semantics { contentDescription = "Remove ${attachment.name}" }) { Text("×") }
                                    }, modifier = Modifier.padding(end = 6.dp))
                            }
                            if (preparingAttachments) Text("Preparing…", color = nativeMuted)
                        }
                    }
                    key(draftTarget) {
                        Row(Modifier.fillMaxWidth().background(nativePanel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(onClick = { attachmentMenu = true },
                                    enabled = !preparingAttachments && terminalDraft.operation == null,
                                    modifier = Modifier.semantics { contentDescription = "Add attachment" }) { Text("+", fontSize = 24.sp) }
                                DropdownMenu(attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                                    fun pick(images: Boolean) {
                                        attachmentMenu = false
                                        pickerTarget = draftTarget; pickerGeneration = drafts.generation; pickerImages = images
                                        attachmentPicker.launch(arrayOf(if (images) "image/*" else "*/*"))
                                    }
                                    DropdownMenuItem(text = { Text("Photos") }, onClick = { pick(true) })
                                    DropdownMenuItem(text = { Text("Files") }, onClick = { pick(false) },
                                        enabled = ComposerAttachment.FILE_CAPABILITY in hostCapabilities)
                                }
                            }
                            RichContentEditor(owner = draftTarget to client,
                                enabled = client != null && terminalDraft.operation == null && !preparingAttachments,
                                onContent = ::acceptTerminalPaste, onError = { error = it }) {
                                OutlinedTextField(terminalDraft.text, { text -> draftTarget?.let { drafts.edit(it, text) } },
                                    Modifier.weight(1f).onPreviewKeyEvent { event ->
                                        val key = event.nativeKeyEvent
                                        if (key.action == AndroidKeyEvent.ACTION_DOWN &&
                                            key.keyCode == AndroidKeyEvent.KEYCODE_ENTER && (key.isCtrlPressed || key.isMetaPressed)) {
                                            sendComposer(submit = true); true
                                        } else false
                                    }, minLines = 1, maxLines = 5,
                                    placeholder = { Text("Message or command") },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default, autoCorrectEnabled = false),
                                    keyboardActions = KeyboardActions(onSend = { sendComposer(submit = true) }))
                            }
                            val canSend = client != null && (terminalDraft.text.isNotEmpty() || terminalDraft.attachments.isNotEmpty()) &&
                                terminalDraft.operation == null && !preparingAttachments
                            TextButton(onClick = { sendComposer(submit = true) }, enabled = canSend) {
                                Text(if (terminalDraft.operation == null) "Send" else "Sending…")
                            }
                        }
                    }
                }
                (terminalDraft.error ?: draftSaveError)?.let { message ->
                    Text(message, Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(12.dp),
                        color = Color(0xFFFFAAAA), fontSize = 12.sp)
                }
            }
            selectedBrowser != null -> {
                val browser = selectedBrowser!!
                val workspace = selectedWorkspace
                val mac = pairedMacs.singleOrNull { it.code == code }
                val browserKey = if (mac != null && workspace != null) localBrowserKey(browserLogin, teamState.scope, mac, workspace.id) else null
                NativeRemoteBrowserPane(client, browser, busy, connectionError,
                    onBack = { selectedBrowser = null; selectedWorkspace = null; selectedSurface = null }, onReconnect = { retry++ },
                    modeRevision = listOf(connectionReady, hostCapabilities, mac, feedSources[mac?.origin]?.availability),
                    prefersOnDevice = browserKey?.let { localBrowsers.prefersOnDevice(it, browser.id) } == true,
                    availability = { mac?.let { feedSession.browserNetworks.network(it)?.availability() } ?: MacBrowserAvailability.NOT_CONNECTED },
                    onOnDevice = { url ->
                        if (browserKey != null && workspace != null && mac != null && signedIn && store.taskSession() == browserLogin &&
                            selectedBrowser?.id == browser.id && selectedWorkspace?.browsers?.any { it.id == browser.id } == true &&
                            selectedWorkspace?.id == workspace.id && code == mac.code &&
                            browserKey == localBrowserKey(store.taskSession(), teamState.scope, mac, workspace.id) &&
                            store.pairedMacs().contains(mac) && connection.allowsSaved(mac)) {
                            focusManager.clearFocus(); softwareKeyboard?.hide(); workspaceTabs.cancel()
                            localBrowsers.openOnDevice(browserKey, workspace, browser.id, url)
                            selectedTerminal = null; selectedWorkspace = null; selectedSurface = null; selectedBrowser = null
                            selectedChangesWorkspace = null; error = null
                        }
                    })
            }
            selectedChangesWorkspace != null -> {
                val active = client
                val workspace = selectedChangesWorkspace!!
                val ownerKey = pairedMacs.singleOrNull { it.code == code }?.let {
                    workspaceTabKey(browserLogin, teamState.scope, it, workspace.id)
                }
                if (active != null && connectionReady && connectedCode == code && browserLogin != null && ownerKey != null)
                    NativeChangesView(active, workspace.id, workspace.title,
                        onBack = { selectedChangesWorkspace = null; selectedWorkspace = null },
                        navigation = changesNavigation.bind(browserLogin, ownerKey))
                else {
                    BackHandler { selectedChangesWorkspace = null; selectedWorkspace = null }
                    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { selectedChangesWorkspace = null; selectedWorkspace = null }) { Text("‹  Workspaces") }
                        Text("Changes in ${workspace.title}", style = MaterialTheme.typography.titleMedium)
                        Text(if (busy) "Reconnecting to your Mac…" else "Changes disconnected", color = nativeMuted)
                        connectionError?.let { Text(it, color = Color(0xFFFF9999)) }
                        TextButton(onClick = { retry++ }, enabled = !busy) { Text("Reconnect") }
                    }
                }
            }
            else -> {
                Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { workspaceRoute = null; finishSearch(); showSettings = true }) {
                        Image(painterResource(R.drawable.cmux_logo), "cmux settings", Modifier.size(24.dp))
                    }
                    NativeComputerSelector(pairedMacs, selectedComputer, appearances, machineColorIndices, computerConnections,
                        computerMenuOpen, { computerMenuOpen = it }, ::selectComputer,
                        onPair = { computerMenuOpen = false; code = "" })
                    Column(Modifier.weight(1f)) {
                        Text(if (notificationTab) "Notifications" else "Workspaces", fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp)
                        Text(selectedComputer?.let(appearances::name) ?: "All Computers", color = nativeMuted,
                            fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (notificationTab) {
                        if (feedEntries.any { !it.notification.isRead }) IconButton(
                            onClick = {
                                pendingReadAllOrigin = selectedOrigin
                                pendingReadAllComputer = selectedComputer?.let(appearances::name) ?: "All Computers"
                                confirmReadAll = true
                            }, enabled = !readAllBusy) {
                            Icon(painterResource(R.drawable.ic_feed_read_all), "Mark All Read",
                                tint = if (readAllBusy) nativeMuted else nativeAccent, modifier = Modifier.size(23.dp))
                        }
                        Box {
                            IconButton(onClick = { notificationFilterMenu = true }) {
                                Icon(painterResource(if (unreadNotificationsOnly) R.drawable.ic_feed_filter_active else R.drawable.ic_feed_filter),
                                    "Notification filter", tint = if (unreadNotificationsOnly) nativeAccent else nativeMuted,
                                    modifier = Modifier.size(23.dp))
                            }
                            DropdownMenu(notificationFilterMenu, onDismissRequest = { notificationFilterMenu = false }) {
                                DropdownMenuItem(text = { Text("All Notifications") }, onClick = {
                                    unreadNotificationsOnly = false; notificationFilterMenu = false
                                }, leadingIcon = { Text(if (!unreadNotificationsOnly) "✓" else " ") })
                                DropdownMenuItem(text = { Text("Unread") }, onClick = {
                                    unreadNotificationsOnly = true; notificationFilterMenu = false
                                }, leadingIcon = { Text(if (unreadNotificationsOnly) "✓" else " ") })
                            }
                        }
                    } else {
                        Box {
                            TextButton(onClick = { workspaceFilterMenuOpen = true }) {
                                Text(if (unreadWorkspacesOnly) "◉" else "☷", color = nativeMuted,
                                    fontSize = 21.sp)
                            }
                            DropdownMenu(workspaceFilterMenuOpen,
                                onDismissRequest = { workspaceFilterMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("All workspaces") }, onClick = {
                                    unreadWorkspacesOnly = false; workspaceFilterMenuOpen = false
                                }, leadingIcon = { Text(if (unreadWorkspacesOnly) " " else "✓") })
                                DropdownMenuItem(text = { Text("Unread") }, onClick = {
                                    unreadWorkspacesOnly = true; workspaceFilterMenuOpen = false
                                }, leadingIcon = { Text(if (unreadWorkspacesOnly) "✓" else " ") })
                            }
                        }
                        Box {
                            TextButton(onClick = { createMenuOpen = true }) {
                                Text("+", color = nativeAccent, fontSize = 25.sp)
                            }
                            DropdownMenu(createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("New workspace") }, enabled = canCreateOnCurrentMac, onClick = {
                                    createMenuOpen = false
                                    val active = client
                                    val entryContext = browserNavigationContext()
                                    val entryNavigation = navigationGeneration.observe(entryContext)
                                    val entryLogin = browserLogin
                                    val mac = pairedMacs.singleOrNull { it.code == code }
                                    fun stillCurrent() = signedIn && client === active && store.taskSession() == entryLogin &&
                                        navigationGeneration.matches(entryNavigation, browserNavigationContext()) && mac != null && store.pairedMacs().contains(mac) && connection.allowsSaved(mac)
                                    if (active != null) scope.launch {
                                        try {
                                            if (!stillCurrent()) return@launch
                                            requireWorkspaceConnection(active, checkNotNull(mac))
                                            val response = workspaceSnapshots.mutate(mac) { active.request("workspace.create") }
                                            if (!stillCurrent()) return@launch
                                            val result = TaskCreationResult.parse(response, "workspace")
                                            val created = applyCreatedWorkspace(checkNotNull(mac), result)
                                            refreshFeed(); notificationTab = false; error = null
                                            selectedSurface = null; selectedBrowser = null; selectedWorkspace = created
                                            selectedTerminal = created.terminals.firstOrNull { it.id == response.optString("created_terminal_id") }
                                                ?: created.preferredTerminal
                                            selectedTerminal?.let { terminal -> mac?.let {
                                                workspaceTabKey(entryLogin, teamState.scope, it, created.id)?.let { key ->
                                                    workspaceTabs.cancel(); terminalStartup.begin(key, terminal)
                                                }
                                            } }
                                        } catch (failure: Exception) {
                                            if (failure is CancellationException) throw failure
                                            if (stillCurrent()) error = failure.message
                                        }
                                    }
                                })
                                DropdownMenuItem(text = { Text("New task") }, onClick = {
                                    createMenuOpen = false; finishSearch(); newTaskDraft()
                                })
                                if ("workspace.group_create.v1" in hostCapabilities) {
                                    DropdownMenuItem(text = { Text("New group") }, enabled = canCreateOnCurrentMac, onClick = {
                                        createMenuOpen = false; showCreateGroup = true
                                    })
                                }
                            }
                        }
                    }
                }
                if (busy && !notificationTab) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (client == null && !busy && !notificationTab && workspaceSources.none { it.hasWorkspaceSnapshot }) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        Button(onClick = { retryDelay = 2_000; retry++ }) { Text("Retry connection") }
                        TextButton(onClick = { store.update { it.put("pairing_code", "") }; code = "" }) { Text("Pair a different Mac") }
                    }
                }
                if (notificationTab) {
                    NativeNotificationFeedView(feedProjection, scopedFeedSources, unreadNotificationsOnly,
                        notificationQuery.isNotBlank(), feedRefreshing, notificationNow, searchLocale, Modifier.weight(1f),
                        onOpen = { entry ->
                            workspaceRoute = null
                            inAppNotification = NotificationDestination(java.util.UUID.randomUUID().toString(),
                                entry.source.mac.origin, entry.notification.id, entry.notification.workspaceId,
                                entry.notification.surfaceId, entry.notification.retargetsToLiveSurfaceOwner)
                        }, onRead = ::setNotificationRead,
                        onToggle = { feedSession.projection = feedProjection.toggle(it) },
                        onMore = { feedRowWindow += 300 }, onRefresh = ::refreshFeed)
                } else {
                    val matches = remember(workspaceSearch, search) { workspaceSearch.matches(search) }
                    val entries = workspaceEntries(workspaceSources, matches, search.isNotEmpty(), unreadWorkspacesOnly, collapsedGroups)
                    Box(Modifier.weight(1f)) {
                    val reorderSource = workspaceSources.singleOrNull()
                    val canReorder = reorderSource != null && reorderSource.canReorderWorkspaces() &&
                        (reorderSource.groups.isNotEmpty() || reorderSource.workspaces.none { it.isPinned }) &&
                        reorderSource.mac.code == connectedCode && search.isBlank() && !unreadWorkspacesOnly &&
                        (moveStatus[reorderSource.mac.origin]?.pending ?: 0) < 3
                    fun move(source: NativeFeedSource, id: String, intent: NativeWorkspaceMove): Boolean {
                        val accepted = workspaceMoves.enqueue(source, id, intent)
                        if (accepted) error = null
                        return accepted
                    }
                    NativeWorkspaceDragList(entries, canReorder, Modifier.fillMaxSize(), onMove = ::move, before = {
                        workspaceSources.filter { it.availability != NativeFeedAvailability.CONNECTED }.forEach { source ->
                            item("status:" + source.mac.origin) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${appearances.name(source.mac)} · ${if (source.availability == NativeFeedAvailability.CONNECTING) "Connecting…" else "Unavailable"}",
                                        Modifier.weight(1f), color = nativeMuted, fontSize = 12.sp)
                                    TextButton(onClick = { refreshFeed() }) { Text("Retry") }
                                }
                            }
                        }
                    }, empty = {
                        Text(if (unreadWorkspacesOnly) "No unread workspaces." else "No workspaces found.",
                            Modifier.padding(24.dp), color = nativeMuted)
                    }) { entry ->
                            val owner = entry.source
                            if (entry is WorkspaceListEntry.Header) {
                                val group = entry.group
                                NativeGroupHeaderRow(group,
                                    expanded = !group.isCollapsed,
                                    unread = entry.unread,
                                    onOpen = group.liveAnchorWorkspaceId?.takeIf { id -> owner.workspaces.any { it.id == id } }?.let { anchor ->
                                        { inAppNotification = null; workspaceRoute = NativeWorkspaceRoute(owner.mac.origin, anchor) }
                                    },
                                    canEdit = "workspace.group_actions.v1" in owner.capabilities,
                                    onToggle = {
                                        collapsedGroups = collapsedGroups + (entry.key to !group.isCollapsed)
                                        store.update { it.put("collapsed_groups", JSONObject(collapsedGroups)) }
                                    },
                                    onAction = { action, title -> scope.launch {
                                        try { feedCoordinator.groupAction(owner.mac, group.id, action, title); error = null }
                                        catch (failure: Exception) {
                                            if (failure is CancellationException) throw failure
                                            error = failure.message
                                        }
                                    } })
                            } else if (entry is WorkspaceListEntry.Footer) {
                                Spacer(Modifier.fillMaxWidth().height(16.dp).semantics { contentDescription = "End of ${entry.group.name}" })
                            } else {
                            val workspace = (entry as WorkspaceListEntry.Workspace).workspace
                            fun open(terminalId: String? = null, browserId: String? = null, changes: Boolean = false, surfaceId: String? = null) {
                                inAppNotification = null
                                workspaceRoute = NativeWorkspaceRoute(owner.mac.origin, workspace.id, terminalId, browserId, changes, surfaceId = surfaceId)
                            }
                            Column(Modifier.padding(start = if (entry.indented) 18.dp else 0.dp)
                                .semantics { contentDescription = "${workspace.title} on ${appearances.name(owner.mac)}" }) {
                            NativeWorkspaceRow(
                                workspace = workspace, groups = owner.groups,
                                computer = appearances.name(owner.mac).takeIf { selectedOrigin == null && pairedMacs.size > 1 },
                                appearance = appearances.get(owner.mac), machineId = owner.mac.deviceId,
                                machineColorIndex = machineColorIndices[owner.mac.deviceId],
                                canMove = canReorder && (owner.groups.none { it.liveAnchorWorkspaceId == workspace.id }),
                                onOpen = { open() },
                                onAction = { action, title ->
                                    if (action == "changes") open(changes = true)
                                    else if (action == "browser.create") openNewBrowser(owner, workspace)
                                    else if (action == "terminal.create") createTerminal(owner, workspace)
                                    else if (action.startsWith("move:")) {
                                        val target = action.removePrefix("move:").takeIf { it.isNotBlank() }
                                        move(owner, workspace.id, NativeWorkspaceMove(target, null))
                                    } else {
                                        val entryContext = browserNavigationContext()
                                        val entryNavigation = navigationGeneration.observe(entryContext)
                                        val entryLogin = browserLogin
                                        val entryOwner = teamState.scope
                                        fun stillCurrent() = signedIn && store.taskSession() == entryLogin && teamState.scope == entryOwner &&
                                            navigationGeneration.matches(entryNavigation, browserNavigationContext()) && store.pairedMacs().contains(owner.mac) && connection.allowsSaved(owner.mac)
                                        scope.launch {
                                            try {
                                                feedCoordinator.workspaceAction(owner.mac, workspace.id, action, title)
                                                if (stillCurrent()) error = null
                                            } catch (failure: Exception) {
                                                if (failure is CancellationException) throw failure
                                                if (stillCurrent()) error = failure.message
                                            }
                                        }
                                    }
                                }
                            )
                            workspace.macSurfaces.forEach { surface ->
                                NativeSurfaceShortcut(surface, nativeAccent) { open(surfaceId = surface.id) }
                            }
                            workspace.browsers.forEach { browser ->
                                Text("▣  ${browser.title.ifBlank { "Browser" }}",
                                    Modifier.fillMaxWidth().clickable { open(browserId = browser.id) }
                                        .padding(start = 80.dp, top = 4.dp, bottom = 12.dp),
                                    color = nativeAccent, fontSize = 12.sp)
                            }
                            }
                            HorizontalDivider(color = Color(0xFF292C31))
                            }
                    }
                    if (searchState.active == null) NativeTaskComposerButton(
                        Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 2.dp), enabled = true) {
                        finishSearch(); newTaskDraft()
                    }
                    }
                }
                NativePrimaryNavigation(notificationTab, feedEntries.count { !it.notification.isRead }, searchState,
                    onTab = { workspaceRoute = null; finishSearch(); notificationTab = it },
                    onBeginSearch = { searchState = searchState.begin(searchScope) },
                    onEdit = { value, generation -> searchState = searchState.edit(value, searchScope, generation) },
                    onSubmit = { finishSearch() }, onCancel = { finishSearch(cancel = true) })
            }
        }
        val visibleError = error ?: connectionError.takeIf { selectedTerminal != null || selectedBrowser != null || workspaceSources.isEmpty() }
        if (signedIn && visibleError != null) Row(Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(visibleError, Modifier.weight(1f).padding(vertical = 12.dp), color = Color(0xFFFFAAAA))
            if (selectedTerminal != null) TextButton(onClick = { retryDelay = 2_000; retry++ }) { Text("Reconnect") }
        }
    }
}

@Composable
private fun NativeHeader(title: String) {
    Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.cmux_logo), "cmux", Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}



@Composable
private fun NativeGroupHeaderRow(
    group: NativeGroup,
    expanded: Boolean,
    unread: NativeWorkspaceUnread,
    onOpen: (() -> Unit)?,
    canEdit: Boolean,
    onToggle: () -> Unit,
    onAction: (String, String?) -> Unit
) {
    var menuOpen by remember(group.id) { mutableStateOf(false) }
    var renaming by remember(group.id) { mutableStateOf(false) }
    var confirmingUngroup by remember(group.id) { mutableStateOf(false) }
    var name by remember(group.id) { mutableStateOf(group.name) }
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NativeUnreadGutter(unread, gap = 3.dp)
        IconButton(onClick = onToggle, modifier = Modifier.size(32.dp).semantics {
            contentDescription = "${if (expanded) "Collapse" else "Expand"} ${group.name}"
        }) { Icon(painterResource(if (expanded) R.drawable.ic_workspace_chevron_down else R.drawable.ic_workspace_chevron_right),
            null, Modifier.size(16.dp), tint = nativeMuted) }
        Row(Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .semantics(mergeDescendants = true) {
                if (onOpen != null) contentDescription = "Open ${group.name}"
                stateDescription = listOfNotNull("Pinned".takeIf { group.isPinned },
                    unread.accessibilityLabel.takeIf { it.isNotEmpty() }).joinToString(", ")
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(nativeWorkspaceGroupIcon(group.iconSymbol)), null, Modifier.size(15.dp), tint = nativeMuted)
            Text(group.name, Modifier.weight(1f, fill = false), color = Color.White,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (group.isPinned) Icon(painterResource(R.drawable.ic_workspace_pin_fill), null,
                Modifier.size(12.dp), tint = nativeMuted)
        }
        if (canEdit) Box {
            TextButton(onClick = { menuOpen = true }, modifier = Modifier.semantics {
                contentDescription = "Actions for ${group.name}"
            }) { Text("⋯", color = nativeMuted) }
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Rename group") }, onClick = {
                    menuOpen = false; name = group.name; renaming = true
                })
                DropdownMenuItem(text = { Text(if (group.isPinned) "Unpin group" else "Pin group") },
                    onClick = {
                        menuOpen = false
                        onAction(if (group.isPinned) "unpin" else "pin", null)
                    })
                DropdownMenuItem(text = { Text("Ungroup workspaces") }, onClick = {
                    menuOpen = false; confirmingUngroup = true
                })
            }
        }
    }
    if (renaming) AlertDialog(
        onDismissRequest = { renaming = false },
        title = { Text("Rename group") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            renaming = false; onAction("rename", name)
        }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
    )
    if (confirmingUngroup) AlertDialog(
        onDismissRequest = { confirmingUngroup = false },
        title = { Text("Ungroup ${group.name}?") },
        text = { Text("The workspaces will stay open.") },
        confirmButton = { TextButton(onClick = {
            confirmingUngroup = false; onAction("ungroup", null)
        }) { Text("Ungroup") } },
        dismissButton = { TextButton(onClick = { confirmingUngroup = false }) { Text("Cancel") } }
    )
}

@Composable
private fun NativeWorkspaceRow(
    workspace: NativeWorkspace,
    groups: List<NativeGroup>,
    canMove: Boolean,
    computer: String? = null,
    appearance: NativeMacAppearance = NativeMacAppearance(), machineId: String? = null, machineColorIndex: Int? = null,
    onOpen: () -> Unit,
    onAction: (String, String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var title by remember(workspace.id) { mutableStateOf(workspace.title) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen)
        .semantics {
            stateDescription = listOfNotNull("Pinned".takeIf { workspace.isPinned },
                workspace.unreadState.accessibilityLabel.takeIf { it.isNotEmpty() }).joinToString(", ")
        }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        NativeUnreadGutter(workspace.unreadState)
        NativeMacAvatar(appearance, machineId ?: workspace.id, index = machineColorIndex, defaultSymbol = "terminal")
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            computer?.let { Text(it, color = nativeMuted, fontSize = 10.sp, maxLines = 1) }
            Text(workspace.title.ifBlank { "Workspace" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(workspace.preview ?: workspace.directory ?: workspace.terminals.firstOrNull()?.title.orEmpty(),
                color = nativeMuted, fontSize = 11.sp, maxLines = 1)
        }
        workspace.lastActivityAt?.let { seconds ->
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((seconds * 1000).toLong())),
                color = nativeMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.semantics {
                contentDescription = "Actions for ${workspace.title.ifBlank { "Workspace" }}"
            }) { Text("⋯", color = nativeMuted, fontSize = 20.sp) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("View changes") }, onClick = {
                    expanded = false; onAction("changes", null)
                })
                DropdownMenuItem(text = { Text("New terminal") }, onClick = {
                    expanded = false; onAction("terminal.create", null)
                })
                DropdownMenuItem(text = { Text("New browser") }, onClick = {
                    expanded = false; onAction("browser.create", null)
                })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; title = workspace.title; rename = true })
                DropdownMenuItem(text = { Text(if (workspace.isPinned) "Unpin" else "Pin") }, onClick = {
                    expanded = false; onAction(if (workspace.isPinned) "unpin" else "pin", null)
                })
                DropdownMenuItem(text = { Text(if (workspace.hasUnread) "Mark read" else "Mark unread") }, onClick = {
                    expanded = false; onAction(if (workspace.hasUnread) "mark_read" else "mark_unread", null)
                })
                if (canMove) {
                    groups.filter { it.id != workspace.groupId }.forEach { group ->
                        DropdownMenuItem(text = { Text("Move to ${group.name}") }, onClick = {
                            expanded = false; onAction("move:${group.id}", null)
                        })
                    }
                    if (workspace.groupId != null) DropdownMenuItem(text = { Text("Remove from group") }, onClick = {
                        expanded = false; onAction("move:", null)
                    })
                }
                DropdownMenuItem(text = { Text("Close workspace", color = Color(0xFFFF9999)) }, onClick = {
                    expanded = false; confirmClose = true
                })
            }
        }
    }
    if (rename) AlertDialog(
        onDismissRequest = { rename = false },
        title = { Text("Rename workspace") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { rename = false; onAction("rename", title) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } }
    )
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Close ${workspace.title}?") },
        text = { Text("Running terminals in this workspace may stop.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onAction("close", null) }) { Text("Close", color = Color(0xFFFF9999)) } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } }
    )
}

private fun nativeConnectionFailure(failure: Throwable): String {
    val networkFailure = generateSequence(failure) { it.cause }.take(8).any {
        it is java.net.SocketException || it is java.net.SocketTimeoutException ||
            it is java.net.UnknownHostException || it is java.io.EOFException
    }
    return if (networkFailure) "Could not reach this Mac. Check that cmux is running, mobile pairing is enabled, and both devices are online, then retry."
        else failure.message ?: "Could not connect to this Mac."
}



@Composable
private fun NativeComputerPicker(
    teamState: NativeAccountTeamsState, computerState: NativeComputersState, runtime: NativeIrohRuntime? = null,
    colorIndices: Map<String, Int> = emptyMap(),
    connections: Map<NativeMacIdentity, NativeComputerConnection> = emptyMap(),
    forgetCallbacks: NativeComputerForgetCallbacks = NativeComputerForgetCallbacks(),
    presentDetails: ((NativeComputerDetailsPresentation) -> Unit)? = null,
    onSsh: (() -> Unit)? = null,
    hasSavedComputers: Boolean, onSelect: (IrohV2Computer) -> Unit, onSettings: () -> Unit,
    onRefresh: () -> Unit, onPairing: (String) -> Unit, onNewTask: () -> Unit,
    onUseHelper: () -> Unit, onLicenses: () -> Unit, onError: (String?) -> Unit
) {
    val context = LocalContext.current
    val appearances = nativeMacAppearances(computerState.account)
    var pairingText by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { NativeHeader("Computers") }
        TextButton(onClick = { onSettings() }) { Text("Settings") }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
        val selectedTeam = teamState.teams.firstOrNull { it.id == teamState.selectedTeamId }
        Text(selectedTeam?.name ?: "Your cmux account", fontWeight = FontWeight.SemiBold)
        Text("Open cmux on your Mac and enable mobile pairing to see it here.", color = nativeMuted)
        if (teamState.loading || computerState.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            Text("Finding your computers…", color = nativeMuted)
        }
        (teamState.error ?: computerState.error)?.let {
            Text(it, color = Color(0xFFFF9999), modifier = Modifier.padding(vertical = 10.dp))
        }
        if (computerState.ready && computerState.computers.isEmpty()) {
            Text("No computers available in this team.", color = nativeMuted,
                modifier = Modifier.padding(vertical = 16.dp))
        }
        computerState.computers.forEach { mac ->
            Surface(Modifier.fillMaxWidth().padding(top = 12.dp).clickable {
                onSelect(mac)
            }, shape = RoundedCornerShape(14.dp), color = nativePanel) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    NativeMacAvatar(appearances.get(mac.deviceId, mac.buildTag), mac.deviceId,
                        index = colorIndices[mac.deviceId])
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(appearances.get(mac.deviceId, mac.buildTag).displayName(mac.name), fontWeight = FontWeight.Medium)
                        Text("Available", color = nativeMuted, fontSize = 12.sp)
                    }
                    val connection = connections[NativeMacIdentity(mac.deviceId, mac.buildTag)] ?: NativeComputerConnection()
                    NativeMacAwakeIndicator(connection)
                    NativeComputerDetailsButton(runtime, computerState, NativeComputerTarget.from(mac), colorIndices[mac.deviceId], connection, forgetCallbacks, presentDetails)
                    Text("›", color = nativeMuted, fontSize = 24.sp)
                }
            }
        }
        TextButton(onClick = onRefresh, enabled = !teamState.loading) { Text("Refresh computers") }
        Spacer(Modifier.height(20.dp))
        var showPairingOptions by remember { mutableStateOf(false) }
        TextButton(onClick = { showPairingOptions = !showPairingOptions }) {
            Text(if (showPairingOptions) "Hide pairing options" else "Scan or paste a pairing code")
        }
        if (showPairingOptions) {
        Button(onClick = {
            runCatching {
                GmsBarcodeScanning.getClient(context,
                    GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .enableAutoZoom().build()).startScan()
            }.onSuccess { scan -> scan.addOnSuccessListener { barcode ->
                val scanned = barcode.rawValue.orEmpty()
                onPairing(scanned)
            }.addOnFailureListener { onError(it.message) } }
                .onFailure { onError(it.message ?: "Could not open the QR scanner") }
        }, modifier = Modifier.fillMaxWidth()) { Text("Scan cmux QR code") }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pairingText, { pairingText = it }, Modifier.fillMaxWidth(), label = { Text("Or paste pairing code") })
        Button(onClick = {
            onPairing(pairingText)
        }, enabled = pairingText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
        TextButton(onClick = onNewTask, modifier = Modifier.fillMaxWidth()) { Text("New task") }
        TextButton(onClick = onUseHelper) { Text("Use existing helper connection") }
        TextButton(onClick = onLicenses) { Text("Open-source licenses") }
        onSsh?.let { TextButton(onClick = it, modifier = Modifier.testTag("computers.ssh")) { Text("SSH Computers") } }
        if (hasSavedComputers) TextButton(onClick = onSettings) { Text("Saved computers") }
    }
}


@Composable
internal fun NativeSignIn(sendCode: suspend (String) -> Unit, signIn: suspend (String) -> Unit,
                         onUseHelper: () -> Unit, onLicenses: () -> Unit, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    NativeHeader("Sign in to cmux")
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Use the same cmux account as your Mac.", color = nativeMuted)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it; codeSent = false; otp = ""; signInError = null },
            Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            scope.launch {
                busy = true; signInError = null; codeSent = false; otp = ""
                try { sendCode(email); codeSent = true }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    signInError = failure.message ?: "Could not send the code. Please try again."
                } finally { busy = false }
            }
        }, enabled = !busy && email.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy && !codeSent) "Sending code…" else if (codeSent) "Send a new code" else "Email me a sign-in code")
        }
        signInError?.let { Text(it, Modifier.fillMaxWidth().padding(vertical = 12.dp), color = Color(0xFFFFAAAA)) }
        if (codeSent) {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(otp, { otp = it.filter { char -> char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' }.take(6) },
                Modifier.fillMaxWidth(), label = { Text("Six-character code") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            Button(onClick = {
                scope.launch {
                    busy = true; signInError = null
                    try { signIn(otp); onSignedIn() }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        signInError = failure.message ?: "Could not sign in. Please try again."
                    } finally { busy = false }
                }
            }, enabled = !busy && otp.length == 6, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Signing in…" else "Sign in") }
        }
        TextButton(onClick = onUseHelper) { Text("Use existing helper connection") }
        TextButton(onClick = onLicenses) { Text("Open-source licenses") }
    }
}
}


@Composable
private fun NativeSavedComputerRows(macs: List<NativeCredentialStore.PairedMac>, appearances: NativeMacAppearances,
    colorIndices: Map<String, Int>, runtime: NativeIrohRuntime?, state: NativeComputersState,
    connections: Map<NativeMacIdentity, NativeComputerConnection>, forgetCallbacks: NativeComputerForgetCallbacks,
    presentDetails: (NativeComputerDetailsPresentation) -> Unit, onSelect: (NativeCredentialStore.PairedMac) -> Unit) {
    macs.forEach { mac ->
        Row(Modifier.fillMaxWidth().clickable { onSelect(mac) }
            .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeMacAvatar(appearances.get(mac), mac.deviceId, index = colorIndices[mac.deviceId])
            Spacer(Modifier.width(14.dp))
            val connection = connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: NativeComputerConnection()
            Column(Modifier.weight(1f)) {
                Text(appearances.name(mac))
                Text(connection.phrase, color = nativeMuted, fontSize = 12.sp)
            }
            NativeMacAwakeIndicator(connection)
            NativeSavedComputerDetailsButton(runtime, state, mac, colorIndices[mac.deviceId], connection, forgetCallbacks, presentDetails)
        }
    }
}

@Composable
private fun NativeComputerSelector(macs: List<NativeCredentialStore.PairedMac>, selected: NativeCredentialStore.PairedMac?,
    appearances: NativeMacAppearances, colorIndices: Map<String, Int>,
    connections: Map<NativeMacIdentity, NativeComputerConnection>, open: Boolean, onOpen: (Boolean) -> Unit,
    onSelect: (NativeCredentialStore.PairedMac?) -> Unit, onPair: () -> Unit) {
    Box {
        IconButton(onClick = { onOpen(true) }, modifier = Modifier.semantics {
            contentDescription = "Computer filter"
            stateDescription = selected?.let(appearances::name) ?: "All Computers"
        }) {
            if (selected == null) Icon(painterResource(R.drawable.ic_feed_computer), null,
                tint = nativeMuted, modifier = Modifier.size(22.dp))
            else NativeMacAvatar(appearances.get(selected), selected.deviceId, Modifier.size(28.dp), colorIndices[selected.deviceId])
        }
        DropdownMenu(open, onDismissRequest = { onOpen(false) }) {
            DropdownMenuItem(text = { Text("All Computers") }, onClick = { onSelect(null) },
                leadingIcon = { Text(if (selected == null) "✓" else " ") })
            macs.forEach { mac ->
                DropdownMenuItem(text = { Text(appearances.name(mac)) }, onClick = { onSelect(mac) },
                    leadingIcon = { Text(if (selected?.origin == mac.origin) "✓" else " ") },
                    trailingIcon = { NativeMacAwakeIndicator(connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: NativeComputerConnection()) })
            }
            DropdownMenuItem(text = { Text("Pair another Mac") }, onClick = onPair)
        }
    }
}

@Composable
private fun NativePairingConfirmation(code: String?, onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    if (code == null) return
    val proposed = PairingCodeParser.parse(code).getOrNull() as? PairingCode.Tailscale ?: return
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Connect to this Mac?") },
        text = { Column {
            Text("cmux will send your account session to this Mac over Tailscale.")
            Spacer(Modifier.height(10.dp))
            proposed.routes.forEach { route -> Text("${route.host}:${route.port}", color = nativeAccent) }
            Spacer(Modifier.height(10.dp))
            Text("Continue only if this address came from your Mac’s pairing QR.", color = nativeMuted)
        } },
        confirmButton = { TextButton(onClick = { onConnect(code) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun NativeTerminalPreferenceSettings(folderTapEnabled: Boolean, showMissingArtifacts: Boolean,
    artifactPreferences: android.content.SharedPreferences) {
    Text("TERMINAL", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = nativeMuted, fontSize = 11.sp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Open Folders on Tap", Modifier.weight(1f))
        Switch(folderTapEnabled, onCheckedChange = { artifactPreferences.edit().putBoolean("terminal-folder-tap", it).apply() },
            modifier = Modifier.semantics { contentDescription = "Open Folders on Tap" })
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Show Missing Files")
            Text("Keep files that were deleted or moved in the Files list", color = nativeMuted, fontSize = 12.sp)
        }
        Switch(showMissingArtifacts, onCheckedChange = { artifactPreferences.edit().putBoolean("show-missing-files", it).apply() },
            modifier = Modifier.semantics { contentDescription = "Show Missing Files" })
    }
}
