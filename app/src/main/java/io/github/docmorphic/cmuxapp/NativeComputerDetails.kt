package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class NativeComputerTarget(val deviceId: String, val buildTag: String, val name: String) {
    fun matches(mac: IrohV2Computer) = canonicalMacDeviceId(deviceId) == canonicalMacDeviceId(mac.deviceId) && buildTag == mac.buildTag
    fun matches(path: NativePrivatePath) = deviceId == path.deviceId && buildTag == path.buildTag
    companion object {
        fun from(mac: IrohV2Computer) = NativeComputerTarget(mac.deviceId, mac.buildTag, mac.name)
        fun from(mac: NativeCredentialStore.PairedMac, team: NativeTeamScope): NativeComputerTarget? {
            val code = PairingCodeParser.parse(mac.code).getOrNull() as? PairingCode.Iroh ?: return null
            // Older native QR codes omit scope hints. They are never authority: the check
            // refreshes this team's directory and resolves the exact device/build before dialing.
            if ((code.userId != null && code.userId != team.userId) || (code.teamId != null && code.teamId != team.teamId) ||
                (code.macDeviceId != null && canonicalMacDeviceId(code.macDeviceId) != canonicalMacDeviceId(mac.deviceId)) ||
                (code.buildTag != null && code.buildTag != mac.instanceTag) || mac.instanceTag == null || mac.deviceId.isBlank()) return null
            return NativeComputerTarget(mac.deviceId, mac.instanceTag, mac.name)
        }
    }
}

internal data class NativeComputerDetailsPresentation(
    val team: NativeTeamScope, val target: NativeComputerTarget, val colorIndex: Int? = null
)

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
internal fun NativeComputerDetailsPresentationHost(runtime: NativeIrohRuntime?, state: NativeComputersState,
    presentation: NativeComputerDetailsPresentation?, connection: NativeComputerConnection,
    forgetCallbacks: NativeComputerForgetCallbacks, credentialStore: NativeCredentialStore? = null, onDismiss: () -> Unit) {
    if (runtime == null || presentation == null) return
    val team = presentation.team
    if (state.account != team) {
        LaunchedEffect(presentation, state.account) { onDismiss() }
        return
    }
    val target = presentation.target
    key(runtime, team, target.deviceId, target.buildTag) {
        val context = LocalContext.current
        val appearanceStore = checkNotNull(rememberNativeAppearanceStore(team))
        val appearances = appearanceStore.state.collectAsState().value
        val connectionStore = remember(context, team) { NativeMacConnectionStore.create(context.applicationContext, team) }
        val connectionPreferences by connectionStore.state.collectAsState()
        val store = credentialStore ?: remember(context) { NativeCredentialStore(context.applicationContext) }
        val latestCallbacks by rememberUpdatedState(forgetCallbacks)
        val forgetFlow = remember(runtime, team, target, store, appearanceStore, connectionStore) {
            nativeComputerForgetFlow(runtime, team, target, store, appearanceStore, connectionStore) {
                latestCallbacks.started(team, target, it)
            }
        }
        val forgetting = forgetFlow.state.collectAsState().value.busy
        val title = appearances.get(target.deviceId, target.buildTag).displayName(target.name)
        Dialog(onDismissRequest = { if (!forgetting) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                dismissOnBackPress = !forgetting, dismissOnClickOutside = !forgetting)) {
            NativeComputerDetailsScreen(target, available = state.computers.any { target.matches(it) },
                canCheck = state.ready,
                check = { runtime.checkComputer(team, target) },
                paths = { runtime.privatePaths(team).filter { target.matches(it) } },
                changePaths = { action -> runtime.privatePaths(team, action).filter { target.matches(it) } },
                share = { report ->
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, report)
                    context.startActivity(android.content.Intent.createChooser(intent, "Share Connection Report"))
                }, onBack = { if (!forgetting) onDismiss() }, backEnabled = !forgetting,
                forget = { NativeComputerForgetSection(title, target.buildTag, forgetFlow, enabled = state.ready) {
                    latestCallbacks.finished()
                    onDismiss()
                    if (runtime.permitsAppearance(team)) runtime.refresh()
                } },
                connectionMethod = { NativeMacConnectionSection(target, connectionPreferences, save = { change ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        connectionStore.update(target, { runtime.permitsAppearance(team) }, change)
                    }
                }, retry = { connectionStore.reload() }) },
                power = { NativeMacPowerSettings(runtime, team, target) }, displayName = title, connection = connection,
                appearance = { NativeMacAppearanceSettings(team, target, presentation.colorIndex) { runtime.permitsAppearance(team) } })
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
    backEnabled: Boolean = true, forget: @Composable () -> Unit = {}, connectionMethod: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0C0E)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, enabled = backEnabled) { Text("‹  Back") }
                Text(displayName.ifBlank { "Mac" }, Modifier.weight(1f).padding(end = 16.dp),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text(if (available) "Available in this team" else "Not currently discovered", color = Color(0xFF9B9FA8))
                    Text("App Build: ${target.buildTag}", fontSize = 13.sp, color = Color(0xFF9B9FA8))
                }
                connectionMethod()
                appearance()
                NativeComputerConnectionSection(connection)
                NativeConnectionCheckSection(canCheck, check, share,
                    disabledMessage = "Wait for your account’s computer list, then try again.")
                power()
                NativePrivatePathsSection(
                    computers = if (available) listOf(IrohV2Computer("", "", target.deviceId, target.buildTag, target.name, emptyList())) else emptyList(),
                    load = paths, change = changePaths, showReset = false,
                    emptyMessage = "This Mac is not currently available for new private addresses.")
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("IDENTITY", color = Color(0xFF9B9FA8), fontSize = 11.sp)
                    Text("Device ID", Modifier.padding(top = 12.dp), fontSize = 13.sp)
                    SelectionContainer { Text(target.deviceId, fontSize = 13.sp, color = Color(0xFF9B9FA8)) }
                }
                forget()
            }
        }
    }
}
