package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.MutableIntState

/**
 * Composition-local UI callbacks and state, with connection data delegated to its owner.
 *
 * NativeScreen remembers this once, just as it previously remembered each delegate.
 * Account-, connection- and target-keyed state must stay at its existing keyed call site.
 * Every mutable property is snapshot-backed, so reads still invalidate their reader.
 * The connection owner handles client disposal; view callbacks remain local to this holder.
 */
@Stable
internal class NativeScreenTransientState(
    initialCode: String,
    initialPairedMacs: List<NativeCredentialStore.PairedMac>,
    initialBackgroundNotifications: Boolean,
    retryState: MutableIntState = mutableIntStateOf(0),
    val connectionState: NativeScreenConnectionState = NativeScreenConnectionState(initialCode),
    private val computerDetailsState: NativeComputerDetailsState = NativeComputerDetailsState()
) {
    var code: String
        get() = connectionState.code
        set(value) { connectionState.code = value }
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    // The supplied ViewModel counter retains an explicit recovery request through recreation.
    var retry by retryState
    var retryDelay by mutableLongStateOf(2_000)
    var client: MobileRpcClient?
        get() = connectionState.client
        set(value) { connectionState.client = value }
    var connectedCode: String?
        get() = connectionState.connectedCode
        set(value) { connectionState.connectedCode = value }
    var connectionReady: Boolean
        get() = connectionState.connectionReady
        set(value) { connectionState.connectionReady = value }
    var hostName: String
        get() = connectionState.hostName
        set(value) { connectionState.hostName = value }
    var hostCapabilities: Set<String>
        get() = connectionState.hostCapabilities
        set(value) { connectionState.hostCapabilities = value }
    var notifications: List<NativeNotification>
        get() = connectionState.notifications
        set(value) { connectionState.notifications = value }
    var savedPairedMacs by mutableStateOf(initialPairedMacs)
    var computersReturnToSettings by mutableStateOf(false)
    var showLicenses by mutableStateOf(false)
    var taskDraftLoadAttempt by mutableIntStateOf(0)
    var creatingGroup by mutableStateOf(false)
    var backgroundNotifications by mutableStateOf(initialBackgroundNotifications)
    var createMenuOpen by mutableStateOf(false)
    var workspaceFilterMenuOpen by mutableStateOf(false)
    var computerMenuOpen by mutableStateOf(false)
    var terminalMeasurement by mutableStateOf(TerminalViewportMeasurement())
    var workspaceRoute by mutableStateOf<NativeWorkspaceRoute?>(null)
    var inAppNotification by mutableStateOf<NotificationDestination?>(null)
    var notificationNow by mutableLongStateOf(System.currentTimeMillis())
    var computerDetails: NativeComputerDetailsPresentation?
        get() = computerDetailsState.value
        set(value) { computerDetailsState.value = value }
    var notificationFilterMenu by mutableStateOf(false)
    var confirmReadAll by mutableStateOf(false)
    var pendingReadAllOrigin by mutableStateOf<String?>(null)
    var pendingReadAllComputer by mutableStateOf("All Computers")
    var readAllBusy by mutableStateOf(false)
    var feedRefreshing by mutableStateOf(false)
    var changingNotifications by mutableStateOf<Set<String>>(emptySet())
    var gridRevision by mutableIntStateOf(0)
    var replayGeneration by connectionState.replayGeneration
    var terminalTransport: TerminalTransport
        get() = connectionState.terminalTransport
        set(value) { connectionState.terminalTransport = value }
    var scrollPosition by mutableDoubleStateOf(0.0)
    var terminalClick by mutableStateOf<((TerminalGeometry.Cell) -> Unit)?>(null)
    var terminalScroll by mutableStateOf<((Double, TerminalGeometry.Cell) -> Boolean)?>(null)
    var cancelQueuedScroll by mutableStateOf<(() -> Unit)?>(null)
    var scrollInteractionEpoch by mutableIntStateOf(0)
    var pickerTarget by mutableStateOf<TerminalDrafts.Target?>(null)
    var pickerGeneration by mutableLongStateOf(0)
    var pickerLogin by mutableStateOf<String?>(null)
    var preparingAttachments by mutableStateOf(false)
    var attachmentMenu by mutableStateOf(false)
}
