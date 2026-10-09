package io.github.docmorphic.cmuxapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeScreen(
    onUseHelper: () -> Unit, incomingCode: String? = null, incomingNotificationRoute: String? = null,
    onNotificationHandled: (String) -> Unit = {}, onPairingHandled: (String) -> Unit = {},
    connector: NativeConnector? = null, sshSessionOverride: NativeSshSession? = null,
    sortStoreOverride: NativeWorkspaceSortStore? = null
) {
    val context = LocalContext.current
    val workspaceSortStore = remember(context, sortStoreOverride) {
        sortStoreOverride ?: context.getSharedPreferences("native_workspace_sort", android.content.Context.MODE_PRIVATE).let { prefs ->
            NativeWorkspaceSortStore({ prefs.getString("state", null) }, { prefs.edit().putString("state", it).apply() })
        }
    }
    val workspaceSort by workspaceSortStore.state.collectAsState()
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
    val cloudModel = remember(runtimeOwner, connector, accountTeams) {
        if (connector == null) ViewModelProvider(runtimeOwner, NativeCloudViewModel.Factory(context, account, store, accountTeams))
            .get(NativeCloudViewModel::class.java) else null
    }
    LaunchedEffect(cloudModel, incomingCode, incomingNotificationRoute) {
        if (incomingCode != null || incomingNotificationRoute != null) cloudModel?.leaveWorkspace()
    }
    val cloudNavigationFailure = cloudModel?.navigationFailure?.collectAsState()?.value
    val cloudController = cloudModel?.controller?.collectAsState()?.value
    val cloudTunnel = cloudModel?.tunnel?.collectAsState()?.value
    val cloudTunnelState = cloudTunnel?.state?.collectAsState()?.value
    val cloudConnectionFailures = cloudModel?.connectionFailures?.collectAsState()?.value.orEmpty()
    val cloudWorkspaces = cloudModel?.workspaces?.collectAsState()?.value
    val allCloudSnapshots = cloudWorkspaces?.state?.collectAsState()?.value.orEmpty()
    val cloudVisibility = cloudModel?.visibility?.collectAsState()?.value
    val cloudVisibilityFailure = cloudVisibility?.failure?.collectAsState()?.value
    val hiddenCloudIds = cloudVisibility?.hidden?.collectAsState()?.value.orEmpty()
    val cloudSnapshots = allCloudSnapshots.filterKeys { it !in hiddenCloudIds }
    val cloudCreation = cloudModel?.creation?.collectAsState()?.value
    val cloudCreationState = cloudCreation?.state?.collectAsState()?.value
    val cloudCreationBusy = cloudCreationState?.pending == true
    val cloudRoute = cloudModel?.route?.collectAsState()?.value
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
    val ticketPairing = feedSession.ticketPairing
    val ticketProposal by ticketPairing.pending.collectAsState()
    LaunchedEffect(signedIn, teamState.scope) {
        if (!signedIn) ticketPairing.clear() else teamState.scope?.let(ticketPairing::reconcile)
    }
    // One stable receiver keeps callbacks from capturing dozens of separate state delegates.
    // Keyed and saveable state below retains its own reset/restoration boundaries.
    val transientState = remember {
        NativeScreenTransientState(
            initialCode = ticketPairing.resumeCode() ?: store.load()?.optString("pairing_code").orEmpty(),
            initialPairedMacs = store.pairedMacs(),
            initialBackgroundNotifications = NativeNotificationService.isEnabled(context)
        )
    }
    with(transientState) {
        var pendingPairingCode by rememberSaveable(signedIn, key = "in_app_pairing_confirmation_v2") { mutableStateOf<String?>(null) }
        var pairingSelectionCode by remember(signedIn) { mutableStateOf<String?>(null) }
        var startedForegroundConnection by rememberSaveable(signedIn) { mutableStateOf(false) }
        val deferStartupForPairing = !startedForegroundConnection && (incomingCode != null || pendingPairingCode != null || ticketProposal != null)
        var connectionError by remember(code) { mutableStateOf<String?>(null) }
        var hostName by remember(code) { mutableStateOf("cmux") }
        var hostCapabilities by remember(code) { mutableStateOf<Set<String>>(emptySet()) }
        var mutationAuthorityTick by remember(client) { mutableIntStateOf(0) }
        LaunchedEffect(client) {
            client?.macMutationTicket()?.expiresAtMillis?.let { expiry ->
                val now = System.currentTimeMillis()
                delay(if (expiry <= now) 0 else expiry - now)
                mutationAuthorityTick++
            }
        }
        // Eligibility depends on wall time, so a remembered result can outlive its ticket
        // when another surface opens or an expiry wakeup precedes a clock adjustment.
        val currentMacMutationAllowed = client?.allowsMacWorkspaceMutations() == true
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
        var showShortcuts by rememberSaveable(signedIn) { mutableStateOf(false) }
        var computersOwner by remember(signedIn) { mutableStateOf<NativeComputerMenuOwner?>(null) }
        LaunchedEffect(showSettings) { if (!showSettings) computersOwner = null }
        var showReconnectList by rememberSaveable(signedIn) { mutableStateOf(false) }
        LaunchedEffect(connectionReady) { if (connectionReady) showReconnectList = false }
        var showSshComputers by rememberSaveable(signedIn) { mutableStateOf(false) }
        var showSshKeys by rememberSaveable(signedIn) { mutableStateOf(false) }
        var showTaskComposer by rememberSaveable(signedIn) { mutableStateOf(false) }
        var taskDraftId by rememberSaveable(signedIn) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
        var taskDraftRepository by remember(signedIn) { mutableStateOf<TaskDraftRepository?>(null) }
        var taskDraftLoadError by remember(signedIn) { mutableStateOf<String?>(null) }
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
        var agentFeedTab by rememberSaveable(signedIn) { mutableStateOf(false) }
        var cloudTab by rememberSaveable(signedIn) { mutableStateOf(false) }
        LaunchedEffect(incomingCode, incomingNotificationRoute) {
            if (incomingCode != null || incomingNotificationRoute != null) { cloudTab = false; agentFeedTab = false }
        }
        var searchState by rememberSaveable(signedIn, stateSaver = listSaver(
            save = { state: NativeSearchState -> state.commit().let { listOf(it.workspaceQuery, it.notificationQuery, it.feedQuery) } },
            restore = { NativeSearchState(workspaceQuery = NativeSearchText.boundQuery(it[0]),
                notificationQuery = NativeSearchText.boundQuery(it[1]), feedQuery = NativeSearchText.boundQuery(it.getOrElse(2) { "" })) }
        )) { mutableStateOf(NativeSearchState()) }
        val searchScope = if (agentFeedTab) NativeSearchScope.FEED else if (notificationTab) NativeSearchScope.NOTIFICATIONS else NativeSearchScope.WORKSPACES
        val search = searchState.text(searchScope).trim()
        val notificationQuery = searchState.text(NativeSearchScope.NOTIFICATIONS).trim()
        fun finishSearch(cancel: Boolean = false) {
            searchState = if (cancel) searchState.clear(searchScope) else searchState.commit()
            focusManager.clearFocus(); softwareKeyboard?.hide()
        }

        var selectedWorkspace by paneSelection.workspace
        var selectedTerminal by paneSelection.terminal
        var selectedSurface by paneSelection.surface
        val terminalZoom = remember(code, selectedWorkspace?.id, selectedTerminal?.id) { TerminalZoomState() }
        val terminalCells = remember(density, terminalZoom.size) {
            TerminalCellMetrics.fromFontSize(with(density) { terminalZoom.size.sp.toPx() }, with(density) { 2.dp.toPx() })
        }
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
        val currentIncomingRoute by rememberUpdatedState(incomingNotificationRoute ?: inAppNotification?.routeId)
        val handleNotification by rememberUpdatedState(onNotificationHandled)
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
        var selectedComputerOrigin by rememberSaveable(signedIn) {
            mutableStateOf(store.load()?.optString("computer_selection").orEmpty())
        }
        val nativeCreation = feedSession.workspaceCreation
        val nativeCreationState by nativeCreation.state.collectAsState()
        // Long-lived inventory callbacks must read the current flag, not their launch composition's value.
        val creatingTerminal by remember(nativeCreation) { derivedStateOf {
            nativeCreationState is NativeCreationState.Running && nativeCreationState.request?.workspaceId != null
        } }
        val creatingWorkspace by remember(nativeCreation) { derivedStateOf {
            nativeCreationState is NativeCreationState.Running && nativeCreationState.request?.workspaceId == null
        } }
        val creationNavigation = rememberSaveable(saver = NativeCreationNavigation.saver) { NativeCreationNavigation() }
        var showComputerOrder by rememberSaveable(browserLogin, teamState.scope) { mutableStateOf(false) }
        var workspaceFilter by rememberSaveable(browserLogin, teamState.scope, stateSaver = NativeWorkspaceFilter.saver) {
            mutableStateOf(NativeWorkspaceFilter())
        }
        val unreadWorkspacesOnly = workspaceFilter.unread
        val sshRuntimeState = sharedConnections?.ssh?.state?.collectAsState()?.value
        val sshSession = if (connector != null) sshSessionOverride?.takeIf { browserLogin != null && it.isOpen }
            else sshRuntimeState?.resource?.takeIf { sshRuntimeState.login == browserLogin && it.isOpen }
        val sshCreationState = sshSession?.workspaceCreation?.state?.collectAsState()?.value
        val sshTargets = nativeSshCreateTargets(sshSession)
        val noKnownComputers = pairedMacs.isEmpty() && hiddenMacs.isEmpty() && sshTargets.isEmpty() && allCloudSnapshots.isEmpty()
        val cloudTabState = key(browserLogin, teamState.scope) { rememberSaveableStateHolder() }
        val sshFeed = sshSession?.workspaceFeed?.state?.collectAsState()?.value.orEmpty()
        val sshNavigation = rememberSaveable(saver = NativeSshCreationNavigation.saver) { NativeSshCreationNavigation() }
        val sshRoute = sshNavigation.route?.takeIf { route -> route.login == browserLogin && sshSession?.isOpen == true &&
            sshTargets.any { it.host.connectsLike(route.host) } }
        var displayedSshTarget by remember(sshRoute) { mutableStateOf(sshRoute?.target) }
        val sshCreationBusy = sshCreationState is SshWorkspaceCreationState.Running

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
        val workspaceTabs = feedSession.workspaceTabs
        val terminalStartup = feedSession.terminalStartup
        val terminalStartupState by terminalStartup.state.collectAsState()
        val pendingWorkspaceTab by workspaceTabs.pending.collectAsState()
        val displayedTab = if (sshRoute != null || showSettings || showTaskComposer || selectedChangesWorkspace != null || workspaceRoute != null || currentIncomingRoute != null) null
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
                val destination = if (sshRoute != null || showSettings || showTaskComposer || currentIncomingRoute != null || (pendingPairingCode != null || ticketProposal != null)) null else
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
            newerNavigation = sshRoute != null || showSettings || showTaskComposer || currentIncomingRoute != null || incomingCode != null || (pendingPairingCode != null || ticketProposal != null) ||
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
            selectedChangesWorkspace?.id, showSettings, showTaskComposer, notificationTab, currentIncomingRoute, localBrowser?.key,
            showSshComputers, sshRoute)
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
        fun beginNativeCreation(mac: NativeCredentialStore.PairedMac, workspaceId: String? = null, groupId: String? = null) {
            val login = browserLogin ?: return
            val id = nativeCreation.begin(login, mac, workspaceId, groupId, teamState.scope) ?: return
            creationNavigation.begin(id, login, browserNavigationContext() + selectedComputerOrigin, teamState.scope)
            workspaceTabs.cancel()
        }
        fun createTerminal(source: NativeFeedSource, workspace: NativeWorkspace) {
            beginNativeCreation(source.mac, workspaceId = workspace.id)
        }
        val feedSources by feedCoordinator.sources.collectAsState()
        var customizationTarget by rememberSaveable(stateSaver = workspaceCustomizationTargetSaver) {
            mutableStateOf<WorkspaceCustomizationTarget?>(null)
        }
        val activeCustomization = customizationTarget
        val customizationMac = pairedMacs.singleOrNull { mac ->
            activeCustomization?.matches(browserLogin, teamState.scope, mac) == true && connection.allowsSaved(mac)
        }
        SideEffect { if (activeCustomization != null && customizationMac == null) customizationTarget = null }
        val customizationWorkspace = customizationMac?.let { feedSources[it.origin]?.workspaces?.singleOrNull { row -> row.id == customizationTarget?.workspaceId } }
        if (signedIn && activeCustomization != null && customizationMac != null && customizationWorkspace != null) {
            // An unconsumed restored child draft must never attach to a later editor owned by another login/team.
            key(activeCustomization) {
                NativeWorkspaceCustomizationSheet(customizationWorkspace, onDismiss = { customizationTarget = null }) { baseline, draft ->
                    check(activeCustomization.matches(store.taskSession(), accountTeams.state.value.scope, customizationMac)) {
                        "Workspace account changed. Reopen the editor."
                    }
                    feedCoordinator.customizeWorkspace(customizationMac, customizationWorkspace.id, baseline, draft)
                }
            }
        }

        fun workspaceSourceForPane(): NativeFeedSource? = pairedMacs.singleOrNull { it.code == code }?.let { mac ->
            feedSources[mac.origin] ?: NativeFeedSource(mac)
        }
        LaunchedEffect(feedSources) { feedSources.values.forEach(localBrowsers::observeWorkspaces) }
        val workspaceMoves = feedSession.workspaceMoves
        val moveSources by workspaceMoves.sources.collectAsState()
        val moveStatus by workspaceMoves.status.collectAsState()
        val selectedComputer = pairedMacs.firstOrNull { it.ownsOrigin(selectedComputerOrigin) }
        val selectedOrigin = selectedComputer?.origin
        val selectedSshComputer = sshTargets.singleOrNull { "ssh:${it.host.id}" == selectedComputerOrigin }
        // Preserve the Cloud host identity even before its catalog loads or after removal.
        // An unavailable selected host must never broaden the list to unrelated computers.
        val selectedCloudId = CloudAddress.parse(selectedComputerOrigin)?.takeIf { it.component == null }?.machineId
        val selectedCloudComputer = selectedCloudId?.let(cloudSnapshots::get)
        val visibleSshRows = if (selectedCloudId != null || selectedOrigin != null) emptyList() else sshTargets
            .filter { selectedSshComputer == null || it.host.id == selectedSshComputer.host.id }
            .flatMap { sshFeed[it.host.id]?.rows.orEmpty() }
        val visibleCloudRows = if (selectedOrigin != null || selectedSshComputer != null) emptyList()
            else cloudSnapshots.values.filter { selectedCloudId == null || it.machine.id == selectedCloudId }.flatMap { it.rows }
        SideEffect {
            sshNavigation.reconcile(browserLogin, sshCreationState, browserNavigationContext() + selectedComputerOrigin,
                hostCurrent = { host -> sshSession?.isOpen == true && sshSession.hosts.state.value.host(host.id)?.connectsLike(host) == true },
                completed = { sshSession?.workspaceCreation?.clearCompleted(it) })?.let { error = it }
        }

        SideEffect {
            // Wait for restored pane/account admission before binding the saved waiter to this Activity.
            if (screenResume.pending == null && (connector != null || teamState.scope != null || !signedIn)) {
                creationNavigation.reconcile(browserLogin, nativeCreationState,
                    browserNavigationContext() + selectedComputerOrigin,
                    admitted = { request -> request.team == teamState.scope && feedSession.allowsCreation(request) },
                    completed = nativeCreation::clearCompleted, team = teamState.scope) { request, destination ->
                    inAppNotification = null; error = null
                    workspaceRoute = NativeWorkspaceRoute(request.mac.origin, destination.workspace.id,
                        terminalId = destination.terminalId, createdWorkspace = destination.workspace,
                        createdAtMillis = android.os.SystemClock.elapsedRealtime())
                }
            }
        }

        var pendingPickerCode by remember(signedIn) { mutableStateOf<String?>(null) }
        var expectedReconnect by remember(signedIn) { mutableStateOf<NativeCredentialStore.PairedMac?>(null) }
        LaunchedEffect(store, historyRevision) {
            val latest = store.pairedMacs()
            val selected = savedPairedMacs.singleOrNull { it.code == code }
            val successor = refreshedNativeSavedSelection(selected, latest, teamState.scope)
            if (selected != null && successor != null && successor != selected && !ticketPairing.requiresTicket(code)) {
                code = successor.code
                if (expectedReconnect == selected) expectedReconnect = successor
            }
            savedPairedMacs = latest
        }
        val pendingPickerComputer = pairedMacs.singleOrNull { it.code == pendingPickerCode }
        val macSwitchRecovery = feedSession.macSwitchRecovery
        fun switchOwner() = store.taskSession()?.takeIf { signedIn }?.let { NativeMacSwitchRecovery.Owner(it, teamState.scope) }
        macSwitchRecovery.reconcile(switchOwner())
        fun canRestoreMac(previous: NativeCredentialStore.PairedMac) = connection.allowsSaved(previous) && store.visiblePairedMacs().any {
            it.code == previous.code && it.deviceId == previous.deviceId && it.instanceTag == previous.instanceTag &&
                it.accountUserId == previous.accountUserId && it.accountTeamId == previous.accountTeamId
        }
        fun selectMacCode(target: String) {
            ticketPairing.clear()
            expectedReconnect = null
            pendingPickerCode = null
            pairingSelectionCode = null
            switchOwner()?.let { owner ->
                macSwitchRecovery.begin(owner, target,
                    connectedCode.takeIf { connectionReady && client?.isClosed == false }, selectedComputerOrigin)
            }
            code = target; error = null; retryDelay = 2_000
        }
        fun selectPairingCode(target: String, usingTicket: Boolean = false) {
            if (!usingTicket) ticketPairing.clear()
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
            cloudModel?.leaveWorkspace()
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
            cloudModel?.leaveWorkspace()
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
        fun canSelectSsh(target: NativeSshCreateTarget) = signedIn && browserLogin != null &&
            store.taskSession() == browserLogin && target.session === sshSession && target.session.isOpen &&
            target.session.hosts.state.value.host(target.host.id)?.connectsLike(target.host) == true
        fun selectSshComputer(target: NativeSshCreateTarget) {
            if (!canSelectSsh(target)) return
            selectPickerComputer(null)
            selectedComputerOrigin = "ssh:${target.host.id}"
            store.update { it.put("computer_selection", selectedComputerOrigin) }
            target.session.workspaceFeed.open(target.host, explicit = false)
        }
        LaunchedEffect(cloudModel, cloudNavigationFailure) { cloudNavigationFailure?.let { error = it } }
        LaunchedEffect(cloudVisibility, cloudVisibilityFailure) { cloudVisibilityFailure?.let { error = it } }
        LaunchedEffect(cloudCreation, cloudCreationState?.machineId, cloudCreationState?.failure) {
            cloudCreationState?.failure?.let { failure ->
                val name = allCloudSnapshots[cloudCreationState.machineId]?.machine?.preferredName
                error = if (name != null) "$name: $failure" else failure
            }
        }
        fun canCreateCloud(snapshot: CloudWorkspaceSnapshot) = cloudWorkspaces != null &&
            cloudModel?.workspaces?.value === cloudWorkspaces && cloudCreation?.canCreate(snapshot.machine.id) == true
        fun createCloudWorkspace(snapshot: CloudWorkspaceSnapshot) {
            if (!canCreateCloud(snapshot)) return
            error = null
            if (cloudWorkspaces?.let { cloudModel?.createWorkspace(snapshot.machine.id, it) } == true) {
                sshNavigation.leave(); screenResume.cancel(); workspaceRoute = null; inAppNotification = null
                selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null; selectedChangesWorkspace = null
            }
        }
        fun canSelectCloud(snapshot: CloudWorkspaceSnapshot) = signedIn && browserLogin != null &&
            store.taskSession() == browserLogin && accountTeams.state.value.scope == teamState.scope &&
            cloudWorkspaces != null && cloudModel?.workspaces?.value === cloudWorkspaces &&
            cloudWorkspaces.state.value.containsKey(snapshot.machine.id)
        fun selectCloudComputer(snapshot: CloudWorkspaceSnapshot) {
            if (!canSelectCloud(snapshot)) return
            selectPickerComputer(null)
            sshNavigation.leave(); inAppNotification = null
            selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null; selectedChangesWorkspace = null
            selectedComputerOrigin = CloudAddress(snapshot.machine.id).identifier
            store.update { it.put("computer_selection", selectedComputerOrigin) }
            workspaceSortStore.recordOpened(selectedComputerOrigin, System.currentTimeMillis())
            cloudWorkspaces?.refresh(snapshot.machine.id)
        }
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
        LaunchedEffect(pairedMacs, selectedComputerOrigin, sshTargets, sshSession) {
            feedSession.taskModels.retainOrigins(pairedMacs.flatMap { it.origins }.toSet())
            if (selectedComputerOrigin.isNotBlank() && selectedComputer == null && selectedSshComputer == null && selectedCloudId == null &&
                (!selectedComputerOrigin.startsWith("ssh:") || sshSession != null)) selectComputer(null)
        }
        val canCreateOnCurrentMac = connectionReady && client != null && connectedCode == code &&
            (selectedCloudId == null && selectedSshComputer == null && (selectedComputer == null || selectedComputer.code == connectedCode))
        val canCreateInCurrentPane = connectionReady && client != null && connectedCode == code
        val computerConnections = nativeComputerConnections(pairedMacs, feedSources,
            activeCode = connectedCode.takeIf { connectionReady && client != null && it == code },
            pendingCode = code.takeIf { signedIn && it.isNotBlank() && !connectionReady && busy },
            foregroundWorkspaces = workspaces)
        NativeSavedComputerDetailsHost(sharedConnections?.native, computerState, computerDetails,
            computerConnections, forgetCallbacks, store, connection, feedCoordinator) { computerDetails = null }
        // Separate feed/terminal/route setup from account and computer state.
        // Nested groups keep callback capture code below ART's method-size guard.
        val workspaceContent: @Composable () -> Unit = {
            val visibleFeedSources = remember(feedSources, pairedMacs) {
                feedSources.values.filter { source -> pairedMacs.any { it.origin == source.mac.origin } }
            }
            val scopedFeedSources = remember(visibleFeedSources, selectedOrigin, selectedSshComputer, selectedCloudId) {
                visibleFeedSources.filter { selectedCloudId == null && selectedSshComputer == null && (selectedOrigin == null || it.mac.origin == selectedOrigin) }
            }
            val agentReadPrefs = remember(context) { context.getSharedPreferences("native_agent_feed_read", android.content.Context.MODE_PRIVATE) }
            val agentReadOwner = remember(browserLogin, teamState.scope) {
                java.security.MessageDigest.getInstance("SHA-256").digest(org.json.JSONArray(listOf(browserLogin, teamState.scope?.toString())).toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            var agentReadState by remember(agentReadOwner) { mutableStateOf(NativeAgentFeedReadState.decode(
                agentReadPrefs.getString(agentReadOwner, null), System.currentTimeMillis() / 1000.0)) }
            LaunchedEffect(agentReadOwner, agentReadState) { agentReadPrefs.edit().putString(agentReadOwner, agentReadState.encode()).apply() }
            val agentEntries = remember(scopedFeedSources) { aggregateNativeAgentFeed(scopedFeedSources) }
            val agentNeedsInputCount = agentEntries.count { agentReadState.needsInput(it) }
            var agentNeedsInputOnly by rememberSaveable(agentReadOwner) { mutableStateOf(false) }
            // Feed is removed when another primary tab or compact detail opens.
            // Its local viewport, question choices and modal draft belong to
            // this account, rather than to the lifetime of the visible list.
            val agentFeedState = key(agentReadOwner) { rememberSaveableStateHolder() }
            var agentFilterMenu by remember { mutableStateOf(false) }
            val feedEntries = remember(scopedFeedSources, selectedOrigin, appearances) {
                aggregateNativeFeed(scopedFeedSources, selectedOrigin, appearances::name)
            }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val terminalBells = remember(client, selectedWorkspace?.id, selectedTerminal?.id) { TerminalBellSignal() }
            val visibleNotificationMac = if (localBrowser != null)
                pairedMacs.singleOrNull { it.ownsOrigin(localBrowser.key.computerId) }
                else pairedMacs.singleOrNull { it.code == code }
            ObserveNativeNotificationSelection(lifecycle,
                if ((cloudTab && !usesWorkspaceSidebar(configuration.screenWidthDp, configuration.screenHeightDp)) ||
                    displayedTab == null || browserLogin == null || visibleNotificationMac == null ||
                    (pendingPairingCode != null || ticketProposal != null) || screenResume.pending != null || showSshComputers || showLicenses) null
                else NativeNotificationSelection(browserLogin, visibleNotificationMac.origin, displayedTab.first.workspaceId,
                    displayedTab.second?.takeIf { it.kind == NativeWorkspaceTabKind.TERMINAL }?.id))
            var feedForeground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
            LaunchedEffect(cloudModel, feedForeground) { cloudModel?.setForeground(feedForeground) }
            DisposableEffect(cloudModel) { onDispose { cloudModel?.setForeground(false) } }
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
            val workspaceSources = remember(pairedMacs, feedSources, moveSources, selectedOrigin, selectedSshComputer, selectedCloudId, connectedCode, client, workspaces, groups, hostCapabilities, mutationAuthorityTick) {
                pairedMacs.filter { selectedCloudId == null && selectedSshComputer == null && (selectedOrigin == null || it.origin == selectedOrigin) }.map { mac ->
                    val snapshot = moveSources[mac.origin] ?: feedSources[mac.origin]
                    if (snapshot?.hasWorkspaceSnapshot == true) snapshot
                    else if (client != null && connectedCode == mac.code) NativeFeedSource(mac, workspaces = workspaces,
                        groups = groups, capabilities = hostCapabilities, availability = NativeFeedAvailability.CONNECTED,
                        hasWorkspaceSnapshot = true, macMutationTicket = client?.macMutationTicket())
                    else snapshot ?: NativeFeedSource(mac)
                }
            }
            val filterMachines = (workspaceSources.filter { it.workspaces.isNotEmpty() }.mapNotNull { source ->
                workspaceMacFilterId(source.mac.deviceId, source.mac.instanceTag)?.let {
                    NativeWorkspaceFilterMachine(it, appearances.name(source.mac), scopedPresence.buildLabel(source.mac))
                }
            } + visibleSshRows.map { NativeWorkspaceFilterMachine(workspaceSshFilterId(it.host.id), it.host.name) } +
                visibleCloudRows.map { NativeWorkspaceFilterMachine(CloudAddress(it.machine.id).identifier, it.machine.preferredName, "Cloud") }).distinctBy { it.id }
            val effectiveWorkspaceFilter = workspaceFilter.forMenu(filterMachines.map { it.id }.toSet(),
                selectedOrigin != null || selectedSshComputer != null || selectedCloudId != null)
            SideEffect { if (workspaceFilter != effectiveWorkspaceFilter) workspaceFilter = effectiveWorkspaceFilter }
            val allWorkspaceComputers = selectedOrigin == null && selectedSshComputer == null && selectedCloudId == null
            val sortComputers = pairedMacs.mapNotNull { mac -> workspaceMacFilterId(mac.deviceId, mac.instanceTag)?.let {
                NativeSortComputer(it, appearances.name(mac), connectedCode == mac.code && connectionReady, scopedPresence.buildLabel(mac))
            } } + sshTargets.map { NativeSortComputer(workspaceSshFilterId(it.host.id), it.name) } +
                cloudSnapshots.values.map { NativeSortComputer(CloudAddress(it.machine.id).identifier, it.machine.preferredName, buildLabel = "Cloud") }
            LaunchedEffect(browserLogin, connectedCode, connectionReady) {
                if (browserLogin != null && connectionReady) pairedMacs.singleOrNull { it.code == connectedCode }?.let {
                    workspaceMacFilterId(it.deviceId, it.instanceTag)?.let { id -> workspaceSortStore.recordOpened(id, System.currentTimeMillis()) }
                }
            }
            LaunchedEffect(browserLogin, selectedSshComputer?.host?.id, selectedSshComputer?.connection?.phase) {
                if (browserLogin != null && selectedSshComputer?.connection?.phase == SshConnectionPhase.CONNECTED)
                    workspaceSortStore.recordOpened(workspaceSshFilterId(selectedSshComputer.host.id), System.currentTimeMillis())
            }
            LaunchedEffect(workspaceRoute?.id) {
                workspaceRoute?.let { route -> pairedMacs.singleOrNull { it.ownsOrigin(route.origin) }?.let {
                    workspaceMacFilterId(it.deviceId, it.instanceTag)?.let { id -> workspaceSortStore.recordOpened(id, System.currentTimeMillis()) }
                } }
            }
            if (showComputerOrder && signedIn) key(browserLogin, teamState.scope) {
                NativeComputerOrderSheet(orderWorkspaceComputers(sortComputers, workspaceSort, searchLocale),
                    onDismiss = { showComputerOrder = false }) { ids ->
                    if (store.taskSession() == browserLogin && accountTeams.state.value.scope == teamState.scope)
                        workspaceSortStore.setPriority(ids.filter { id -> sortComputers.any { it.id == id } })
                }
            }
            val workspaceSearch = remember(workspaceSources, searchLocale, appearances) {
                NativeSearchIndex(workspaceSearchRows(workspaceSources, appearances::name), searchLocale)
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
            val scrollViewport = TerminalScrollViewport.at(scrollPosition, grid.historyLineCount, grid.activeScreen)
            val scrollOffset = scrollViewport.rowOffset
            val terminalMotion = rememberTerminalScrollMotion(draftTarget, client)
            fun stopTerminalScrolling() { terminalMotion.stop(); scrollInteractionEpoch++; cancelQueuedScroll?.invoke() }
            var textSnapshot by remember(draftTarget, client) { mutableStateOf<TerminalTextSource?>(null) }
            fun openTerminalText() {
                stopTerminalScrolling()
                val target = grid
                textSnapshot = terminalTextSource(target) { grid === target }
            }
            textSnapshot?.let { TerminalTextSheet(it) { textSnapshot = null } }
            val retainedPanel = rememberNativePanel(feedSession, browserLogin, teamState.scope, pairedMacs, code,
                selectedWorkspace, selectedSurface, showSettings || showTaskComposer || sshRoute != null || localBrowser != null || selectedChangesWorkspace != null)
            val filesMemory = rememberSaveable(saver = TerminalFilesMemory.saver) { TerminalFilesMemory() }
            val filesMac = pairedMacs.singleOrNull { it.code == code }
            val retainedFiles = feedSession.filesSheet
            val filesKey = draftTarget?.let { target -> filesMac?.let {
                workspaceTabKey(browserLogin, teamState.scope, it, target.workspace)
            } }
            val emptyFilesState = remember { TerminalFilesState() }
            val filesState = if (connectionReady && connectedCode == code && browserLogin != null && filesKey != null && draftTarget != null)
                filesMemory.bind(browserLogin, filesKey, draftTarget.surface,
                    retainedFiles?.takeIf { it.matches(browserLogin, filesKey, draftTarget.surface) }?.navigation) else emptyFilesState
            SideEffect {
                filesMemory.retainLogin(browserLogin)
                if (draftTarget == null && screenResume.pending == null && workspaceRoute?.resume == null) filesMemory.clear()
            }
            var showTerminalFiles by filesState::showing
            val terminalArtifactPath = filesState.path
            val artifactRpc = remember(client, hostCapabilities) { client?.let { ArtifactRpc(it, hostCapabilities) } }
            val artifactPreferences = remember(context) { context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE) }
            val displayState = rememberNativeDisplayPreferences(artifactPreferences)
            LaunchedEffect(displayState.feedReplacesNotifications, notificationTab, cloudTab) {
                if (displayState.feedReplacesNotifications && notificationTab && !cloudTab) {
                    // Preserve each destination's query when a now-hidden tab is replaced.
                    finishSearch(); notificationTab = false; agentFeedTab = true
                }
            }
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
            // The verified feed channel and its lease survive main-screen reconnection during recreation.
            // Observing feedSources also rechecks admission after a host/capability/workspace change.
            val filesFeed = filesMac?.let { feedSources[it.origin] }
            val retainedFilesAllowed = retainedFiles?.let {
                it.login == browserLogin && it.mac.code == code && it.current() &&
                    (!connectionReady || draftTarget != null && it.matches(browserLogin, filesKey, draftTarget.surface))
            } == true
            LaunchedEffect(showTerminalFiles, terminalArtifactPath, filesMac) {
                if ((showTerminalFiles || terminalArtifactPath != null) && filesMac != null && !retainedFilesAllowed) {
                    try { feedCoordinator.refreshWorkspaceLists(listOf(filesMac)) }
                    catch (failure: Exception) { currentCoroutineContext().ensureActive() }
                }
            }
            SideEffect {
                if (retainedFiles != null) {
                    if (!retainedFilesAllowed) feedSession.dismissFiles(retainedFiles)
                    else feedSession.recoverFiles(retainedFiles)
                }
                if (artifactsReady && (showTerminalFiles || terminalArtifactPath != null) && browserLogin != null &&
                    filesKey != null && filesMac != null && draftTarget != null && filesFeed != null) {
                    feedSession.openFiles(browserLogin, filesKey, filesMac,
                        ArtifactAuthorization.Terminal(draftTarget.workspace, draftTarget.surface), filesState)
                }
            }
            if (retainedFilesAllowed && retainedFiles != null) {
                val retainedPath = retainedFiles.navigation.path
                if (retainedPath != null) ArtifactPathSheet(retainedFiles.access.rpc, retainedFiles.terminal, retainedPath,
                    navigation = retainedFiles.navigation.direct, retainedPreview = retainedFiles.directPreview, retainedFolder = retainedFiles.directFolder,
                    connection = filesFeed?.availability ?: NativeFeedAvailability.OFFLINE) {
                    retainedFiles.closePath(); filesState.closePath()
                    if (!retainedFiles.navigation.showing) feedSession.dismissFiles(retainedFiles)
                }
                if (retainedFiles.navigation.showing) ArtifactFilesSheet(retainedFiles.access.rpc, retainedFiles.terminal, artifactRefresh,
                    navigation = retainedFiles.navigation.gallery, retained = retainedFiles,
                    connection = filesFeed?.availability ?: NativeFeedAvailability.OFFLINE) {
                    retainedFiles.closeGallery(); filesState.closeGallery()
                    if (retainedFiles.navigation.path == null) feedSession.dismissFiles(retainedFiles)
                }
            } else if (artifactsReady && (showTerminalFiles || terminalArtifactPath != null)) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { filesState.closeGallery(); filesState.closePath() },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            FilesHeader("Files", null, { filesState.closeGallery(); filesState.closePath() })
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            FilesMessage("Connecting to files…", filesFeed?.error)
                        }
                    }
                }
            }

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

            val composerFocus = remember(draftTarget) { ComposerKeyboardFocus() }
            var composerAttachmentError by remember(draftTarget) { mutableStateOf<String?>(null) }
            val dictationGeneration = drafts.generation
            val dictationTarget = draftTarget
            val dictationLogin = store.taskSession()
            val dictation = rememberComposerDictation(listOf(draftTarget, dictationGeneration, client, dictationLogin),
                enabled = signedIn && connectionReady && !directTyping && terminalAttached && client != null &&
                    draftTarget != null && selectedTerminal?.isReady == true && inputFailure == null &&
                    terminalDraft.operation == null && !preparingAttachments,
                readText = { drafts.state.value[dictationTarget]?.text.orEmpty() },
                writeText = { text ->
                    if (dictationTarget != null && store.taskSession() == dictationLogin && drafts.generation == dictationGeneration) {
                        drafts.edit(dictationTarget, text); true
                    } else false
                }, isCurrent = { drafts.generation == dictationGeneration && store.taskSession() == dictationLogin })
            val dictationState by dictation.state.collectAsState()
            fun leaveComposerInput() {
                dictation.cancel(); composerFocus.cancel(); rawKeyboardView?.finishComposition()
                stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide()
            }

            val attachmentFiles = remember(context) { AttachmentFiles(context.applicationContext) }
            fun pickedAttachments(uris: List<android.net.Uri>, photoLibrary: Boolean) {
                val target = pickerTarget
                val generation = pickerGeneration
                val pickedLogin = pickerLogin
                pickerTarget = null; pickerLogin = null
                if (target == null || uris.isEmpty()) return
                if (preparingAttachments) { composerAttachmentError = "Wait for the current attachments to finish preparing."; return }
                val remaining = 10 - drafts.state.value[target]?.attachments.orEmpty().size
                if (uris.size > remaining) { composerAttachmentError = "Each terminal can hold up to 10 attachments"; return }
                fun checkTarget() {
                    check(signedIn && store.taskSession() == pickedLogin && code == target.pairing &&
                        selectedWorkspace?.id == target.workspace && selectedTerminal?.id == target.surface && drafts.generation == generation &&
                        workspaces.any { it.id == target.workspace && it.terminals.any { terminal -> terminal.id == target.surface } }) {
                        "The attachment target changed. Choose the attachment again."
                    }
                }
                composerAttachmentError = null
                preparingAttachments = true
                scope.launch {
                    try {
                        for (uri in uris) {
                            val prepared = readComposerAttachment(::checkTarget, { composerAttachmentError = it }) {
                                val image = photoLibrary && attachmentFiles.isPhotoImage(uri)
                                require(image || ComposerAttachment.FILE_CAPABILITY in hostCapabilities) {
                                    "Update cmux on your Mac to attach videos and files."
                                }
                                attachmentFiles.prepare(uri, image)
                            } ?: continue
                            draftRepository.attach(target, prepared, generation)
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        composerAttachmentError = failure.message ?: "Could not open the attachment"
                    } finally { preparingAttachments = false }
                }
            }
            val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                pickedAttachments(it, photoLibrary = false)
            }
            val attachmentPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) {
                pickedAttachments(it, photoLibrary = true)
            }

            fun acceptTerminalPaste(content: TerminalPasteContent): Boolean {
                dictation.cancel()
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
                if (directTyping && items.any { it is TerminalPasteContent.Item.Attachment && !it.image } &&
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
                composerAttachmentError = null
                preparingAttachments = true
                scope.launch {
                    try {
                        for (item in items) {
                            checkTarget()
                            when (item) {
                                is TerminalPasteContent.Item.Text -> drafts.edit(target, (drafts.state.value[target]?.text ?: "") + item.value)
                                is TerminalPasteContent.Item.Attachment -> {
                                    val prepared = readComposerAttachment(::checkTarget, { composerAttachmentError = it }) {
                                        require(item.image || ComposerAttachment.FILE_CAPABILITY in hostCapabilities) {
                                            "Update cmux on your Mac to paste files"
                                        }
                                        attachmentFiles.prepare(item.uri, item.image)
                                    } ?: continue
                                    checkTarget()
                                    draftRepository.attach(target, prepared, generation)
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        composerAttachmentError = failure.message ?: "Could not open the pasted attachment"
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
                dictation.cancel()
                val send = drafts.begin(target) ?: return
                if (!directTyping) composerFocus.request()
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
                val mac = pairedMacs.singleOrNull { it.code == code } ?: return
                rawKeyboardView?.finishComposition(); directTyping = false
                stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide()
                beginNativeCreation(mac)
            }

            fun canCreateSsh(target: NativeSshCreateTarget, kind: SshWorkspaceKind): Boolean =
                signedIn && browserLogin != null && store.taskSession() == browserLogin && target.session === sshSession &&
                    target.session.isOpen && target.session.hosts.state.value.host(target.host.id)?.connectsLike(target.host) == true &&
                    sshTargets.singleOrNull { it.host.id == target.host.id && it.session === target.session }
                        ?.options?.singleOrNull { it.kind == kind }?.let { it.unavailableReason == null } == true
            fun createSshWorkspace(target: NativeSshCreateTarget, kind: SshWorkspaceKind) {
                if (creatingWorkspace || creatingTerminal || sshCreationBusy || !canCreateSsh(target, kind)) return
                rawKeyboardView?.finishComposition(); directTyping = false
                stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide(); workspaceTabs.cancel()
                sshNavigation.begin(checkNotNull(browserLogin), target.session.workspaceCreation, target.host, kind,
                    browserNavigationContext() + selectedComputerOrigin)
            }

            LaunchedEffect(sshRoute) {
                if (sshRoute != null) {
                    rawKeyboardView?.finishComposition(); directTyping = false
                    stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide()
                    workspaceTabs.cancel(); localBrowsers.leave(close = false)
                    selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null
                    selectedSurface = null; selectedChangesWorkspace = null
                }
            }

            fun createWorkspaceGroup(mac: NativeCredentialStore.PairedMac) {
                if (creatingGroup || creatingWorkspace || creatingTerminal || sshCreationBusy) return
                val login = browserLogin
                val team = teamState.scope
                val selection = selectedComputerOrigin
                fun current() = signedIn && store.taskSession() == login && accountTeams.state.value.scope == team &&
                    code == mac.code && connectedCode == mac.code && selectedComputerOrigin == selection &&
                    (selection.isBlank() || mac.ownsOrigin(selection)) && store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac)
                if (!current()) return
                creatingGroup = true
                scope.launch {
                    try {
                        feedCoordinator.createGroup(mac, ::current)
                        if (current()) { error = null; refreshFeed() }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        if (current()) recordWorkspaceActionFailure(failure)
                    } finally { creatingGroup = false }
                }
            }

            fun createWorkspaceOnMac(mac: NativeCredentialStore.PairedMac, groupId: String? = null) {
                beginNativeCreation(mac, groupId = groupId)
            }

            fun createTerminalInPane() {
                if (!canCreateInCurrentPane) return
                val workspace = selectedWorkspace ?: return
                val source = workspaceSourceForPane() ?: return
                rawKeyboardView?.finishComposition(); directTyping = false
                stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide()
                createTerminal(source, workspace)
            }

            fun proposePairingFrom(value: String, entry: NativePairingEntry) {
                pendingPairingCode = null
                ticketPairing.dismiss()
                PairingCodeParser.parse(value).fold(
                    onSuccess = { pairing ->
                        val compatibilityError = connection.pairingCompatibilityError(pairing)
                        if (compatibilityError != null) error = compatibilityError
                        else if (pairing is PairingCode.Tailscale) {
                            when {
                                entry != NativePairingEntry.IN_APP -> error = NativePairingEntryPolicy.ENTER_IN_APP
                                !NativePairingEntryPolicy.isExactTailscale(pairing) -> error = NativePairingEntryPolicy.NUMERIC_ADDRESS
                                else -> { pendingPairingCode = value.trim(); error = null }
                            }
                        } else if (pairing is PairingCode.Iroh) {
                            val action = incomingPairingAction(value.trim(), signedIn, false, teamState.scope, computerState)
                            if (action is NativePairingLinkAction.Select && connection.allowsSaved(PairingCodeParser.parse(action.code).getOrThrow())) {
                                selectPairingCode(action.code)
                            } else error = "This Mac is not available in your selected team. Check its Mobile settings and refresh Computers."
                        }
                    },
                    onFailure = {
                        try {
                            val owner = checkNotNull(teamState.scope) { "Refresh your account teams before pairing." }
                            check(signedIn && accountTeams.isCurrent(owner)) { "Sign in before pairing." }
                            ticketPairing.propose(MobileAttachTicketCodec.decodeLegacyUrl(value).getOrThrow(), owner, teamState.email, entry,
                                sharedConnections?.externalTicketRoutes(owner))
                            pendingPairingCode = null; error = null
                        } catch (failure: Exception) { error = failure.message }
                    }
                )
            }
            fun proposePairing(value: String) = proposePairingFrom(value, NativePairingEntry.IN_APP)

            val handlePairing by rememberUpdatedState(onPairingHandled)
            val pairingLookup = incomingCode?.takeIf { signedIn && PairingCodeParser.parse(it).getOrNull() is PairingCode.Iroh }
            // Effect keys and captured session identities stay together in a smaller
            // generated method; this group is invoked unconditionally while composed.
            val foregroundEffects: @Composable () -> Unit = {
                LaunchedEffect(incomingCode, signedIn, code, pairedMacs, teamState.scope, computerState) {
                    val incoming = incomingCode ?: return@LaunchedEffect
                    pendingPairingCode = null
                    ticketPairing.dismiss()
                    if (PairingCodeParser.parse(incoming).isFailure) {
                        if (!signedIn || teamState.scope == null) return@LaunchedEffect
                        proposePairingFrom(incoming, NativePairingEntry.EXTERNAL_LINK); handlePairing(incoming); return@LaunchedEffect
                    }
                    PairingCodeParser.parse(incoming).getOrNull()?.let(connection::pairingCompatibilityError)?.let {
                        error = it; handlePairing(incoming); return@LaunchedEffect
                    }
                    val action = incomingPairingAction(incoming, signedIn,
                        alreadySelected = incoming == code && pairedMacs.any { it.code == code }, teamState.scope, computerState)
                    when (action) {
                        NativePairingLinkAction.Wait -> return@LaunchedEffect
                        NativePairingLinkAction.Consumed -> Unit
                        NativePairingLinkAction.EnterInApp -> error = NativePairingEntryPolicy.ENTER_IN_APP
                        NativePairingLinkAction.Unavailable -> error = "This Mac is not available in your selected team. Check its Mobile settings and refresh Computers."
                        is NativePairingLinkAction.Select -> {
                            if (connection.allowsSaved(PairingCodeParser.parse(action.code).getOrThrow())) {
                                pendingPairingCode = null; selectPairingCode(action.code)
                            } else error = "This Mac is not available in your selected team."
                        }
                    }
                    handlePairing(incoming)
                }
                LaunchedEffect(pairingLookup, browserLogin) {
                    val incoming = pairingLookup ?: return@LaunchedEffect
                    delay(30_000)
                    error = "This Mac could not be found. Check its Mobile settings and try the pairing link again."
                    handlePairing(incoming)
                }
                LaunchedEffect(currentIncomingRoute, showSettings, showTaskComposer) {
                    if (currentIncomingRoute != null || showSettings || showTaskComposer) localBrowsers.leave(close = false)
                }
                LaunchedEffect(incomingNotificationRoute) { if (incomingNotificationRoute != null) { ticketPairing.cancel(); pendingPairingCode = null; inAppNotification = null; workspaceRoute = null } }
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
                    sshNavigation.leave()
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
                                showLicenses = false; selectedChangesWorkspace = null
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
                    sshNavigation.leave()
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
                        check(route.creation != null || route.changes || pane != null || workspaceTabs.pending.value != null ||
                            (route.createdWorkspace != null && !explicitPane)) { "This workspace pane is no longer available." }
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

                // A visible pairing confirmation owns its chosen route for this attempt.
                // Its own atomic saved-record upgrade must not cancel that live admission.
                val explicitPairingAttempt = remember(signedIn, code, retry) { pairingSelectionCode == code }
                LaunchedEffect(signedIn, code, retry, deferStartupForPairing,
                    if (explicitPairingAttempt) NativeForegroundReconnectKey(teamState.scope, null, null, null)
                    else nativeForegroundReconnectKey(computerState, pairedMacs, teamState.scope, code)) {
                    if (deferStartupForPairing) return@LaunchedEffect
                    if (pendingPickerCode != code) pendingPickerCode = null
                    if (pairingSelectionCode != code) pairingSelectionCode = null
                    val switchAttempt = macSwitchRecovery.entering(switchOwner(), code)
                    connectionReady = false
                    client?.close(); client = null; connectedCode = null
                    if (!signedIn || code.isBlank()) return@LaunchedEffect
                    startedForegroundConnection = true
                    val requestedCode = code
                    val ticketAttempt = ticketPairing.current(requestedCode, teamState.scope)
                    val expected = expectedReconnect?.takeIf { it.code == requestedCode }
                    val capturedReconnect = NativeDirectoryRouteUpgrade.refreshedSelection(expected,
                        store.visiblePairedMacs().singleOrNull { it.code == requestedCode }, teamState.scope,
                        TailscaleGrantStore(store::load, store::update))
                    if (capturedReconnect != expected) expectedReconnect = capturedReconnect
                    fun requireCurrentReconnect() {
                        check(store.pairedMacs().none { it.code == requestedCode &&
                            NativeComputerVisibility.isHidden(store.load(), it) }) { "This computer is hidden on this phone. Show it in Computers before connecting." }
                        check(capturedReconnect == null || (NativeComputerMenuPairing.isCurrent(capturedReconnect, store.visiblePairedMacs()) &&
                            connection.allowsSaved(capturedReconnect))) { "This saved computer changed. Choose it again from Computers." }
                    }
                    busy = true
                    try {
                        check(!ticketPairing.requiresTicket(requestedCode) || ticketAttempt != null) { "Pairing account changed. Scan or paste the ticket again." }
                        val pairing = PairingCodeParser.parse(requestedCode).getOrThrow()
                        val pairingOwner = if (sharedConnections != null || ticketAttempt != null) kotlinx.coroutines.withTimeout(30_000) {
                            accountTeams.state.first { it.scope != null || it.error != null }.scope
                                ?: error("Refresh your account teams before connecting.")
                        } else null
                        requireCurrentReconnect()
                        val saved = store.visiblePairedMacs().singleOrNull { it.code == requestedCode && connection.allowsSaved(it) }
                        if (pairingOwner != null) check(accountTeams.isCurrent(pairingOwner)) { "Account or team changed. Reconnect to the Mac." }
                        val active = if (ticketAttempt != null) {
                            check(ticketPairing.isCurrent(ticketAttempt) && ticketAttempt.owner == pairingOwner) { "Pairing or account changed" }
                            connection.connectTicket(pairing, ticketAttempt.ticket, account, ticketAttempt.admission)
                        } else if (saved != null && !explicitPairingAttempt) connection.connectSaved(saved, account) else connection.connectPairing(pairing, account)
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
                            val verified = NativeCredentialStore.PairedMac(active.authenticatedSavedRouteCode ?: requestedCode, status.optString("mac_device_id"), displayName,
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
                            val upgrade = active.confirmedTailscaleUpgrade
                            val remembered = if (upgrade != null && pairingOwner != null) {
                                check(upgrade.owner == pairingOwner && accountTeams.isCurrent(pairingOwner)) { "Account or team changed" }
                                if (ticketAttempt != null) check(ticketPairing.isCurrent(ticketAttempt)) { "Pairing changed" }
                                upgrade.commit(store::update, verified, ticketAttempt?.ticket, teamState.email)
                            } else if (ticketAttempt != null && pairingOwner != null) {
                                store.rememberAuthenticatedTicketMac(verified, pairingOwner, ticketAttempt.ticket, teamState.email,
                                    expected = saved ?: capturedReconnect) {
                                    accountTeams.isCurrent(pairingOwner) && signedIn && code == requestedCode && ticketPairing.isCurrent(ticketAttempt)
                                }
                            } else if (pairingOwner != null) store.rememberAuthenticatedMac(verified, pairingOwner, expected = capturedReconnect ?: saved) {
                                accountTeams.isCurrent(pairingOwner) && signedIn && code == requestedCode
                            } else {
                                store.rememberMac(verified.code, verified.deviceId, verified.name, verified.instanceTag, expected = capturedReconnect ?: saved)
                                verified
                            }
                            if (remembered.code != requestedCode || (upgrade == null && ticketAttempt == null && saved != null && saved.instanceTag != remembered.instanceTag)) {
                                active.close()
                                savedPairedMacs = store.pairedMacs()
                                if (expectedReconnect == capturedReconnect) expectedReconnect = null
                                macSwitchRecovery.retarget(switchAttempt, switchOwner(), remembered.code)
                                if (pendingPickerCode == requestedCode) pendingPickerCode = remembered.code
                                if (pairingSelectionCode == requestedCode) pairingSelectionCode = remembered.code
                                code = remembered.code
                                if (explicitPairingAttempt && remembered.code == requestedCode) retry++
                                connectionError = null; retryDelay = 2_000; busy = false
                                return@LaunchedEffect
                            }
                            feedSession.recordMacSeen(remembered)
                            hostName = displayName; hostCapabilities = capabilities
                            terminalTransport = TerminalTransport.resolve(capabilities, status.optString("terminal_fidelity"))
                            applyListing(listing); notifications = feed
                            ticketAttempt?.let { attempt ->
                                val target = attempt.ticket.workspaceId.trim().takeIf { it.isNotEmpty() }
                                if (target != null) {
                                    selectedWorkspace = workspaces.singleOrNull { it.id == target }
                                    val terminal = attempt.ticket.terminalId?.trim()?.takeIf { it.isNotEmpty() }
                                    val pane = selectedWorkspace?.let { if (terminal == null) it.defaultPane() else it.explicitPane(terminalId = terminal) }
                                    selectedTerminal = pane?.terminal; selectedBrowser = pane?.browser; selectedSurface = pane?.surface
                                    if (terminal != null && pane == null) selectedWorkspace = null
                                    if (selectedWorkspace == null || (terminal != null && pane == null)) error = "The ticket's workspace or terminal is no longer available."
                                }
                                ticketPairing.completed(attempt)
                            }
                            inputOwner(remembered, store.taskSession())?.let { owner ->
                                val sizingStream = terminalSizing.bind(owner, active)
                                if (TerminalSizingTraffic.CAPABILITY in capabilities) {
                                    // Upstream has no Android enum; use its truthful unknown kind and actual model name.
                                    active.terminalDeviceIdentity = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        TerminalDeviceIdentity(android.os.Build.MODEL, runCatching {
                                            TerminalDeviceIdentityStore(context.applicationContext.noBackupFilesDir).loadOrCreate()
                                        }.getOrNull())
                                    }
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
            }
            foregroundEffects()

            // Keep route rendering in its own composition group. Generating all route
            // callbacks in NativeScreen makes that one DEX method too large for ART.
            val screenContent: @Composable () -> Unit = {
                BackHandler(enabled = signedIn && code.isNotBlank() && searchState.active != null && selectedTerminal == null &&
                    selectedBrowser == null && selectedChangesWorkspace == null && !showSettings && !showTaskComposer) { finishSearch(cancel = true) }
                BackHandler(enabled = workspaceRoute != null && selectedTerminal == null && selectedBrowser == null && selectedSurface == null) { screenResume.cancel(); workspaceRoute = null }
                BackHandler(enabled = selectedTerminal != null && selectedSurface == null) { selectedTerminal = null; selectedWorkspace = null; selectedSurface = null }
                BackHandler(enabled = selectedBrowser != null) { selectedBrowser = null; selectedWorkspace = null; selectedSurface = null }
                BackHandler(enabled = showSettings && selectedTerminal == null) { showSettings = false }
                BackHandler(enabled = cloudTab && showSettings) { showSettings = false }

                if (showLicenses) OpenSourceLicensesDialog { showLicenses = false }

                if (pairingLookup != null) AlertDialog(onDismissRequest = { handlePairing(pairingLookup) },
                    title = { Text("Finding this Mac…") },
                    text = { Text("Checking your account and selected team's computers.") },
                    confirmButton = {}, dismissButton = { TextButton(onClick = { handlePairing(pairingLookup) }) { Text("Cancel") } })
                if (signedIn) ticketProposal?.let { proposal ->
                    NativeTicketPairingConfirmation(proposal, onDismiss = ticketPairing::dismiss) { choice ->
                        try {
                            val owner = checkNotNull(teamState.scope)
                            check(accountTeams.isCurrent(owner)) { "Account or team changed" }
                            val attempt = ticketPairing.select(proposal, choice, owner, computerState)
                            val pairing = PairingCodeParser.parse(attempt.code).getOrThrow()
                            connection.pairingCompatibilityError(pairing)?.let { error(it) }
                            if (pairing is PairingCode.Tailscale && attempt.freshTailscaleAuthorization) connection.authorizePairing(pairing)
                            selectPairingCode(attempt.code, usingTicket = true); retry++
                        } catch (failure: Exception) { error = failure.message; ticketPairing.clear() }
                    }
                }
                NativePairingConfirmation(if (signedIn) pendingPairingCode else null,
                    onDismiss = { pendingPairingCode = null },
                    onConnect = { proposed ->
                        try {
                            connection.authorizePairing(PairingCodeParser.parse(proposed).getOrThrow() as PairingCode.Tailscale)
                            selectPairingCode(proposed); pendingPairingCode = null; retry++
                        } catch (failure: Exception) { error = failure.message; pendingPairingCode = null }
                    })

                if (signedIn && sshSession != null) key(sshSession) { SshPromptHost(sshSession) }

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

                fun setCloudComputerVisibility(snapshot: CloudWorkspaceSnapshot, visible: Boolean) {
                    try {
                        val visibility = checkNotNull(cloudVisibility) { "Cloud computers are still loading" }
                        check(cloudModel?.setHidden(snapshot.machine.id, !visible, visibility) == true) { "Cloud account or computer changed" }
                        if (!visible && selectedCloudId == snapshot.machine.id) selectPickerComputer(null)
                    } catch (failure: Exception) { error = failure.message ?: "Could not change Cloud computer visibility" }
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
                val onboardingExplicitRoute = cloudRoute != null || cloudTab || sshRoute != null || incomingCode != null || currentIncomingRoute != null || workspaceRoute != null
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
                    pendingPairingCode, ticketProposal, busy, onboardingReady) {
                    val owner = onboardingOwner ?: return@LaunchedEffect
                    if (!nativeOnboardingMayChoose(showOnboarding, feedForeground, deferStartupForPairing || (pendingPairingCode != null || ticketProposal != null),
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
                                NativeOnboardingMacPowerSettings(sharedConnections?.native, onboardingOwner, pairedMacs, connectedCode, feedCoordinator)
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
                            !creatingGroup && !confirmReadAll && computerDetails == null && deletionReceipt == null &&
                            (pendingPairingCode == null && ticketProposal == null) && pairingLookup == null && !onboardingPermissionBusy && !whatsNewPromptPending &&
                            selectedTerminal == null && selectedBrowser == null && selectedSurface == null && selectedChangesWorkspace == null &&
                            screenResume.pending == null && textSnapshot == null && !showTerminalFiles && terminalArtifactPath == null &&
                            !createMenuOpen && !computerMenuOpen && !workspaceFilterMenuOpen && !notificationFilterMenu,
                        archive = showWhatsNew && showSettings && signedIn && !showOnboarding && !onboardingExplicitRoute,
                        onCloseArchive = { showWhatsNew = false }, policy = displayPolicy,
                        webArchive = whatsNewModel?.webArchive, replay = whatsNewModel?.replay,
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
                            computersOwner = null; showSettings = false; showReconnectList = true
                            sshNavigation.leave(); screenResume.cancel(); workspaceRoute = null
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
                            NativeCloudComputerRows(allCloudSnapshots.values.toList(), hiddenCloudIds,
                                enabled = admitted() && cloudVisibility != null, onVisibility = ::setCloudComputerVisibility)
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
                    if (connector == null) NativeAccountPlanSettings(account, accountTeams, teamState.scope)
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
                    PhonePushSettings(teamState.scope, accountTeams::isCurrent,
                        onEnableBackground = { enableNotifications(false) }, onConnect = ::presentComputers)
                    PhoneMacPushSettings(client.takeIf { connectionReady && connectedCode == code },
                        notificationSyncMac, teamState.scope,
                        isCurrent = { active, mac, owner ->
                            signedIn && accountTeams.isCurrent(owner) && client === active && connectionReady &&
                                code == mac.code && connectedCode == mac.code && store.taskSession() == owner.login &&
                                store.visiblePairedMacs().contains(mac) && connection.allowsSaved(mac)
                        }, onConnect = ::presentComputers)
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
                        supportsGroups = if (taskConnected) currentMacMutationAllowed && "workspace.create_in_group.v1" in hostCapabilities else null,
                        groupsLoaded = taskConnected && taskGroupsLoaded,
                        groupIsCurrent = { group -> group == null || (currentMacMutationAllowed && connectionReady && connectedCode == taskCode && code == taskCode && "workspace.create_in_group.v1" in hostCapabilities &&
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
                        supportsTaskCreation = if (taskConnected) currentMacMutationAllowed && "workspace.task_create.v1" in hostCapabilities else null,
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
                        onNewBrowser = { createFromBrowser(NativeWorkspaceCreation.BROWSER) }, menuSource = ::browserMenu, sidebarSelection = NativeSidebarSelection.Mac(browserMac, localBrowser.key.workspaceId),
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
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        NativeComputerPicker(teamState, computerState.takeIf { it.account == teamState.scope }
                            ?: NativeComputersState(account = teamState.scope, loading = true), runtime = sharedConnections?.native,
                            onSsh = if (sharedConnections != null) ({ showSshComputers = true }) else null,
                            colorIndices = machineColorIndices, connections = computerConnections, presence = scopedPresence, saved = displayedVisible, hidden = displayedHidden, onVisibility = ::setComputerVisibility,
                            cachedDisplay = cachedComputers != null, displayAppearances = appearances, macPolicy = displayPolicy,
                            lastSeenHistory = lastSeenHistory, preferences = computerPreferences, tailscaleRoutes = tailscaleRouteLabels,
                            forgetCallbacks = forgetCallbacks, presentDetails = { computerDetails = it },
                            connectingCode = code.takeIf { busy && cachedComputers == null }, connectionFailure = error ?: connectionError,
                            onCancelConnect = { ticketPairing.clear(); macSwitchRecovery.cancel(); pendingPickerCode = null; pairingSelectionCode = null; expectedReconnect = null
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
                    if (noKnownComputers && cloudModel != null) NativePrimaryNavigation(false, 0, searchState,
                        onTab = {}, onBeginSearch = {}, onEdit = { _, _ -> }, onSubmit = {}, onCancel = {},
                        emptyComputers = true, onCloud = { finishSearch(); cloudTab = true; cloudModel.activate() })
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
                            ComposerKeyboardFocusEffect(composerFocus, dictationState.locksField)
                            draftTarget?.let { target -> NativeTerminalAttachmentStrip(draftRepository, target,
                                    terminalDraft.attachments, canRemove = true,
                                    preparing = preparingAttachments, modifier = Modifier.background(nativePanel),
                                    beforePreview = ::leaveComposerInput) }
                            key(draftTarget) {
                                Row(Modifier.fillMaxWidth().background(nativePanel).padding(8.dp), verticalAlignment = Alignment.Bottom) {
                                    fun pickAttachment(images: Boolean) {
                                        leaveComposerInput()
                                        pickerTarget = draftTarget; pickerGeneration = drafts.generation; pickerLogin = store.taskSession()
                                        if (images) attachmentPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                        else attachmentPicker.launch(arrayOf("*/*"))
                                    }
                                    ComposerAttachmentMenu(owner = draftTarget to client, enabled = !preparingAttachments && terminalDraft.operation == null,
                                        onPhotos = { pickAttachment(true) },
                                        onFiles = if (ComposerAttachment.FILE_CAPABILITY in hostCapabilities) ({ pickAttachment(false) }) else null,
                                        onPaste = {
                                            composerAttachmentError = null
                                            val paste = ComposerClipboardPaste(context,
                                                current = { signedIn && !directTyping && draftTarget != null },
                                                enabled = { terminalAttached && client != null && terminalDraft.operation == null && !preparingAttachments },
                                                receive = ::acceptTerminalPaste, report = { composerAttachmentError = it })
                                            if (!paste.paste()) composerAttachmentError = "No copied photos or files. Paste text into the composer."
                                        })
                                    ComposerDictationButton(dictation,
                                        enabled = terminalAttached && connectionReady && terminalDraft.operation == null && !preparingAttachments,
                                        beforeStart = { composerFocus.cancel(); stopTerminalScrolling(); focusManager.clearFocus(); softwareKeyboard?.hide() })
                                    val canSend = terminalAttached && client != null && (terminalDraft.text.isNotEmpty() || terminalDraft.attachments.isNotEmpty()) &&
                                        terminalDraft.operation == null && !preparingAttachments
                                    RichContentEditor(owner = draftTarget to client,
                                        enabled = !dictationState.locksField && terminalAttached && client != null && terminalDraft.operation == null && !preparingAttachments,
                                        onContent = ::acceptTerminalPaste, onError = { composerAttachmentError = it }) { pasteModifier ->
                                        TerminalComposerField(terminalDraft.text,
                                            { text -> if (!dictationState.locksField) draftTarget?.let { drafts.edit(it, text) } },
                                            onSend = { sendComposer(submit = true) }, canSend = canSend,
                                            sending = terminalDraft.operation != null, failed = terminalDraft.error != null,
                                            modifier = Modifier.weight(1f),
                                            editorModifier = Modifier.focusRequester(composerFocus.requester).testTag("native.composer").then(pasteModifier).onPreviewKeyEvent { event ->
                                                val key = event.nativeKeyEvent
                                                if (key.action == AndroidKeyEvent.ACTION_DOWN &&
                                                    key.keyCode == AndroidKeyEvent.KEYCODE_ENTER && (key.isCtrlPressed || key.isMetaPressed)) {
                                                    sendComposer(submit = true); true
                                                } else false
                                            }, readOnly = dictationState.locksField,
                                            sendModifier = Modifier.testTag("native.composer.send"))
                                    }
                                }
                            }
                        }
                        (dictationState.error ?: composerAttachmentError ?: terminalDraft.error ?: draftSaveError)?.let { message ->
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
                val sidebarChanges = feedSession.changesSheet
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
                            NativeWorkspaceBackControl { TextButton(onClick = { selectedChangesWorkspace = null; selectedWorkspace = null }) { Text("‹  Workspaces") } }
                            Text("Changes in ${workspace.title}", style = MaterialTheme.typography.titleMedium)
                            Text(if (busy) "Reconnecting to your Mac…" else "Changes disconnected", color = nativeMuted)
                            connectionError?.let { Text(it, color = Color(0xFFFF9999)) }
                            TextButton(onClick = { retry++ }, enabled = !busy) { Text("Reconnect") }
                        }
                    }
                }
                val sidebarSelection: NativeSidebarSelection? = when {
                    sshRoute != null -> displayedSshTarget?.let { NativeSidebarSelection.Ssh(sshRoute.host, it) }
                    showSettings || showTaskComposer || workspaceRoute != null || currentIncomingRoute != null -> null
                    localBrowser != null -> pairedMacs.singleOrNull {
                        localBrowserKey(browserLogin, teamState.scope, it, localBrowser.key.workspaceId) == localBrowser.key
                    }?.let { NativeSidebarSelection.Mac(it, localBrowser.key.workspaceId) }
                    else -> (selectedChangesWorkspace ?: selectedWorkspace)?.let { workspace ->
                        pairedMacs.singleOrNull { it.code == code }?.let { NativeSidebarSelection.Mac(it, workspace.id) }
                    }
                }
                val workspaceListContent: @Composable ColumnScope.() -> Unit = {
                    LaunchedEffect(sshSession, feedForeground) { if (feedForeground) sshSession?.workspaceFeed?.refreshConnected() }
                    LaunchedEffect(sshSession, feedForeground, selectedSshComputer?.host, selectedSshComputer?.connection?.phase) {
                        if (feedForeground) selectedSshComputer?.let { it.session.workspaceFeed.open(it.host, explicit = false, refresh = false) }
                    }
                    val sidebarChrome = LocalWorkspaceShellChrome.current
                    val isSidebar = sidebarChrome.split
                    @Composable fun ListTitle(modifier: Modifier) {
                        Column(modifier) {
                            Text(if (agentFeedTab) "Feed" else if (notificationTab) "Notifications" else "Workspaces", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(selectedCloudId?.let { selectedCloudComputer?.machine?.preferredName ?: "Cloud computer unavailable" }
                                ?: selectedSshComputer?.name ?: selectedComputer?.let(appearances::name) ?: "All Computers", color = nativeMuted,
                                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (isSidebar) Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ListTitle(Modifier.weight(1f))
                        NativeWorkspaceSidebarToggle()
                    }
                    Row(Modifier.fillMaxWidth().height(if (isSidebar) 48.dp else 62.dp).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { workspaceRoute = null; finishSearch(); showSettings = true }) {
                            Image(painterResource(R.drawable.cmux_logo), "cmux settings", Modifier.size(24.dp))
                        }
                        IconButton(onClick = ::presentComputers) {
                            Icon(painterResource(R.drawable.ic_computer_desktop), "Manage computers", tint = nativeMuted,
                                modifier = Modifier.size(22.dp))
                        }
                        NativeComputerSelector(pairedMacs, selectedComputer, appearances, machineColorIndices, computerConnections,
                            computerMenuOpen, { computerMenuOpen = it }, ::selectPickerComputer, pendingPickerComputer,
                            onPair = { computerMenuOpen = false; showReconnectList = true; code = "" },
                            owner = NativeComputerMenuOwner(store.taskSession(), teamState.scope),
                            isOwnerCurrent = { owner -> account.isSignedIn() && store.taskSession() == owner.login &&
                                accountTeams.state.value.scope == owner.team },
                            canSelect = { mac -> NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) && connection.allowsSaved(mac) },
                            presence = scopedPresence, sshTargets = sshTargets, selectedSsh = selectedSshComputer,
                            canSelectSsh = ::canSelectSsh, onSelectSsh = ::selectSshComputer,
                            cloud = cloudSnapshots.values.toList(), selectedCloud = selectedCloudId,
                            canSelectCloud = ::canSelectCloud, onSelectCloud = ::selectCloudComputer)
                        if (isSidebar) Spacer(Modifier.weight(1f)) else ListTitle(Modifier.weight(1f))
                        if (agentFeedTab) {
                            Box {
                                IconButton(onClick = { agentFilterMenu = true }) {
                                    Icon(painterResource(if (agentNeedsInputOnly) R.drawable.ic_feed_filter_active else R.drawable.ic_feed_filter),
                                        "Feed filter", tint = if (agentNeedsInputOnly) nativeAccent else nativeMuted, modifier = Modifier.size(23.dp))
                                }
                                DropdownMenu(agentFilterMenu, { agentFilterMenu = false }) {
                                    DropdownMenuItem(text = { Text("All Activity") }, onClick = { agentNeedsInputOnly = false; agentFilterMenu = false })
                                    DropdownMenuItem(text = { Text("Needs Input ($agentNeedsInputCount)") }, onClick = { agentNeedsInputOnly = true; agentFilterMenu = false })
                                }
                            }
                        } else if (notificationTab) {
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
                            NativeWorkspaceFilterMenu(effectiveWorkspaceFilter,
                                if (allWorkspaceComputers) filterMachines else emptyList(),
                                workspaceFilterMenuOpen, { workspaceFilterMenuOpen = it }, onChange = { next ->
                                    if (store.taskSession() == browserLogin && accountTeams.state.value.scope == teamState.scope)
                                        workspaceFilter = next.forMenu(filterMachines.map { it.id }.toSet(), !allWorkspaceComputers)
                                }, sortMode = workspaceSort.mode.takeIf { allWorkspaceComputers }, onSort = { mode ->
                                    workspaceSortStore.setMode(mode)
                                }, onOrder = { showComputerOrder = true })
                            NativeWorkspaceCreateMenu(
                                macs = pairedMacs.filter { selectedCloudId == null && selectedSshComputer == null && (selectedOrigin == null || it.origin == selectedOrigin) },
                                appearances = appearances, presence = scopedPresence,
                                connections = computerConnections, open = createMenuOpen, onOpen = { createMenuOpen = it },
                                owner = NativeComputerMenuOwner(browserLogin, teamState.scope), selection = selectedComputerOrigin,
                                busy = creatingWorkspace || creatingTerminal || sshCreationBusy || creatingGroup || cloudCreationBusy,
                                isOwnerCurrent = { owner -> signedIn && store.taskSession() == owner.login &&
                                    accountTeams.state.value.scope == owner.team },
                                canCreate = { mac -> NativeComputerMenuPairing.isCurrent(mac, store.visiblePairedMacs()) &&
                                    connection.allowsSaved(mac) && feedSources[mac.origin]?.let {
                                        it.mac == mac && it.availability == NativeFeedAvailability.CONNECTED && it.canMutateMacWorkspaces()
                                    } == true },
                                onCreate = { createWorkspaceOnMac(it) },
                                onGroup = if (canCreateOnCurrentMac && currentMacMutationAllowed &&
                                    "workspace.group_create.v1" in hostCapabilities)
                                    pairedMacs.singleOrNull { it.code == connectedCode }?.let { mac -> { createWorkspaceGroup(mac) } } else null,
                                sshTargets = if (selectedCloudId == null && selectedOrigin == null) sshTargets.filter { selectedSshComputer == null || it.host.id == selectedSshComputer.host.id } else emptyList(),
                                canCreateSsh = ::canCreateSsh, onCreateSsh = ::createSshWorkspace,
                                groupMac = pairedMacs.singleOrNull { it.code == connectedCode },
                                cloud = if (selectedOrigin != null || selectedSshComputer != null) emptyList() else
                                    cloudSnapshots.values.filter { selectedCloudId == null || it.machine.id == selectedCloudId },
                                canCreateCloud = ::canCreateCloud, onCreateCloud = ::createCloudWorkspace)

                        }
                    }
                    if ((busy || sshCreationBusy || creatingGroup || cloudCreationBusy) && !notificationTab && !agentFeedTab) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (selectedCloudId == null && allCloudSnapshots.isEmpty() && client == null && !busy && !notificationTab && !agentFeedTab && workspaceSources.none { it.hasWorkspaceSnapshot } && sshTargets.isEmpty()) {
                        Column(Modifier.padding(horizontal = 18.dp)) {
                            Button(onClick = { retryDelay = 2_000; retry++ }) { Text("Retry connection") }
                            TextButton(onClick = { store.update { it.put("pairing_code", "") }; code = "" }) { Text("Pair a different Mac") }
                        }
                    }
                    if (agentFeedTab) {
                        agentFeedState.SaveableStateProvider("feed") {
                        key(agentReadOwner, selectedComputerOrigin) {
                            NativeAgentFeedView(scopedFeedSources, search, agentNeedsInputOnly, agentReadState,
                                onReadState = { agentReadState = it }, session = feedCoordinator::agentFeedSession,
                                computerName = appearances::name, onRefresh = ::refreshFeed, modifier = Modifier.weight(1f), locale = searchLocale, display = displayState,
                                scopeKey = agentReadOwner, allowedMacs = pairedMacs.filter { connection.allowsSaved(it) },
                                onOpen = { entry, openTab ->
                                    val owner = store.visiblePairedMacs().singleOrNull { it == entry.source.mac && connection.allowsSaved(it) }
                                    val workspace = feedCoordinator.sources.value[owner?.origin]?.workspaces?.singleOrNull { it.id == entry.item.workspaceId }
                                    val terminal = workspace?.terminals?.singleOrNull { it.id == entry.item.surfaceId }
                                    if (owner == null || workspace == null || openTab && terminal == null) error = "This Feed destination is no longer available."
                                    else {
                                        finishSearch(); inAppNotification = null
                                        workspaceRoute = NativeWorkspaceRoute(owner.origin, workspace.id, terminalId = terminal?.id.takeIf { openTab })
                                    }
                                })
                        }
                        }
                    } else if (notificationTab) {
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
                        val filteredSources = workspaceSources.filter { source ->
                            effectiveWorkspaceFilter.matches(workspaceMacFilterId(source.mac.deviceId, source.mac.instanceTag), true)
                        }
                        val sshSearch = remember(visibleSshRows, searchLocale) { NativeSearchIndex(visibleSshRows.map { row ->
                            row.key to row.searchFields()
                        }, searchLocale) }
                        val sshMatches = remember(sshSearch, search) { sshSearch.matches(search) }
                        val sshEntries = visibleSshRows.filter {
                            effectiveWorkspaceFilter.matches(workspaceSshFilterId(it.host.id), it.workspace.hasUnread) &&
                                (search.isBlank() || it.key in sshMatches)
                        }
                        val cloudSearch = remember(visibleCloudRows, searchLocale) {
                            NativeSearchIndex(visibleCloudRows.map { it.key to it.searchFields() }, searchLocale)
                        }
                        val cloudMatches = remember(cloudSearch, search) { cloudSearch.matches(search) }
                        val cloudEntries = visibleCloudRows.filter { effectiveWorkspaceFilter.matches(CloudAddress(it.machine.id).identifier, false) &&
                            (search.isBlank() || it.key in cloudMatches) }
                        val displayRows = sortedWorkspaceRows(filteredSources, sshEntries, sortComputers, workspaceSort, allWorkspaceComputers,
                            matches, search.isNotEmpty() || effectiveWorkspaceFilter.active, unreadWorkspacesOnly, collapsedGroups, searchLocale, cloudEntries)
                        val entries = displayRows.filterIsInstance<NativeWorkspaceDisplayRow.Mac>().map { it.entry }
                        PullToRefreshBox(isRefreshing = feedRefreshing || sshFeed.values.any { it.loading }, onRefresh = {
                            if (selectedCloudId != null) cloudWorkspaces?.refresh(selectedCloudId)
                            else if (selectedOrigin == null && selectedSshComputer == null) cloudWorkspaces?.refreshAll()
                            selectedSshComputer?.let { it.session.workspaceFeed.open(it.host, explicit = true) }
                                ?: if (selectedCloudId == null) sshSession?.workspaceFeed?.refreshConnected() else Unit
                            if (selectedCloudId == null && selectedSshComputer == null) refreshFeed()
                        }, modifier = Modifier.weight(1f)) {
                        val reorderSource = workspaceSources.singleOrNull()
                        val canReorder = !(allWorkspaceComputers && workspaceSort.mode == NativeWorkspaceSortMode.ACTIVITY) &&
                            sshEntries.isEmpty() && cloudEntries.isEmpty() && reorderSource != null && reorderSource.canReorderWorkspaces() &&
                            (reorderSource.groups.isNotEmpty() || reorderSource.workspaces.none { it.isPinned }) &&
                            reorderSource.mac.code == connectedCode && search.isBlank() && !effectiveWorkspaceFilter.active &&
                            (moveStatus[reorderSource.mac.origin]?.pending ?: 0) < 3
                        fun move(source: NativeFeedSource, id: String, intent: NativeWorkspaceMove): Boolean {
                            val accepted = workspaceMoves.enqueue(source, id, intent)
                            return accepted
                        }
                        val sshStatusRows = sshTargets.filter { selectedCloudId == null && selectedOrigin == null &&
                            (selectedSshComputer == null || it.host.id == selectedSshComputer.host.id) }
                            .filter { effectiveWorkspaceFilter.matches(workspaceSshFilterId(it.host.id), false) }
                            .filter { it.connection?.phase != SshConnectionPhase.CONNECTED || sshFeed[it.host.id]?.error != null }
                        val macStatusRows = filteredSources.filter { it.availability != NativeFeedAvailability.CONNECTED }
                        val cloudStatusRows = if (selectedOrigin != null || selectedSshComputer != null) emptyList() else cloudSnapshots.values.filter {
                            (selectedCloudId == null || it.machine.id == selectedCloudId) && it.availability != NativeFeedAvailability.CONNECTED && effectiveWorkspaceFilter.matches(CloudAddress(it.machine.id).identifier, false)
                        }
                        NativeWorkspaceDragList(entries, canReorder, Modifier.fillMaxSize(), onMove = ::move,
                            leading = sshStatusRows.map { target -> WorkspaceListChrome("ssh-status:${target.host.id}",
                                "${target.name} · ${sshFeed[target.host.id]?.error ?: target.connection?.error ?: target.status}", "Retry",
                                enabled = target.connection?.phase != SshConnectionPhase.CONNECTING,
                                actionTag = "ssh.feed.retry:${target.host.id}") } + macStatusRows.map { source ->
                                WorkspaceListChrome("status:${source.mac.origin}",
                                    "${appearances.name(source.mac)} · ${if (source.availability == NativeFeedAvailability.CONNECTING) "Connecting…" else "Unavailable"}", "Retry")
                            } + cloudStatusRows.map { snapshot -> WorkspaceListChrome("cloud-status:${snapshot.machine.id}",
                                "${snapshot.machine.preferredName} · Cloud · ${snapshot.failure?.detail ?: if (snapshot.availability == NativeFeedAvailability.CONNECTING) "Connecting…" else snapshot.machine.status}",
                                "Retry") }, onChromeAction = { id ->
                                cloudStatusRows.singleOrNull { "cloud-status:${it.machine.id}" == id }?.let { cloudWorkspaces?.let { owner -> cloudModel?.retryConnection(it.machine.id, owner) } }
                                sshStatusRows.singleOrNull { "ssh-status:${it.host.id}" == id }?.let { target ->
                                    if (canSelectSsh(target)) target.session.workspaceFeed.open(target.host, explicit = true)
                                }
                                if (macStatusRows.any { "status:${it.mac.origin}" == id }) refreshFeed()
                        }, displayRows = displayRows, sshRow = { row ->
                                val status = sshTargets.singleOrNull { it.host.id == row.host.id }?.connection?.phase
                                val availability = when (status) {
                                    SshConnectionPhase.CONNECTED -> NativeFeedAvailability.CONNECTED
                                    SshConnectionPhase.CONNECTING -> NativeFeedAvailability.CONNECTING
                                    else -> NativeFeedAvailability.OFFLINE
                                }
                                key(browserLogin, row.host.id, row.generation, row.registry) {
                                    NativeWorkspaceRow(row.workspace, isSelected = sidebarSelection.matches(row), displayPreferences = displayState, availability = availability,
                                        canClose = sshSession?.workspaceFeed?.canClose(row) == true, handlesHold = true,
                                        closeConfirmation = row.confirmation, onOpen = {
                                            if (store.taskSession() == browserLogin && browserLogin != null && sshSession?.workspaceFeed?.isCurrent(row) == true) {
                                                cloudModel?.leaveWorkspace()
                                                val first = row.openTarget()
                                                val remembered = first?.let { store.lastWorkspaceTab(browserLogin, sshWorkspaceTabKey(browserLogin, row.host, it)) }
                                                val target = first
                                                if (target != null) {
                                                    workspaceSortStore.recordOpened(workspaceSshFilterId(row.host.id), System.currentTimeMillis())
                                                    screenResume.cancel(); workspaceRoute = null; sshNavigation.open(browserLogin, row.host, target, remembered)
                                                }
                                                else error = "This workspace has no live terminal or browser. Open Computers to manage it."
                                            }
                                        }, onAction = { action, _ -> if (action == "close" && store.taskSession() == browserLogin)
                                            sshSession?.workspaceFeed?.closeWorkspace(row) })
                                }
                        }, cloudRow = { row ->
                            NativeWorkspaceRow(row.workspace, displayPreferences = displayState,
                                availability = cloudSnapshots[row.machine.id]?.availability ?: NativeFeedAvailability.OFFLINE,
                                isSelected = cloudRoute?.workspaceId == row.key, onOpen = {
                                    runCatching {
                                        cloudModel?.openWorkspace(row, expected = checkNotNull(cloudWorkspaces))
                                        workspaceSortStore.recordOpened(CloudAddress(row.machine.id).identifier, System.currentTimeMillis())
                                        sshNavigation.leave(); screenResume.cancel(); workspaceRoute = null
                                        selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null; selectedChangesWorkspace = null
                                    }.onFailure { error = it.message ?: "Could not open Cloud terminal" }
                                }, onAction = { _, _ -> })
                        }, empty = {
                            NativeWorkspaceEmptyRow(when {
                                search.isNotBlank() -> NativeWorkspaceEmptyGuidance.SEARCH
                                effectiveWorkspaceFilter.machines.isNotEmpty() && unreadWorkspacesOnly -> NativeWorkspaceEmptyGuidance.UNREAD_MACHINES
                                effectiveWorkspaceFilter.machines.isNotEmpty() -> NativeWorkspaceEmptyGuidance.MACHINES
                                unreadWorkspacesOnly -> NativeWorkspaceEmptyGuidance.UNREAD
                                hiddenCloudIds.isNotEmpty() && (selectedCloudId in hiddenCloudIds ||
                                    (allWorkspaceComputers && cloudSnapshots.isEmpty() && workspaceSources.isEmpty() && visibleSshRows.isEmpty())) -> NativeWorkspaceEmptyGuidance.HIDDEN_CLOUD
                                selectedCloudId != null -> NativeWorkspaceEmptyGuidance.CLOUD_HOST
                                selectedOrigin == null && (sshTargets.isNotEmpty() || cloudSnapshots.isNotEmpty()) -> NativeWorkspaceEmptyGuidance.ALL_COMPUTERS
                                else -> NativeWorkspaceEmptyGuidance.MAC
                            }, emptyWorkspaceRecoveryState, onClearFilter = if (search.isBlank() && effectiveWorkspaceFilter.active &&
                                (workspaceSources.any { it.workspaces.isNotEmpty() } || visibleSshRows.isNotEmpty() || visibleCloudRows.isNotEmpty())) ({
                                    if (store.taskSession() == browserLogin && accountTeams.state.value.scope == teamState.scope) {
                                        workspaceFilter = NativeWorkspaceFilter(); selectPickerComputer(null)
                                    }
                                }) else null, onRetry = if (workspaceSources.isEmpty()) null else ({
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
                                    NativeGroupHeaderRow(group, isSelected = sidebarSelection.matches(owner.mac, group.liveAnchorWorkspaceId) &&
                                        owner.workspaces.any { it.id == group.liveAnchorWorkspaceId },
                                        expanded = !group.isCollapsed,
                                        unread = entry.unread,
                                        onOpen = group.liveAnchorWorkspaceId?.takeIf { id -> owner.workspaces.any { it.id == id } }?.let { anchor ->
                                            { inAppNotification = null; workspaceRoute = NativeWorkspaceRoute(owner.mac.origin, anchor) }
                                        },
                                        canEdit = owner.canEditGroups(),
                                        canCreate = owner.canCreateInGroup(),
                                        creationEnabled = !creatingWorkspace && !creatingTerminal,
                                        onCreate = { createWorkspaceOnMac(owner.mac, group.id) },
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
                                    cloudModel?.leaveWorkspace()
                                    inAppNotification = null
                                    workspaceRoute = NativeWorkspaceRoute(owner.mac.origin, workspace.id, terminalId, browserId, changes, surfaceId = surfaceId)
                                }
                                Column(Modifier.semantics { contentDescription = "${workspace.title} on ${appearances.name(owner.mac)}" }) {
                                NativeWorkspaceRow(
                                    workspace = workspace, leadingIndent = if (entry.indented) 18 else 0, isSelected = sidebarSelection.matches(owner.mac, workspace.id), canCustomize = owner.canCustomizeWorkspace(),
                                    displayPreferences = displayState,
                                    availability = owner.availability, changesChip = owner.changes[workspace.id],
                                    canReadState = "workspace.read_state.v1" in owner.capabilities,
                                    canClose = "workspace.close.v1" in owner.capabilities,
                                    canWorkspaceActions = "workspace.actions.v1" in owner.capabilities,
                                    groupMoveMenu = NativeWorkspaceGroupMoveMenu.forWorkspace(owner, workspace.id,
                                        moveStatus[owner.mac.origin]?.pending ?: 0),
                                    onOpen = { open() },
                                    onAction = { action, title ->
                                        if (action == "customize") customizationTarget = WorkspaceCustomizationTarget.capture(browserLogin, teamState.scope, owner.mac, workspace.id)
                                        else if (action == "changes") {
                                            try { feedSession.openChanges(owner.mac, workspace) }
                                            catch (failure: Exception) {
                                                recordWorkspaceActionFailure(failure)
                                                error = failure.message ?: "Could not open workspace changes"
                                            }
                                        }
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
                        if (searchState.active == null && !isSidebar) NativeTaskComposerButton(
                            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 2.dp), enabled = true) {
                            finishSearch(); newTaskDraft()
                        }
                        }
                    }
                    NativePrimaryNavigation(notificationTab, feedEntries.count { !it.notification.isRead }, searchState,
                        onTab = { if (!isSidebar) workspaceRoute = null; finishSearch(); agentFeedTab = false; notificationTab = it },
                        onBeginSearch = { searchState = searchState.begin(searchScope) },
                        onEdit = { value, generation -> searchState = searchState.edit(value, searchScope, generation) },
                        onSubmit = { finishSearch() }, onCancel = { finishSearch(cancel = true) },
                        sidebar = isSidebar, onNewTask = { finishSearch(); newTaskDraft() },
                        agentFeedTab = agentFeedTab, agentFeedCount = agentNeedsInputCount,
                        showsNotifications = !displayState.feedReplacesNotifications,
                        onAgentFeed = { if (!isSidebar) workspaceRoute = null; finishSearch(); notificationTab = false; agentFeedTab = true },
                        onCloud = cloudModel?.let { model -> { finishSearch(); cloudTab = true; model.activate() } })
                }
                val showWorkspaceReconnect = !agentFeedTab && selectedCloudId == null && allCloudSnapshots.isEmpty() && ((cachedComputers != null && sshTargets.isEmpty()) || (code.isBlank() && sshTargets.isEmpty()) || (selectedWorkspace == null && workspaceRoute == null && !notificationTab &&
                            (showReconnectList || (client == null && connectionError != null && workspaceSources.none { it.hasWorkspaceSnapshot } && sshTargets.isEmpty()))))
                val customizePane: ((NativeWorkspace) -> Unit)? = workspaceSourceForPane()?.takeIf { it.canCustomizeWorkspace() }?.let { source ->
                    { workspace -> customizationTarget = WorkspaceCustomizationTarget.capture(browserLogin, teamState.scope, source.mac, workspace.id) }
                }
                val sidebarOwner = remember(browserLogin, teamState.scope) { browserLogin?.let { NativeComputerMenuOwner(it, teamState.scope) } }
                fun sidebarCurrent() = sidebarOwner != null && account.isSignedIn() && store.taskSession() == sidebarOwner.login &&
                    accountTeams.state.value.scope == sidebarOwner.team
                val sidebarInput by rememberUpdatedState<() -> NativeSidebarInput?>({
                    if (!sidebarCurrent()) null else {
                        val macs = store.visiblePairedMacs().filter(connection::allowsSaved)
                        val sources = macs.map { mac ->
                            val snapshot = workspaceMoves.sources.value[mac.origin]?.takeIf { it.mac == mac }
                                ?: feedCoordinator.sources.value[mac.origin]?.takeIf { it.mac == mac }
                            if (snapshot?.hasWorkspaceSnapshot == true) snapshot
                            else if (client != null && connectedCode == mac.code) NativeFeedSource(mac, workspaces = workspaces,
                                groups = groups, items = notifications, capabilities = hostCapabilities,
                                availability = NativeFeedAvailability.CONNECTED, hasWorkspaceSnapshot = true)
                            else snapshot ?: NativeFeedSource(mac)
                        }
                        val ssh = sshSession?.takeIf { it.isOpen && (connector != null || sharedConnections?.ssh?.state?.value?.resource === it) }
                        val hosts = ssh?.hosts?.state?.value?.hosts.orEmpty()
                        val rows = ssh?.workspaceFeed?.state?.value.orEmpty().values.flatMap { it.rows }
                            .filter { row -> hosts.any { it.connectsLike(row.host) } && ssh?.workspaceFeed?.isCurrent(row) == true }
                        val availability = hosts.associate { host -> host.id to when (ssh?.connections?.statuses?.value?.get(host.id)?.phase) {
                            SshConnectionPhase.CONNECTED -> NativeFeedAvailability.CONNECTED
                            SshConnectionPhase.CONNECTING -> NativeFeedAvailability.CONNECTING
                            else -> NativeFeedAvailability.OFFLINE
                        } }
                        val display = cachedAppearanceState?.value ?: appearances
                        NativeSidebarInput(sources, rows, macs.mapNotNull { mac -> workspaceMacFilterId(mac.deviceId, mac.instanceTag)?.let {
                            NativeSortComputer(it, display.name(mac), connectedCode == mac.code && connectionReady, scopedPresence.buildLabel(mac))
                        } } + hosts.map { NativeSortComputer(workspaceSshFilterId(it.id), it.name) } +
                            cloudSnapshots.values.map { NativeSortComputer(CloudAddress(it.machine.id).identifier, it.machine.preferredName, buildLabel = "Cloud") }, workspaceSortStore.state.value,
                            availability, display, searchLocale, actions = buildSet {
                                add(RoutedSidebarActionKind.SETTINGS); add(RoutedSidebarActionKind.COMPUTERS)
                                if (taskDraftRepository != null) add(RoutedSidebarActionKind.NEW_TASK)
                            }, pendingMoves = workspaceMoves.status.value.mapValues { it.value.pending },
                            creation = NativeSidebarCreation(creatingWorkspace || creatingTerminal || sshCreationBusy || creatingGroup || cloudCreationBusy,
                                sshTargets.filter { it.session === ssh && it.session.isOpen && hosts.any { host -> host.connectsLike(it.host) } },
                                foregroundMac = macs.singleOrNull { it.code == connectedCode }, cloud = cloudSnapshots.values.toList()),
                            display = NativeDisplayPreferences.read(displayPreferences),
                            cloud = cloudSnapshots.values.flatMap { it.rows }, cloudAvailability = cloudSnapshots.mapValues { it.value.availability },
                            cloudSelection = cloudRoute?.workspaceId, agentReadState = agentReadState)
                    }
                })
                val sidebarInitial by rememberUpdatedState<() -> NativeSidebarPresentation>({
                    val mac = store.visiblePairedMacs().singleOrNull { it.ownsOrigin(selectedComputerOrigin) }
                    val ssh = sshSession?.hosts?.state?.value?.hosts?.singleOrNull { "ssh:${it.id}" == selectedComputerOrigin }
                    NativeSidebarPresentation(mac?.let { workspaceMacFilterId(it.deviceId, it.instanceTag) } ?: ssh?.let { workspaceSshFilterId(it.id) } ?: selectedCloudId?.let { CloudAddress(it).identifier },
                        notificationTab, searchState.text(NativeSearchScope.WORKSPACES), searchState.text(NativeSearchScope.NOTIFICATIONS),
                        unreadWorkspacesOnly, unreadNotificationsOnly, workspaceFilter.machines, feedSession.projection, collapsedGroups,
                        agentFeedTab, searchState.text(NativeSearchScope.FEED), agentNeedsInputOnly)
                })
                val sidebarAdopt by rememberUpdatedState<(NativeSidebarPresentation) -> Unit>({ presentation ->
                    if (sidebarCurrent()) {
                        val mac = store.visiblePairedMacs().singleOrNull { workspaceMacFilterId(it.deviceId, it.instanceTag) == presentation.computer }
                        val ssh = sshSession?.hosts?.state?.value?.hosts?.singleOrNull { workspaceSshFilterId(it.id) == presentation.computer }
                        selectedComputerOrigin = mac?.origin ?: ssh?.let { "ssh:${it.id}" }
                            ?: presentation.computer?.let(CloudAddress::parse)?.takeIf { it.component == null }?.identifier.orEmpty()
                        store.update { it.put("computer_selection", selectedComputerOrigin) }
                        agentFeedTab = presentation.feed; notificationTab = presentation.notifications; agentNeedsInputOnly = presentation.feedNeedsInputOnly
                        searchState = searchState.commit().copy(workspaceQuery = presentation.workspaceQuery, notificationQuery = presentation.notificationQuery, feedQuery = presentation.feedQuery)
                        unreadNotificationsOnly = presentation.notificationUnread
                        workspaceFilter = NativeWorkspaceFilter(presentation.workspaceUnread, presentation.machines)
                        feedSession.projection = presentation.projection
                        collapsedGroups = collapsedGroups + presentation.collapsedGroups
                        store.update { it.put("collapsed_groups", JSONObject(collapsedGroups)) }
                    }
                })
                val sidebarNavigate by rememberUpdatedState<(NativeSidebarTarget) -> Unit>({ target ->
                    check(sidebarCurrent()) { "Sidebar account changed" }
                    when (target) {
                        is NativeSidebarTarget.CreateCloud -> {
                            cloudSnapshots[target.machineId]?.let(::createCloudWorkspace)
                        }
                        is NativeSidebarTarget.Cloud -> {
                            cloudModel?.openWorkspace(target.row, expected = checkNotNull(cloudWorkspaces))
                            workspaceSortStore.recordOpened(CloudAddress(target.row.machine.id).identifier, System.currentTimeMillis())
                            sshNavigation.leave(); screenResume.cancel(); workspaceRoute = null; inAppNotification = null
                            selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedSurface = null; selectedChangesWorkspace = null
                        }
                        is NativeSidebarTarget.CreateWorkspace -> {
                            check(!creatingWorkspace && !creatingTerminal && !sshCreationBusy) { "Workspace creation is in progress" }
                            check(store.visiblePairedMacs().contains(target.mac) && connection.allowsSaved(target.mac)) { "Saved computer changed" }
                            finishSearch(); createWorkspaceOnMac(target.mac, target.groupId)
                        }
                        is NativeSidebarTarget.CreateSsh -> {
                            check(!creatingWorkspace && !creatingTerminal && !sshCreationBusy && canCreateSsh(target.target, target.kind)) { "SSH creation is no longer available" }
                            finishSearch(); createSshWorkspace(target.target, target.kind)
                        }
                        is NativeSidebarTarget.Action -> {
                            finishSearch()
                            when (target.kind) {
                                RoutedSidebarActionKind.SETTINGS -> { workspaceRoute = null; showSettings = true }
                                RoutedSidebarActionKind.COMPUTERS -> presentComputers()
                                RoutedSidebarActionKind.NEW_TASK -> {
                                    check(taskDraftRepository != null) { "Task composer is no longer available" }
                                    newTaskDraft()
                                }
                            }
                        }
                        is NativeSidebarTarget.Workspace -> {
                            cloudModel?.leaveWorkspace()
                            check(store.visiblePairedMacs().contains(target.mac) && connection.allowsSaved(target.mac))
                            sshNavigation.leave(); screenResume.cancel(); inAppNotification = null
                            workspaceRoute = NativeWorkspaceRoute(target.mac.origin, target.id)
                        }
                        is NativeSidebarTarget.Ssh -> {
                            cloudModel?.leaveWorkspace()
                            val row = target.row
                            check(sshSession?.workspaceFeed?.isCurrent(row) == true)
                            val first = checkNotNull(row.openTarget())
                            val login = checkNotNull(browserLogin)
                            val remembered = store.lastWorkspaceTab(login, sshWorkspaceTabKey(login, row.host, first))
                            workspaceSortStore.recordOpened(workspaceSshFilterId(row.host.id), System.currentTimeMillis())
                            screenResume.cancel(); workspaceRoute = null; inAppNotification = null
                            sshNavigation.open(login, row.host, first, remembered)
                        }
                        is NativeSidebarTarget.Agent -> {
                            val entry = target.entry
                            check(store.visiblePairedMacs().contains(entry.source.mac) && connection.allowsSaved(entry.source.mac))
                            cloudModel?.leaveWorkspace(); sshNavigation.leave(); screenResume.cancel(); inAppNotification = null
                            agentFeedTab = true; notificationTab = false; cloudTab = false
                            workspaceRoute = NativeWorkspaceRoute(entry.source.mac.origin, checkNotNull(entry.item.workspaceId),
                                terminalId = entry.item.surfaceId.takeIf { target.tab })
                        }
                        is NativeSidebarTarget.Notification -> {
                            cloudModel?.leaveWorkspace()
                            val entry = target.entry
                            check(store.visiblePairedMacs().contains(entry.source.mac) && connection.allowsSaved(entry.source.mac))
                            sshNavigation.leave(); screenResume.cancel(); workspaceRoute = null
                            inAppNotification = NotificationDestination(java.util.UUID.randomUUID().toString(), entry.source.mac.origin,
                                entry.notification.id, entry.notification.workspaceId, entry.notification.surfaceId, entry.notification.retargetsToLiveSurfaceOwner)
                        }
                    }
                })
                val sidebarHost = remember(sidebarOwner, feedSession, sshSession) { sidebarOwner?.let { owner ->
                    // Updated callbacks can be rebound after recomposition; retain this captured authority.
                    fun currentOwner() = account.isSignedIn() && store.taskSession() == owner.login && accountTeams.state.value.scope == owner.team
                    NativeRoutedSidebarHost(owner, feedSession.sidebarSalt, { if (currentOwner()) sidebarInput() else null }, retainFeed = {
                        val held = feedSession.holdSidebar { currentOwner() && sidebarInput() != null }
                        val connections = try { if (sharedConnections != null) NativeAppConnections.acquire(context.applicationContext) else null }
                            catch (failure: Exception) { held.close(); throw failure }
                        RoutedSidebarLease({ active -> held.active(active); connections?.connections?.setProbeActive(held, active) },
                            { held.close(); connections?.close() })
                    }, navigate = { check(currentOwner()); sidebarNavigate(it) },
                        initial = { if (currentOwner()) sidebarInitial() else NativeSidebarPresentation() },
                        adoptPresentation = { if (currentOwner()) sidebarAdopt(it) },
                        saveSort = { mode, order ->
                            check(currentOwner()) { "Sidebar account changed" }
                            mode?.let(workspaceSortStore::setMode); order?.let(workspaceSortStore::setPriority)
                        }, readNotification = { entry, read, canSend ->
                            check(currentOwner()) { "Sidebar account changed" }
                            feedCoordinator.setRead(entry, read) { currentOwner() && canSend() }
                        }, readAllNotifications = { macs, canSend ->
                            check(currentOwner()) { "Sidebar account changed" }
                            feedCoordinator.markNotificationsRead(macs) { currentOwner() && canSend() }
                        }, refreshNotifications = {
                            check(currentOwner()) { "Sidebar account changed" }
                            feedCoordinator.refresh()
                        }, agentSession = { if (currentOwner()) feedCoordinator.agentFeedSession(it) else null },
                        readAgent = { entry, needs ->
                            check(currentOwner()) { "Sidebar account changed" }
                            agentReadState = if (needs == null) agentReadState.interacted(entry) else agentReadState.triage(entry, needs)
                        }, refreshAgent = { check(currentOwner()); feedCoordinator.refresh() },
                        history = feedSession.sidebarHistory, mutateWorkspace = { target, command, canSend ->
                            check(currentOwner()) { "Sidebar account changed" }
                            val permitted = { currentOwner() && canSend() }
                            if (target.group) feedCoordinator.groupAction(target.mac, target.id, command.kind.verb, command.title, permitted)
                            else feedCoordinator.workspaceAction(target.mac, target.id, command.kind.verb, command.title, permitted)
                        }, moveWorkspace = { source, workspace, intent, canSend ->
                            check(currentOwner()) { "Sidebar account changed" }
                            workspaceMoves.submit(source, workspace, intent) { currentOwner() && canSend() }
                        }, customizeWorkspace = { target, baseline, submitted, canSend ->
                            check(currentOwner()) { "Sidebar account changed" }
                            feedCoordinator.customizeWorkspace(target.mac, target.id, baseline, submitted) { currentOwner() && canSend() }
                        }, createWorkspaceGroup = { mac, canSend ->
                            check(currentOwner()) { "Sidebar account changed" }
                            feedCoordinator.createGroup(mac) { currentOwner() && canSend() }
                        }, readChanges = { mac, workspace, permitted ->
                            feedCoordinator.changesAccess(mac, workspace) { currentOwner() && permitted() }
                        }, canCloseSsh = { row -> currentOwner() && sshSession?.workspaceFeed?.isCloseAvailable(row) == true },
                        closeSsh = { row, canSend ->
                            check(currentOwner()) { "Sidebar account changed" }
                            checkNotNull(sshSession).workspaceFeed.submitClose(row) { currentOwner() && canSend() }
                        })
                } }
                sidebarChanges?.let { presentation ->
                    if (presentation.access.current()) WorkspaceChangesSheet(presentation, { feedSession.dismissChanges(presentation) })
                    else SideEffect { feedSession.dismissChanges(presentation) }
                }
                CompositionLocalProvider(LocalMacCompatibilityWarnings provides displayWarnings,
                    LocalWorkspaceCustomizationAction provides customizePane, LocalRoutedSidebarHost provides sidebarHost) {
                NativeScreenLayout(Modifier.fillMaxSize().background(nativePage).statusBarsPadding().navigationBarsPadding().imePadding(), browserLogin, teamState.email) {
                    LocalBrowserCreationProgress(localBrowserState.creating != null, localBrowsers::cancelRequest)
                    if (signedIn && terminalStartupState.failure?.key?.let { it == displayedTab?.first } == true) {
                        NativeTerminalCreationRecovery(creatingTerminal, connectionReady && selectedWorkspace != null) {
                            selectedWorkspace?.let { workspace -> workspaceSourceForPane()?.let { createTerminal(it, workspace) } }
                        }
                    }
                    val cloudContent: @Composable ColumnScope.() -> Unit = {
                        Column(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.weight(1f).fillMaxWidth()) {
                                cloudTabState.SaveableStateProvider("cloud") {
                                    NativeCloudFlow(cloudController, onSettings = { showSettings = true }, onBack = { cloudTab = false },
                                        onPlans = { plan -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(cloudPlansUrl(plan)))) }
                                            .onFailure { android.widget.Toast.makeText(context, "No browser is available to open cmux.com/pricing", android.widget.Toast.LENGTH_LONG).show() } },
                                        modifier = Modifier.fillMaxSize(), connectionState = cloudTunnelState,
                                        onRetryConnection = { cloudTunnel?.retry() }, vpn = sharedConnections?.cloudVpn,
                                        machines = allCloudSnapshots, connectionFailures = cloudConnectionFailures,
                                        onRetryConnections = { cloudModel?.let { model -> cloudWorkspaces?.let(model::retryConnections) } })
                                }
                            }
                            NativePrimaryNavigation(notificationTab, feedEntries.count { !it.notification.isRead }, searchState,
                                onTab = { cloudTab = false; finishSearch(); agentFeedTab = false; notificationTab = it },
                                onBeginSearch = {}, onEdit = { _, _ -> }, onSubmit = {}, onCancel = {}, cloudTab = true, onCloud = {},
                                sidebar = LocalWorkspaceShellChrome.current.split, emptyComputers = noKnownComputers, agentFeedTab = agentFeedTab, agentFeedCount = agentNeedsInputCount,
                                showsNotifications = !displayState.feedReplacesNotifications,
                                onAgentFeed = { cloudTab = false; finishSearch(); notificationTab = false; agentFeedTab = true })
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
                        else -> NativeWorkspaceShell(owner = browserLogin to teamState.scope,
                            hasDetail = cloudRoute != null || sshRoute != null || screenResume.pending != null || localBrowser != null ||
                                selectedWorkspace != null || selectedTerminal != null || selectedBrowser != null ||
                                selectedChangesWorkspace != null || (showWorkspaceReconnect && !cloudTab),
                            allowSplit = cloudTab || !showWorkspaceReconnect,
                            showSidebarInCompact = cloudTab && cloudModel != null,
                            onSearchBack = if (searchState.active != null) ({ finishSearch(cancel = true) }) else null,
                            onSidebarHidden = if (searchState.active != null) ({ finishSearch() }) else null,
                            modifier = Modifier.weight(1f).fillMaxWidth(), sidebar = {
                                if (cloudTab && cloudModel != null) cloudContent() else workspaceListContent()
                            }) {
                            when {
                        cloudRoute != null && cloudModel != null -> NativeCloudTerminalPane(cloudModel, cloudRoute, cloudSnapshots[cloudRoute.host.machineId])
                        sshRoute != null && sshSession != null -> key(sshRoute.login, sshRoute.host.id, sshRoute.target) {
                            SshWorkspacesRoute(sshSession, sshRoute.host.id, sshRoute.target, rememberedTab = sshRoute.rememberedTab,
                                onDisplayed = { target, localBrowser ->
                                if (store.taskSession() == sshRoute.login && sshSession.isOpen &&
                                    sshSession.hosts.state.value.host(sshRoute.host.id)?.connectsLike(sshRoute.host) == true) {
                                    displayedSshTarget = target
                                    (if (localBrowser) NativeWorkspaceTab.LocalBrowser else target.rememberedTab())?.let { tab ->
                                        store.rememberWorkspaceTab(sshRoute.login, sshWorkspaceTabKey(sshRoute.login, sshRoute.host, target), tab)
                                    }
                                }
                            }) { sshNavigation.leave() }
                        }
                        screenResume.pending != null && selectedWorkspace == null && localBrowser == null -> {
                            NativeWorkspaceWaitingPane("Restoring workspace…",
                                onBack = { screenResume.cancel(); workspaceRoute = null },
                                connected = connectionReady && error == null, connectionError = error ?: connectionError,
                                onReconnect = { error = null; retryDelay = 2_000; retry++ })
                        }
                        localBrowser != null && pairedMacs.any { localBrowserKey(browserLogin, teamState.scope, it, localBrowser.key.workspaceId) == localBrowser.key } -> localBrowserContent()
                        showWorkspaceReconnect -> reconnectContent()
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
                                panel = retainedPanel,
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
                        else -> NativeWorkspaceSelectionPlaceholder()
                            }
                        }
                    }
                    if (signedIn) NativeCreationNoticeBanner(creationNavigation.noticeFor(browserLogin, teamState.scope), creationNavigation::dismissNotice)
                    val visibleError = error ?: connectionError.takeIf { sshRoute == null && (selectedTerminal != null || selectedBrowser != null || (workspaceSources.isEmpty() && sshTargets.isEmpty())) }
                    if (signedIn && visibleError != null) Row(Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(visibleError, Modifier.weight(1f).padding(vertical = 12.dp), color = Color(0xFFFFAAAA))
                        if (selectedTerminal != null) TextButton(onClick = { retryDelay = 2_000; retry++ }) { Text("Reconnect") }
                    }
                }
                }
            }
            screenContent()
        }
        workspaceContent()
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
internal fun NativeWorkspaceRow(
    workspace: NativeWorkspace,
    groupMoveMenu: NativeWorkspaceGroupMoveMenu = NativeWorkspaceGroupMoveMenu(),
    canCustomize: Boolean = false,
    displayPreferences: NativeDisplayPreferences = NativeDisplayPreferences(),
    availability: NativeFeedAvailability = NativeFeedAvailability.CONNECTED,
    changesChip: WorkspaceChangesChip? = null,
    canReadState: Boolean = false,
    canClose: Boolean = false,
    canWorkspaceActions: Boolean = false,
    handlesHold: Boolean = false, leadingIndent: Int = 0, closeConfirmation: WorkspaceCloseConfirmation? = WorkspaceCloseConfirmation.mac,
    onOpen: () -> Unit,
    onAction: (String, String?) -> Unit,
    remoteGroupMenu: (@Composable (onBack: () -> Unit, onDismiss: () -> Unit) -> Unit)? = null,
    isSelected: Boolean = false
) {
    val admitted by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
    val currentOpen by rememberUpdatedState(onOpen)
    val currentAction by rememberUpdatedState(onAction)
    fun dispatchOpen() { if (admitted()) currentOpen() }
    val actionPolicy by rememberUpdatedState(WorkspaceRowActionPolicy(canReadState, canClose, canWorkspaceActions,
        canCustomize, !groupMoveMenu.isEmpty || remoteGroupMenu != null, changesChip?.files?.let { it > 0 } == true))
    fun dispatchAction(action: String, value: String?): Boolean {
        if (!admitted() || !actionPolicy.permits(action)) return false
        currentAction(action, value)
        return true
    }
    val highlighted = isSelected && LocalWorkspaceShellChrome.current.split
    val menu = rememberWorkspaceContextMenu(workspace.id)
    var groupPicker by remember(menu, menu.expanded) { mutableStateOf(false) }
    val canMoveGroups = !groupMoveMenu.isEmpty || remoteGroupMenu != null
    val showingGroups = groupPicker && canMoveGroups
    val hasMenu = canWorkspaceActions || canReadState || canClose || canMoveGroups || canCustomize
    val menuExpanded = menu.expanded && hasMenu
    LaunchedEffect(menu, hasMenu, menu.expanded) { if (!hasMenu) menu.expanded = false }
    val moveActions = LocalWorkspaceMoveActions.current
    var rename by remember(menu) { mutableStateOf(false) }
    var pendingClose by remember(menu) { mutableStateOf<Pair<WorkspaceCloseConfirmation, () -> Unit>?>(null) }
    val rowPresent = admitted()
    LaunchedEffect(rowPresent) { if (!rowPresent) { menu.expanded = false; rename = false; pendingClose = null } }
    fun requestClose(): Boolean {
        if (!admitted() || !actionPolicy.close) return false
        if (closeConfirmation == null) return dispatchAction("close", null)
        pendingClose = closeConfirmation to { dispatchAction("close", null); Unit }
        return true
    }
    LaunchedEffect(canClose, canWorkspaceActions) {
        if (!canClose) pendingClose = null
        if (!canWorkspaceActions) rename = false
    }
    var title by remember(menu, workspace.id) { mutableStateOf(workspace.title) }
    val readLabel = if (workspace.hasUnread) "Mark as Read" else "Mark as Unread"
    NativeWorkspaceSwipeActions(workspace.id, workspace.hasUnread, canReadState, canClose,
        onRead = { unread -> dispatchAction(if (unread) "mark_unread" else "mark_read", null) },
        onClose = { requestClose() }) { dismissSwipe, swipeActions ->
    Box {
    val openRow = { if (!menuExpanded && !menu.held && !dismissSwipe()) dispatchOpen() }
    val locale = LocalConfiguration.current.locales[0]
    val zone = java.util.TimeZone.getDefault()
    val now = System.currentTimeMillis()
    val day = java.time.Instant.ofEpochMilli(now).atZone(zone.toZoneId()).toLocalDate()
    val trailing = remember(workspace.lastActivityAt, workspace.previewAt, availability, locale, zone.id, day) {
        workspaceActivityLabel(workspace, availability, now, locale, zone)
    }
    val visual = WorkspaceRowVisual(workspace, displayPreferences, highlighted, changesChip, trailing, leadingIndent)
    WorkspaceMeasuredContent(visual, LocalWorkspaceGeometryHeld.current) { shown, measuring ->
        NativeWorkspaceRowBody(shown, measuring, if (measuring) Modifier.fillMaxWidth().height(IntrinsicSize.Min) else Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(if (handlesHold)
        Modifier.combinedClickable(onClick = openRow, onLongClick = { if (hasMenu) { dismissSwipe(); menu.expanded = true } })
        else Modifier.clickable(onClick = openRow))
        .semantics {
            selected = highlighted
            if (hasMenu) onLongClick("Show workspace actions") { dismissSwipe(); menu.expanded = true; true }
            customActions = buildList {
                addAll(moveActions)
                if (hasMenu) add(CustomAccessibilityAction("Show workspace actions") { dismissSwipe(); menu.expanded = true; true })
                if (swipeActions.canRead) add(CustomAccessibilityAction(if (swipeActions.hasUnread) "Mark as Read" else "Mark as Unread") {
                    dismissSwipe(); dispatchAction(if (swipeActions.hasUnread) "mark_read" else "mark_unread", null)
                })
                if (swipeActions.canClose) add(CustomAccessibilityAction("Delete workspace") { dismissSwipe(); requestClose() })
            }
            stateDescription = listOfNotNull("Pinned".takeIf { workspace.isPinned },
                workspace.unreadState.accessibilityLabel.takeIf { it.isNotEmpty() }).joinToString(", ")
        }) {
            dispatchAction("changes", null)
        }
    }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = {
                if (!menu.held) { if (showingGroups) groupPicker = false else menu.expanded = false }
            }, properties = PopupProperties(focusable = !menu.held)) {
                if (showingGroups) {
                    if (remoteGroupMenu != null) remoteGroupMenu({ groupPicker = false }, { menu.expanded = false })
                    else NativeWorkspaceGroupMoveItems(groupMoveMenu, onBack = { groupPicker = false }, onMove = { groupId ->
                        menu.expanded = false; dispatchAction("move:${groupId.orEmpty()}", null)
                    })
                } else {
                    if (canWorkspaceActions) DropdownMenuItem(text = { Text(if (workspace.isPinned) "Unpin" else "Pin") },
                        leadingIcon = { WorkspaceActionIcon(if (workspace.isPinned) R.drawable.ic_workspace_unpin else R.drawable.ic_workspace_pin) },
                        onClick = { menu.expanded = false; dispatchAction(if (workspace.isPinned) "unpin" else "pin", null) })
                    if (canCustomize) DropdownMenuItem(text = { Text("Customize") },
                        leadingIcon = { WorkspaceActionIcon(R.drawable.ic_task_options) },
                        onClick = { menu.expanded = false; dispatchAction("customize", null) })
                    if (canWorkspaceActions) DropdownMenuItem(text = { Text("Rename") },
                        leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_rename) },
                        onClick = { menu.expanded = false; title = workspace.title; rename = true })
                    if (canReadState) DropdownMenuItem(text = { Text(readLabel) },
                        leadingIcon = { WorkspaceActionIcon(if (workspace.hasUnread) R.drawable.ic_feed_read_all else R.drawable.ic_workspace_mark_unread) }, onClick = {
                        menu.expanded = false; dispatchAction(if (workspace.hasUnread) "mark_read" else "mark_unread", null)
                    })
                    if (canMoveGroups) DropdownMenuItem(text = { Text("Move to Group") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_workspace_folder), null, Modifier.size(20.dp)) },
                        trailingIcon = { Icon(painterResource(R.drawable.ic_workspace_chevron_right), null, Modifier.size(16.dp)) },
                        onClick = { groupPicker = true })
                    if (canClose) DropdownMenuItem(text = { Text("Delete") },
                        colors = MenuDefaults.itemColors(textColor = Color(0xFFFF9999), leadingIconColor = Color(0xFFFF9999)),
                        leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_delete) }, onClick = {
                        menu.expanded = false; requestClose()
                    })
                }
            }
    }
    }
    if (rename) AlertDialog(
        onDismissRequest = { rename = false },
        title = { Text("Rename workspace") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { rename = false; dispatchAction("rename", title) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } }
    )
    pendingClose?.let { pending -> WorkspaceCloseDialog(pending.first,
        onDismiss = { pendingClose = null }, onConfirm = { pendingClose = null; pending.second() }) }
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
    var pairingText by rememberSaveable(teamState.userId, teamState.selectedTeamId, stateSaver = NativePairingDraftSaver) { mutableStateOf("") }
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
