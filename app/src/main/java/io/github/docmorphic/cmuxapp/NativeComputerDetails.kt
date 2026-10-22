package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

internal data class NativeComputerTarget(val deviceId: String, val buildTag: String, val name: String) {
    fun matches(mac: IrohV2Computer) = canonicalMacDeviceId(deviceId) == canonicalMacDeviceId(mac.deviceId) && buildTag == mac.buildTag
    fun matches(path: NativePrivatePath) = deviceId == path.deviceId && buildTag == path.buildTag
    companion object {
        fun from(mac: IrohV2Computer) = NativeComputerTarget(mac.deviceId, mac.buildTag, mac.name)
        fun from(mac: NativeCredentialStore.PairedMac, team: NativeTeamScope): NativeComputerTarget? {
            if ((mac.accountUserId != null && mac.accountUserId != team.userId) ||
                (mac.accountTeamId != null && mac.accountTeamId != team.teamId)) return null
            val pairing = PairingCodeParser.parse(mac.code).getOrNull() ?: return null
            if (pairing is PairingCode.Tailscale && pairing.stackUserId != null && pairing.stackUserId != team.userId) return null
            val retained = NativePairingRecords.retainedNativeRoute(mac)
            if (pairing is PairingCode.Tailscale && retained == null) {
                // Only persisted authenticated metadata supplies a raw row's identity.
                // The URL's user/address hints alone cannot create Computer Details authority.
                if (mac.accountUserId != team.userId || mac.accountTeamId != team.teamId || mac.stableOrigin == null ||
                    (pairing.stackUserId != null && pairing.stackUserId != team.userId) ||
                    mac.deviceId.isBlank() || mac.instanceTag.isNullOrBlank()) return null
                return NativeComputerTarget(mac.deviceId, mac.instanceTag, mac.name)
            }
            val code = pairing as? PairingCode.Iroh ?: retained ?: return null
            val build = mac.instanceTag ?: retained?.buildTag ?: return null
            // Older native QR codes omit scope hints. They are never authority: the check
            // refreshes this team's directory and resolves the exact device/build before dialing.
            if ((code.userId != null && code.userId != team.userId) || (code.teamId != null && code.teamId != team.teamId) ||
                (code.macDeviceId != null && canonicalMacDeviceId(code.macDeviceId) != canonicalMacDeviceId(mac.deviceId)) ||
                (code.buildTag != null && code.buildTag != build) || mac.deviceId.isBlank()) return null
            return NativeComputerTarget(mac.deviceId, build, mac.name)
        }
    }
}

internal data class NativeComputerDetailsPresentation(
    val team: NativeTeamScope, val target: NativeComputerTarget, val colorIndex: Int? = null,
    val foreground: NativeForegroundComputerDetailAccess? = null
) {
    val lifetime = NativeComputerDetailsLifetime()
    init {
        require(foreground == null || (foreground.team == team &&
            canonicalMacDeviceId(foreground.target.deviceId) == canonicalMacDeviceId(target.deviceId) &&
            foreground.target.buildTag == target.buildTag)) {
            "Computer detail presentation must use its captured owner and target"
        }
    }
}

@Composable
internal fun NativeSavedComputerDetailsButton(runtime: NativeIrohRuntime?, state: NativeComputersState,
    mac: NativeCredentialStore.PairedMac, colorIndex: Int? = null,
    connection: NativeComputerConnection = NativeComputerConnection(),
    forgetCallbacks: NativeComputerForgetCallbacks = NativeComputerForgetCallbacks(),
    present: ((NativeComputerDetailsPresentation) -> Unit)? = null) {
    val team = state.account ?: return
    val target = NativeComputerTarget.from(mac, team) ?: return
    NativeComputerDetailsButton(runtime, state, target, colorIndex, connection, forgetCallbacks, present)
}

@Composable
internal fun NativeComputerDetailsButton(runtime: NativeIrohRuntime?, state: NativeComputersState,
    target: NativeComputerTarget, colorIndex: Int? = null,
    connection: NativeComputerConnection = NativeComputerConnection(),
    forgetCallbacks: NativeComputerForgetCallbacks = NativeComputerForgetCallbacks(),
    present: ((NativeComputerDetailsPresentation) -> Unit)? = null) {
    val team = state.account
    if (runtime == null || team == null) return
    var localPresentation by remember { mutableStateOf<NativeComputerDetailsPresentation?>(null) }
    val appearances = rememberNativeAppearanceStore(team)?.state?.collectAsState()?.value ?: NativeMacAppearances()
    val title = appearances.get(target.deviceId, target.buildTag).displayName(target.name)
    TextButton(onClick = {
        val captured = NativeComputerDetailsPresentation(team, target, colorIndex)
        if (present != null) present(captured) else localPresentation = captured
    }, modifier = Modifier.semantics { contentDescription = "Details for $title (${target.buildTag})" }) { Text("Details") }
    NativeComputerDetailsPresentationHost(runtime, state, localPresentation, connection, forgetCallbacks) { localPresentation = null }
}

/** Lives above computer rows so a successful revoke cannot dispose local cleanup
 * when discovery removes that row. The presentation captures its original owner.
 */
@Composable
internal fun NativeSavedComputerDetailsHost(runtime: NativeIrohRuntime?, state: NativeComputersState,
    presentation: NativeComputerDetailsPresentation?, connections: Map<NativeMacIdentity, NativeComputerConnection>,
    callbacks: NativeComputerForgetCallbacks, store: NativeCredentialStore, connector: NativeConnector,
    feed: NativeFeedCoordinator, confirm: ((NativeComputerDetailsPresentation) -> Unit)? = null,
    prepareDetached: ((NativeTeamScope, NativeComputerTarget, List<NativeCredentialStore.PairedMac>) -> Unit)? = null,
    connectionFor: ((NativeCredentialStore.PairedMac) -> NativeComputerConnection?)? = null, onDismiss: () -> Unit) {
    val connection = presentation?.let { page ->
        if (page.foreground != null && connectionFor != null) connectionFor(page.foreground.mac)
        else if (state.account == page.team) connections[NativeMacIdentity(page.target.deviceId, page.target.buildTag)]
        else null
    } ?: NativeComputerConnection()
    val power = presentation?.let { feed.powerSession(it.team, it.target) }
    NativeComputerDetailsPresentationHost(runtime, state, presentation, connection, callbacks,
        credentialStore = store, savedConnector = connector, savedPower = power, confirm = confirm,
        prepareDetached = prepareDetached, onDismiss = onDismiss)
}

@Composable
internal fun NativeComputerDetailsPresentationHost(runtime: NativeIrohRuntime?, state: NativeComputersState,
    presentation: NativeComputerDetailsPresentation?, connection: NativeComputerConnection,
    forgetCallbacks: NativeComputerForgetCallbacks, credentialStore: NativeCredentialStore? = null,
    savedConnector: NativeConnector? = null, savedPower: NativeMacPowerSession? = null,
    confirm: ((NativeComputerDetailsPresentation) -> Unit)? = null,
    prepareDetached: ((NativeTeamScope, NativeComputerTarget, List<NativeCredentialStore.PairedMac>) -> Unit)? = null,
    onDismiss: () -> Unit) {
    if (runtime == null || presentation == null) return
    val team = presentation.team
    val target = presentation.target
    val capturedForeground = presentation.foreground
    val lifetime = presentation.lifetime
    key(presentation) {
        val context = LocalContext.current
        val appearanceStore = checkNotNull(rememberNativeAppearanceStore(team))
        val appearances = appearanceStore.state.collectAsState().value
        val connectionStore = remember(context, team) { NativeMacConnectionStore.create(context.applicationContext, team) }
        val connectionPreferences by connectionStore.state.collectAsState()
        val store = credentialStore ?: remember(context) { NativeCredentialStore(context.applicationContext) }
        val credentialRevision by store.revisions.collectAsState()
        val savedCheck = remember(store, savedConnector, runtime, team, target) { savedConnector?.let { connector ->
            val account = NativeAccount(store)
            NativeSavedComputerCheck(team, target, { runtime.permitsAppearance(team) && store.taskSession() == team.login },
                store::visiblePairedMacs, connector::allowsSaved) { connector.connectSaved(it, account) }
        } }
        val rawOnly = remember(store, credentialRevision, team, target) {
            store.visiblePairedMacs().singleOrNull { NativeComputerTarget.from(it, team)?.let { saved ->
                canonicalMacDeviceId(saved.deviceId) == canonicalMacDeviceId(target.deviceId) && saved.buildTag == target.buildTag
            } == true }?.let { PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Tailscale &&
                NativePairingRecords.retainedNativeRoute(it) == null } == true
        }
        var routeReload by remember { mutableIntStateOf(0) }
        val grants = remember(store) { TailscaleGrantStore(store::load, store::update) }
        val foreground = capturedForeground?.takeIf { it.current() }
        val foregroundRoutes = remember(context, foreground, runtime, store) { foreground?.let {
            NativeForegroundTailscaleRoutes.create(context, it, runtime, store)
        } }
        val tailscale = remember(grants, credentialRevision, routeReload, team, target) {
            runCatching { foregroundRoutes?.routes() ?: grants.computer(team, target) }.getOrNull()
        }
        val token = remember(lifetime) { Any() }
        DisposableEffect(lifetime, token) {
            lifetime.attach(token, forgetCallbacks)
            onDispose { lifetime.detach(token) }
        }
        SideEffect { lifetime.update(token, forgetCallbacks) }
        val forgetFlow = lifetime.forget {
            val prepare: (List<NativeCredentialStore.PairedMac>) -> Unit = { rows ->
                lifetime.prepare(team, target, rows) { prepareDetached?.invoke(team, target, rows) }
            }
            if (capturedForeground != null) nativeForegroundComputerForgetFlow(runtime, capturedForeground.management, store,
                appearanceStore, connectionStore, beginAllowed = capturedForeground::current, prepare = prepare)
            else nativeComputerForgetFlow(runtime, team, target, store, appearanceStore, connectionStore, prepare)
        }
        val removal by forgetFlow.state.collectAsState()
        LaunchedEffect(lifetime, removal.finished) { if (removal.finished && lifetime.deliverFinished()) onDismiss() }
        val selectedOwner = state.account == team && runtime.permitsAppearance(team)
        if (!lifetime.canHost(selectedOwner, foreground != null, store.taskSession(), team)) {
            LaunchedEffect(presentation, state.account, credentialRevision) { onDismiss() }
        } else {
        val forgetting = removal.busy
        val permits = { !forgetFlow.state.value.busy && !forgetFlow.state.value.remoteConfirmed &&
            (if (foreground != null) foreground.current() else runtime.permitsAppearance(team)) }
        val title = appearances.get(target.deviceId, target.buildTag).displayName(target.name)
        val displayedConnection = if (capturedForeground != null && !capturedForeground.current())
            connection.copy(availability = NativeFeedAvailability.OFFLINE, foreground = false, keepAwake = null, transport = null)
            else connection
        NativeDialog(onDismissRequest = { if (!forgetting) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                dismissOnBackPress = !forgetting, dismissOnClickOutside = !forgetting)) {
            NativeComputerDetailsScreen(target, available = foreground != null || (state.account == team && state.computers.any { target.matches(it) }),
                canCheck = !forgetting && !removal.remoteConfirmed && (if (foreground != null) foreground.current()
                    else if (rawOnly) savedCheck?.available() == true || runtime.usesSavedTailscale(team, target)
                    else selectedOwner && (state.ready || runtime.usesSavedTailscale(team, target))),
                check = { if (foreground != null) foreground.check()
                    else if (rawOnly && savedCheck != null && !runtime.usesSavedTailscale(team, target)) savedCheck.run()
                    else runtime.checkComputer(team, target) },
                paths = { if (foreground != null) runtime.foregroundPrivatePaths(foreground)
                    else runtime.privatePaths(team).filter { target.matches(it) } },
                changePaths = { action -> if (foreground != null) runtime.foregroundPrivatePaths(foreground, action)
                    else runtime.privatePaths(team, action).filter { target.matches(it) } },
                share = { report ->
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, report)
                    context.startActivity(android.content.Intent.createChooser(intent, "Share Connection Report"))
                }, onBack = { if (!forgetting) onDismiss() }, backEnabled = !forgetting,
                forget = { NativeComputerForgetSection(title, target.buildTag, forgetFlow,
                    enabled = if (capturedForeground != null) capturedForeground.current() else selectedOwner && state.ready,
                    confirmation = lifetime.confirmation, confirm = confirm?.let { action -> { action(presentation) } }) {
                    if (lifetime.deliverFinished()) onDismiss()
                } },
                connectionMethod = { NativeMacConnectionSection(target, connectionPreferences, save = { change ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        connectionStore.update(target, permits, change)
                    }
                }, retry = { connectionStore.reload() })
                    NativeTailscaleSection(target, connectionPreferences.get(target).method, tailscale,
                        pair = { input, replacing -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val pairing = tailscalePairingInput(input)
                            if (foregroundRoutes != null) foregroundRoutes.pair(pairing, replacing) else {
                            val permits = { runtime.permitsAppearance(team) && store.taskSession() == team.login }
                            val account = NativeAccount(store)
                            TailscalePairingAuthority({ team }, { it == team && permits() }, grants,
                                resolve = { route, allowed -> TailscaleRoute.resolvePeer(context.applicationContext, route, allowed) },
                                dial = { route, allowed, token -> MobileRpcClient(TailscaleRoute.resolve(context.applicationContext, route, allowed), token) },
                                expected = { NativeCredentialStore.PairedMac("", target.deviceId, target.name, target.buildTag) },
                                replacing = replacing, admitCompatibility = runtime::admitAuthenticatedHost,
                                manualTicket = runtime::requestManualAttachTicket).use { authority ->
                                    authority.authorize(pairing)
                                    authority.connect(pairing, forceToken = { account.accessToken(true) }, token = account::accessToken).use { }
                                }
                            }
                        } }, remove = { grant -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            if (foregroundRoutes != null) foregroundRoutes.remove(grant)
                            else grants.removeRoute(team, target, grant) { runtime.permitsAppearance(team) }
                        } }, reload = { routeReload++ }, enabled = !forgetting && permits())
                },
                power = { if (foreground != null) NativeForegroundMacPowerSettings(foreground)
                    else if (savedPower != null) NativeBorrowedMacPowerSettings(savedPower)
                    else NativeMacPowerSettings(runtime, team, target) }, displayName = title, connection = displayedConnection,
                showPrivateAddresses = connectionPreferences.get(target).method == NativeMacConnectionMethod.IROH,
                appearance = { NativeMacAppearanceSettings(team, target, presentation.colorIndex, permits) },
                checkDisabledMessage = if (rawOnly) "Choose an available connection method or add this Mac’s Tailscale address, then try again."
                    else "Wait for your account’s computer list, then try again.")
        }
        }
    }
}

@Composable
internal fun NativeComputerDetailsScreen(target: NativeComputerTarget, available: Boolean, canCheck: Boolean,
    check: suspend () -> NativeConnectionReport, paths: suspend () -> List<NativePrivatePath>,
    changePaths: suspend ((NativePrivatePathStore) -> Unit) -> List<NativePrivatePath>,
    share: (String) -> Unit, onBack: () -> Unit, power: @Composable () -> Unit = {},
    displayName: String = target.name, appearance: @Composable () -> Unit = {},
    connection: NativeComputerConnection = NativeComputerConnection(),
    backEnabled: Boolean = true, forget: @Composable () -> Unit = {}, connectionMethod: @Composable () -> Unit = {},
    showPrivateAddresses: Boolean = true,
    checkDisabledMessage: String = "Wait for your account’s computer list, then try again.") {
    Surface(Modifier.fillMaxSize(), color = NativeAppColors.page) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, enabled = backEnabled) { Text("‹  Back") }
                Text(displayName.ifBlank { "Mac" }, Modifier.weight(1f).padding(end = 16.dp),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text(if (available) "Available in this team" else "Not currently discovered", color = NativeAppColors.muted)
                    Text("App Build: ${target.buildTag}", fontSize = 13.sp, color = NativeAppColors.muted)
                }
                connectionMethod()
                appearance()
                NativeComputerConnectionSection(connection)
                NativeConnectionCheckSection(canCheck, check, share,
                    disabledMessage = checkDisabledMessage)
                power()
                if (showPrivateAddresses) NativePrivatePathsSection(
                    computers = if (available) listOf(IrohV2Computer("", "", target.deviceId, target.buildTag, target.name, emptyList())) else emptyList(),
                    load = paths, change = changePaths, showReset = false,
                    emptyMessage = "This Mac is not currently available for new private addresses.")
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("IDENTITY", color = NativeAppColors.muted, fontSize = 11.sp)
                    Text("Device ID", Modifier.padding(top = 12.dp), fontSize = 13.sp)
                    SelectionContainer { Text(target.deviceId, fontSize = 13.sp, color = NativeAppColors.muted) }
                }
                forget()
            }
        }
    }
}
