package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableDoubleStateOf

/**
 * Composition-local state with no keys or saved-state restoration.
 *
 * NativeScreen remembers this once, just as it previously remembered each delegate.
 * Account-, connection- and target-keyed state must stay at its existing keyed call site.
 * Every mutable property is snapshot-backed, so reads still invalidate their reader.
 * This holder does not own client disposal or survive a removed screen.
 */
@Stable
internal class NativeScreenTransientState(
    initialCode: String,
    initialPairedMacs: List<NativeCredentialStore.PairedMac>,
    initialBackgroundNotifications: Boolean
) {
    var code by mutableStateOf(initialCode)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var retry by mutableIntStateOf(0)
    var retryDelay by mutableLongStateOf(2_000)
    var client by mutableStateOf<MobileRpcClient?>(null)
    var connectedCode by mutableStateOf<String?>(null)
    var connectionReady by mutableStateOf(false)
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
    var computerDetails by mutableStateOf<NativeComputerDetailsPresentation?>(null)
    var notificationFilterMenu by mutableStateOf(false)
    var confirmReadAll by mutableStateOf(false)
    var pendingReadAllOrigin by mutableStateOf<String?>(null)
    var pendingReadAllComputer by mutableStateOf("All Computers")
    var readAllBusy by mutableStateOf(false)
    var feedRefreshing by mutableStateOf(false)
    var changingNotifications by mutableStateOf<Set<String>>(emptySet())
    var gridRevision by mutableIntStateOf(0)
    var replayGeneration by mutableIntStateOf(0)
    var terminalTransport by mutableStateOf(TerminalTransport.resolve(emptySet()))
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
