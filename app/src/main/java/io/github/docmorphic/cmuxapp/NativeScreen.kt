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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
    val presenceStates = remember(sharedConnections) {
        sharedConnections?.presence?.state ?: kotlinx.coroutines.flow.MutableStateFlow(NativeMacPresenceState())
    }
    val presenceState by presenceStates.collectAsState()
    val compatibilityStates = remember(sharedConnections) {
        sharedConnections?.compatibility?.gate?.warnings
            ?: kotlinx.coroutines.flow.MutableStateFlow<Map<MacCompatibilityKey, MacCompatibilityViolation>>(emptyMap())
    }
    val compatibilityWarnings by compatibilityStates.collectAsState()
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
    val whatsNewModel = remember(runtimeOwner, connector) {
        if (connector == null) ViewModelProvider(runtimeOwner, NativeWhatsNewViewModel.Factory(context))
            .get(NativeWhatsNewViewModel::class.java) else null
    }
    val whatsNewCenter = whatsNewModel?.center?.collectAsState()?.value
    val whatsNewState = whatsNewCenter?.state?.collectAsState()?.value
    val terminalInputs = feedSession.terminalInputs
    val terminalSizing = feedSession.terminalSizing
    val terminalSizingStates by terminalSizing.state.collectAsState()
    val scope = rememberCoroutineScope()
    val terminalFocusRequester = remember { FocusRequester() }
    var signedIn by remember { mutableStateOf(account.isSignedIn()) }
    val accountDeletion = remember(sharedConnections, account, store) {
        sharedConnections?.accountDeletion ?: NativeAccountDeletionController(scope, store::load, store::update) { login ->
            NativeAccountDeletionClient({ account.deletionCredentials(login) }, { store.taskSession() == login }).delete()
        }
    }
    val deletionReceipt by accountDeletion.state.collectAsState()
    LaunchedEffect(store, accountDeletion) {
        store.revisions.collect { accountDeletion.reconcile(); signedIn = account.isSignedIn() }
    }
    val accountTeams = remember(account, store) { sharedConnections?.teams ?: NativeAccountTeams(account, store) }
    val teamState by accountTeams.state.collectAsState()
    val historyRevision by store.revisions.collectAsState()
    val cachedComputers = remember(store, historyRevision, teamState, signedIn) {
        if (signedIn) NativeCachedComputers.project(store.load(), store.taskSession(), teamState) else null
    }
    val cachedAppearanceState = remember(context, cachedComputers?.owner) {
        cachedComputers?.owner?.let { NativeMacAppearanceStore.display(context, it) }
    }
    val liveAppearances = nativeMacAppearances(teamState.scope)
    val appearances = cachedAppearanceState?.collectAsState()?.value ?: liveAppearances
    val scopedPresence = presenceState.takeIf { it.owner != null && it.owner == teamState.scope && accountTeams.isCurrent(it.owner) }
        ?: NativeMacPresenceState()
    val lastSeenHistory = remember(store, historyRevision) { runCatching { NativeMacLastSeen.values(store.load()) }.getOrDefault(emptyMap()) }
    DisposableEffect(accountTeams) { onDispose { if (sharedConnections == null) accountTeams.close() } }
    LaunchedEffect(signedIn, accountTeams) {
        if (!signedIn) accountTeams.clear()
    }
    var code by remember { mutableStateOf(store.load()?.optString("pairing_code").orEmpty()) }
    var pendingPairingCode by rememberSaveable(signedIn) { mutableStateOf<String?>(null) }
    var pairingSelectionCode by remember(signedIn) { mutableStateOf<String?>(null) }
    var startedForegroundConnection by rememberSaveable(signedIn) { mutableStateOf(false) }
    val deferStartupForPairing = !startedForegroundConnection && (incomingCode != null || pendingPairingCode != null)
    var error by remember { mutableStateOf<String?>(null) }
    var connectionError by remember(code) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var retryDelay by remember { mutableLongStateOf(2_000) }
    var client by remember { mutableStateOf<MobileRpcClient?>(null) }
    var connectedCode by remember { mutableStateOf<String?>(null) }
    var connectionReady by remember { mutableStateOf(false) }
    var hostName by remember(code) { mutableStateOf("cmux") }
    var hostCapabilities by remember(code) { mutableStateOf<Set<String>>(emptySet()) }
    var savedPairedMacs by remember { mutableStateOf(store.pairedMacs()) }
    LaunchedEffect(store, historyRevision) { savedPairedMacs = store.pairedMacs() }
    val eligibleMacs = savedPairedMacs.filter {
        connection.allowsSaved(it)
    }
    val hiddenOrigins = remember(store, historyRevision) { NativeComputerVisibility.hiddenOrigins(store.load()) }
    val hiddenMacs = eligibleMacs.filter { NativeComputerVisibility.isHidden(hiddenOrigins, it) }
    val pairedMacs = eligibleMacs.filterNot { NativeComputerVisibility.isHidden(hiddenOrigins, it) }
    val computerPreferenceStore = remember(context, teamState.scope) {
        teamState.scope?.let { NativeMacConnectionStore.create(context.applicationContext, it) }
    }
    val cachedPreferenceState = remember(context, cachedComputers?.owner) {
        cachedComputers?.owner?.let { NativeMacConnectionStore.display(context, it) }
    }
    val computerPreferences = (cachedPreferenceState ?: computerPreferenceStore?.state)?.collectAsState()?.value
        ?: NativeMacConnectionPreferences()
    val displayedMacs = cachedComputers?.macs ?: eligibleMacs
    val displayedHidden = displayedMacs.filter { NativeComputerVisibility.isHidden(hiddenOrigins, it) }
    val displayedVisible = displayedMacs.filterNot { NativeComputerVisibility.isHidden(hiddenOrigins, it) }
    val currentDirectory = computerState.computers.takeIf { computerState.account == teamState.scope }.orEmpty()
    val tailscaleRouteLabels = remember(store, historyRevision, teamState.scope, pairedMacs, currentDirectory, cachedComputers) {
        cachedComputers?.tailscaleRoutes ?: runCatching {
            val targets = pairedMacs.mapNotNull { mac -> mac.instanceTag?.let { NativeComputerTarget(mac.deviceId, it, mac.name) } } +
                currentDirectory.map(NativeComputerTarget::from)
            NativeComputerList.tailscaleRoutes(store.load(), teamState.scope, targets)
        }.getOrDefault(emptyMap())
    }
    val machineColorIndices = feedSession.macColorSlots.select(if (signedIn) store.taskSession() else null,
        teamState.scope, pairedMacs, computerState.computers.takeIf { computerState.account == teamState.scope }.orEmpty(),
        foreground = pairedMacs.singleOrNull { connectionReady && it.code == connectedCode }?.colorIdentity)
    val paneSelection = feedSession.paneNavigation.select(if (signedIn) store.taskSession() else null,
        pairedMacs.singleOrNull { it.code == code }, teamState.scope)
    val onboardingPrefs = remember(context) { context.getSharedPreferences("native_onboarding", android.content.Context.MODE_PRIVATE) }
    val onboardingStore = remember(onboardingPrefs) { NativeOnboardingProgressStore(
        { onboardingPrefs.getString(NativeOnboardingProgressStore.KEY, null) },
        { onboardingPrefs.edit().putString(NativeOnboardingProgressStore.KEY, it).commit() }, forceComplete = connector != null) }
    var onboardingProgress by remember(onboardingStore) { mutableStateOf(onboardingStore.progress) }
    var replayOnboarding by rememberSaveable(signedIn) { mutableStateOf(false) }
    var onboardingPermissionBusy by rememberSaveable(signedIn) { mutableStateOf(false) }
    var onboardingPermissionResult by rememberSaveable(signedIn) { mutableLongStateOf(0L) }
    var permissionOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionForOnboarding by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable(signedIn) { mutableStateOf(false) }
    var computersOwner by remember(signedIn) { mutableStateOf<NativeComputerMenuOwner?>(null) }
    var computersReturnToSettings by remember { mutableStateOf(false) }
    LaunchedEffect(showSettings) { if (!showSettings) computersOwner = null }
    var showReconnectList by rememberSaveable(signedIn) { mutableStateOf(false) }
    LaunchedEffect(connectionReady) { if (connectionReady) showReconnectList = false }
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
    var terminalMeasurement by remember { mutableStateOf(TerminalViewportMeasurement()) }
    val terminalViewportPixels = terminalMeasurement.visible
    val terminalImeInsets = WindowInsets.ime
    val terminalNavigationInsets = WindowInsets.navigationBars
    var selectedBrowser by paneSelection.browser
    var selectedChangesWorkspace by paneSelection.changesWorkspace
    LaunchedEffect(teamState.scope, signedIn) {
        val pairing = PairingCodeParser.parse(code).getOrNull()
        if (pairing is PairingCode.Iroh && (!signedIn || (teamState.scope != null && !connection.allowsSaved(pairing)))) {
            connection.pairingCompatibilityError(pairing)?.let { error = it }
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
    fun workspaceOwner(requestedCode: String = code) = store.visiblePairedMacs().singleOrNull {
        it.code == requestedCode && connection.allowsSaved(it)
    } ?: throw java.io.IOException("This workspace's saved Mac is no longer available.")
    val localBrowsers = feedSession.localBrowsers
    DisposableEffect(localBrowsers) { onDispose { localBrowsers.cancelRequest() } }
    val localBrowserState by localBrowsers.state.collectAsState()
    val localBrowser = localBrowserState.local
    val browserLogin = if (signedIn) store.taskSession() else null
    var showWhatsNew by rememberSaveable(signedIn, browserLogin) { mutableStateOf(false) }
    var notificationRecovery by remember(currentIncomingRoute, browserLogin) { mutableStateOf(NativeNotificationRouteRecovery()) }
    fun inputOwner(mac: NativeCredentialStore.PairedMac, login: String?) = nativeTerminalInputOwner(mac, login)
    SideEffect {
        val owner = pairedMacs.singleOrNull { it.code == code }?.let { inputOwner(it, browserLogin) }
        terminalInputs.retainOwner(owner); terminalSizing.retainOwner(owner)
    }
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
            store.taskSession() == browserLogin && store.visiblePairedMacs().contains(owner) && connection.allowsSaved(owner)) {
            "Workspace connection changed. Reconnect to this Mac."
        }
    }
    var creatingTerminal by remember { mutableStateOf(false) }
    var creatingWorkspace by remember { mutableStateOf(false) }
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
                store.visiblePairedMacs().contains(source.mac) && connection.allowsSaved(source.mac) &&
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
        if (creatingTerminal || creatingWorkspace) return
        val entryNavigation = navigationGeneration.observe(browserNavigationContext())
        val entryLogin = browserLogin
        val entryOwner = teamState.scope
        fun stillCurrent() = signedIn && store.taskSession() == entryLogin && teamState.scope == entryOwner &&
            navigationGeneration.matches(entryNavigation, browserNavigationContext()) &&
            store.visiblePairedMacs().contains(source.mac) && connection.allowsSaved(source.mac)
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
    var customizationTarget by rememberSaveable(browserLogin, teamState.scope, stateSaver = workspaceCustomizationTargetSaver) {
        mutableStateOf<WorkspaceCustomizationTarget?>(null)
    }
    val customizationMac = pairedMacs.singleOrNull { it.origin == customizationTarget?.origin && connection.allowsSaved(it) }
    val customizationWorkspace = customizationMac?.let { feedSources[it.origin]?.workspaces?.singleOrNull { row -> row.id == customizationTarget?.workspaceId } }
    if (signedIn && customizationMac != null && customizationWorkspace != null) {
        NativeWorkspaceCustomizationSheet(customizationWorkspace, onDismiss = { customizationTarget = null }) { baseline, draft ->
            feedCoordinator.customizeWorkspace(customizationMac, customizationWorkspace.id, baseline, draft)
        }
    }

    fun workspaceSourceForPane(): NativeFeedSource? = pairedMacs.singleOrNull { it.code == code }?.let { mac ->
        feedSources[mac.origin] ?: NativeFeedSource(mac)
    }
    LaunchedEffect(feedSources) { feedSources.values.forEach(localBrowsers::observeWorkspaces) }
    val workspaceMoves = feedSession.workspaceMoves
    val moveSources by workspaceMoves.sources.collectAsState()
    val moveStatus by workspaceMoves.status.collectAsState()
    var selectedComputerOrigin by rememberSaveable(signedIn) {
        mutableStateOf(store.load()?.optString("computer_selection").orEmpty())
    }
    val selectedComputer = pairedMacs.firstOrNull { it.ownsOrigin(selectedComputerOrigin) }
    val selectedOrigin = selectedComputer?.origin
    var pendingPickerCode by remember(signedIn) { mutableStateOf<String?>(null) }
    var expectedReconnect by remember(signedIn) { mutableStateOf<NativeCredentialStore.PairedMac?>(null) }
    val pendingPickerComputer = pairedMacs.singleOrNull { it.code == pendingPickerCode }
    val macSwitchRecovery = feedSession.macSwitchRecovery
    fun switchOwner() = store.taskSession()?.takeIf { signedIn }?.let { NativeMacSwitchRecovery.Owner(it, teamState.scope) }
    macSwitchRecovery.reconcile(switchOwner())
    fun canRestoreMac(previous: NativeCredentialStore.PairedMac) = connection.allowsSaved(previous) && store.visiblePairedMacs().any {
        it.code == previous.code && it.deviceId == previous.deviceId && it.instanceTag == previous.instanceTag &&
            it.accountUserId == previous.accountUserId && it.accountTeamId == previous.accountTeamId
    }
    fun selectMacCode(target: String) {
        expectedReconnect = null
        pendingPickerCode = null
        pairingSelectionCode = null
        switchOwner()?.let { owner ->
            macSwitchRecovery.begin(owner, target,
                connectedCode.takeIf { connectionReady && client?.isClosed == false }, selectedComputerOrigin)
        }
        code = target; error = null; retryDelay = 2_000
    }
    fun selectPairingCode(target: String) {
        expectedReconnect = null
        pendingPickerCode = null
        screenResume.cancel(); workspaceRoute = null
        switchOwner()?.let { owner ->
            val storedCode = store.load()?.optString("pairing_code")
            val fallback = store.visiblePairedMacs().singleOrNull { it.code == storedCode && connection.allowsSaved(it) }
            macSwitchRecovery.begin(owner, target,
                connectedCode.takeIf { connectionReady && client?.isClosed == false }, selectedComputerOrigin, fallback)
        }
        pairingSelectionCode = target
        code = target; error = null; retryDelay = 2_000
    }
    fun selectComputer(mac: NativeCredentialStore.PairedMac?) {
        pendingPickerCode = null
        pairingSelectionCode = null
        screenResume.cancel()
        if (mac != null) selectMacCode(mac.code) else macSwitchRecovery.cancel()
        selectedComputerOrigin = mac?.origin.orEmpty()
        store.update { it.put("computer_selection", selectedComputerOrigin) }
        workspaceRoute = null
        computerMenuOpen = false
    }
    fun selectPickerComputer(mac: NativeCredentialStore.PairedMac?) {
        screenResume.cancel(); workspaceRoute = null; computerMenuOpen = false
        if (mac != null) {
            val isConnected = connectionReady && connectedCode == mac.code && client?.isClosed == false
            if (isConnected) selectComputer(mac) else {
                selectMacCode(mac.code)
                pendingPickerCode = mac.code
            }
        } else {
            val pendingCode = pendingPickerCode
            val restore = if (pendingCode != null)
                macSwitchRecovery.cancelAndRestore(switchOwner(), code, "", ::canRestoreMac) else null
            pendingPickerCode = null; pairingSelectionCode = null
            selectedComputerOrigin = ""
            store.update { it.put("computer_selection", "") }
            if (pendingCode != null && code == pendingCode) {
                // Changing the effect key retires the dial, including late callbacks.
                // Without an authorized baseline, return to Computers.
                code = restore?.mac?.code.orEmpty()
                if (restore != null) store.update { it.put("pairing_code", restore.mac.code) }
                retryDelay = 2_000; error = null
            } else macSwitchRecovery.cancel()
        }
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
    val canCreateInCurrentPane = connectionReady && client != null && connectedCode == code
    val computerConnections = nativeComputerConnections(pairedMacs, feedSources,
        activeCode = connectedCode.takeIf { connectionReady && client != null && it == code },
        pendingCode = code.takeIf { signedIn && it.isNotBlank() && !connectionReady && busy },
        foregroundWorkspaces = workspaces)
    NativeComputerDetailsPresentationHost(sharedConnections?.native, computerState, computerDetails,
        computerDetails?.target?.let { computerConnections[NativeMacIdentity(it.deviceId, it.buildTag)] } ?: NativeComputerConnection(),
        forgetCallbacks) { computerDetails = null }
    val visibleFeedSources = remember(feedSources, pairedMacs) {
        feedSources.values.filter { source -> pairedMacs.any { it.origin == source.mac.origin } }
    }
    val scopedFeedSources = remember(visibleFeedSources, selectedOrigin) {
        visibleFeedSources.filter { selectedOrigin == null || it.mac.origin == selectedOrigin }
    }
    val feedEntries = remember(visibleFeedSources, selectedOrigin, appearances) {
        aggregateNativeFeed(visibleFeedSources, selectedOrigin, appearances::name)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val terminalBells = remember(client, selectedWorkspace?.id, selectedTerminal?.id) { TerminalBellSignal() }
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
    LaunchedEffect(signedIn, pairedMacs, feedForeground, deferStartupForPairing, computerState.connectionKeys, computerState.localConnectionKeys) {
        if (!signedIn) feedSession.clear()
        else feedSession.configureFeed(pairedMacs, computerState.connectionKeys, computerState.localConnectionKeys,
            feedForeground && !deferStartupForPairing)
    }
    DisposableEffect(feedCoordinator) { onDispose { feedSession.leaveMainScreen() } }

    val notificationSyncMac = pairedMacs.singleOrNull { it.code == connectedCode }
    LaunchedEffect(client, lifecycle, browserLogin, notificationSyncMac, connectionReady) {
        val active = client ?: return@LaunchedEffect
        val mac = notificationSyncMac ?: return@LaunchedEffect
        val login = browserLogin ?: return@LaunchedEffect
        if (!connectionReady) return@LaunchedEffect
        // Returning to a retained terminal must catch up banners even when no
        // terminal subscription is rebuilt. Borrow the verified client only.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val attempt = currentCoroutineContext()
            fun isCurrent() = attempt.isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                signedIn && connectionReady && client === active && !active.isClosed &&
                code == mac.code && connectedCode == mac.code && store.taskSession() == login &&
                store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac)
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                reconcileNativeNotifications(NativeNotificationSync(
                    delivered = { notificationDelivery.deliveredIDs(mac.origin, ::isCurrent) },
                    handled = { notificationDelivery.clearHandled(mac.origin, it, ::isCurrent) }
                )) { ids ->
                    ensureActive()
                    if (!isCurrent()) throw CancellationException("Notification connection changed")
                    active.reconcileNotifications(ids)
                }
            }
        }
    }
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
    val emptyWorkspaceRecovery = remember(browserLogin, teamState.scope, selectedOrigin) { NativeWorkspaceEmptyRecovery(scope) }
    val emptyWorkspaceRecoveryState by emptyWorkspaceRecovery.state.collectAsState()
    DisposableEffect(emptyWorkspaceRecovery) { onDispose { emptyWorkspaceRecovery.close() } }
    LaunchedEffect(feedForeground, emptyWorkspaceRecovery) { if (!feedForeground) emptyWorkspaceRecovery.cancel() }
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
    var terminalActiveScreen by remember(draftTarget, client) { mutableStateOf("primary") }
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
    val displayState = rememberNativeDisplayPreferences(artifactPreferences)
    val currentScrollbackRows by rememberUpdatedState(displayState.scrollbackRows)
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
    val selectedSizing = selectedTerminal?.id?.let(terminalSizingStates::get)
    val terminalAttached = selectedSizing?.allowsTraffic ?: true
    val keepKeyboardGrid = terminalActiveScreen == "primary" || selectedSizing?.state != null || displayState.useFullTerminalHeight
    val terminalViewportPresentation = rememberTerminalViewportPresentation(client, selectedTerminal?.id,
        terminalMeasurement, keepKeyboardGrid)
    val terminalReportPixels = terminalViewportPresentation.pixels
    val keyboardPresentation = remember(client, selectedWorkspace?.id, selectedTerminal?.id) { TerminalKeyboardPresentation() }
    SideEffect {
        val target = terminalMeasurement.targetSize(terminalViewportPresentation.targetKeyboard)
        keyboardPresentation.transition(!keepKeyboardGrid && terminalAttached, terminalViewportPresentation.moving,
            terminalViewportPresentation.targetKeyboard, TerminalViewport.fit(target.width, target.height, terminalCells))
    }
    val terminalViewport = TerminalViewport.fit(terminalReportPixels.width, terminalReportPixels.height, terminalCells)
    val terminalColumns = terminalViewport?.columns ?: 0
    val terminalRows = terminalViewport?.rows ?: 0
    // Each viewport effect owns its own confirmation. Old acknowledgements/cleanup
    // cannot confirm or hide the chrome of a replacement surface or connection.
    var sizingViewportConfirmed by remember(client, selectedWorkspace?.id, selectedTerminal?.id,
        selectedTerminal?.isReady, terminalViewport, terminalCells, terminalTransport, terminalAttached,
        selectedSizing?.viewportRevision ?: 0L, selectedSizing?.reconnecting ?: false) { mutableStateOf(false) }
    var outputInput by remember(inputClient, inputTarget) { mutableStateOf<TerminalOutputLaneOwner?>(null) }
    val nativeInput = remember(inputClient, inputTarget, terminalTransport.mode, connectionReady, selectedTerminal?.isReady, terminalAttached) {
        if (terminalAttached && inputClient != null && inputTarget != null && selectedTerminal?.isReady == true && connectionReady && terminalTransport.mode == TerminalOutputMode.GRID)
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
    LaunchedEffect(client, terminalSizingStates) {
        client?.let { active -> terminalSizingStates.filterValues { !it.allowsTraffic }.keys.forEach {
            terminalInputs.forgetDetachedSurface(active, it)
        } }
    }
    val retainedInputQueue = remember(inputClient, inputTarget, hostCapabilities, terminalAttached) {
        if (terminalAttached && inputClient != null && inputTarget != null) terminalInputs.orderedQueue(inputClient, inputTarget.workspace, inputTarget.surface) else null
    }
    val inputQueue = remember(inputClient, inputTarget, nativeInput, retainedInputQueue) {
        retainedInputQueue ?: TerminalInputQueue(scope) { entry ->
            check(TerminalInputDelivery.CAPABILITY !in hostCapabilities) { "Could not reserve this terminal's input queue" }
            check(inputClient != null && inputTarget != null && client === inputClient &&
                code == inputTarget.pairing && signedIn && selectedTerminal?.id == inputTarget.surface && selectedTerminal?.isReady == true) { "Terminal connection changed" }
            inputClient.checkTerminalTraffic(inputTarget.surface)
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
            mac != null && store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac)
        if (mac == null || !admitted()) null
        else if (mac.code == code) {
            val active = client
            val workspace = target.resolve(workspaces)
            fun ready() = admitted() && active != null && client === active && !active.isClosed &&
                connectionReady && connectedCode == mac.code && code == mac.code && target.resolve(workspaces) == workspace &&
                active.terminalTrafficAllowed(target.surface)
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
        if (!terminalAttached || client == null || inputFailure != null || selectedTerminal?.isReady != true || drafts.state.value[target]?.operation != null) return false
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
        val owned = signedIn && permissionOwner != null && store.taskSession() == permissionOwner
        if (owned) {
            if (granted) runCatching { NativeNotificationService.setEnabled(context, true) }
                .onSuccess { backgroundNotifications = true; error = null }
                .onFailure { error = it.message }
            else error = "Notifications are not allowed. You can enable them later in Android Settings."
            if (permissionForOnboarding) onboardingPermissionResult++
        }
        onboardingPermissionBusy = false; permissionOwner = null; permissionForOnboarding = false
    }
    fun enableNotifications(fromOnboarding: Boolean) {
        if (!signedIn || onboardingPermissionBusy || permissionOwner != null) return
        permissionOwner = store.taskSession() ?: return
        permissionForOnboarding = fromOnboarding
        onboardingPermissionBusy = fromOnboarding
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (failure: Exception) {
                error = failure.message; onboardingPermissionBusy = false; permissionOwner = null; permissionForOnboarding = false
            }
        } else {
            runCatching { NativeNotificationService.setEnabled(context, true) }
                .onSuccess { backgroundNotifications = true; error = null }
                .onFailure { error = it.message }
            if (fromOnboarding) onboardingPermissionResult++
            onboardingPermissionBusy = false; permissionOwner = null; permissionForOnboarding = false
        }
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
                            val prepared = readComposerAttachment(::checkTarget, { error = it }) {
                                attachmentFiles.prepare(item.uri, item.image)
                            } ?: continue
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
        if (!terminalAttached || preparingAttachments || inputFailure != null || selectedTerminal?.isReady != true) return
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

    fun createWorkspace() {
        if (creatingWorkspace || creatingTerminal || !canCreateInCurrentPane) return
        val active = client ?: return
        val entryNavigation = navigationGeneration.observe(browserNavigationContext())
        val entryLogin = browserLogin
        val mac = pairedMacs.singleOrNull { it.code == code } ?: return
        fun stillCurrent() = signedIn && client === active && store.taskSession() == entryLogin &&
            navigationGeneration.matches(entryNavigation, browserNavigationContext()) &&
            store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac)
        rawKeyboardView?.finishComposition(); directTyping = false
        stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide()
        creatingWorkspace = true
        scope.launch {
            try {
                if (!stillCurrent()) return@launch
                requireWorkspaceConnection(active, mac)
                val response = workspaceSnapshots.mutate(mac) { active.request("workspace.create") }
                if (!stillCurrent()) return@launch
                val result = TaskCreationResult.parse(response, "workspace")
                val created = applyCreatedWorkspace(mac, result)
                refreshFeed(); notificationTab = false; error = null
                selectedSurface = null; selectedBrowser = null; selectedWorkspace = created
                selectedTerminal = created.terminals.firstOrNull { it.id == response.optString("created_terminal_id") }
                    ?: created.preferredTerminal
                selectedTerminal?.let { terminal ->
                    workspaceTabKey(entryLogin, teamState.scope, mac, created.id)?.let { key ->
                        workspaceTabs.cancel(); terminalStartup.begin(key, terminal)
                    }
                }
            } catch (failure: Exception) {
                recordWorkspaceActionFailure(failure)
            } finally { creatingWorkspace = false }
        }
    }

    fun createTerminalInPane() {
        if (!canCreateInCurrentPane) return
        val workspace = selectedWorkspace ?: return
        val source = workspaceSourceForPane() ?: return
        rawKeyboardView?.finishComposition(); directTyping = false
        stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide()
        createTerminal(source, workspace)
    }

    fun proposePairing(value: String) {
        PairingCodeParser.parse(value).fold(
            onSuccess = { pairing ->
                val compatibilityError = connection.pairingCompatibilityError(pairing)
                if (compatibilityError != null) error = compatibilityError
                else if (pairing is PairingCode.Tailscale) {
                    pendingPairingCode = value.trim()
                    error = null
                } else if (pairing is PairingCode.Iroh) {
                    val action = incomingPairingAction(value.trim(), signedIn, false, teamState.scope, computerState)
                    if (action is NativePairingLinkAction.Select && connection.allowsSaved(PairingCodeParser.parse(action.code).getOrThrow())) {
                        selectPairingCode(action.code)
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
        PairingCodeParser.parse(incoming).getOrNull()?.let(connection::pairingCompatibilityError)?.let {
            error = it; handlePairing(incoming); return@LaunchedEffect
        }
        val action = incomingPairingAction(incoming, signedIn,
            alreadySelected = incoming == code && pairedMacs.any { it.code == code }, teamState.scope, computerState)
        when (action) {
            NativePairingLinkAction.Wait -> return@LaunchedEffect
            NativePairingLinkAction.Consumed -> Unit
            NativePairingLinkAction.Confirm -> { pendingPairingCode = incoming; error = null }
            NativePairingLinkAction.Unavailable -> error = "This Mac is not available in your selected team. Check its Mobile settings and refresh Computers."
            is NativePairingLinkAction.Select -> {
                if (connection.allowsSaved(PairingCodeParser.parse(action.code).getOrThrow())) {
                    pendingPairingCode = null; selectPairingCode(action.code)
                } else error = "This Mac is not available in your selected team."
            }
        }
        handlePairing(incoming)
    }
    val pairingLookup = incomingCode?.takeIf { signedIn && PairingCodeParser.parse(it).getOrNull() is PairingCode.Iroh }
    LaunchedEffect(pairingLookup, browserLogin) {
        val incoming = pairingLookup ?: return@LaunchedEffect
        delay(30_000)
        error = "This Mac could not be found. Check its Mobile settings and try the pairing link again."
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
    val routeLogin = browserLogin
    LaunchedEffect(incomingNotificationRoute, routeInAppNotification?.routeId, routeSignedIn, routeLogin,
        routePairingCode, routeConnectedCode, routeClient, teamState.scope, pairedMacs, notificationRecovery.retryGeneration) {
        val routeId = incomingNotificationRoute ?: routeInAppNotification?.routeId ?: return@LaunchedEffect
        val openedFromFeed = incomingNotificationRoute == null
        fun consumeRoute() {
            if (openedFromFeed) { if (inAppNotification?.routeId == routeId) inAppNotification = null }
            else handleNotification(routeId)
        }
        if (!routeSignedIn || currentIncomingRoute != routeId || (connector == null && teamState.scope == null)) return@LaunchedEffect
        val route = if (openedFromFeed) routeInAppNotification else notificationDelivery.destination(routeId)
        val mac = store.visiblePairedMacs().singleOrNull { it.ownsOrigin(route?.origin) && connection.allowsSaved(it) }
        if (route == null || mac == null || (route.login != null && route.login != routeLogin)) {
            error = "This notification's saved Mac is no longer available."
            notificationRecovery = notificationRecovery.complete()
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
        if (!notificationRecovery.allows(active)) return@LaunchedEffect
        notificationRecovery = notificationRecovery.resolving()
        fun isCurrent() = currentIncomingRoute == routeId && client === active && code == mac.code &&
            signedIn && store.taskSession() == routeLogin && store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac)
        var navigationCommitted = false
        try {
            val feed = parseNotifications(active.notifications())
            val listing = workspaceSnapshots.read(mac, active)
            if (!isCurrent() || !notificationRecovery.allows(active)) return@LaunchedEffect
            listing.requireCurrent()
            val notification = feed.firstOrNull { it.id == route.notificationId } ?: route.notification()
            val available = listing.workspaces
            val workspace = notification.destination(available)
            val exactBrowser = workspace?.browsers?.firstOrNull { it.id == notification.surfaceId }
            val surface = workspace?.macSurfaces?.firstOrNull { it.id == notification.surfaceId }
            val terminal = workspace?.terminals?.firstOrNull { it.id == notification.surfaceId }
                ?: if (notification.surfaceId == null && exactBrowser == null && surface == null) workspace?.terminals?.firstOrNull() else null
            val browser = exactBrowser ?: if (notification.surfaceId == null && terminal == null && surface == null) workspace?.browsers?.firstOrNull() else null
            if (workspace == null || (terminal == null && browser == null && surface == null)) {
                error = "This notification's workspace or tab is no longer available."
                notificationRecovery = notificationRecovery.complete()
                consumeRoute()
                return@LaunchedEffect
            }
            val opened = withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (!isCurrent() || !notificationRecovery.allows(active)) false else {
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
            navigationCommitted = true
            notificationRecovery = notificationRecovery.complete()
            // Navigation is committed. A failed read acknowledgment never targets a different session.
            active.markNotificationRead(notification.id)
            if (!isCurrent()) return@LaunchedEffect
            notifications = feed.map { if (it.id == notification.id) it.copy(isRead = true) else it }
            scope.launch { feedCoordinator.refresh() }
            error = null
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (isCurrent()) {
                if (!navigationCommitted) {
                    notificationRecovery = notificationRecovery.failed(active, failure.message ?: "Could not open this notification")
                    return@LaunchedEffect
                }
                error = failure.message ?: "Could not mark this notification read"
            }
        }
        if (isCurrent()) consumeRoute()
    }

    LaunchedEffect(connectionError, busy, notificationRecovery.phase) {
        if (currentIncomingRoute != null && !busy && connectionError != null &&
            notificationRecovery.phase == NativeNotificationRouteRecovery.Phase.RECONNECTING)
            notificationRecovery = notificationRecovery.failed(client, checkNotNull(connectionError))
    }
    if (currentIncomingRoute != null && signedIn) {
        val recoveryRoute = currentIncomingRoute
        NativeNotificationRecoveryDialog(notificationRecovery, onRetry = {
            if (currentIncomingRoute == recoveryRoute && notificationRecovery.phase == NativeNotificationRouteRecovery.Phase.FAILED) {
                notificationRecovery = notificationRecovery.retry(client)
                connectionError = null; error = null; retryDelay = 2_000; retry++
            }
        }, onCancel = {
            if (currentIncomingRoute == recoveryRoute) {
                notificationRecovery = notificationRecovery.complete()
                if (incomingNotificationRoute != null) handleNotification(checkNotNull(recoveryRoute)) else inAppNotification = null
            }
        })
    }

    val capturedWorkspaceRoute = workspaceRoute
    LaunchedEffect(capturedWorkspaceRoute?.id, routeSignedIn, routePairingCode, routeConnectedCode, routeClient) {
        val route = capturedWorkspaceRoute ?: return@LaunchedEffect
        if (!routeSignedIn) { screenResume.cancel(); screenResume.complete(); workspaceRoute = null; return@LaunchedEffect }
        val mac = store.visiblePairedMacs().singleOrNull { it.ownsOrigin(route.origin) && connection.allowsSaved(it) }
        if (mac == null || (route.creation != null && (route.creationLogin == null || route.creationLogin != store.taskSession())) ||
            (route.resume != null && !route.resume.matches(store.taskSession(), teamState.scope, mac))) {
            screenResume.cancel()
            error = "This workspace's saved Mac is no longer available."
            workspaceRoute = null
            return@LaunchedEffect
        }
        val browserKey = localBrowserKey(browserLogin, teamState.scope, mac, route.workspaceId)
        val browserWorkspace = workspaceSources.firstOrNull { it.mac.origin == mac.origin }?.workspaces?.firstOrNull { it.id == route.workspaceId }
        val explicitPane = route.terminalId != null || route.browserId != null || route.surfaceId != null || route.changes || route.creation != null
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
            store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac) &&
            (route.creation == null || route.creationLogin == store.taskSession()) &&
            (route.resume == null || (screenResume.pending == route.resume && route.resume.matches(store.taskSession(), teamState.scope, mac)))
        try {
            // Opening a known pane is navigation, not evidence that an inventory is current.
            // An unrelated in-flight mutation must not block that explicit user action.
            val cachedDuringMutation = browserWorkspace?.takeIf { route.creation == null && route.createdWorkspace == null && workspaceSnapshots.hasMutation(mac) }
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
            check(route.creation != null || route.changes || pane != null || workspaceTabs.pending.value != null) { "This workspace pane is no longer available." }
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
                    when (route.creation) {
                        NativeWorkspaceCreation.WORKSPACE -> createWorkspace()
                        NativeWorkspaceCreation.TERMINAL -> createTerminal(feedSources[mac.origin] ?: NativeFeedSource(mac), workspace)
                        NativeWorkspaceCreation.BROWSER -> openNewBrowser(feedSources[mac.origin] ?: NativeFeedSource(mac), workspace)
                        null -> Unit
                    }
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

    LaunchedEffect(signedIn, code, retry, deferStartupForPairing,
        computerState.connectionKey(PairingCodeParser.parse(code).getOrNull() as? PairingCode.Iroh)) {
        if (deferStartupForPairing) return@LaunchedEffect
        if (pendingPickerCode != code) pendingPickerCode = null
        if (pairingSelectionCode != code) pairingSelectionCode = null
        val switchAttempt = macSwitchRecovery.entering(switchOwner(), code)
        connectionReady = false
        client?.close(); client = null; connectedCode = null
        if (!signedIn || code.isBlank()) return@LaunchedEffect
        startedForegroundConnection = true
        val requestedCode = code
        val capturedReconnect = expectedReconnect?.takeIf { it.code == requestedCode }
        fun requireCurrentReconnect() {
            check(store.pairedMacs().none { it.code == requestedCode &&
                NativeComputerVisibility.isHidden(store.load(), it) }) { "This computer is hidden on this phone. Show it in Computers before connecting." }
            check(capturedReconnect == null || (NativeComputerMenuPairing.isCurrent(capturedReconnect, store.visiblePairedMacs()) &&
                connection.allowsSaved(capturedReconnect))) { "This saved computer changed. Choose it again from Computers." }
        }
        busy = true
        try {
            val pairing = PairingCodeParser.parse(requestedCode).getOrThrow()
            val pairingOwner = if (sharedConnections != null) kotlinx.coroutines.withTimeout(30_000) {
                accountTeams.state.first { it.scope != null || it.error != null }.scope
                    ?: error("Refresh your account teams before connecting.")
            } else null
            requireCurrentReconnect()
            val saved = store.visiblePairedMacs().singleOrNull { it.code == requestedCode && connection.allowsSaved(it) }
            if (pairingOwner != null) check(accountTeams.isCurrent(pairingOwner)) { "Account or team changed. Reconnect to the Mac." }
            val active = if (saved != null) connection.connectSaved(saved, account) else connection.connectPairing(pairing, account)
            try {
                val status = active.hostStatus()
                require(status.optString("mac_device_id").isNotBlank()) { "The Mac did not provide its device identity." }
                store.visiblePairedMacs().singleOrNull { it.code == requestedCode && connection.allowsSaved(it) }?.requireMatchingHost(status)
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
                requireCurrentReconnect()
                val remembered = if (pairingOwner != null) store.rememberAuthenticatedMac(verified, pairingOwner, expected = capturedReconnect) {
                    accountTeams.isCurrent(pairingOwner) && signedIn && code == requestedCode
                } else {
                    store.rememberMac(verified.code, verified.deviceId, verified.name, verified.instanceTag, expected = capturedReconnect)
                    verified
                }
                if (remembered.code != requestedCode) {
                    active.close()
                    savedPairedMacs = store.pairedMacs()
                    if (expectedReconnect == capturedReconnect) expectedReconnect = null
                    macSwitchRecovery.retarget(switchAttempt, switchOwner(), remembered.code)
                    if (pendingPickerCode == requestedCode) pendingPickerCode = remembered.code
                    if (pairingSelectionCode == requestedCode) pairingSelectionCode = remembered.code
                    code = remembered.code
                    connectionError = null; retryDelay = 2_000; busy = false
                    return@LaunchedEffect
                }
                feedSession.recordMacSeen(remembered)
                hostName = displayName; hostCapabilities = capabilities
                terminalTransport = TerminalTransport.resolve(capabilities, status.optString("terminal_fidelity"))
                applyListing(listing); notifications = feed
                inputOwner(remembered, store.taskSession())?.let { owner ->
                    val sizingStream = terminalSizing.bind(owner, active)
                    if (TerminalSizingTraffic.CAPABILITY in capabilities) {
                        // Upstream has no Android enum; use its truthful unknown kind and actual model name.
                        active.terminalDeviceName = android.os.Build.MODEL
                        try { active.subscribe(TerminalSizingTraffic.topics, sizingStream) }
                        catch (failure: Throwable) { terminalSizing.unbind(active); throw failure }
                    }
                    terminalInputs.attach(owner, active, capabilities, inputTargets(workspaces)) { feedSession.allowsTerminalInput(owner) }
                }
                client = active; connectedCode = requestedCode
                connectionReady = true
                if (expectedReconnect == capturedReconnect) expectedReconnect = null
                if (pairingSelectionCode == requestedCode || pendingPickerCode == requestedCode) {
                    selectedComputerOrigin = remembered.origin
                    store.update { it.put("computer_selection", remembered.origin) }
                    pairingSelectionCode = null
                    pendingPickerCode = null
                }
                switchOwner()?.let { macSwitchRecovery.connected(it, remembered) }
                savedPairedMacs = store.pairedMacs()
                connectionError = null
                retryDelay = 2_000
            } catch (failure: Throwable) { active.close(); throw failure }
        } catch (failure: Throwable) {
            // A child RPC/dial deadline is a connection failure, not retirement
            // of this screen's attempt. A newer attempt still wins cancellation.
            ensureActive()
            if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
            connectionError = nativeConnectionFailure(failure)
            busy = false
            if (pendingPickerCode == requestedCode) pendingPickerCode = null
            val restore = macSwitchRecovery.failed(switchAttempt, switchOwner(), requestedCode, ::canRestoreMac)
            if (restore != null) {
                // Restore only a previously verified, still-authorized route. Clear a
                // target-specific UI intent before it can redial the failed computer.
                workspaceRoute = null
                selectedComputerOrigin = restore.selection
                store.update { it.put("pairing_code", restore.mac.code).put("computer_selection", restore.selection) }
                code = restore.mac.code; retryDelay = 2_000
                error = "Could not switch computers. Your previous computer is selected again."
                return@LaunchedEffect
            }
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000)
            retry++
        }
        busy = false
    }

    LaunchedEffect(client) {
        val active = client ?: return@LaunchedEffect
        val subscription = terminalSizing.subscription(active) ?: return@LaunchedEffect
        active.useEventSession { wire ->
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { runCatching { wire.unsubscribe(subscription) } } }
        }
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

    LaunchedEffect(client, selectedWorkspace?.id, selectedTerminal?.id, selectedTerminal?.isReady) {
        val active = client ?: return@LaunchedEffect
        val workspace = selectedWorkspace ?: return@LaunchedEffect
        val terminal = selectedTerminal?.takeUnless { it.isReady } ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                active.prepareTerminal(workspace.id, terminal.id)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // The existing inventory poll/startup deadline owns readiness and recovery.
                // A first replay may start the surface before returning a transient error.
            }
            awaitCancellation()
        }
    }

    LaunchedEffect(client, selectedWorkspace?.id, selectedTerminal?.id, selectedTerminal?.isReady, terminalColumns, terminalRows, terminalCells, terminalTransport, terminalAttached, selectedSizing?.viewportRevision ?: 0L, selectedSizing?.reconnecting ?: false) {
        val active = client ?: return@LaunchedEffect
        val workspace = selectedWorkspace ?: return@LaunchedEffect
        val terminal = selectedTerminal?.takeIf { it.isReady } ?: return@LaunchedEffect
        if (!terminalAttached) return@LaunchedEffect
        val requestedViewport = terminalViewport ?: return@LaunchedEffect
        val generation = ++replayGeneration
        val viewportGeneration = ++viewportRequestGeneration
        val transport = terminalTransport
        val mirror = TerminalStreamMirror(terminal.id, transport, requestedViewport, ghosttyTerminalFactory(terminalCells), onBell = terminalBells::ring)
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
            terminalActiveScreen = next.activeScreen
            terminalSizing.rendered(active, terminal.id, SharedTerminalGrid(next.columns, next.rows))
            gridRevision++
            keyboardPresentation.outputApplied(viewportGeneration, gridRevision)
        }
        suspend fun replayTerminal() {
            if (replayRunning || recoveryFailed || !active.terminalTrafficAllowed(terminal.id)) return
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
                            maxScrollbackRows = if (mirror.historyLineCount == 0) currentScrollbackRows else 0)
                    }
                    if (generation != replayGeneration || client !== active) return
                    terminalSizing.replay(active, terminal.id, snapshot)
                    active.checkTerminalTraffic(terminal.id)
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
            if (!active.terminalTrafficAllowed(terminal.id)) return
            nativeOutput?.pause()
            mirror.beginReplay()
            if (replayRunning) replayAgain = true
            else if (subscriptionReady && !recoveryFailed) launch(start = CoroutineStart.UNDISPATCHED) { replayTerminal() }
        }
        val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
            var lastDelivery: Long? = null
            active.events.collect { event ->
                if (generation != replayGeneration || client !== active || !active.terminalTrafficAllowed(terminal.id)) return@collect
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
        fun isCurrent() = generation == replayGeneration && client === active && active.terminalTrafficAllowed(terminal.id) &&
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
            keyboardPresentation.reportPublished(viewportGeneration, requestedViewport)
            runCatching {
                active.reportViewport(workspace.id, terminal.id, requestedViewport, viewportGeneration)
            }.onSuccess {
                if (isCurrent()) {
                    sizingViewportConfirmed = true
                    keyboardPresentation.reportConfirmed(viewportGeneration)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                keyboardPresentation.reportFailed(viewportGeneration)
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
            sizingViewportConfirmed = false
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
        onDispose { disposableClient?.let { terminalSizing.unbind(it); terminalInputs.detach(it); it.close() } }
    }
    BackHandler(enabled = signedIn && code.isNotBlank() && searchState.active != null && selectedTerminal == null &&
        selectedBrowser == null && selectedChangesWorkspace == null && !showSettings && !showTaskComposer) { finishSearch(cancel = true) }
    BackHandler(enabled = workspaceRoute != null && selectedTerminal == null && selectedBrowser == null && selectedSurface == null) { screenResume.cancel(); workspaceRoute = null }
    BackHandler(enabled = selectedTerminal != null && selectedSurface == null) { selectedTerminal = null; selectedWorkspace = null; selectedSurface = null }
    BackHandler(enabled = selectedBrowser != null) { selectedBrowser = null; selectedWorkspace = null; selectedSurface = null }
    BackHandler(enabled = showSettings && selectedTerminal == null) { showSettings = false }

    if (showLicenses) OpenSourceLicensesDialog { showLicenses = false }

    if (pairingLookup != null) AlertDialog(onDismissRequest = { handlePairing(pairingLookup) },
        title = { Text("Finding this Mac…") },
        text = { Text("Checking your account and selected team's computers.") },
        confirmButton = {}, dismissButton = { TextButton(onClick = { handlePairing(pairingLookup) }) { Text("Cancel") } })
    NativePairingConfirmation(if (signedIn) pendingPairingCode else null,
        onDismiss = { pendingPairingCode = null },
        onConnect = { proposed ->
            try {
                connection.authorizePairing(PairingCodeParser.parse(proposed).getOrThrow() as PairingCode.Tailscale)
                selectPairingCode(proposed); pendingPairingCode = null; retry++
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
                        applyListing(listing); refreshFeed(); newGroupName = ""
                    }
                } catch (failure: Exception) {
                    recordWorkspaceActionFailure(failure)
                }
            }
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("Cancel") } }
    )

    if (signedIn) sharedConnections?.ssh?.let { NativeSshPromptHost(it) }

    fun signOutCurrentAccount(owner: String? = null): Boolean = try {
        if (account.signOut(owner)) {
            // Retire credentials first: service/connection teardown cannot leave a
            // deleted account admitted if stopping a background component fails.
            runCatching { NativeNotificationService.setEnabled(context, false) }
            backgroundNotifications = NativeNotificationService.isEnabled(context)
            drafts.clear(); accountTeams.clear(); TaskDraftRepository.clearAttachments(context)
            signedIn = false; client?.close(); client = null
        }
        accountDeletion.reconcile()
        true // A different login is intentionally untouched.
    } catch (failure: Exception) { error = "Could not sign out on this device"; false }

    NativeAccountDeletionAlerts(deletionReceipt, ::signOutCurrentAccount, accountDeletion::acknowledge)

    fun retireHiddenForeground() {
        if (eligibleMacs.none { it.code == code && NativeComputerVisibility.isHidden(
                NativeComputerVisibility.hiddenOrigins(store.load()), it) }) return
        macSwitchRecovery.cancel(); expectedReconnect = null; pendingPickerCode = null; pairingSelectionCode = null
        screenResume.cancel(); workspaceRoute = null
        selectedTerminal = null; selectedWorkspace = null; selectedSurface = null; selectedBrowser = null
        selectedChangesWorkspace = null; workspaces = emptyList(); groups = emptyList(); notifications = emptyList()
        client?.close(); client = null; connectedCode = null; connectionReady = false
        code = ""; selectedComputerOrigin = ""; error = null; connectionError = null
    }
    LaunchedEffect(hiddenOrigins, eligibleMacs, code) {
        retireHiddenForeground()
        computerDetails?.let { details ->
            if (hiddenMacs.any { canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(details.target.deviceId) &&
                it.instanceTag == details.target.buildTag }) computerDetails = null
        }
    }
    fun setComputerVisibility(mac: NativeCredentialStore.PairedMac, visible: Boolean) {
        val login = store.taskSession()
        val team = teamState.scope
        try {
            check(store.setComputerVisible(login, mac, visible) {
                account.isSignedIn() && store.taskSession() == login &&
                    accountTeams.state.value.scope == team && connection.allowsSaved(mac)
            }) { "This computer changed. Open Computers again." }
            retireHiddenForeground()
        } catch (failure: Exception) { error = failure.message ?: "Could not change computer visibility" }
    }

    fun presentComputers() {
        computersReturnToSettings = showSettings
        computersOwner = NativeComputerMenuOwner(store.taskSession(), teamState.scope)
        finishSearch(); showSettings = true
    }
    fun dismissComputers() {
        computersOwner = null; showSettings = computersReturnToSettings
    }
    val displayPolicy = sharedConnections?.compatibility?.gate?.policyState?.collectAsState()?.value
        ?: NativeMacCompatibilityPolicy.baked
    val displayWarnings = cachedComputers?.warnings(displayPolicy) ?: compatibilityWarnings
        .filterKeys { it.owner == teamState.scope }.mapKeys { it.key.identity }
    val onboardingOwner = teamState.scope?.takeIf { accountTeams.isCurrent(it) }
    val onboardingExplicitRoute = incomingCode != null || currentIncomingRoute != null || workspaceRoute != null
    val onboardingEligible = nativeOnboardingEligible(onboardingProgress, signedIn, onboardingOwner != null,
        teamState.cached, onboardingExplicitRoute)
    val showOnboarding = (onboardingEligible && !showSettings && !showSshComputers) ||
        (replayOnboarding && signedIn && !onboardingExplicitRoute)
    LaunchedEffect(onboardingExplicitRoute) { if (onboardingExplicitRoute) { replayOnboarding = false; showWhatsNew = false } }
    var onboardingRetry by remember(onboardingOwner, replayOnboarding) { mutableIntStateOf(0) }
    var onboardingMethod by rememberSaveable(signedIn) { mutableStateOf(
        NativeOnboardingMethod.entries.firstOrNull { it.name == onboardingPrefs.getString("connection_method", null) }
            ?: NativeOnboardingMethod.AUTOMATIC) }
    val onboardingAutomatic = onboardingMethod == NativeOnboardingMethod.AUTOMATIC
    val onboardingCandidates = currentDirectory.filter { candidate -> hiddenMacs.none {
        canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(candidate.deviceId) && it.instanceTag == candidate.buildTag
    } }
    val onboardingReady = connectionReady && client?.isClosed == false && pairedMacs.any { it.code == connectedCode }
    fun onboardingSearch() {
        val owner = onboardingOwner ?: return
        if (!feedForeground || !accountTeams.isCurrent(owner) || onboardingReady || busy) return
        error = null
        if (code.isNotBlank()) { retryDelay = 2_000; retry++ }
        else { onboardingRetry++; sharedConnections?.native?.refresh() }
    }
    // Discovery and connection still use the existing shared owners. A single
    // available Mac can be selected automatically; multiple Macs stay explicit.
    LaunchedEffect(showOnboarding, onboardingOwner, onboardingAutomatic, computerState.ready,
        onboardingCandidates, onboardingRetry, code, feedForeground, replayOnboarding, deferStartupForPairing,
        pendingPairingCode, busy, onboardingReady) {
        val owner = onboardingOwner ?: return@LaunchedEffect
        if (!nativeOnboardingMayChoose(showOnboarding, feedForeground, deferStartupForPairing || pendingPairingCode != null,
            replayOnboarding, onboardingRetry > 0, onboardingAutomatic, code.isNotBlank(), busy, onboardingReady) ||
            !accountTeams.isCurrent(owner) || !computerState.ready || computerState.account != owner) return@LaunchedEffect
        val candidate = onboardingCandidates.singleOrNull() ?: return@LaunchedEffect
        selectPairingCode(PairingCodeParser.computer(candidate, owner))
    }
    fun scanForOnboarding() {
        val owner = onboardingOwner ?: return
        runCatching {
            GmsBarcodeScanning.getClient(context,
                GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()).startScan()
        }.onSuccess { scan -> scan.addOnSuccessListener { barcode ->
            if (signedIn && accountTeams.isCurrent(owner)) proposePairing(barcode.rawValue.orEmpty())
        }.addOnFailureListener { if (accountTeams.isCurrent(owner)) error = it.message } }
            .onFailure { error = it.message ?: "Could not open the QR scanner. Paste a code instead." }
    }
    // Keep onboarding in a separate composition group: the root route lambda is
    // already large, and combining its captures can produce invalid release DEX.
    val onboardingContent: @Composable () -> Unit = {
        key(browserLogin, teamState.userId, teamState.selectedTeamId, replayOnboarding) {
            NativeOnboardingFlow(onboardingProgress, replayOnboarding,
                NativeOnboardingPhase.resolve(onboardingReady,
                    busy || computerState.loading, computerState.ready || computerState.error != null || connectionError != null),
                hostName.takeIf { connectionReady }, displayPolicy, onboardingPermissionBusy, onboardingPermissionResult,
                error ?: connectionError ?: computerState.error ?: if (onboardingOwner == null) "Refresh your account in Settings before connecting to a Mac." else null,
                canConnect = onboardingOwner != null, onEnableNotifications = { enableNotifications(true) },
                onReachedConnection = {
                    if (!replayOnboarding) runCatching { onboardingStore.connect(); onboardingProgress = onboardingStore.progress }
                        .onFailure { error = it.message }
                    if (!replayOnboarding) onboardingSearch()
                }, onComplete = {
                    if (replayOnboarding) replayOnboarding = false
                    else runCatching { onboardingStore.complete(); onboardingProgress = onboardingStore.progress }
                        .onFailure { error = it.message }
                }, onRetry = ::onboardingSearch, onScan = ::scanForOnboarding, onPairing = ::proposePairing,
                initialMethod = onboardingMethod, onMethod = { method ->
                    onboardingMethod = method
                    if (onboardingPrefs.getString("connection_method", null) != method.name &&
                        !onboardingPrefs.edit().putString("connection_method", method.name).commit())
                        error = "Could not save the connection method."
                },
                onSettings = { replayOnboarding = false; showSettings = true },
                computers = {
                    val saved = displayedVisible.filter(connection::allowsSaved)
                    if (onboardingCandidates.size + saved.size > 1) Text("Choose a Mac", fontWeight = FontWeight.SemiBold)
                    saved.forEach { mac -> TextButton(enabled = !busy, onClick = {
                        val owner = onboardingOwner
                        if (owner != null && accountTeams.isCurrent(owner) &&
                            NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) && connection.allowsSaved(mac)) {
                            selectPickerComputer(mac); expectedReconnect = mac
                        }
                    }) { Text("${mac.name} · ${mac.instanceTag ?: "legacy"}") } }
                    onboardingCandidates.filter { candidate -> saved.none {
                        canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(candidate.deviceId) && it.instanceTag == candidate.buildTag
                    } }.forEach { mac -> TextButton(enabled = !busy, onClick = {
                        val owner = onboardingOwner
                        if (owner != null && accountTeams.isCurrent(owner) &&
                            NativeReconnectComputers.currentDiscovery(mac, computerStates.value, owner))
                            selectPairingCode(PairingCodeParser.computer(mac, owner))
                    }) { Text("${mac.name} · ${mac.buildTag}") } }
                }, keepAwake = {
                    val owner = onboardingOwner
                    val target = if (owner != null) pairedMacs.singleOrNull { it.code == connectedCode }
                        ?.let { NativeComputerTarget.from(it, owner) } else null
                    if (owner != null && target != null && sharedConnections != null)
                        NativeMacPowerSettings(sharedConnections.native, owner, target, offerOnly = true)
                })
        }
    }
    val whatsNewPromptPending = nativeWhatsNewSshPromptPending(sharedConnections?.ssh)
    whatsNewCenter?.let { noticeCenter -> whatsNewModel?.presentation?.let { noticePresentation ->
        val noticeBroker = remember(noticeCenter, browserLogin, account) {
            NativeWebSessionBroker(noticeCenter.webPolicy, NativeAccount.PROJECT_ID,
                snapshot = { browserLogin?.let { account.webSessionSnapshot(it) } }, isCurrent = account::isWebSessionCurrent)
        }
        SideEffect {
            whatsNewModel?.configureWeb(browserLogin.takeIf { signedIn },
                { login -> account.isSignedIn() && store.taskSession() == login }, noticeBroker::cookies)
        }
        NativeWhatsNewHost(noticeCenter, noticePresentation,
            owner = browserLogin.takeIf { signedIn },
            eligible = signedIn && feedForeground &&
                onboardingProgress == NativeOnboardingProgress.COMPLETE && !showOnboarding && !onboardingExplicitRoute &&
                !showSettings && !showSshComputers && !showTaskComposer && !showLicenses && !showShortcuts &&
                !showCreateGroup && !confirmReadAll && computerDetails == null && deletionReceipt == null &&
                pendingPairingCode == null && pairingLookup == null && !onboardingPermissionBusy && !whatsNewPromptPending &&
                selectedTerminal == null && selectedBrowser == null && selectedSurface == null && selectedChangesWorkspace == null &&
                screenResume.pending == null && textSnapshot == null && !showTerminalFiles && terminalArtifactPath == null &&
                !createMenuOpen && !computerMenuOpen && !workspaceFilterMenuOpen && !notificationFilterMenu,
            archive = showWhatsNew && showSettings && signedIn && !showOnboarding && !onboardingExplicitRoute,
            onCloseArchive = { showWhatsNew = false }, policy = displayPolicy,
            webArchive = whatsNewModel?.webArchive,
            isOwnerCurrent = { login -> account.isSignedIn() && store.taskSession() == login },
            sessionCookies = noticeBroker::cookies)
    } }
    // Keep each route in its own composition lambda. Creating all route lambdas
    // inside the dispatcher forces it to capture the entire screen and can produce
    // invalid release DEX. The dispatcher retains route selection and ColumnScope.
    val computersContent: @Composable ColumnScope.() -> Unit = {
        val owner = checkNotNull(computersOwner)
        fun admitted() = account.isSignedIn() && store.taskSession() == owner.login && accountTeams.state.value.scope == owner.team
        if (!admitted()) {
            LaunchedEffect(owner) { computersOwner = null }
        } else key(owner) {
            var legacyDetails by remember { mutableStateOf<NativeCredentialStore.PairedMac?>(null) }
            val rows = NativeComputerList.rows(displayedMacs, appearances, scopedPresence, lastSeenHistory,
                computerPreferences, currentDirectory, tailscaleRouteLabels)
            val legacy = legacyDetails?.let { mac -> rows.singleOrNull { it.mac.origin == mac.origin } }
            fun current(mac: NativeCredentialStore.PairedMac) = admitted() &&
                NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) && connection.allowsSaved(mac)
            fun pairMac() {
                if (!admitted()) return
                computersOwner = null; showSettings = false
                code = ""; selectedTerminal = null; selectedWorkspace = null; selectedSurface = null
                selectedBrowser = null
            }
            if (legacy != null) NativeLegacyComputerDetails(legacy, { legacyDetails = null }) {
                if (current(legacy.mac)) {
                    computersOwner = null; showSettings = false
                    val alreadyConnected = connectionReady && connectedCode == legacy.mac.code && client?.isClosed == false
                    val sameAttempt = code == legacy.mac.code
                    selectPickerComputer(legacy.mac)
                    if (!alreadyConnected) {
                        expectedReconnect = legacy.mac
                        if (sameAttempt) retry++
                    }
                }
            } else NativeComputersRoute(sharedConnections?.ssh, ::dismissComputers, ::pairMac) {
                if (cachedComputers != null) NativeCachedComputersNotice()
                NativeManagedComputerRows(rows, appearances, machineColorIndices, computerConnections, scopedPresence,
                    onDetails = { mac -> if (current(mac)) {
                        val target = owner.team?.let { NativeComputerTarget.from(mac, it) }
                        if (target != null && sharedConnections != null) {
                            if (computerState.account == owner.team)
                                computerDetails = NativeComputerDetailsPresentation(checkNotNull(owner.team), target, machineColorIndices[mac.colorIdentity])
                            else error = "Computer settings are still loading. Try again."
                        } else legacyDetails = mac
                    } }, onPair = ::pairMac, hiddenOrigins = hiddenOrigins, onVisibility = ::setComputerVisibility,
                    readOnly = cachedComputers != null)
            }
        }
    }
    val settingsContent: @Composable ColumnScope.() -> Unit = {
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
        TextButton(onClick = { signOutCurrentAccount() },
            modifier = Modifier.padding(horizontal = 14.dp)) { Text("Sign out") }
        NativeAccountDeletionButton(browserLogin, deletionReceipt, accountDeletion::begin)
        }, computers = {
        Text("COMPUTERS", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = nativeMuted, fontSize = 11.sp)
        TextButton(onClick = ::presentComputers, modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.computers")) {
            Text("Manage computers")
        }
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
                    enableNotifications(false)
                } else runCatching { NativeNotificationService.setEnabled(context, false) }
                    .onSuccess { backgroundNotifications = false; error = null }
                    .onFailure { error = it.message }
            }, enabled = signedIn && code.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Background notifications" })
        }
        NativeNotificationSettings()
        }, preferences = {
        if (whatsNewState?.archive?.isNotEmpty() == true) TextButton(onClick = { showWhatsNew = true },
            modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.whatsnew")) { Text("What's New") }
        TextButton(onClick = { replayOnboarding = true }, modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.introduction")) { Text("View Introduction Again") }
        NativeFeedbackSettingsButton()
        NativeDiagnosticsSettings()
        TextButton(onClick = { showSshKeys = true }, modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.ssh.keys")) { Text("SSH Keys") }
        NativeTerminalPreferenceSettings(folderTapEnabled, showMissingArtifacts, artifactPreferences, displayState)
        NativeDisplaySettings(artifactPreferences, displayState)
        NativeHapticSettings(artifactPreferences, displayState)
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
        NativeLegalSupportSettings()
        NativeLocalResetSection()
        NativeAboutSettings(session = {
            val current = accountTeams.state.value
            val connected = connectionReady && connectedCode == code && client?.isClosed == false
            val route = if (connected) when (PairingCodeParser.parse(code).getOrNull()) {
                is PairingCode.Iroh -> "iroh"
                is PairingCode.Tailscale -> "tailscale"
                null -> null
            } else null
            NativeSupportSession(current.userId, current.selectedTeamId, connected, route)
        })
        })
    }
    val taskComposerContent: @Composable ColumnScope.() -> Unit = {
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
    val localBrowserContent: @Composable ColumnScope.() -> Unit = {
        val localBrowser = checkNotNull(localBrowser)
        val browserMac = pairedMacs.first { localBrowserKey(browserLogin, teamState.scope, it, localBrowser.key.workspaceId) == localBrowser.key }
        fun browserMenu(): RoutedBrowserMenu? {
            if (!signedIn || localBrowserKey(browserLogin, teamState.scope, browserMac, localBrowser.key.workspaceId) != localBrowser.key) return null
            val source = (moveSources[browserMac.origin] ?: feedSources[browserMac.origin])?.takeIf { it.mac == browserMac }
            val primary = connectionReady && connectedCode == browserMac.code && client?.isClosed == false
            val currentWorkspace = when {
                source?.hasWorkspaceSnapshot == true -> source.workspaces.singleOrNull { it.id == localBrowser.key.workspaceId }
                client != null && connectedCode == browserMac.code -> workspaces.singleOrNull { it.id == localBrowser.key.workspaceId }
                else -> localBrowser.workspace
            } ?: return null
            val feed = feedSources[browserMac.origin]?.takeIf { it.mac == browserMac }
            val support = if (primary) NativeBrowserPickerState.from(true, hostCapabilities)
                else feed?.let { NativeBrowserPickerState.from(it.availability == NativeFeedAvailability.CONNECTED, it.capabilities) }
                    ?: NativeBrowserPickerState(known = false, streaming = false)
            return RoutedBrowserMenu(currentWorkspace, !creatingWorkspace && !creatingTerminal &&
                (primary || feed?.availability == NativeFeedAvailability.CONNECTED), browserState = support,
                customizationEnabled = feed?.canCustomizeWorkspace() == true)
        }
        fun createFromBrowser(kind: NativeWorkspaceCreation) {
            val login = store.taskSession() ?: return
            if (browserMenu()?.creationEnabled != true || localBrowsers.state.value.local?.surface !== localBrowser.surface ||
                localBrowserKey(login, teamState.scope, browserMac, localBrowser.key.workspaceId) != localBrowser.key ||
                !store.visiblePairedMacs().contains(browserMac) || !connection.allowsSaved(browserMac)) return
            localBrowsers.leave(close = true)
            workspaceRoute = NativeWorkspaceRoute(browserMac.origin, localBrowser.key.workspaceId,
                creation = kind, creationLogin = login)
        }
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
            onRoute = { workspaceRoute = it }, browserModes = true,
            onNewWorkspace = { createFromBrowser(NativeWorkspaceCreation.WORKSPACE) },
            onNewTerminal = { createFromBrowser(NativeWorkspaceCreation.TERMINAL) },
            onNewBrowser = { createFromBrowser(NativeWorkspaceCreation.BROWSER) }, menuSource = ::browserMenu,
            customizeWorkspace = { baseline, submitted ->
                check(browserMenu()?.customizationEnabled == true && connection.allowsSaved(browserMac) &&
                    store.visiblePairedMacs().contains(browserMac) && ownsBrowserDestination(localBrowser, localBrowsers.state.value.local)) { "Browser workspace changed." }
                feedCoordinator.customizeWorkspace(browserMac, localBrowser.key.workspaceId, baseline, submitted)
            })
    }
    val reconnectContent: @Composable ColumnScope.() -> Unit = {
        val reconnectOwner = NativeComputerMenuOwner(store.taskSession(), teamState.scope)
        fun isReconnectOwnerCurrent() = account.isSignedIn() && store.taskSession() == reconnectOwner.login &&
            accountTeams.state.value.scope == reconnectOwner.team
        NativeComputerPicker(teamState, computerState.takeIf { it.account == teamState.scope }
            ?: NativeComputersState(account = teamState.scope, loading = true), runtime = sharedConnections?.native,
            onSsh = if (sharedConnections != null) ({ showSshComputers = true }) else null,
            colorIndices = machineColorIndices, connections = computerConnections, presence = scopedPresence, saved = displayedVisible, hidden = displayedHidden, onVisibility = ::setComputerVisibility,
            cachedDisplay = cachedComputers != null, displayAppearances = appearances, macPolicy = displayPolicy,
            lastSeenHistory = lastSeenHistory, preferences = computerPreferences, tailscaleRoutes = tailscaleRouteLabels,
            forgetCallbacks = forgetCallbacks, presentDetails = { computerDetails = it },
            connectingCode = code.takeIf { busy && cachedComputers == null }, connectionFailure = error ?: connectionError,
            onCancelConnect = { macSwitchRecovery.cancel(); pendingPickerCode = null; pairingSelectionCode = null; expectedReconnect = null
                code = ""; connectionError = null; error = null; showReconnectList = false },
            canSelectSaved = { mac -> isReconnectOwnerCurrent() &&
                NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) && connection.allowsSaved(mac) },
            canSelectDiscovered = { mac -> isReconnectOwnerCurrent() &&
                NativeReconnectComputers.currentDiscovery(mac, computerStates.value, reconnectOwner.team) &&
                hiddenMacs.none { canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(mac.deviceId) && it.instanceTag == mac.buildTag } },
            onSelectSaved = { mac ->
                val sameAttempt = code == mac.code
                showReconnectList = true; selectPickerComputer(mac); expectedReconnect = mac
                if (sameAttempt) retry++
            },
            onSelect = { mac -> reconnectOwner.team?.let { owner ->
                showReconnectList = true; selectPairingCode(PairingCodeParser.computer(mac, owner))
            } },
            onSettings = { workspaceRoute = null; finishSearch(); showSettings = true },
            onComputers = ::presentComputers,
            onRefresh = { scope.launch {
                try { accountTeams.refresh(); sharedConnections?.native?.refresh() }
                catch (failure: Exception) { if (failure is CancellationException) throw failure }
            } }, onPairing = ::proposePairing, onNewTask = ::newTaskDraft,
            onUseHelper = onUseHelper, onLicenses = { showLicenses = true }, onError = { error = it })
    }
    val terminalContent: @Composable ColumnScope.() -> Unit = {
        ObserveTerminalBells(terminalBells, terminalAttached && connectionReady)
        NativeTerminalContent {
            RetireTerminalInputOnBackground(rawKeyboardView)
            val terminal = selectedTerminal!!
            var showSizing by remember(client, terminal.id) { mutableStateOf(false) }
            NativeTerminalHeader(terminal, selectedWorkspace, workspaces.size, hostCapabilities, connectionReady, directTyping,
                onNewWorkspace = if (canCreateInCurrentPane && !creatingWorkspace && !creatingTerminal) ::createWorkspace else null,
                onNewTerminal = if (canCreateInCurrentPane && !creatingTerminal && !creatingWorkspace) ::createTerminalInPane else null,
                onBack = { selectedTerminal = null; selectedWorkspace = null; selectedSurface = null },
                onTerminal = { next ->
                    rawKeyboardView?.finishComposition(); directTyping = false
                    inputModifiers = TerminalInputModifiers(); stopTerminalScrolling(); softwareKeyboard?.hide()
                    selectPane(NativeWorkspacePane(terminal = next))
                },
                onSurface = { surface ->
                    rawKeyboardView?.finishComposition(); directTyping = false
                    inputModifiers = TerminalInputModifiers(); stopTerminalScrolling(); softwareKeyboard?.hide()
                    selectPane(NativeWorkspacePane(surface = surface))
                }, debugText = { RenderGrid.plainText(grid.visibleLines(scrollOffset)) }, onText = ::openTerminalText, onFiles = {
                    inputModifiers = TerminalInputModifiers(); stopTerminalScrolling(); softwareKeyboard?.hide(); showTerminalFiles = true
                }, onNewBrowser = {
                    rawKeyboardView?.finishComposition(); directTyping = false; stopTerminalScrolling(); softwareKeyboard?.hide()
                    selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { openNewBrowser(it, workspace) } }
                }, onKeyboard = {
                    inputModifiers = TerminalInputModifiers()
                    if (directTyping) { rawKeyboardView?.finishComposition(); directTyping = false }
                    else openDirectKeyboard()
                }, onSizing = if (terminalAttached && selectedSizing?.state != null) ({ showSizing = true }) else null,
                altScreenNotice = {
                    NativeAltScreenNotice(client, terminal.id,
                        terminalActiveScreen == "alternate" && displayState.showAltScreenNotice) {
                        artifactPreferences.edit().putBoolean(NativeDisplayPreferences.altScreenNoticeKey, false).apply()
                    }
                }, onBrowser = { browser ->
                    rawKeyboardView?.finishComposition(); directTyping = false
                    inputModifiers = TerminalInputModifiers(); stopTerminalScrolling(); softwareKeyboard?.hide()
                    selectPane(NativeWorkspacePane(browser = browser))
                })
            NativeTerminalTabs(selectedWorkspace?.terminals.orEmpty(), terminal) { selectPane(NativeWorkspacePane(terminal = it)) }
            val currentGrid = grid
            val gridPresentation = rememberTerminalGridPresentation(client, terminal.id, selectedSizing?.state,
                currentGrid, terminalReportPixels, terminalCells, density.density, terminalViewportPixels.height,
                gridRevision, scrollViewport, scrollInteractionEpoch)
            val displayGeometry = gridPresentation.geometry
            val visibleArtifactScroll by rememberUpdatedState(scrollViewport)
            val localScroll = currentGrid.activeScreen == "primary" &&
                (terminalTransport.screenAnchor || terminalTransport.mode != TerminalOutputMode.GRID)
            val scrollOwner = client
            val scrollAllowed = { client === scrollOwner && selectedTerminal?.id == terminal.id &&
                scrollOwner?.terminalTrafficAllowed(terminal.id) == true && terminalScroll != null && !keyboardPresentation.frozen }
            val scrollTerminal: (Double, TerminalGeometry.Cell) -> Boolean = { lines, cell ->
                if (!scrollAllowed()) false else {
                    val move = gridPresentation.scroll(lines, scrollPosition, currentGrid.historyLineCount, localScroll)
                    val sent = if (move.rows != 0.0) terminalScroll?.invoke(move.rows, cell) ?: false else false
                    sent || move.revealed
                }
            }
            val accessibleScroll = TerminalAccessibilityScroll(localScroll, currentGrid.historyLineCount, scrollPosition,
                gridPresentation.reveal, gridPresentation.maximumReveal, displayGeometry?.cellHeight ?: 0f, terminalViewportPixels.height)
            Box(Modifier.fillMaxWidth().weight(1f)) {
            RenderGridView(currentGrid, terminalCells, gridRevision,
                Modifier.fillMaxSize().testTag("native-terminal")
                    .onGloballyPositioned { coordinates ->
                        terminalMeasurement = TerminalViewportMeasurement(coordinates.size,
                            (terminalImeInsets.getBottom(density) - terminalNavigationInsets.getBottom(density)).coerceAtLeast(0))
                    }
                    .focusRequester(terminalFocusRequester)
                    .onPreviewKeyEvent { event -> directHardware(event.nativeKeyEvent) }
                    .focusable()
                    .nativeTerminalAccessibility(terminalZoom.size, accessibleScroll, scrollAllowed(), ::openDirectKeyboard, ::openTerminalText,
                        latest = {
                            if (!scrollAllowed() || terminalActiveScreen != "primary") false else {
                                stopTerminalScrolling(); scrollPosition = 0.0; true
                            }
                        }) { rows ->
                        terminalMotion.stop()
                        val cell = displayGeometry?.cell(terminalViewportPixels.width / 2f, terminalViewportPixels.height / 2f)
                        if (cell == null) false else scrollTerminal(rows, cell)
                    }
                    .then(if (keyboardPresentation.frozen) Modifier else Modifier.terminalPinchZoom(
                        terminalZoom, gridPresentation.sharedLayout, gridPresentation.pinchOffset, gridPresentation.transform))
                    .pointerInput(terminal.id, currentGrid, terminalCells, displayGeometry, artifactRpc, artifactsReady, artifactTapController) {
                        detectTapGestures(onTap = { point ->
                            if (keyboardPresentation.frozen) return@detectTapGestures
                            artifactTapController.invalidate()
                            var handlingArtifact = false
                            displayGeometry?.let { geometry ->
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
                                } else if (geometry.contains(point.x, point.y)) terminalClick?.invoke(cell)
                            }
                            if (!handlingArtifact) openDirectKeyboard()
                        }, onLongPress = { artifactTapController.invalidate(); openTerminalText() })
                    }
                    .terminalScrollGestures(terminalMotion,
                        displayGeometry,
                        replayGeneration, currentGrid.activeScreen,
                        linePath = !localScroll, enabled = scrollAllowed(), onScroll = scrollTerminal), scrollPosition = scrollPosition,
                displayGeometry = displayGeometry, keyboardPresentation = keyboardPresentation.takeUnless { keepKeyboardGrid },
                displayLines = gridPresentation.visibleLines)
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
            val sheetClient = client
            val sheetWorkspace = selectedWorkspace
            val sheetCode = code
            TerminalSizingSurfaceView(selectedSizing, terminal.id, currentGrid, terminalCells, displayGeometry,
                terminalViewport, sizingViewportConfirmed, connectionReady && sheetClient != null,
                terminalZoom.overlayVisible, sheetClient?.terminalParticipantId, showSizing,
                onSheet = { showSizing = it }, onAction = { action ->
                    val active = checkNotNull(sheetClient)
                    val workspace = checkNotNull(sheetWorkspace)
                    val viewport = terminalViewport
                    val viewportGeneration = viewportRequestGeneration
                    val selfId = selectedSizing?.selfId ?: active.terminalParticipantId
                    if (action is TerminalSizingAction.Disconnect) require(action.ids.none { it == selfId })
                    fun current() = client === active && selectedWorkspace?.id == workspace.id &&
                        selectedTerminal?.id == terminal.id && code == sheetCode && signedIn && connectionReady &&
                        TerminalSizingTraffic.CAPABILITY in hostCapabilities &&
                        (action !is TerminalSizingAction.Counts ||
                            (terminalViewport == viewport && viewportRequestGeneration == viewportGeneration))
                    val response = active.changeTerminalSizing(workspace.id, terminal.id, action, viewport, viewportGeneration, ::current)
                    terminalSizing.mutation(active, terminal.id, response)
                }, onReattach = { viewer ->
                    val active = checkNotNull(sheetClient)
                    val workspace = checkNotNull(sheetWorkspace)
                    val expected = checkNotNull(selectedSizing)
                    check(client === active && selectedWorkspace?.id == workspace.id && selectedTerminal?.id == terminal.id) {
                        "Terminal connection changed. Check its status before retrying."
                    }
                    val result = active.reattachTerminal(workspace.id, terminal.id, viewer, terminalViewport)
                    check(terminalSizing.reattached(active, terminal.id, result, expected)) {
                        "Terminal connection changed. Check its status before retrying."
                    }
                })
            TerminalZoomOverlay(terminalZoom, displayPreferences,
                foreground = runCatching { Color(android.graphics.Color.parseColor(currentGrid.foreground)) }.getOrDefault(Color.White),
                background = runCatching { Color(android.graphics.Color.parseColor(currentGrid.background)) }.getOrDefault(nativePanel),
                modifier = Modifier.align(Alignment.Center))
            }
            TerminalToolbarView(toolbarStore.layout, inputModifiers, inputOwner = inputClient to inputTarget,
                canInput = terminalAttached && selectedTerminal?.isReady == true && connectionReady && client != null && inputFailure == null && terminalDraft.operation == null,
                filesEnabled = artifactsReady,
                onModifier = { inputModifiers = inputModifiers.tap(it, android.os.SystemClock.uptimeMillis()) },
                onButton = { button ->
                    when (button) {
                        TerminalToolbarButton.PASTE -> pasteClipboard()
                        TerminalToolbarButton.FILES -> if (artifactsReady) {
                            inputModifiers = TerminalInputModifiers()
                            stopTerminalScrolling(); softwareKeyboard?.hide(); showTerminalFiles = true
                        }
                        TerminalToolbarButton.ZOOM_IN, TerminalToolbarButton.ZOOM_OUT -> {
                            inputModifiers = TerminalInputModifiers()
                            terminalZoom.step(if (button == TerminalToolbarButton.ZOOM_IN) 1 else -1)
                        }
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
                }, insert = if (terminalAttached && !directTyping && client != null && terminalDraft.operation == null && !preparingAttachments &&
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
                        val enabled = terminalAttached && client != null && inputFailure == null && terminalDraft.operation == null
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
                            enabled = terminalAttached && client != null && terminalDraft.operation == null && !preparingAttachments,
                            onContent = ::acceptTerminalPaste, onError = { error = it }) { pasteModifier ->
                            OutlinedTextField(terminalDraft.text, { text -> draftTarget?.let { drafts.edit(it, text) } },
                                Modifier.weight(1f).then(pasteModifier).onPreviewKeyEvent { event ->
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
                        val canSend = terminalAttached && client != null && (terminalDraft.text.isNotEmpty() || terminalDraft.attachments.isNotEmpty()) &&
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
    }
    val remoteBrowserContent: @Composable ColumnScope.() -> Unit = {
        val browser = selectedBrowser!!
        val workspace = selectedWorkspace
        val mac = pairedMacs.singleOrNull { it.code == code }
        val browserKey = if (mac != null && workspace != null) localBrowserKey(browserLogin, teamState.scope, mac, workspace.id) else null
        NativeRemoteBrowserPane(client, browser, busy, connectionError,
            panePicker = { title ->
                NativePanePicker(title, workspace, NativeWorkspacePane(browser = browser), Modifier.fillMaxWidth(),
                    onTerminal = { focusManager.clearFocus(); softwareKeyboard?.hide(); selectPane(NativeWorkspacePane(terminal = it)) },
                    onSurface = { focusManager.clearFocus(); softwareKeyboard?.hide(); selectPane(NativeWorkspacePane(surface = it)) },
                    onBrowser = { focusManager.clearFocus(); softwareKeyboard?.hide(); selectPane(NativeWorkspacePane(browser = it)) },
                    onNewWorkspace = if (canCreateInCurrentPane && !creatingWorkspace && !creatingTerminal) ::createWorkspace else null,
                    onNewTerminal = if (canCreateInCurrentPane && !creatingTerminal && !creatingWorkspace) ::createTerminalInPane else null,
                    onNewBrowser = workspace?.let { current -> workspaceSourceForPane()?.let { source -> ({ openNewBrowser(source, current) }) } },
                    browserState = NativeBrowserPickerState.from(connectionReady, hostCapabilities))
            },
            onBack = { selectedBrowser = null; selectedWorkspace = null; selectedSurface = null }, onReconnect = { retry++ },
            modeRevision = listOf(connectionReady, hostCapabilities, mac, feedSources[mac?.origin]?.availability),
            prefersOnDevice = browserKey?.let { localBrowsers.prefersOnDevice(it, browser.id) } == true,
            availability = { mac?.let { feedSession.browserNetworks.network(it)?.availability() } ?: MacBrowserAvailability.NOT_CONNECTED },
            onOnDevice = { url ->
                if (browserKey != null && workspace != null && mac != null && signedIn && store.taskSession() == browserLogin &&
                    selectedBrowser?.id == browser.id && selectedWorkspace?.browsers?.any { it.id == browser.id } == true &&
                    selectedWorkspace?.id == workspace.id && code == mac.code &&
                    browserKey == localBrowserKey(store.taskSession(), teamState.scope, mac, workspace.id) &&
                    store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac)) {
                    focusManager.clearFocus(); softwareKeyboard?.hide(); workspaceTabs.cancel()
                    localBrowsers.openOnDevice(browserKey, workspace, browser.id, url)
                    selectedTerminal = null; selectedWorkspace = null; selectedSurface = null; selectedBrowser = null
                    selectedChangesWorkspace = null; error = null
                }
            })
    }
    val changesContent: @Composable ColumnScope.() -> Unit = {
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
    val workspaceListContent: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { workspaceRoute = null; finishSearch(); showSettings = true }) {
                Image(painterResource(R.drawable.cmux_logo), "cmux settings", Modifier.size(24.dp))
            }
            IconButton(onClick = ::presentComputers) {
                Icon(painterResource(R.drawable.ic_computer_desktop), "Manage computers", tint = nativeMuted,
                    modifier = Modifier.size(22.dp))
            }
            NativeComputerSelector(pairedMacs, selectedComputer, appearances, machineColorIndices, computerConnections,
                computerMenuOpen, { computerMenuOpen = it }, ::selectPickerComputer, pendingPickerComputer,
                onPair = { computerMenuOpen = false; code = "" },
                owner = NativeComputerMenuOwner(store.taskSession(), teamState.scope),
                isOwnerCurrent = { owner -> account.isSignedIn() && store.taskSession() == owner.login &&
                    accountTeams.state.value.scope == owner.team },
                canSelect = { mac -> NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) && connection.allowsSaved(mac) },
                presence = scopedPresence)
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
                        DropdownMenuItem(text = { Text("New workspace") }, enabled = canCreateOnCurrentMac && !creatingWorkspace && !creatingTerminal, onClick = {
                            createMenuOpen = false; createWorkspace()
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
                NativeWorkspaceEmptyRow(when {
                    search.isNotBlank() -> NativeWorkspaceEmptyGuidance.SEARCH
                    unreadWorkspacesOnly -> NativeWorkspaceEmptyGuidance.UNREAD
                    else -> NativeWorkspaceEmptyGuidance.MAC
                }, emptyWorkspaceRecoveryState, onRetry = if (workspaceSources.isEmpty()) null else ({
                    val login = browserLogin
                    val owner = teamState.scope
                    val targets = workspaceSources.map { it.mac }
                    emptyWorkspaceRecovery.start {
                        check(store.taskSession() == login && accountTeams.state.value.scope == owner &&
                            targets.all { store.visiblePairedMacs().contains(it) && connection.allowsSaved(it) })
                        feedCoordinator.refreshWorkspaceLists(targets)
                    }
                }))
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
                                try { feedCoordinator.groupAction(owner.mac, group.id, action, title) }
                                catch (failure: Exception) { recordWorkspaceActionFailure(failure) }
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
                        workspace = workspace, groups = owner.groups, canCustomize = owner.canCustomizeWorkspace(),
                        displayPreferences = displayState,
                        computer = appearances.name(owner.mac).takeIf { selectedOrigin == null && pairedMacs.size > 1 },
                        appearance = appearances.get(owner.mac), machineId = owner.mac.colorIdentity.colorSeed,
                        machineColorIndex = machineColorIndices[owner.mac.colorIdentity],
                        canMove = canReorder && (owner.groups.none { it.liveAnchorWorkspaceId == workspace.id }),
                        onOpen = { open() },
                        onAction = { action, title ->
                            if (action == "customize") customizationTarget = WorkspaceCustomizationTarget(owner.mac.origin, workspace.id)
                            else if (action == "changes") open(changes = true)
                            else if (action == "browser.create") openNewBrowser(owner, workspace)
                            else if (action == "terminal.create") createTerminal(owner, workspace)
                            else if (action.startsWith("move:")) {
                                val target = action.removePrefix("move:").takeIf { it.isNotBlank() }
                                move(owner, workspace.id, NativeWorkspaceMove(target, null))
                            } else {
                                scope.launch {
                                    try { feedCoordinator.workspaceAction(owner.mac, workspace.id, action, title) }
                                    catch (failure: Exception) { recordWorkspaceActionFailure(failure) }
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
    val customizePane: ((NativeWorkspace) -> Unit)? = workspaceSourceForPane()?.takeIf { it.canCustomizeWorkspace() }?.let { source ->
        { workspace -> customizationTarget = WorkspaceCustomizationTarget(source.mac.origin, workspace.id) }
    }
    CompositionLocalProvider(LocalMacCompatibilityWarnings provides displayWarnings,
        LocalWorkspaceCustomizationAction provides customizePane) {
    NativeScreenLayout(Modifier.fillMaxSize().background(nativePage).statusBarsPadding().navigationBarsPadding().imePadding(), browserLogin, teamState.email) {
        LocalBrowserCreationProgress(localBrowserState.creating != null, localBrowsers::cancelRequest)
        if (signedIn && terminalStartupState.failure?.key?.let { it == displayedTab?.first } == true) {
            NativeTerminalCreationRecovery(creatingTerminal, connectionReady && selectedWorkspace != null) {
                selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { createTerminal(it, workspace) } }
            }
        }
        when {
            !signedIn -> NativeSignIn(account::sendCode, account::signIn, onUseHelper,
                onLicenses = { showLicenses = true }, macPolicy = displayPolicy, onSignedIn = { signedIn = true; error = null })
            showOnboarding -> onboardingContent()
            showSshComputers && sharedConnections != null -> NativeSshComputersRoute(sharedConnections.ssh) { showSshComputers = false }
            showSettings && showSshKeys -> NativeSshKeysRoute(store, browserLogin) { showSshKeys = false }
            showSettings && computersOwner != null -> computersContent()
            showSettings -> settingsContent()
            showTaskComposer -> taskComposerContent()
            screenResume.pending != null && selectedWorkspace == null && localBrowser == null -> {
                NativeWorkspaceWaitingPane("Restoring workspace…",
                    onBack = { screenResume.cancel(); workspaceRoute = null },
                    connected = connectionReady && error == null, connectionError = error ?: connectionError,
                    onReconnect = { error = null; retryDelay = 2_000; retry++ })
            }
            localBrowser != null && pairedMacs.any { localBrowserKey(browserLogin, teamState.scope, it, localBrowser.key.workspaceId) == localBrowser.key } -> localBrowserContent()
            cachedComputers != null || code.isBlank() || (selectedWorkspace == null && workspaceRoute == null && !notificationTab &&
                (showReconnectList || (client == null && connectionError != null && workspaceSources.none { it.hasWorkspaceSnapshot }))) -> reconnectContent()
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
            selectedWorkspace != null && (selectedSurface != null || selectedWorkspace!!.browserFallback(selectedBrowser?.id,
                NativeBrowserPickerState.from(connectionReady, hostCapabilities)) != null) -> {
                val shownSurface = selectedSurface ?: checkNotNull(selectedWorkspace!!.browserFallback(selectedBrowser?.id,
                    NativeBrowserPickerState.from(connectionReady, hostCapabilities)))
                NativeSurfaceView(selectedWorkspace!!, shownSurface, client, hostCapabilities, connectionReady,
                    onNewWorkspace = if (canCreateInCurrentPane && !creatingWorkspace && !creatingTerminal) ::createWorkspace else null,
                    onNewTerminal = if (canCreateInCurrentPane && !creatingTerminal && !creatingWorkspace) ::createTerminalInPane else null,
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
                    onNewWorkspace = if (canCreateInCurrentPane && !creatingWorkspace && !creatingTerminal) ::createWorkspace else null,
                    onNewTerminal = if (canCreateInCurrentPane && !creatingTerminal && !creatingWorkspace) ::createTerminalInPane else null,
                    onBack = { selectedTerminal = null; selectedWorkspace = null; selectedSurface = null },
                    onTerminal = { selectPane(NativeWorkspacePane(terminal = it)) },
                    onSurface = { selectPane(NativeWorkspacePane(surface = it)) },
                    onBrowser = { selectPane(NativeWorkspacePane(browser = it)) },
                    onNewBrowser = { selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { openNewBrowser(it, workspace) } } })
            }
            selectedTerminal != null -> terminalContent()
            selectedBrowser != null -> remoteBrowserContent()
            selectedChangesWorkspace != null -> changesContent()
            else -> workspaceListContent()
        }
        val visibleError = error ?: connectionError.takeIf { selectedTerminal != null || selectedBrowser != null || workspaceSources.isEmpty() }
        if (signedIn && visibleError != null) Row(Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(visibleError, Modifier.weight(1f).padding(vertical = 12.dp), color = Color(0xFFFFAAAA))
            if (selectedTerminal != null) TextButton(onClick = { retryDelay = 2_000; retry++ }) { Text("Reconnect") }
        }
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
internal fun NativeWorkspaceRow(
    workspace: NativeWorkspace,
    groups: List<NativeGroup>,
    canMove: Boolean,
    canCustomize: Boolean = false,
    displayPreferences: NativeDisplayPreferences = NativeDisplayPreferences(),
    computer: String? = null,
    appearance: NativeMacAppearance = NativeMacAppearance(), machineId: String? = null, machineColorIndex: Int? = null,
    onOpen: () -> Unit,
    onAction: (String, String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var title by remember(workspace.id) { mutableStateOf(workspace.title) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onOpen)
        .semantics {
            stateDescription = listOfNotNull("Pinned".takeIf { workspace.isPinned },
                workspace.unreadState.accessibilityLabel.takeIf { it.isNotEmpty() }).joinToString(", ")
        }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        NativeUnreadGutter(workspace.unreadState)
        NativeMacAvatar(appearance, machineId ?: workspace.id, index = machineColorIndex, defaultSymbol = "terminal")
        Spacer(Modifier.width(8.dp))
        val workspaceAccent = workspace.color?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.drop(1)?.toLongOrNull(16)
        Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 5.dp)
            .background(workspaceAccent?.let { Color(0xFF000000L or it).copy(alpha = .95f) } ?: Color.Transparent, RoundedCornerShape(1.5.dp))
            .testTag("workspace.color:${workspace.id}"))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            computer?.let { Text(it, color = nativeMuted, fontSize = 10.sp, maxLines = 1) }
            Text(workspace.title.ifBlank { "Workspace" }, Modifier.testTag("workspace.title:${workspace.id}"),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = if (displayPreferences.wrapTitles) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            workspace.description?.trim()?.takeIf { it.isNotEmpty() }?.let {
                Text(it, Modifier.testTag("workspace.description:${workspace.id}"), fontSize = 12.sp,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(workspace.preview?.takeIf { it.isNotEmpty() } ?: workspace.terminals.firstOrNull()?.title ?: workspace.title,
                Modifier.testTag("workspace.preview:${workspace.id}"), color = nativeMuted, fontSize = 11.sp,
                minLines = displayPreferences.previewLines, maxLines = displayPreferences.previewLines, overflow = TextOverflow.Ellipsis)
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
                if (canCustomize) DropdownMenuItem(text = { Text("Customize Workspace") }, onClick = { expanded = false; onAction("customize", null) })
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
    if (confirmClose) WorkspaceCloseDialog(WorkspaceCloseConfirmation.mac,
        onDismiss = { confirmClose = false }, onConfirm = { confirmClose = false; onAction("close", null) })
}

private fun nativeConnectionFailure(failure: Throwable): String {
    val networkFailure = generateSequence(failure) { it.cause }.take(8).any {
        it is java.net.SocketException || it is java.net.SocketTimeoutException ||
            it is java.net.UnknownHostException || it is java.io.EOFException ||
            it is kotlinx.coroutines.TimeoutCancellationException
    }
    return if (networkFailure) "Could not reach this Mac. Check that cmux is running, mobile pairing is enabled, and both devices are online, then retry."
        else failure.message ?: "Could not connect to this Mac."
}



@Composable
internal fun NativeComputerPicker(
    teamState: NativeAccountTeamsState, computerState: NativeComputersState, runtime: NativeIrohRuntime? = null,
    colorIndices: Map<NativeMacIdentity, Int> = emptyMap(),
    connections: Map<NativeMacIdentity, NativeComputerConnection> = emptyMap(),
    presence: NativeMacPresenceState = NativeMacPresenceState(), saved: List<NativeCredentialStore.PairedMac> = emptyList(),
    lastSeenHistory: Map<String, Long> = emptyMap(),
    preferences: NativeMacConnectionPreferences = NativeMacConnectionPreferences(),
    tailscaleRoutes: Map<NativeMacIdentity, String> = emptyMap(),
    forgetCallbacks: NativeComputerForgetCallbacks = NativeComputerForgetCallbacks(),
    presentDetails: ((NativeComputerDetailsPresentation) -> Unit)? = null,
    onSsh: (() -> Unit)? = null,
    hidden: List<NativeCredentialStore.PairedMac> = emptyList(),
    onVisibility: ((NativeCredentialStore.PairedMac, Boolean) -> Unit)? = null,
    cachedDisplay: Boolean = false, displayAppearances: NativeMacAppearances? = null,
    macPolicy: NativeMacCompatibilityPolicy = NativeMacCompatibilityPolicy.baked,
    connectingCode: String? = null, connectionFailure: String? = null, onCancelConnect: () -> Unit = {},
    canSelectSaved: (NativeCredentialStore.PairedMac) -> Boolean,
    canSelectDiscovered: (IrohV2Computer) -> Boolean,
    onSelectSaved: (NativeCredentialStore.PairedMac) -> Unit,
    onSelect: (IrohV2Computer) -> Unit, onSettings: () -> Unit, onComputers: (() -> Unit)? = null,
    onRefresh: () -> Unit, onPairing: (String) -> Unit, onNewTask: () -> Unit,
    onUseHelper: () -> Unit, onLicenses: () -> Unit, onError: (String?) -> Unit
) {
    val context = LocalContext.current
    val liveAppearances = nativeMacAppearances(computerState.account)
    val appearances = displayAppearances ?: liveAppearances
    val savedRows = NativeComputerList.rows(saved, appearances, presence, lastSeenHistory, preferences,
        computerState.computers, tailscaleRoutes)
    val reconnect = NativeReconnectComputers.merge(savedRows, computerState.computers, hidden)
    val connectingPairing = connectingCode?.let { PairingCodeParser.parse(it).getOrNull() }
    var rowSelected by remember(teamState.scope) { mutableStateOf(false) }
    LaunchedEffect(connectingCode, connectionFailure) { if (connectingCode == null) rowSelected = false }
    var pairingText by rememberSaveable(teamState.userId, teamState.selectedTeamId) { mutableStateOf("") }
    var showPairingOptions by rememberSaveable(teamState.userId, teamState.selectedTeamId) { mutableStateOf(false) }
    var showPairingHelp by rememberSaveable(teamState.userId, teamState.selectedTeamId) { mutableStateOf(false) }
    if (showPairingHelp) NativePairingHelp(macPolicy, signedIn = true,
        onDismiss = { showPairingHelp = false }, onFindMac = { showPairingHelp = false; onRefresh() },
        onTailscalePairing = { showPairingHelp = false; showPairingOptions = true })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { NativeHeader("Computers") }
        TextButton(onClick = { onSettings() }) { Text("Settings") }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
        val selectedTeam = teamState.teams.firstOrNull { it.id == teamState.selectedTeamId }
        Text(selectedTeam?.name ?: "Your cmux account", fontWeight = FontWeight.SemiBold)
        if (cachedDisplay) NativeCachedComputersNotice()
        else Text(if (savedRows.isEmpty()) "Open cmux on your Mac and enable mobile pairing to see it here."
            else "Tap a computer to reconnect. Its connection method can be changed in Details.", color = nativeMuted)
        TextButton(onClick = { showPairingHelp = true }, modifier = Modifier.testTag("computers.pairing.help")) {
            Text("How to connect your Mac")
        }
        if (teamState.loading || (!cachedDisplay && computerState.loading)) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            Text("Finding your computers…", color = nativeMuted)
        }
        (teamState.error ?: computerState.error)?.let {
            Text(it, color = Color(0xFFFF9999), modifier = Modifier.padding(vertical = 10.dp))
        }
        connectionFailure?.let { Text(it, color = Color(0xFFFF9999), modifier = Modifier.padding(vertical = 10.dp)) }
        if (connectingCode != null) TextButton(onClick = onCancelConnect) { Text("Cancel connection") }
        if (computerState.ready && reconnect.isEmpty) {
            Text("No computers available in this team.", color = nativeMuted,
                modifier = Modifier.padding(vertical = 16.dp))
        }
        if (reconnect.saved.isNotEmpty()) {
            Text("Your Computers", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            reconnect.saved.forEach { row ->
                val mac = row.mac
                key(mac.origin) {
                    Surface(Modifier.fillMaxWidth().padding(top = 12.dp).clickable(enabled = !cachedDisplay) {
                        if (!cachedDisplay && !rowSelected && connectingCode == null && canSelectSaved(mac)) { rowSelected = true; onSelectSaved(mac) }
                    }, shape = RoundedCornerShape(14.dp), color = nativePanel) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            NativeMacAvatar(appearances.get(mac), mac.colorIdentity.colorSeed, index = colorIndices[mac.colorIdentity])
                            val connection = if (connectingCode == mac.code) NativeComputerConnection(NativeFeedAvailability.CONNECTING)
                                else connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: NativeComputerConnection()
                            NativeComputerRowLabel(row.name, presence.buildLabel(mac), connection, row.presence,
                                reconnect = true, modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                                routeDescription = row.route.endpoint, olderPairing = row.olderPairing,
                                identity = NativeMacIdentity(mac.deviceId, mac.instanceTag))
                            NativeComputerStatusDot(connection, row.presence, reconnect = true)
                            NativeMacAwakeIndicator(connection)
                            if (!cachedDisplay) NativeSavedComputerDetailsButton(runtime, computerState, mac, colorIndices[mac.colorIdentity], connection,
                                forgetCallbacks, presentDetails)
                            onVisibility?.let { change ->
                                NativeComputerVisibilitySwitch(mac, row.name, true, !cachedDisplay && connectingCode == null) { change(mac, it) }
                            }
                        }
                    }
                }
            }
        }
        if (reconnect.discovered.isNotEmpty()) Text(if (reconnect.saved.isEmpty()) "Available computers" else "Other computers",
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
        reconnect.discovered.forEach { mac ->
            key(canonicalMacDeviceId(mac.deviceId), mac.buildTag) {
                Surface(Modifier.fillMaxWidth().padding(top = 12.dp).clickable {
                    if (!rowSelected && connectingCode == null && canSelectDiscovered(mac)) { rowSelected = true; onSelect(mac) }
                }, shape = RoundedCornerShape(14.dp), color = nativePanel) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        NativeMacAvatar(appearances.get(mac.deviceId, mac.buildTag), mac.colorIdentity.colorSeed,
                            index = colorIndices[mac.colorIdentity])
                        val connecting = connectingPairing is PairingCode.Iroh &&
                            connectingPairing.macDeviceId?.let(::canonicalMacDeviceId) == canonicalMacDeviceId(mac.deviceId) &&
                            connectingPairing.buildTag == mac.buildTag
                        val connection = if (connecting) NativeComputerConnection(NativeFeedAvailability.CONNECTING)
                            else connections[NativeMacIdentity(mac.deviceId, mac.buildTag)] ?: NativeComputerConnection()
                        val heartbeat = presence.presence(mac.deviceId, mac.buildTag)
                        NativeComputerRowLabel(appearances.get(mac.deviceId, mac.buildTag).displayName(mac.name),
                            presence.buildLabel(mac.deviceId, mac.buildTag), connection, heartbeat,
                            reconnect = true, modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                            routeDescription = NativeComputerList.route(mac.deviceId, mac.buildTag, preferences,
                                computerState.computers, tailscaleRoutes).endpoint,
                            identity = NativeMacIdentity(mac.deviceId, mac.buildTag))
                        NativeComputerStatusDot(connection, heartbeat, reconnect = true)
                        NativeComputerDetailsButton(runtime, computerState, NativeComputerTarget.from(mac), colorIndices[mac.colorIdentity], connection,
                            forgetCallbacks, presentDetails)
                    }
                }
            }
        }
        if (hidden.isNotEmpty() && onVisibility != null)
            NativeHiddenComputerRows(hidden, appearances, colorIndices, enabled = !cachedDisplay, onVisibility = onVisibility)
        TextButton(onClick = onRefresh, enabled = !teamState.loading) { Text("Refresh computers") }
        Spacer(Modifier.height(20.dp))
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
        if (savedRows.isNotEmpty() || hidden.isNotEmpty()) TextButton(onClick = onComputers ?: onSettings) { Text("Manage computers") }
    }
}


@Composable
internal fun NativeSignIn(sendCode: suspend (String) -> Unit, signIn: suspend (String) -> Unit,
                         onUseHelper: () -> Unit, onLicenses: () -> Unit,
                         macPolicy: NativeMacCompatibilityPolicy = NativeMacCompatibilityPolicy.baked,
                         onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showPairingHelp by rememberSaveable { mutableStateOf(false) }
    if (showPairingHelp) NativePairingHelp(macPolicy, signedIn = false,
        onDismiss = { showPairingHelp = false }, onFindMac = { showPairingHelp = false },
        onTailscalePairing = {})
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    NativeHeader("Sign in to cmux")
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Use the same cmux account as your Mac.", color = nativeMuted)
        TextButton(onClick = { showPairingHelp = true }, modifier = Modifier.testTag("signin.pairing.help")) {
            Text("Set up cmux on your Mac")
        }
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
internal fun NativeSavedComputerRows(macs: List<NativeCredentialStore.PairedMac>, appearances: NativeMacAppearances,
    colorIndices: Map<NativeMacIdentity, Int>, runtime: NativeIrohRuntime?, state: NativeComputersState,
    connections: Map<NativeMacIdentity, NativeComputerConnection>, presence: NativeMacPresenceState, lastSeenHistory: Map<String, Long>,
    preferences: NativeMacConnectionPreferences, tailscaleRoutes: Map<NativeMacIdentity, String>,
    forgetCallbacks: NativeComputerForgetCallbacks,
    presentDetails: (NativeComputerDetailsPresentation) -> Unit, onSelect: (NativeCredentialStore.PairedMac) -> Unit) {
    val rows = NativeComputerList.rows(macs, appearances, presence, lastSeenHistory, preferences, state.computers, tailscaleRoutes)
    NativeComputerMethodSections(rows) { row ->
        val mac = row.mac
        Row(Modifier.fillMaxWidth().clickable { onSelect(mac) }
            .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeMacAvatar(appearances.get(mac), mac.colorIdentity.colorSeed, index = colorIndices[mac.colorIdentity])
            Spacer(Modifier.width(14.dp))
            val connection = connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: NativeComputerConnection()
            NativeComputerRowLabel(row.name, presence.buildLabel(mac), connection, row.presence,
                reconnect = false, modifier = Modifier.weight(1f), routeDescription = row.route.endpoint,
                olderPairing = row.olderPairing, identity = NativeMacIdentity(mac.deviceId, mac.instanceTag))
            NativeComputerStatusDot(connection, row.presence, reconnect = false)
            NativeMacAwakeIndicator(connection)
            NativeSavedComputerDetailsButton(runtime, state, mac, colorIndices[mac.colorIdentity], connection, forgetCallbacks, presentDetails)
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
    artifactPreferences: android.content.SharedPreferences, displayState: NativeDisplayPreferences) {
    Text("TERMINAL", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = nativeMuted, fontSize = 11.sp)
    NativeTerminalSizingSettings(artifactPreferences, displayState)
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
