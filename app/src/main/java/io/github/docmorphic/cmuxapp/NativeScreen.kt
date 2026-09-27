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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.platform.LocalClipboardManager
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
    val connection = remember(context, connector) { connector ?: TailscaleConnector(context.applicationContext) }
    val clipboard = LocalClipboardManager.current
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
    var effectiveTerminalViewport by remember { mutableStateOf<TerminalViewport?>(null) }
    var viewportRequestGeneration by remember { mutableLongStateOf(0L) }
    val store = remember(context) { NativeCredentialStore(context.applicationContext) }
    val account = remember(store) { NativeAccount(store) }
    val scope = rememberCoroutineScope()
    val terminalFocusRequester = remember { FocusRequester() }
    var signedIn by remember { mutableStateOf(account.isSignedIn()) }
    var code by remember { mutableStateOf(store.load()?.optString("pairing_code").orEmpty()) }
    var pairingText by remember { mutableStateOf("") }
    var pendingPairingCode by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectionError by remember(code) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var retryDelay by remember { mutableLongStateOf(2_000) }
    var client by remember { mutableStateOf<MobileRpcClient?>(null) }
    var hostName by remember(code) { mutableStateOf("cmux") }
    var hostCapabilities by remember(code) { mutableStateOf<Set<String>>(emptySet()) }
    var pairedMacs by remember { mutableStateOf(store.pairedMacs()) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showTaskComposer by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var backgroundNotifications by remember { mutableStateOf(NativeNotificationService.isEnabled(context)) }
    var workspaces by remember(code) { mutableStateOf<List<NativeWorkspace>>(emptyList()) }
    var groups by remember(code) { mutableStateOf<List<NativeGroup>>(emptyList()) }
    var locallyExpandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
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
    val notificationDelivery = remember(context) { NativeNotificationDelivery(context.applicationContext) }
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
    var selectedComputerOrigin by rememberSaveable(signedIn) {
        mutableStateOf(store.load()?.optString("computer_selection").orEmpty())
    }
    val selectedComputer = pairedMacs.firstOrNull { it.origin == selectedComputerOrigin }
    val selectedOrigin = selectedComputer?.origin
    fun selectComputer(mac: NativeCredentialStore.PairedMac?) {
        selectedComputerOrigin = mac?.origin.orEmpty()
        store.update { it.put("computer_selection", selectedComputerOrigin) }
        if (mac != null) code = mac.code
        computerMenuOpen = false
    }
    LaunchedEffect(pairedMacs, selectedComputerOrigin) {
        if (selectedComputerOrigin.isNotBlank() && selectedComputer == null) selectComputer(null)
    }
    val scopedFeedSources = remember(feedSources, selectedOrigin) {
        feedSources.values.filter { selectedOrigin == null || it.mac.origin == selectedOrigin }
    }
    val feedEntries = remember(feedSources, selectedOrigin) { aggregateNativeFeed(feedSources.values, selectedOrigin) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var feedForeground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> feedForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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
    val workspaceSearch = remember(workspaces, groups, hostName, searchLocale) {
        val groupNames = groups.associate { it.id to it.name }
        NativeSearchIndex(workspaces.map { workspace -> workspace.id to
            (listOf(workspace.title, workspace.description, workspace.directory, workspace.preview,
                hostName, groupNames[workspace.groupId]) + workspace.terminals.map { it.title }) }, searchLocale)
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
    var grid by remember(draftTarget) { mutableStateOf<TerminalDisplay>(RenderGrid()) }
    var gridRevision by remember { mutableIntStateOf(0) }
    var replayGeneration by remember { mutableIntStateOf(0) }
    var terminalTransport by remember { mutableStateOf(TerminalTransport.resolve(emptySet())) }
    var scrollOffset by remember { mutableIntStateOf(0) }
    var terminalClick by remember { mutableStateOf<((TerminalGeometry.Cell) -> Unit)?>(null) }
    var terminalScroll by remember { mutableStateOf<((Double, TerminalGeometry.Cell) -> Unit)?>(null) }
    var textSnapshot by remember(draftTarget, client) { mutableStateOf<TerminalTextSnapshot?>(null) }
    fun openTerminalText() { textSnapshot = TerminalTextSnapshot.capture(grid) }
    textSnapshot?.let { TerminalTextSheet(it) { textSnapshot = null } }

    var controlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var shiftArmed by remember { mutableStateOf(false) }
    var directTyping by remember(draftTarget) { mutableStateOf(false) }
    var rawKeyboardView by remember(draftTarget) { mutableStateOf<TerminalKeyboardView?>(null) }
    LaunchedEffect(directTyping, rawKeyboardView) {
        if (directTyping) rawKeyboardView?.let { view ->
            // Let the removed Compose editor finish its IME session before requesting the native editor.
            withFrameNanos { }
            view.showKeyboard()
        }
    }
    val hardwareInput = remember(draftTarget) { TerminalHardwareInput() }
    val inputClient = client
    val inputTarget = draftTarget
    val inputQueue = remember(inputClient, inputTarget) {
        TerminalInputQueue(scope) { entry ->
            check(inputClient != null && inputTarget != null && client === inputClient &&
                code == inputTarget.pairing && signedIn) { "Terminal connection changed" }
            if (entry.paste) inputClient.paste(inputTarget.workspace, inputTarget.surface, entry.text, submit = false)
            else inputClient.input(inputTarget.workspace, inputTarget.surface, entry.text)
        }
    }
    val inputStatus by inputQueue.status.collectAsState()
    DisposableEffect(inputQueue) { onDispose { inputQueue.close() } }

    fun queueInput(value: String, paste: Boolean = false): Boolean {
        val target = draftTarget ?: return false
        if (client == null || drafts.state.value[target]?.operation != null) return false
        scrollOffset = 0
        return inputQueue.offer(value, paste)
    }
    fun directText(value: String) {
        val text = value.replace("\r\n", "\r").replace('\n', '\r')
        queueInput(TerminalKeyEncoding.text(text, controlArmed, altArmed, shiftArmed))
        controlArmed = false; altArmed = false; shiftArmed = false
    }
    fun directHardware(event: AndroidKeyEvent): Boolean {
        val sequence = hardwareInput.sequence(event, grid.applicationCursorKeys, controlArmed, altArmed, shiftArmed) ?: return false
        if (sequence.isNotEmpty()) {
            queueInput(sequence)
            controlArmed = false; altArmed = false; shiftArmed = false
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

    fun sendComposer(submit: Boolean) {
        val target = draftTarget ?: return
        val active = client ?: return
        if (preparingAttachments) return
        val send = drafts.begin(target) ?: return
        val supportsFiles = ComposerAttachment.FILE_CAPABILITY in hostCapabilities
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
        if (value.has("groups")) groups = parseGroups(value)
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
                } else error = "This code uses Iroh. Ask cmux on your Mac to show its Tailscale QR."
            },
            onFailure = { error = it.message }
        )
    }

    LaunchedEffect(incomingCode) {
        if (incomingCode != null && incomingCode != code) proposePairing(incomingCode)
    }
    LaunchedEffect(incomingNotificationRoute) { if (incomingNotificationRoute != null) inAppNotification = null }
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

    LaunchedEffect(signedIn, code, retry) {
        client?.close(); client = null; connectedCode = null
        if (!signedIn || code.isBlank()) return@LaunchedEffect
        val requestedCode = code
        busy = true
        try {
            val pairing = PairingCodeParser.parse(requestedCode).getOrThrow()
            require(pairing is PairingCode.Tailscale) { "This cmux pairing code uses a transport this build cannot connect to yet" }
            val active = connection.connect(pairing, account)
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
                pairedMacs = store.pairedMacs()
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

    LaunchedEffect(client, selectedWorkspace?.id, selectedTerminal?.id, terminalColumns, terminalRows) {
        val active = client ?: return@LaunchedEffect
        val workspace = selectedWorkspace ?: return@LaunchedEffect
        val terminal = selectedTerminal ?: return@LaunchedEffect
        val requestedViewport = terminalViewport ?: return@LaunchedEffect
        val generation = ++replayGeneration
        val transport = terminalTransport
        val mirror = TerminalStreamMirror(terminal.id, transport, requestedViewport)
        grid = mirror.display; gridRevision++
        scrollOffset = 0
        effectiveTerminalViewport = null
        var replayRunning = false
        var replayAgain = false
        var recoveryFailed = false
        var subscriptionReady = false
        val subscriptionId = java.util.UUID.randomUUID().toString()
        fun publish() {
            grid = mirror.display
            gridRevision++
            if (grid.activeScreen == "alternate") scrollOffset = 0
        }
        suspend fun replayTerminal() {
            if (replayRunning || recoveryFailed) return
            replayRunning = true
            mirror.beginReplay()
            try {
                repeat(3) {
                    replayAgain = false
                    val size = effectiveTerminalViewport ?: requestedViewport
                    val snapshot = active.replay(workspace.id, terminal.id, size.columns, size.rows,
                        screenAnchor = transport.screenAnchor,
                        maxScrollbackRows = if (mirror.historyLineCount == 0) 10_000 else 0)
                    if (generation != replayGeneration || client !== active) return
                    val result = mirror.replay(snapshot)
                    publish()
                    if (result != TerminalStreamMirror.Result.REPLAY && !replayAgain) {
                        error = null
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
                selectedTerminal?.id == terminal.id) error = failure.message ?: "Terminal scroll failed"
        }, canSend = ::isCurrent) { delivery ->
            val response = active.terminalScroll(workspace.id, terminal.id, delivery)
            if (generation == replayGeneration && client === active && selectedWorkspace?.id == workspace.id &&
                selectedTerminal?.id == terminal.id && response.optJSONObject("render_grid") != null) {
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
            val viewportGeneration = ++viewportRequestGeneration
            runCatching {
                active.reportViewport(workspace.id, terminal.id, requestedViewport, viewportGeneration)
            }.onSuccess { response ->
                val columns = response.optInt("columns")
                val rows = response.optInt("rows")
                if (columns > 0 && rows > 0) effectiveTerminalViewport = TerminalViewport(columns, rows)
            }.onFailure {
                if (it is CancellationException) throw it
                error = it.message ?: "Terminal resize failed"
            }
            subscriptionReady = true
            replayTerminal()
            if (!recoveryFailed && isCurrent()) {
                terminalClick = { cell ->
                    if (isCurrent() && scrollOffset == 0) launch {
                        try { if (isCurrent()) active.terminalClick(workspace.id, terminal.id, cell) }
                        catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            if (isCurrent()) error = failure.message ?: "Terminal click failed"
                        }
                    }
                }
                terminalScroll = { lines, cell ->
                    if (isCurrent()) {
                        val primary = mirror.display.activeScreen == "primary"
                        // Raw-byte mirrors retain their own history; viewport-anchored grids
                        // instead receive the Mac's viewport after the scroll RPC.
                        if (primary && (transport.screenAnchor || transport.mode != TerminalOutputMode.GRID)) {
                            scrollOffset = (scrollOffset + lines.toInt()).coerceIn(0, mirror.historyLineCount)
                        }
                        if (!(transport.screenAnchor && primary)) scrollQueue.offer(lines, cell)
                    }
                }
            }
            eventJob.join()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Terminal subscription failed"
        } finally {
            scrollQueue.close()
            if (generation == replayGeneration) { terminalClick = null; terminalScroll = null }
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
                    .onSuccess { applyListing(it); newGroupName = ""; error = null }
                    .onFailure { error = it.message }
            }
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("Cancel") } }
    )

    Column(Modifier.fillMaxSize().background(nativePage).statusBarsPadding().navigationBarsPadding().imePadding()) {
        when {
            !signedIn -> {
                NativeHeader("Sign in to cmux")
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Use the same cmux account as your Mac.", color = nativeMuted)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        scope.launch { busy = true; runCatching { account.sendCode(email) }
                            .onSuccess { codeSent = true; error = null }
                            .onFailure { error = it.message }; busy = false }
                    }, enabled = !busy && email.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Email me a sign-in code") }
                    if (codeSent) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(otp, { otp = it }, Modifier.fillMaxWidth(), label = { Text("Code or link code") })
                        Button(onClick = {
                            scope.launch { busy = true; runCatching { account.signIn(otp) }
                                .onSuccess { signedIn = true; error = null }
                                .onFailure { error = it.message }; busy = false }
                        }, enabled = !busy && otp.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
                    }
                    TextButton(onClick = onUseHelper) { Text("Use existing helper connection") }
                    TextButton(onClick = { showLicenses = true }) { Text("Open-source licenses") }
                }
            }
            showSettings -> {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showSettings = false }) { Text("‹  Back") }
                    Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("COMPUTERS", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), color = nativeMuted, fontSize = 11.sp)
                pairedMacs.forEach { mac ->
                    Row(Modifier.fillMaxWidth().clickable { code = mac.code; showSettings = false }
                        .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("◉", color = nativeAccent, fontSize = 20.sp)
                        Spacer(Modifier.width(14.dp))
                        Text(mac.name.ifBlank { "cmux" }, Modifier.weight(1f))
                        if (code == mac.code) Text(if (client != null) "Connected" else "Selected", color = nativeAccent, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = {
                    code = ""; showSettings = false; selectedTerminal = null; selectedWorkspace = null
                }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Pair another Mac") }
                if (code.isNotBlank()) TextButton(onClick = {
                    store.forgetMac(code)
                    pairedMacs = store.pairedMacs()
                    code = store.load()?.optString("pairing_code").orEmpty()
                    showSettings = false
                }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Forget current Mac", color = Color(0xFFFF9999)) }
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = {
                    NativeNotificationService.setEnabled(context, false)
                    backgroundNotifications = false
                    drafts.clear()
                    account.signOut(); signedIn = false; client?.close(); client = null
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
                    }, enabled = signedIn && code.isNotBlank())
                }
                TextButton(onClick = { showLicenses = true }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Open-source licenses") }
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
                }
            }
            showTaskComposer -> {
                val active = client
                if (active != null) NativeTaskComposerView(active,
                    directories = workspaces.mapNotNull { it.directory },
                    onCreated = { response ->
                        applyListing(response)
                        val created = workspaces.firstOrNull {
                            it.id == response.optString("created_workspace_id")
                        }
                        showTaskComposer = false
                        if (created != null) {
                            selectedWorkspace = created
                            selectedTerminal = created.terminals.firstOrNull {
                                it.id == response.optString("created_terminal_id")
                            } ?: created.terminals.firstOrNull()
                        }
                    }, onBack = { showTaskComposer = false })
            }
            code.isBlank() -> {
                NativeHeader("Pair your Mac")
                Column(Modifier.padding(24.dp)) {
                    Text("Open cmux Mobile Pairing on your Mac and scan its QR code.", color = nativeMuted)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        runCatching {
                            GmsBarcodeScanning.getClient(context,
                                GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .enableAutoZoom().build()).startScan()
                        }.onSuccess { scan -> scan.addOnSuccessListener { barcode ->
                            val scanned = barcode.rawValue.orEmpty()
                            proposePairing(scanned)
                        }.addOnFailureListener { error = it.message } }
                            .onFailure { error = it.message ?: "Could not open the QR scanner" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Scan cmux QR code") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(pairingText, { pairingText = it }, Modifier.fillMaxWidth(), label = { Text("Or paste pairing code") })
                    Button(onClick = {
                        proposePairing(pairingText)
                    }, enabled = pairingText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Connect") }
                    TextButton(onClick = onUseHelper) { Text("Use existing helper connection") }
                    TextButton(onClick = { showLicenses = true }) { Text("Open-source licenses") }
                    if (pairedMacs.isNotEmpty()) TextButton(onClick = { finishSearch(); showSettings = true }) { Text("Saved computers") }
                }
            }
            selectedTerminal != null -> {
                val terminal = selectedTerminal!!
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedTerminal = null; selectedWorkspace = null }) {
                        Text("‹  ${workspaces.size}", color = nativeAccent)
                    }
                    Spacer(Modifier.weight(1f))
                    var terminalMenu by remember(terminal.id) { mutableStateOf(false) }
                    Box {
                        Text(terminal.title.ifBlank { selectedWorkspace?.title ?: "Terminal" } + " ▾",
                            Modifier.clickable { terminalMenu = true }.background(nativePanel, RoundedCornerShape(18.dp))
                                .padding(horizontal = 15.dp, vertical = 7.dp),
                            fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                        DropdownMenu(expanded = terminalMenu, onDismissRequest = { terminalMenu = false }) {
                            DropdownMenuItem(text = { Text("View as Text") }, onClick = {
                                terminalMenu = false; openTerminalText()
                            })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
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
                        .pointerInput(terminal.id, currentGrid, terminalCells) {
                            detectTapGestures(onTap = { point ->
                                TerminalGeometry.fit(size.width.toFloat(), size.height.toFloat(),
                                    currentGrid.columns, currentGrid.rows, terminalCells)?.let { geometry ->
                                    terminalClick?.invoke(geometry.cell(point.x, point.y))
                                }
                                directTyping = true
                                rawKeyboardView?.showKeyboard()
                            }, onLongPress = { openTerminalText() })
                        }
                        .pointerInput(terminal.id, currentGrid, terminalCells) {
                        var dragPixels = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragPixels = 0f },
                            onVerticalDrag = { change, amount ->
                                val geometry = TerminalGeometry.fit(size.width.toFloat(), size.height.toFloat(),
                                    currentGrid.columns, currentGrid.rows, terminalCells)
                                if (geometry != null) {
                                    dragPixels += amount
                                    val steps = (dragPixels / geometry.cellHeight).toInt()
                                    if (steps != 0) {
                                        terminalScroll?.invoke(steps.toDouble(), geometry.cell(change.position.x, change.position.y))
                                        dragPixels -= steps * geometry.cellHeight
                                    }
                                }
                                change.consume()
                            }
                        )
                    }, scrollOffset = scrollOffset.coerceAtMost(currentGrid.historyLineCount))
                if (scrollOffset > 0) Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(nativePanel, RoundedCornerShape(14.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Scrollback · $scrollOffset rows", color = nativeMuted, fontSize = 12.sp)
                    TextButton(onClick = { scrollOffset = 0 }) { Text("Latest") }
                }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).background(nativePanel),
                    verticalAlignment = Alignment.CenterVertically) {
                    listOf("Ctrl" to controlArmed, "Alt" to altArmed, "Shift" to shiftArmed)
                        .forEach { (label, armed) ->
                            TextButton(onClick = {
                                when (label) {
                                    "Ctrl" -> controlArmed = !controlArmed
                                    "Alt" -> altArmed = !altArmed
                                    else -> shiftArmed = !shiftArmed
                                }
                            }) { Text(label, color = if (armed) nativeAccent else nativeMuted) }
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
                                val sequence = when (key) {
                                    "CtrlC" -> TerminalKeyEncoding.encode("c", control = true)
                                    "CtrlD" -> TerminalKeyEncoding.encode("d", control = true)
                                    "CtrlZ" -> TerminalKeyEncoding.encode("z", control = true)
                                    "CtrlL" -> TerminalKeyEncoding.encode("l", control = true)
                                    else -> TerminalKeyEncoding.encode(key, controlArmed, altArmed,
                                        shiftArmed, currentGrid.applicationCursorKeys)
                                }
                                controlArmed = false; altArmed = false; shiftArmed = false
                                scrollOffset = 0
                                queueInput(sequence)
                            }) { Text(label, color = nativeMuted) }
                        }
                    TextButton(onClick = {
                        val pasted = clipboard.getText()?.text.orEmpty()
                        rawKeyboardView?.finishComposition()
                        if (pasted.isNotEmpty()) queueInput(pasted, paste = true)
                    }) { Text("Paste", color = nativeMuted) }

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
                            view.onKey = ::directHardware
                            view.onPaste = { queueInput(it, paste = true) }
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
                    viewportWidth = configuration.screenWidthDp.coerceAtLeast(240),
                    viewportHeight = (configuration.screenHeightDp - 180).coerceAtLeast(240),
                    viewportScale = context.resources.displayMetrics.density.toDouble(),
                    onBack = { selectedBrowser = null; selectedWorkspace = null }
                )
            }
            selectedChangesWorkspace != null -> {
                val active = client
                val workspace = selectedChangesWorkspace!!
                if (active != null) NativeChangesView(active, workspace.id, workspace.title,
                    onBack = { selectedChangesWorkspace = null })
            }
            else -> {
                Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finishSearch(); showSettings = true }) {
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
                            if (notificationTab) DropdownMenuItem(text = { Text("All Computers") },
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
                        if (notificationTab) Text(selectedComputer?.name ?: "All Computers", color = nativeMuted,
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
                            TextButton(onClick = { createMenuOpen = true }, enabled = client != null) {
                                Text("+", color = nativeAccent, fontSize = 25.sp)
                            }
                            DropdownMenu(createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("New workspace") }, onClick = {
                                    createMenuOpen = false
                                    val active = client
                                    if (active != null) scope.launch { runCatching { active.request("workspace.create") }
                                        .onSuccess { response ->
                                            applyListing(response); notificationTab = false; error = null
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
                                    createMenuOpen = false; finishSearch(); showTaskComposer = true
                                })
                                if ("workspace.group_create.v1" in hostCapabilities) {
                                    DropdownMenuItem(text = { Text("New group") }, onClick = {
                                        createMenuOpen = false; showCreateGroup = true
                                    })
                                }
                            }
                        }
                    }
                }
                if (busy && !notificationTab) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (client == null && !busy && !notificationTab) {
                    Column(Modifier.padding(horizontal = 18.dp)) {
                        Button(onClick = { retryDelay = 2_000; retry++ }) { Text("Retry connection") }
                        TextButton(onClick = { store.update { it.put("pairing_code", "") }; code = "" }) { Text("Pair a different Mac") }
                    }
                }
                if (notificationTab) {
                    NativeNotificationFeedView(feedProjection, scopedFeedSources, unreadNotificationsOnly,
                        notificationQuery.isNotBlank(), feedRefreshing, notificationNow, searchLocale, Modifier.weight(1f),
                        onOpen = { entry ->
                            inAppNotification = NotificationDestination(java.util.UUID.randomUUID().toString(),
                                entry.source.mac.origin, entry.notification.id, entry.notification.workspaceId,
                                entry.notification.surfaceId, entry.notification.retargetsToLiveSurfaceOwner)
                        }, onRead = ::setNotificationRead,
                        onToggle = { feedSession.projection = feedProjection.toggle(it) },
                        onMore = { feedRowWindow += 300 }, onRefresh = ::refreshFeed)
                } else {
                    val matches = remember(workspaceSearch, search) { workspaceSearch.matches(search) }
                    val matching = workspaces.filter { (!unreadWorkspacesOnly || it.hasUnread) && it.id in matches }
                    val entries = buildList<WorkspaceListEntry> {
                        if (groups.isEmpty() || search.isNotEmpty() || unreadWorkspacesOnly) matching.forEach { add(WorkspaceListEntry.Workspace(it)) }
                        else {
                            matching.filter { it.groupId == null || groups.none { group -> group.id == it.groupId } }
                                .forEach { add(WorkspaceListEntry.Workspace(it)) }
                            groups.forEach { group ->
                                val members = matching.filter { it.groupId == group.id }
                                if (members.isNotEmpty() || (search.isBlank() && !unreadWorkspacesOnly))
                                    add(WorkspaceListEntry.Header(group))
                                if (search.isNotBlank() || (group.isCollapsed == (group.id in locallyExpandedGroups)))
                                    members.forEach { add(WorkspaceListEntry.Workspace(it)) }
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 84.dp)) {
                        items(entries, key = {
                            when (it) {
                                is WorkspaceListEntry.Header -> "group:${it.group.id}"
                                is WorkspaceListEntry.Workspace -> "workspace:${it.workspace.id}"
                            }
                        }) { entry ->
                            if (entry is WorkspaceListEntry.Header) {
                                val group = entry.group
                                NativeGroupHeaderRow(group,
                                    expanded = group.isCollapsed == (group.id in locallyExpandedGroups),
                                    canEdit = "workspace.group_actions.v1" in hostCapabilities,
                                    onToggle = {
                                        locallyExpandedGroups = if (group.id in locallyExpandedGroups)
                                            locallyExpandedGroups - group.id else locallyExpandedGroups + group.id
                                    },
                                    onAction = { action, title ->
                                        val active = client
                                        if (active != null) scope.launch {
                                            runCatching { active.groupAction(group.id, action, title); active.workspaces() }
                                                .onSuccess { applyListing(it); error = null }
                                                .onFailure { error = it.message }
                                        }
                                    })
                                return@items
                            }
                            val workspace = (entry as WorkspaceListEntry.Workspace).workspace
                            NativeWorkspaceRow(
                                workspace = workspace,
                                groups = groups,
                                canMove = "workspace.move.v1" in hostCapabilities,
                                onOpen = {
                                    finishSearch()
                                    workspace.terminals.firstOrNull()?.let { terminal ->
                                        selectedWorkspace = workspace; selectedTerminal = terminal
                                    } ?: workspace.browsers.firstOrNull()?.let { browser ->
                                        selectedWorkspace = workspace; selectedBrowser = browser
                                    }
                                },
                                onAction = { action, title ->
                                    val active = client
                                    if (active != null) scope.launch {
                                        runCatching {
                                            when (action) {
                                                "changes" -> selectedChangesWorkspace = workspace
                                                "terminal.create" -> {
                                                    val listing = active.createTerminal(workspace.id)
                                                    applyListing(listing)
                                                    val updated = workspaces.firstOrNull { it.id == workspace.id }
                                                    selectedWorkspace = updated
                                                    selectedTerminal = updated?.terminals?.firstOrNull {
                                                        it.id == listing.optString("created_terminal_id")
                                                    } ?: updated?.terminals?.lastOrNull()
                                                }
                                                "browser.create" -> {
                                                    val created = active.createBrowser(workspace.id)
                                                    val panelId = created.optString("panel_id")
                                                    require(panelId.isNotBlank()) { "Mac did not return a browser panel" }
                                                    applyListing(active.workspaces())
                                                    val updated = workspaces.firstOrNull { it.id == workspace.id }
                                                    selectedWorkspace = updated
                                                    selectedBrowser = updated?.browsers?.firstOrNull {
                                                        it.id == panelId
                                                    } ?: NativeBrowser(panelId, created.optString("title"))
                                                }
                                                else -> {
                                                    if (action.startsWith("move:")) active.moveWorkspace(
                                                        workspace.id, workspace.windowId,
                                                        action.removePrefix("move:").takeIf { it.isNotBlank() })
                                                    else if (action == "close") active.closeWorkspace(workspace.id, workspace.windowId)
                                                    else active.workspaceAction(workspace.id, workspace.windowId, action, title)
                                                    applyListing(active.workspaces())
                                                }
                                            }
                                        }.onSuccess { error = null }
                                            .onFailure { error = it.message }
                                    }
                                }
                            )
                            workspace.browsers.forEach { browser ->
                                Text("▣  ${browser.title.ifBlank { "Browser" }}",
                                    Modifier.fillMaxWidth().clickable {
                                        finishSearch()
                                        selectedWorkspace = workspace; selectedBrowser = browser
                                    }.padding(start = 80.dp, top = 4.dp, bottom = 12.dp),
                                    color = nativeAccent, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = Color(0xFF292C31))
                        }
                        if (entries.isEmpty()) item {
                            Text(if (unreadWorkspacesOnly) "No unread workspaces." else "No workspaces found.",
                                Modifier.padding(24.dp), color = nativeMuted)
                        }
                    }
                    if (searchState.active == null) NativeTaskComposerButton(
                        Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 2.dp), enabled = client != null) {
                        finishSearch(); showTaskComposer = true
                    }
                    }
                }
                NativePrimaryNavigation(notificationTab, feedEntries.count { !it.notification.isRead }, searchState,
                    onTab = { finishSearch(); notificationTab = it },
                    onBeginSearch = { searchState = searchState.begin(searchScope) },
                    onEdit = { value, generation -> searchState = searchState.edit(value, searchScope, generation) },
                    onSubmit = { finishSearch() }, onCancel = { finishSearch(cancel = true) })
            }
        }
        val visibleError = error ?: connectionError.takeIf { !notificationTab || selectedTerminal != null || selectedBrowser != null }
        if (visibleError != null) Row(Modifier.fillMaxWidth().background(Color(0xFF402626)).padding(horizontal = 12.dp),
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


private fun parseGroups(value: JSONObject): List<NativeGroup> {
    val array = value.optJSONArray("groups") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) add(NativeGroup(id, item.optString("name", "Group"),
                item.optBoolean("is_collapsed"), item.optBoolean("is_pinned")))
        }
    }
}

@Composable
private fun NativeGroupHeaderRow(
    group: NativeGroup,
    expanded: Boolean,
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
        Text("${if (expanded) "⌄" else "›"}  ${group.name}",
            Modifier.weight(1f).clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 12.dp),
            color = nativeMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (canEdit) Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋯", color = nativeMuted) }
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
    onOpen: () -> Unit,
    onAction: (String, String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var title by remember(workspace.id) { mutableStateOf(workspace.title) }
    Row(Modifier.fillMaxWidth().clickable(enabled = workspace.terminals.isNotEmpty() || workspace.browsers.isNotEmpty(), onClick = onOpen)
        .padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (workspace.hasUnread) Text("●", color = nativeAccent, fontSize = 9.sp, modifier = Modifier.width(10.dp))
        else Spacer(Modifier.width(10.dp))
        val colors = listOf(Color(0xFFFFB52E), Color(0xFF58CFA2), Color(0xFF83B9FF), Color(0xFFFF8E80))
        val accent = runCatching { android.graphics.Color.parseColor(workspace.color) }
            .getOrNull()?.let { Color(it) } ?: colors[(workspace.id.hashCode() and Int.MAX_VALUE) % colors.size]
        Box(Modifier.size(37.dp).background(accent, CircleShape),
            contentAlignment = Alignment.Center) { Text("›", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
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
            TextButton(onClick = { expanded = true }) { Text("⋯", color = nativeMuted, fontSize = 20.sp) }
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
    return if (networkFailure) "Could not reach this Mac. Check that cmux and Tailscale are running, then retry."
        else failure.message ?: "Could not connect to this Mac."
}
