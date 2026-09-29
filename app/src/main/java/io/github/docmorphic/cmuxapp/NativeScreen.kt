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
import org.json.JSONObject

private val nativePage = Color(0xFF0B0C0E)
private val nativePanel = Color(0xFF191B1F)
private val nativeAccent = Color(0xFF76B9FF)
private val nativeMuted = Color(0xFF9B9FA8)

@Composable
fun NativeScreen(
    onUseHelper: () -> Unit, incomingCode: String? = null, incomingNotificationRoute: String? = null,
    onNotificationHandled: (String) -> Unit = {},
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
    var terminalScale by remember(displayPreferences) {
        mutableFloatStateOf(displayPreferences.getFloat("terminal_scale", 1f).coerceIn(0.75f, 1.5f))
    }
    val terminalCells = remember(density, terminalScale) {
        TerminalCellMetrics.fromFontSize(
            with(density) { 14.sp.toPx() } * terminalScale,
            with(density) { 2.dp.toPx() }
        )
    }
    var terminalViewportPixels by remember { mutableStateOf(IntSize.Zero) }
    val terminalViewport = TerminalViewport.fit(
        terminalViewportPixels.width, terminalViewportPixels.height, terminalCells)
    val terminalColumns = terminalViewport?.columns ?: 0
    val terminalRows = terminalViewport?.rows ?: 0
    var viewportRequestGeneration by remember { mutableLongStateOf(0L) }
    val store = remember(context, sharedConnections) { sharedConnections?.store ?: NativeCredentialStore(context.applicationContext) }
    val account = remember(store) { sharedConnections?.account ?: NativeAccount(store) }
    val scope = rememberCoroutineScope()
    val terminalFocusRequester = remember { FocusRequester() }
    var signedIn by remember { mutableStateOf(account.isSignedIn()) }
    val accountTeams = remember(account, store) { sharedConnections?.teams ?: NativeAccountTeams(account, store) }
    val teamState by accountTeams.state.collectAsState()
    DisposableEffect(accountTeams) { onDispose { if (sharedConnections == null) accountTeams.close() } }
    LaunchedEffect(signedIn, accountTeams) {
        if (!signedIn) accountTeams.clear()
    }
    var code by remember { mutableStateOf(store.load()?.optString("pairing_code").orEmpty()) }
    var pendingPairingCode by remember { mutableStateOf<String?>(null) }
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
        PairingCodeParser.parse(it.code).getOrNull()?.let(connection::allowsSaved) == true
    }
    var showSettings by remember { mutableStateOf(false) }
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
    var workspaces by remember(code) { mutableStateOf<List<NativeWorkspace>>(emptyList()) }
    var groups by remember(code) { mutableStateOf<List<NativeGroup>>(emptyList()) }
    var taskGroupsLoaded by remember(code) { mutableStateOf(false) }
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
    var selectedWorkspace by remember(code) { mutableStateOf<NativeWorkspace?>(null) }
    var selectedTerminal by remember(code) { mutableStateOf<NativeTerminal?>(null) }
    var selectedBrowser by remember(code) { mutableStateOf<NativeBrowser?>(null) }
    var selectedChangesWorkspace by remember(code) { mutableStateOf<NativeWorkspace?>(null) }
    var connectedCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(teamState.scope, signedIn) {
        val pairing = PairingCodeParser.parse(code).getOrNull()
        if (pairing is PairingCode.Iroh && (!signedIn || (teamState.scope != null && !connection.allowsSaved(pairing)))) {
            client?.close(); client = null; code = ""
            selectedTerminal = null; selectedWorkspace = null; selectedBrowser = null
        }
    }
    val notificationDelivery = remember(context) { NativeNotificationDelivery(context.applicationContext) }
    var workspaceRoute by remember { mutableStateOf<NativeWorkspaceRoute?>(null) }
    var inAppNotification by remember { mutableStateOf<NotificationDestination?>(null) }
    val currentIncomingRoute by rememberUpdatedState(incomingNotificationRoute ?: inAppNotification?.routeId)
    val handleNotification by rememberUpdatedState(onNotificationHandled)
    var notificationNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val feedOwner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val feedSession = remember(feedOwner) {
        ViewModelProvider(feedOwner, NativeFeedSession.Factory(connection, account, store))
            .get(NativeFeedSession::class.java)
    }
    val feedCoordinator = feedSession.coordinator
    val feedSources by feedCoordinator.sources.collectAsState()
    val workspaceMoves = feedSession.workspaceMoves
    val moveSources by workspaceMoves.sources.collectAsState()
    val moveStatus by workspaceMoves.status.collectAsState()
    LaunchedEffect(moveStatus) { moveStatus.values.mapNotNull { it.error }.firstOrNull()?.let { error = it } }
    var selectedComputerOrigin by rememberSaveable(signedIn) {
        mutableStateOf(store.load()?.optString("computer_selection").orEmpty())
    }
    val selectedComputer = pairedMacs.firstOrNull { it.origin == selectedComputerOrigin }
    val selectedOrigin = selectedComputer?.origin
    fun selectComputer(mac: NativeCredentialStore.PairedMac?) {
        selectedComputerOrigin = mac?.origin.orEmpty()
        store.update { it.put("computer_selection", selectedComputerOrigin) }
        if (mac != null) code = mac.code
        workspaceRoute = null
        computerMenuOpen = false
    }
    fun newTaskDraft() {
        taskDraftRepository?.templates?.state?.value?.lastOrigin?.let { origin ->
            pairedMacs.firstOrNull { it.origin == origin }?.let(::selectComputer)
        }
        taskDraftId = java.util.UUID.randomUUID().toString()
        showTaskComposer = true
    }
    LaunchedEffect(pairedMacs, selectedComputerOrigin) {
        feedSession.taskModels.retainOrigins(pairedMacs.map { it.origin }.toSet())
        if (selectedComputerOrigin.isNotBlank() && selectedComputer == null) selectComputer(null)
    }
    val canCreateOnCurrentMac = connectionReady && client != null && connectedCode == code &&
        (selectedComputer == null || selectedComputer.code == connectedCode)
    val scopedFeedSources = remember(feedSources, selectedOrigin) {
        feedSources.values.filter { selectedOrigin == null || it.mac.origin == selectedOrigin }
    }
    val feedEntries = remember(feedSources, selectedOrigin) { aggregateNativeFeed(feedSources.values, selectedOrigin) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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
    LaunchedEffect(signedIn, pairedMacs, feedForeground) {
        if (!signedIn) feedSession.clear()
        else if (feedForeground) feedCoordinator.updateMacs(pairedMacs)
        else feedCoordinator.pause()
    }
    DisposableEffect(feedCoordinator) { onDispose { feedCoordinator.pause() } }
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
    val workspaceSearch = remember(workspaceSources, searchLocale) {
        NativeSearchIndex(workspaceSources.flatMap { source ->
            val groupNames = source.groups.associate { it.id to it.name }
            source.workspaces.map { workspace -> workspaceSearchId(source, workspace) to
                (listOf(workspace.title, workspace.description, workspace.directory, workspace.preview,
                    source.mac.name, groupNames[workspace.groupId]) + workspace.terminals.map { it.title }) }
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
    var scrollOffset by remember { mutableIntStateOf(0) }
    var terminalClick by remember { mutableStateOf<((TerminalGeometry.Cell) -> Unit)?>(null) }
    var terminalScroll by remember { mutableStateOf<((Double, TerminalGeometry.Cell) -> Boolean)?>(null) }
    var cancelQueuedScroll by remember { mutableStateOf<(() -> Unit)?>(null) }
    var scrollInteractionEpoch by remember { mutableIntStateOf(0) }
    val terminalMotion = rememberTerminalScrollMotion(draftTarget, client)
    fun stopTerminalScrolling() { terminalMotion.stop(); scrollInteractionEpoch++; cancelQueuedScroll?.invoke() }
    var textSnapshot by remember(draftTarget, client) { mutableStateOf<TerminalTextSnapshot?>(null) }
    fun openTerminalText() { stopTerminalScrolling(); textSnapshot = TerminalTextSnapshot.capture(grid) }
    textSnapshot?.let { TerminalTextSheet(it) { textSnapshot = null } }
    var showTerminalFiles by remember(draftTarget, client) { mutableStateOf(false) }
    var terminalArtifactPath by remember(draftTarget, client) { mutableStateOf<String?>(null) }
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
    val artifactsReady = connectionReady && connectedCode == code && artifactRpc?.capabilities?.terminal == true && draftTarget != null
    val artifactController = remember(artifactRpc, draftTarget, artifactsReady, showMissingArtifacts) {
        if (artifactsReady) TerminalArtifactController(scope, artifactRpc!!,
            ArtifactAuthorization.Terminal(draftTarget!!.workspace, draftTarget.surface), showMissingArtifacts) else null
    }
    DisposableEffect(artifactController) { onDispose { artifactController?.close() } }
    LaunchedEffect(artifactController) {
        val controller = artifactController ?: return@LaunchedEffect
        snapshotFlow { gridRevision to scrollOffset }.collectLatest {
            controller.observe(RenderGrid.plainText(grid.visibleLines(scrollOffset)))
        }
    }
    val artifactTapController = remember(artifactRpc, draftTarget, grid, artifactsReady, folderTapEnabled) { TerminalArtifactTapController(scope) }
    DisposableEffect(artifactTapController) { onDispose { artifactTapController.close() } }
    val artifactChipCount = artifactController?.count?.collectAsState()?.value
    val artifactRefresh = artifactController?.galleryRefresh?.collectAsState()?.value ?: 0
    if (terminalArtifactPath != null && artifactsReady && artifactRpc != null && draftTarget != null) {
        ArtifactPathSheet(artifactRpc, ArtifactAuthorization.Terminal(draftTarget.workspace, draftTarget.surface), terminalArtifactPath!!) {
            terminalArtifactPath = null
        }
    }
    if (showTerminalFiles && connectionReady && connectedCode == code && artifactRpc != null && draftTarget != null) {
        ArtifactFilesSheet(artifactRpc, ArtifactAuthorization.Terminal(draftTarget.workspace, draftTarget.surface), artifactRefresh) {
            showTerminalFiles = false
        }
    }

    var inputModifiers by remember(draftTarget, client) { mutableStateOf(TerminalInputModifiers()) }
    var directTyping by remember(draftTarget) { mutableStateOf(false) }
    var rawKeyboardView by remember(draftTarget) { mutableStateOf<TerminalKeyboardView?>(null) }
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
    val nativeInput = remember(inputClient, inputTarget, terminalTransport.mode, connectionReady) {
        if (inputClient != null && inputTarget != null && connectionReady && terminalTransport.mode == TerminalOutputMode.GRID)
            TerminalInputLaneOwner(scope) { use -> inputClient.useTerminalInputLane(inputTarget.surface, use) }
        else null
    }
    DisposableEffect(nativeInput) { onDispose { nativeInput?.close() } }
    val inputQueue = remember(inputClient, inputTarget, nativeInput) {
        TerminalInputQueue(scope) { entry ->
            check(inputClient != null && inputTarget != null && client === inputClient &&
                code == inputTarget.pairing && signedIn) { "Terminal connection changed" }
            if (entry.paste) inputClient.paste(inputTarget.workspace, inputTarget.surface, entry.text, submit = false)
            else if (nativeInput?.send(entry.text) != true && outputInput?.send(entry.text) != true)
                inputClient.input(inputTarget.workspace, inputTarget.surface, entry.text)
        }
    }
    val inputStatus by inputQueue.status.collectAsState()
    DisposableEffect(inputQueue) { onDispose { inputQueue.close() } }

    fun queueInput(value: String, paste: Boolean = false): Boolean {
        val target = draftTarget ?: return false
        if (client == null || drafts.state.value[target]?.operation != null) return false
        stopTerminalScrolling(); scrollOffset = 0
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
        if (preparingAttachments || terminalDraft.operation != null || inputStatus.error != null) return false
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
        fun checkTarget() {
            check(signedIn && client === active && code == target.pairing && drafts.generation == generation &&
                workspaces.any { it.id == target.workspace && it.terminals.any { terminal -> terminal.id == target.surface } }) {
                "The paste target changed. Paste again in the intended terminal."
            }
        }
        if (directTyping) {
            stopTerminalScrolling(); scrollOffset = 0
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
        if (preparingAttachments) return
        val send = drafts.begin(target) ?: return
        val supportsFiles = ComposerAttachment.FILE_CAPABILITY in hostCapabilities
        stopTerminalScrolling(); scrollOffset = 0
        scope.launch {
            try {
                inputQueue.awaitIdle()
                draftRepository.persistNow()
                check(client === active && signedIn && code == target.pairing) { "Connection changed" }
                val deliveredFiles = deliverTerminalComposer(active, drafts, send, submit, supportsFiles,
                    read = draftRepository::read, persist = draftRepository::persistNow,
                    isCurrent = { client === active && signedIn && code == target.pairing })
                drafts.finish(send, deliveredFiles = deliveredFiles)
                draftRepository.persistNow()
                if (selectedTerminal?.id == target.surface) scrollOffset = 0
            } catch (failure: Exception) {
                drafts.finish(send, TerminalDrafts.DELIVERY_UNCONFIRMED)
                if (failure is CancellationException) throw failure
            }
        }
    }

    fun applyListing(value: JSONObject) {
        val updated = parseWorkspaces(value)
        workspaces = updated
        if (value.has("groups")) { groups = parseGroups(value); taskGroupsLoaded = true }
        selectedWorkspace?.let { previous ->
            val current = updated.firstOrNull { it.id == previous.id }
            selectedWorkspace = current
            selectedTerminal = selectedTerminal?.let { terminal ->
                current?.terminals?.firstOrNull { it.id == terminal.id }
            }
            selectedBrowser = selectedBrowser?.let { browser ->
                current?.browsers?.firstOrNull { it.id == browser.id }
            }
        }
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

    LaunchedEffect(incomingCode) {
        if (incomingCode != null && incomingCode != code) proposePairing(incomingCode)
    }
    LaunchedEffect(incomingNotificationRoute) { if (incomingNotificationRoute != null) { inAppNotification = null; workspaceRoute = null } }
    val routeClient = client
    val routeConnectedCode = connectedCode
    val routePairingCode = code
    val routeSignedIn = signedIn
    val routeInAppNotification = inAppNotification
    LaunchedEffect(incomingNotificationRoute, routeInAppNotification?.routeId, routeSignedIn,
        routePairingCode, routeConnectedCode, routeClient) {
        val routeId = incomingNotificationRoute ?: routeInAppNotification?.routeId ?: return@LaunchedEffect
        val openedFromFeed = incomingNotificationRoute == null
        fun consumeRoute() {
            if (openedFromFeed) { if (inAppNotification?.routeId == routeId) inAppNotification = null }
            else handleNotification(routeId)
        }
        if (!routeSignedIn || currentIncomingRoute != routeId) return@LaunchedEffect
        val route = if (openedFromFeed) routeInAppNotification else notificationDelivery.destination(routeId)
        val mac = store.pairedMacs().singleOrNull { it.origin == route?.origin }
        if (route == null || mac == null) {
            error = "This notification's saved Mac is no longer available."
            consumeRoute()
            return@LaunchedEffect
        }
        if (routePairingCode != mac.code) {
            store.update { it.put("pairing_code", mac.code) }
            showSettings = false; selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null
            code = mac.code
            return@LaunchedEffect
        }
        // Use the session captured with the effect keys. Reading mutable client here
        // can run this route twice if a handshake completes before this effect starts.
        val active = routeClient ?: return@LaunchedEffect
        if (routeConnectedCode != mac.code) return@LaunchedEffect
        fun isCurrent() = currentIncomingRoute == routeId && client === active && code == mac.code &&
            signedIn && store.pairedMacs().contains(mac)
        try {
            val listing = active.workspaces()
            val feed = parseNotifications(active.notifications())
            if (!isCurrent()) return@LaunchedEffect
            val notification = feed.firstOrNull { it.id == route.notificationId } ?: route.notification()
            val available = parseWorkspaces(listing)
            val workspace = notification.destination(available)
            val exactBrowser = workspace?.browsers?.firstOrNull { it.id == notification.surfaceId }
            val terminal = workspace?.terminals?.firstOrNull { it.id == notification.surfaceId }
                ?: if (exactBrowser == null) workspace?.terminals?.firstOrNull() else null
            val browser = exactBrowser ?: if (terminal == null) workspace?.browsers?.firstOrNull() else null
            check(workspace != null && (terminal != null || browser != null)) {
                "This notification's workspace is no longer available."
            }
            val opened = withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (!isCurrent()) false else {
                    applyListing(listing); notifications = feed
                    finishSearch(); notificationTab = openedFromFeed; showSettings = false; showTaskComposer = false
                    showCreateGroup = false; showLicenses = false; selectedChangesWorkspace = null
                    selectedWorkspace = workspace; selectedTerminal = terminal; selectedBrowser = browser
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
        if (!routeSignedIn) { workspaceRoute = null; return@LaunchedEffect }
        val mac = store.pairedMacs().singleOrNull { it.origin == route.origin }
        if (mac == null) {
            error = "This workspace's saved Mac is no longer available."
            workspaceRoute = null
            return@LaunchedEffect
        }
        if (routePairingCode != mac.code) {
            store.update { it.put("pairing_code", mac.code) }
            selectedWorkspace = null; selectedTerminal = null; selectedBrowser = null; selectedChangesWorkspace = null
            code = mac.code
            return@LaunchedEffect
        }
        val active = routeClient ?: return@LaunchedEffect
        if (routeConnectedCode != mac.code) return@LaunchedEffect
        fun isCurrent() = workspaceRoute?.id == route.id && signedIn && client === active && code == mac.code &&
            store.pairedMacs().contains(mac)
        try {
            val listing = active.workspaces()
            if (!isCurrent()) return@LaunchedEffect
            val workspace = parseWorkspaces(listing).singleOrNull { it.id == route.workspaceId }
                ?: error("This workspace is no longer available on ${mac.name}.")
            val terminal = if (route.browserId != null || route.changes) null else if (route.terminalId != null)
                workspace.terminals.singleOrNull { it.id == route.terminalId } else workspace.terminals.firstOrNull()
            val browser = if (route.changes || route.terminalId != null) null else if (route.browserId != null)
                workspace.browsers.singleOrNull { it.id == route.browserId } else if (terminal == null) workspace.browsers.firstOrNull() else null
            check(route.changes || terminal != null || browser != null) { "This workspace pane is no longer available." }
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (isCurrent()) {
                    applyListing(listing); finishSearch(); notificationTab = false
                    showSettings = false; showTaskComposer = false
                    selectedWorkspace = workspace; selectedTerminal = terminal; selectedBrowser = browser
                    selectedChangesWorkspace = if (route.changes) workspace else null
                    error = null; workspaceRoute = null
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (isCurrent()) { error = failure.message ?: "Could not open this workspace"; workspaceRoute = null }
        }
    }

    LaunchedEffect(signedIn, code, retry) {
        connectionReady = false
        client?.close(); client = null; connectedCode = null
        if (!signedIn || code.isBlank()) return@LaunchedEffect
        val requestedCode = code
        busy = true
        try {
            val pairing = PairingCodeParser.parse(requestedCode).getOrThrow()
            val active = connection.connectPairing(pairing, account)
            try {
                val status = active.hostStatus()
                require(status.optString("mac_device_id").isNotBlank()) { "The Mac did not provide its device identity." }
                store.pairedMacs().firstOrNull { it.code == requestedCode }?.requireMatchingHost(status)
                val displayName = status.optString("mac_display_name").ifBlank { "cmux" }
                val capabilities = status.optJSONArray("capabilities")?.let { values ->
                    (0 until values.length()).mapNotNull { index ->
                        values.optString(index).takeIf { it.isNotBlank() }
                    }.toSet()
                } ?: emptySet()
                val listing = active.workspaces()
                val feed = try { parseNotifications(active.notifications()) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        emptyList()
                    }
                ensureActive()
                if (code != requestedCode || !signedIn) throw CancellationException("Connection changed")
                store.rememberMac(requestedCode, status.optString("mac_device_id"), displayName,
                    status.optString("mac_instance_tag").takeIf { !status.isNull("mac_instance_tag") && it.isNotBlank() })
                hostName = displayName; hostCapabilities = capabilities
                terminalTransport = TerminalTransport.resolve(capabilities, status.optString("terminal_fidelity"))
                applyListing(listing); notifications = feed
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
                runCatching { active.workspaces() }
                    .onSuccess { applyListing(it); connectionError = null }
                    .onFailure { connectionError = nativeConnectionFailure(it) }
            }
        }
    }

    LaunchedEffect(client, selectedTerminal) {
        val active = client ?: return@LaunchedEffect
        if (selectedTerminal != null) return@LaunchedEffect
        while (true) {
            runCatching { active.workspaces() }.onSuccess { applyListing(it); connectionError = null }
                .onFailure { connectionError = nativeConnectionFailure(it) }
            runCatching { active.notifications() }.onSuccess { notifications = parseNotifications(it) }
                .onFailure { connectionError = nativeConnectionFailure(it) }
            delay(5_000)
        }
    }

    LaunchedEffect(client, selectedWorkspace?.id, selectedTerminal?.id, terminalColumns, terminalRows, terminalTransport) {
        val active = client ?: return@LaunchedEffect
        val workspace = selectedWorkspace ?: return@LaunchedEffect
        val terminal = selectedTerminal ?: return@LaunchedEffect
        val requestedViewport = terminalViewport ?: return@LaunchedEffect
        val generation = ++replayGeneration
        val viewportGeneration = ++viewportRequestGeneration
        val transport = terminalTransport
        val mirror = TerminalStreamMirror(terminal.id, transport, requestedViewport)
        val replayRecovery = TerminalReplayRecovery()
        // Keep the last painted frame while this viewport gets a fresh replay.
        // The display state above resets for a different terminal or connection;
        // protocol cursors and parser state always belong to this new mirror.
        scrollOffset = 0
        var replayRunning = false
        var replayAgain = false
        var recoveryFailed = false
        var subscriptionReady = false
        var nativeOutput: TerminalOutputLaneOwner? = null
        val subscriptionId = java.util.UUID.randomUUID().toString()
        fun publish() {
            val next = mirror.display
            if (next.columns <= 0 || next.rows <= 0) return
            grid = next
            gridRevision++
            if (grid.activeScreen == "alternate") scrollOffset = 0
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
            val response = active.terminalScroll(workspace.id, terminal.id, delivery)
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
                        }, resync = ::requestReplay)
                    outputInput = nativeOutput
                    nativeOutput?.resume()
                }
                terminalClick = { cell ->
                    if (isCurrent() && scrollOffset == 0) launch {
                        try { if (isCurrent()) active.terminalClick(workspace.id, terminal.id, cell) }
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
                            val next = (scrollOffset.toLong() + lines.toLong()).coerceIn(0, mirror.historyLineCount.toLong()).toInt()
                            moved = next != scrollOffset; scrollOffset = next
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
            nativeOutput?.close()
            if (outputInput === nativeOutput) outputInput = null
            scrollQueue.close()
            if (generation == replayGeneration) { terminalClick = null; terminalScroll = null; cancelQueuedScroll = null }
            eventJob.cancel()
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
        onDispose { disposableClient?.close() }
    }
    BackHandler(enabled = signedIn && code.isNotBlank() && searchState.active != null && selectedTerminal == null &&
        selectedBrowser == null && selectedChangesWorkspace == null && !showSettings && !showTaskComposer) { finishSearch(cancel = true) }
    BackHandler(enabled = workspaceRoute != null && selectedTerminal == null && selectedBrowser == null) { workspaceRoute = null }
    BackHandler(enabled = selectedTerminal != null) { selectedTerminal = null; selectedWorkspace = null }
    BackHandler(enabled = selectedBrowser != null) { selectedBrowser = null; selectedWorkspace = null }
    BackHandler(enabled = showSettings && selectedTerminal == null) { showSettings = false }

    if (showLicenses) OpenSourceLicensesDialog { showLicenses = false }

    val proposedCode = pendingPairingCode
    if (signedIn && proposedCode != null) {
        val proposed = PairingCodeParser.parse(proposedCode).getOrNull() as? PairingCode.Tailscale
        if (proposed != null) AlertDialog(
            onDismissRequest = { pendingPairingCode = null },
            title = { Text("Connect to this Mac?") },
            text = {
                Column {
                    Text("cmux will send your account session to this Mac over Tailscale.")
                    Spacer(Modifier.height(10.dp))
                    proposed.routes.forEach { route ->
                        Text("${route.host}:${route.port}", color = nativeAccent)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Continue only if this address came from your Mac’s pairing QR.",
                        color = nativeMuted)
                }
            },
            confirmButton = { TextButton(onClick = {
                code = proposedCode
                pendingPairingCode = null
            }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { pendingPairingCode = null }) { Text("Cancel") } }
        )
    }
    if (showCreateGroup) AlertDialog(
        onDismissRequest = { showCreateGroup = false },
        title = { Text("New group") },
        text = { OutlinedTextField(newGroupName, { newGroupName = it },
            label = { Text("Name (optional)") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val active = client
            showCreateGroup = false
            if (active != null) scope.launch {
                runCatching { active.createGroup(newGroupName); active.workspaces() }
                    .onSuccess { applyListing(it); refreshFeed(); newGroupName = ""; error = null }
                    .onFailure { error = it.message }
            }
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("Cancel") } }
    )

    Column(Modifier.fillMaxSize().background(nativePage).statusBarsPadding().navigationBarsPadding().imePadding()) {
        when {
            !signedIn -> NativeSignIn(account::sendCode, account::signIn, onUseHelper,
                onLicenses = { showLicenses = true }, onSignedIn = { signedIn = true; error = null })
            showSettings -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSettings = false }) { Text("‹  Back") }
                    Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
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
                            selectedTerminal = null; selectedWorkspace = null
                        } catch (failure: Exception) { if (failure is CancellationException) throw failure }
                    }
                }, onCreate = { name ->
                    try {
                        accountTeams.create(name)
                        client?.close(); client = null; code = ""
                        selectedTerminal = null; selectedWorkspace = null
                        true
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        accountTeams.state.value.createdTeam != null
                    }
                })
                Text("COMPUTERS", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = nativeMuted, fontSize = 11.sp)
                pairedMacs.forEach { mac ->
                    Row(Modifier.fillMaxWidth().clickable { code = mac.code; showSettings = false }
                        .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("◉", color = nativeAccent, fontSize = 20.sp)
                        Spacer(Modifier.width(14.dp))
                        Text(mac.name.ifBlank { "cmux" }, Modifier.weight(1f))
                        NativeSavedComputerDetailsButton(sharedConnections?.native, computerState, mac)
                        if (code == mac.code) Text(if (client != null) "Connected" else "Selected", color = nativeAccent, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = {
                    code = ""; showSettings = false; selectedTerminal = null; selectedWorkspace = null
                }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Find another Mac") }
                if (code.isNotBlank()) TextButton(onClick = {
                    store.forgetMac(code)
                    savedPairedMacs = store.pairedMacs()
                    code = store.load()?.optString("pairing_code").orEmpty()
                    showSettings = false
                }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Forget current Mac", color = Color(0xFFFF9999)) }
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = {
                    NativeNotificationService.setEnabled(context, false)
                    backgroundNotifications = false
                    drafts.clear()
                    accountTeams.clear(); account.signOut(); TaskDraftRepository.clearAttachments(context); signedIn = false; client?.close(); client = null
                },
                    modifier = Modifier.padding(horizontal = 14.dp)) { Text("Sign out") }
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
                TextButton(onClick = { showLicenses = true }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Open-source licenses") }
                NativeNetworkingSettings(sharedConnections?.native, computerState)
                NativeLegacyConnectionCheckSettings(client, pairedMacs, code, connectionReady)
                Text("DISPLAY", Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    color = nativeMuted, fontSize = 11.sp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Terminal text size", Modifier.weight(1f))
                    TextButton(onClick = {
                        terminalScale = (terminalScale - 0.125f).coerceAtLeast(0.75f)
                        displayPreferences.edit().putFloat("terminal_scale", terminalScale).apply()
                    }, enabled = terminalScale > 0.75f) { Text("A−") }
                    Text("${(terminalScale * 100).toInt()}%", color = nativeMuted, fontSize = 12.sp)
                    TextButton(onClick = {
                        terminalScale = (terminalScale + 0.125f).coerceAtMost(1.5f)
                        displayPreferences.edit().putFloat("terminal_scale", terminalScale).apply()
                    }, enabled = terminalScale < 1.5f) { Text("A+") }
                }
                Text("CONNECTION", Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    color = nativeMuted, fontSize = 11.sp)
                Text("$hostName · ${if (client != null) "Connected" else "Disconnected"}",
                    Modifier.padding(horizontal = 22.dp), color = nativeMuted, fontSize = 13.sp)
                TextButton(onClick = onUseHelper, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Text("Use existing helper connection", color = nativeMuted)
                }
                NativeLocalResetSection()
                }
            }
            showTaskComposer -> {
                val repository = taskDraftRepository
                val restored = taskDraftEntries[taskDraftId]
                val restoredMac = restored?.let { draft -> pairedMacs.firstOrNull { it.origin == draft.origin } }
                LaunchedEffect(restored?.origin, pairedMacs) {
                    if (restoredMac != null && code != restoredMac.code) selectComputer(restoredMac)
                }
                val taskMac = pairedMacs.firstOrNull { it.code == code }
                val taskOrigin = restored?.origin ?: taskMac?.origin ?: pairingOrigin(code)
                val selectedTaskMac = pairedMacs.firstOrNull { it.origin == taskOrigin }
                val taskCode = selectedTaskMac?.code
                val taskConnected = connectionReady && taskCode != null && connectedCode == taskCode && code == taskCode
                val active = client.takeIf { taskConnected }
                val taskWorkspaces = if (taskMac?.origin == taskOrigin) workspaces else emptyList()
                if (repository != null) key(taskDraftId, repository.session) {
                NativeTaskComposerView(active,
                    directories = preferredTaskDirectories(taskWorkspaces, selectedWorkspace?.id),
                    origin = taskOrigin,
                    models = feedSession.taskModels,
                    onCreated = { response ->
                        val result = TaskCreationResult.parse(response)
                        workspaces = result.merge(workspaces)
                        // Like iOS, partial create responses cannot replace group metadata.
                        refreshFeed()
                        val created = result.created
                        showTaskComposer = false
                        selectedWorkspace = created
                        selectedBrowser = null
                        selectedTerminal = created.terminals.firstOrNull {
                            it.id == response.optString("created_terminal_id")
                        } ?: created.terminals.firstOrNull()
                    }, onBack = { showTaskComposer = false },
                    isCurrent = { signedIn && taskDraftRepository === repository },
                    savedDrafts = repository.drafts, draftId = taskDraftId,
                    macName = selectedTaskMac?.name ?: restored?.macName ?: "Choose a Mac",
                    hasSelectedMac = selectedTaskMac != null,
                    resolvedMacOrigin = taskMac?.origin.takeIf { selectedTaskMac == null && taskOrigin == pairingOrigin(code) },
                    savedTemplates = repository.templates, persistTemplateChange = repository::updateTemplates,
                    attachmentRepository = repository, supportsAttachments = taskMac?.origin == taskOrigin && ComposerAttachment.FILE_CAPABILITY in hostCapabilities,
                    macs = pairedMacs, workspaceGroups = if (taskMac?.origin == taskOrigin) groups else emptyList(),
                    supportsGroups = if (taskConnected) "workspace.create_in_group.v1" in hostCapabilities else null,
                    groupsLoaded = taskConnected && taskGroupsLoaded,
                    groupIsCurrent = { group -> group == null || (connectionReady && connectedCode == taskCode && code == taskCode && "workspace.create_in_group.v1" in hostCapabilities &&
                        taskGroupsLoaded && groups.count { it.id == group } == 1) },
                    directoryWorkspaces = taskWorkspaces, selectedWorkspaceId = selectedWorkspace?.id,
                    selectMac = { editor, nextOrigin ->
                        val target = requireNotNull(pairedMacs.singleOrNull { it.origin == nextOrigin }) { "This Mac is no longer paired" }
                        val snapshot = workspaceSources.firstOrNull { it.mac.origin == nextOrigin && it.availability == NativeFeedAvailability.CONNECTED }
                        val currentDraft = checkNotNull(repository.drafts.state.value[editor.id])
                        val templates = repository.templates.state.value
                        val nextDirectory = templates.suggestedDirectory(templates.selected(currentDraft.templateId), nextOrigin,
                            snapshot?.let { preferredTaskDirectories(it.workspaces, null).firstOrNull() })
                        repository.selectMac(editor, nextOrigin, target.name, nextDirectory)
                        check(signedIn && taskDraftRepository === repository && pairedMacs.any { it.origin == nextOrigin }) { "Task account or Mac changed" }
                        selectComputer(target)
                    },
                    persistDrafts = repository::persistNow, flushDrafts = repository::flush,
                    onResumeDraft = { draft ->
                        taskDraftId = draft.id
                        pairedMacs.firstOrNull { it.origin == draft.origin }?.let(::selectComputer)
                    }, onNewDraft = { newTaskDraft() },
                    supportsTaskCreation = if (taskConnected) "workspace.task_create.v1" in hostCapabilities else null,
                    refreshWorkspaces = {
                        val listing = checkNotNull(active) { "That Mac is not connected" }.workspaces()
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
            code.isBlank() -> NativeComputerPicker(teamState, computerState, runtime = sharedConnections?.native,
                hasSavedComputers = pairedMacs.isNotEmpty(),
                onSelect = { mac -> computerState.account?.let { code = PairingCodeParser.computer(mac, it) } },
                onSettings = { workspaceRoute = null; finishSearch(); showSettings = true },
                onRefresh = { scope.launch {
                    try { accountTeams.refresh(); sharedConnections?.native?.refresh() }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure }
                } }, onPairing = ::proposePairing, onNewTask = ::newTaskDraft,
                onUseHelper = onUseHelper, onLicenses = { showLicenses = true }, onError = { error = it })
            selectedTerminal != null -> {
                val terminal = selectedTerminal!!
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedTerminal = null; selectedWorkspace = null }, modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) {
                        Text("‹  ${workspaces.size}", color = nativeAccent)
                    }
                    var terminalMenu by remember(terminal.id) { mutableStateOf(false) }
                    // Reserve both navigation controls before measuring a command/title.
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(terminal.title.ifBlank { selectedWorkspace?.title ?: "Terminal" } + " ▾",
                            Modifier.clickable { terminalMenu = true }.background(nativePanel, RoundedCornerShape(18.dp))
                                .padding(horizontal = 15.dp, vertical = 7.dp),
                            fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        DropdownMenu(expanded = terminalMenu, onDismissRequest = { terminalMenu = false }) {
                            DropdownMenuItem(text = { Text("View as Text") }, onClick = {
                                terminalMenu = false; openTerminalText()
                            })
                            if ("terminal.artifact.v1" in hostCapabilities) DropdownMenuItem(text = { Text("Files") }, onClick = {
                                inputModifiers = TerminalInputModifiers()
                                terminalMenu = false; stopTerminalScrolling(); softwareKeyboard?.hide(); showTerminalFiles = true
                            }, enabled = connectionReady)
                        }
                    }
                    TextButton(onClick = {
                        inputModifiers = TerminalInputModifiers()
                        if (directTyping) rawKeyboardView?.finishComposition()
                        directTyping = !directTyping
                    }) { Text(if (directTyping) "Compose" else "Keyboard", color = nativeAccent, fontSize = 12.sp) }
                }
                selectedWorkspace?.terminals?.takeIf { it.size > 1 }?.let { terminals ->
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        terminals.forEach { item ->
                            TextButton(onClick = { selectedTerminal = item }) {
                                Text(item.title.ifBlank { "Terminal" },
                                    color = if (item.id == terminal.id) nativeAccent else nativeMuted,
                                    fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
                val currentGrid = grid
                val visibleArtifactScroll by rememberUpdatedState(scrollOffset)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                RenderGridView(currentGrid, terminalCells, gridRevision,
                    Modifier.fillMaxSize()
                        .onSizeChanged { terminalViewportPixels = it }
                        .focusRequester(terminalFocusRequester)
                        .onPreviewKeyEvent { event -> directHardware(event.nativeKeyEvent) }
                        .focusable()
                        .semantics(mergeDescendants = true) {
                            onClick("Open keyboard") { directTyping = true; rawKeyboardView?.showKeyboard(); true }
                            customActions = listOf(CustomAccessibilityAction("View as Text") { openTerminalText(); true })
                        }
                        .pointerInput(terminal.id, currentGrid, terminalCells, artifactRpc, artifactsReady, artifactTapController) {
                            detectTapGestures(onTap = { point ->
                                artifactTapController.invalidate()
                                var handlingArtifact = false
                                TerminalGeometry.fit(size.width.toFloat(), size.height.toFloat(),
                                    currentGrid.columns, currentGrid.rows, terminalCells)?.let { geometry ->
                                    val cell = geometry.cell(point.x, point.y)
                                    val path = if (artifactsReady && geometry.contains(point.x, point.y)) TerminalArtifactHitTest.path(
                                        RenderGrid.plainText(currentGrid.visibleLines(visibleArtifactScroll)), cell.column, cell.row, currentGrid.columns) else null
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
                                                    TerminalArtifactHitTest.path(RenderGrid.plainText(currentGrid.visibleLines(visibleArtifactScroll)), cell.column, cell.row, columns) == path
                                            },
                                            open = {
                                                stopTerminalScrolling(); directTyping = false; softwareKeyboard?.hide()
                                                terminalArtifactPath = path
                                            },
                                            focus = { sendClick ->
                                                if (sendClick) terminalClick?.invoke(cell)
                                                directTyping = true; rawKeyboardView?.showKeyboard()
                                            })
                                    } else terminalClick?.invoke(cell)
                                }
                                if (!handlingArtifact) { directTyping = true; rawKeyboardView?.showKeyboard() }
                            }, onLongPress = { artifactTapController.invalidate(); openTerminalText() })
                        }
                        .terminalScrollGestures(terminalMotion,
                            TerminalGeometry.fit(terminalViewportPixels.width.toFloat(), terminalViewportPixels.height.toFloat(),
                                currentGrid.columns, currentGrid.rows, terminalCells),
                            replayGeneration, currentGrid.activeScreen,
                            linePath = !(terminalTransport.screenAnchor && currentGrid.activeScreen == "primary"),
                            enabled = terminalScroll != null,
                            onScroll = { lines, cell -> terminalScroll?.invoke(lines, cell) ?: false }), scrollOffset = scrollOffset.coerceAtMost(currentGrid.historyLineCount))
                if (scrollOffset > 0) Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(nativePanel, RoundedCornerShape(14.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Scrollback · $scrollOffset rows", color = nativeMuted, fontSize = 12.sp)
                    TextButton(onClick = { stopTerminalScrolling(); scrollOffset = 0 }) { Text("Latest") }
                }
                artifactChipCount?.let { count ->
                    TerminalArtifactChip(count, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (scrollOffset > 0) 62.dp else 10.dp)) {
                        inputModifiers = TerminalInputModifiers()
                        stopTerminalScrolling(); softwareKeyboard?.hide(); showTerminalFiles = true
                    }
                }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).background(nativePanel),
                    verticalAlignment = Alignment.CenterVertically) {
                    TerminalInputModifiers.Key.entries.forEach { key ->
                        val armed = inputModifiers.armed == key
                        val locked = armed && inputModifiers.sticky
                        TextButton(onClick = {
                            inputModifiers = inputModifiers.tap(key, android.os.SystemClock.uptimeMillis())
                        }, modifier = Modifier.semantics {
                            stateDescription = if (locked) "Locked" else if (armed) "Armed" else "Off"
                        }, shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (armed) nativeAccent else Color.Transparent,
                                contentColor = if (armed) Color.Black else nativeMuted),
                            border = if (locked) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null
                        ) { Text(key.label, fontWeight = if (locked) FontWeight.Bold else FontWeight.Normal) }
                    }
                    if (!directTyping) TextButton(onClick = { sendComposer(submit = false) },
                        enabled = client != null && terminalDraft.operation == null && !preparingAttachments &&
                            (terminalDraft.text.isNotEmpty() || terminalDraft.attachments.isNotEmpty())) { Text("Insert", color = nativeMuted) }
                    listOf("Esc" to "Esc", "Tab" to "Tab", "⌫" to "Backspace",
                        "⌦" to "Delete", "↵" to "Enter", "↑" to "Up", "↓" to "Down",
                        "←" to "Left", "→" to "Right", "Home" to "Home", "End" to "End",
                        "Pg↑" to "PageUp", "Pg↓" to "PageDown", "^C" to "CtrlC",
                        "^D" to "CtrlD", "^Z" to "CtrlZ", "^L" to "CtrlL")
                        .forEach { (label, key) ->
                            TextButton(onClick = {
                                rawKeyboardView?.finishComposition()
                                val sequence = inputModifiers.special(key, currentGrid.applicationCursorKeys)
                                inputModifiers = inputModifiers.consume()
                                scrollOffset = 0
                                queueInput(sequence)
                            }) { Text(label, color = nativeMuted) }
                        }
                    TextButton(onClick = ::pasteClipboard) { Text("Paste", color = nativeMuted) }

                }
                inputStatus.error?.let { message ->
                    Row(Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = Color(0xFFFFAAAA), fontSize = 12.sp)
                        TextButton(onClick = { if (inputQueue.resume()) rawKeyboardView?.showKeyboard() }) { Text("Resume typing") }
                    }
                }
                if (directTyping) {
                    key(draftTarget, client) {
                        AndroidView(factory = { viewContext ->
                            TerminalKeyboardView(viewContext).also { rawKeyboardView = it }
                        }, update = { view ->
                            val enabled = client != null && inputStatus.error == null && terminalDraft.operation == null
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
                val active = client
                val browser = selectedBrowser!!
                if (active != null) NativeBrowserView(
                    client = active, panelId = browser.id, title = browser.title,
                    onBack = { selectedBrowser = null; selectedWorkspace = null }
                )
                else {
                    BackHandler { selectedBrowser = null; selectedWorkspace = null }
                    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { selectedBrowser = null; selectedWorkspace = null }) { Text("‹  Workspaces") }
                        Text(browser.title.ifBlank { "Browser" }, style = MaterialTheme.typography.titleMedium)
                        Text(if (busy) "Reconnecting to your Mac…" else "Browser disconnected", color = nativeMuted)
                        connectionError?.let { Text(it, color = Color(0xFFFF9999)) }
                        TextButton(onClick = { retry++ }, enabled = !busy) { Text("Reconnect") }
                    }
                }
            }
            selectedChangesWorkspace != null -> {
                val active = client
                val workspace = selectedChangesWorkspace!!
                if (active != null) NativeChangesView(active, workspace.id, workspace.title,
                    onBack = { selectedChangesWorkspace = null })
                else {
                    BackHandler { selectedChangesWorkspace = null }
                    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { selectedChangesWorkspace = null }) { Text("‹  Workspaces") }
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
                    Box {
                        IconButton(onClick = { computerMenuOpen = true }, modifier = Modifier.semantics {
                            stateDescription = selectedComputer?.name ?: "All Computers"
                        }) {
                            Icon(painterResource(R.drawable.ic_feed_computer), "Computer filter",
                                tint = if (selectedComputer == null) nativeMuted else nativeAccent,
                                modifier = Modifier.size(22.dp))
                        }
                        DropdownMenu(computerMenuOpen, onDismissRequest = { computerMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("All Computers") },
                                onClick = { selectComputer(null) },
                                leadingIcon = { Text(if (selectedComputer == null) "✓" else " ") })
                            pairedMacs.forEach { mac ->
                                DropdownMenuItem(text = { Text(mac.name.ifBlank { "cmux" }) }, onClick = {
                                    selectComputer(mac)
                                }, leadingIcon = { Text(if (selectedOrigin == mac.origin) "✓" else " ") })
                            }
                            DropdownMenuItem(text = { Text("Pair another Mac") }, onClick = {
                                computerMenuOpen = false; code = ""
                            })
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (notificationTab) "Notifications" else "Workspaces", fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp)
                        Text(selectedComputer?.name ?: "All Computers", color = nativeMuted,
                            fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (notificationTab) {
                        if (feedEntries.any { !it.notification.isRead }) IconButton(
                            onClick = {
                                pendingReadAllOrigin = selectedOrigin
                                pendingReadAllComputer = selectedComputer?.name ?: "All Computers"
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
                                    if (active != null) scope.launch { runCatching { active.request("workspace.create") }
                                        .onSuccess { response ->
                                            applyListing(response); refreshFeed(); notificationTab = false; error = null
                                            val created = workspaces.firstOrNull {
                                                it.id == response.optString("created_workspace_id")
                                            }
                                            if (created != null) {
                                                selectedWorkspace = created
                                                selectedTerminal = created.terminals.firstOrNull {
                                                    it.id == response.optString("created_terminal_id")
                                                } ?: created.terminals.firstOrNull()
                                            }
                                        }
                                        .onFailure { error = it.message } }
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
                                    Text("${source.mac.name} · ${if (source.availability == NativeFeedAvailability.CONNECTING) "Connecting…" else "Unavailable"}",
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
                            fun open(terminalId: String? = null, browserId: String? = null, changes: Boolean = false) {
                                inAppNotification = null
                                workspaceRoute = NativeWorkspaceRoute(owner.mac.origin, workspace.id, terminalId, browserId, changes)
                            }
                            Column(Modifier.padding(start = if (entry.indented) 18.dp else 0.dp)
                                .semantics { contentDescription = "${workspace.title} on ${owner.mac.name}" }) {
                            NativeWorkspaceRow(
                                workspace = workspace, groups = owner.groups,
                                computer = owner.mac.name.takeIf { selectedOrigin == null && pairedMacs.size > 1 },
                                canMove = canReorder && (owner.groups.none { it.liveAnchorWorkspaceId == workspace.id }),
                                onOpen = { open() },
                                onAction = { action, title ->
                                    if (action == "changes") open(changes = true)
                                    else if (action.startsWith("move:")) {
                                        val target = action.removePrefix("move:").takeIf { it.isNotBlank() }
                                        move(owner, workspace.id, NativeWorkspaceMove(target, null))
                                    } else scope.launch {
                                        try {
                                            val response = feedCoordinator.workspaceAction(owner.mac, workspace.id, action, title)
                                            error = null
                                            if (action == "terminal.create") {
                                                val id = response.optString("created_terminal_id")
                                                check(id.isNotBlank()) { "Mac did not return the created terminal." }
                                                open(terminalId = id)
                                            } else if (action == "browser.create") {
                                                val id = response.optString("panel_id")
                                                check(id.isNotBlank()) { "Mac did not return the created browser." }
                                                open(browserId = id)
                                            }
                                        } catch (failure: Exception) {
                                            if (failure is CancellationException) throw failure
                                            error = failure.message
                                        }
                                    }
                                }
                            )
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
    onOpen: () -> Unit,
    onAction: (String, String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var title by remember(workspace.id) { mutableStateOf(workspace.title) }
    Row(Modifier.fillMaxWidth().clickable(enabled = workspace.terminals.isNotEmpty() || workspace.browsers.isNotEmpty(), onClick = onOpen)
        .semantics {
            stateDescription = listOfNotNull("Pinned".takeIf { workspace.isPinned },
                workspace.unreadState.accessibilityLabel.takeIf { it.isNotEmpty() }).joinToString(", ")
        }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        NativeUnreadGutter(workspace.unreadState)
        val colors = listOf(Color(0xFFFFB52E), Color(0xFF58CFA2), Color(0xFF83B9FF), Color(0xFFFF8E80))
        val accent = runCatching { android.graphics.Color.parseColor(workspace.color) }
            .getOrNull()?.let { Color(it) } ?: colors[(workspace.id.hashCode() and Int.MAX_VALUE) % colors.size]
        Box(Modifier.size(37.dp).background(accent, CircleShape),
            contentAlignment = Alignment.Center) { Text("›", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
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
    hasSavedComputers: Boolean, onSelect: (IrohV2Computer) -> Unit, onSettings: () -> Unit,
    onRefresh: () -> Unit, onPairing: (String) -> Unit, onNewTask: () -> Unit,
    onUseHelper: () -> Unit, onLicenses: () -> Unit, onError: (String?) -> Unit
) {
    val context = LocalContext.current
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
                    Text("◉", color = nativeAccent, fontSize = 22.sp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(mac.name.ifBlank { "Mac" }, fontWeight = FontWeight.Medium)
                        Text("Available", color = nativeMuted, fontSize = 12.sp)
                    }
                    NativeComputerDetailsButton(runtime, computerState, NativeComputerTarget.from(mac))
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
