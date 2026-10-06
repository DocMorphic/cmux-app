package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val cloudPanel = Color(0xFF191B1F)
private val cloudMuted = Color(0xFF9B9FA8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeCloudScreen(controller: CloudMachinesController?, onSettings: () -> Unit,
    onPlans: (String?) -> Unit, modifier: Modifier = Modifier, connectionState: CloudTunnelState? = null,
    onRetryConnection: () -> Unit = {}, onBasics: (() -> Unit)? = null, vpnControl: (@Composable () -> Unit)? = null,
    machines: Map<String, CloudWorkspaceSnapshot> = emptyMap(), onRetryConnections: () -> Unit = {},
    connectionFailures: Map<String, CloudSessionFailure> = emptyMap()) {
    key(controller) {
        val state = controller?.state?.collectAsState()?.value ?: CloudMachinesState()
        val swipes = remember(controller) { CloudMachineSwipeCoordinator() }
        fun refresh() { controller?.refresh(); onRetryConnections() }
        var createSheet by rememberSaveable { mutableStateOf(false) }
        var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(state.catalog.machines, deleteId) {
            if (deleteId != null && state.catalog.machines.none { it.id == deleteId }) deleteId = null
        }
        Column(modifier.fillMaxWidth().testTag("cloud.screen")) {
            Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.cmux_logo), "cmux settings", Modifier.size(24.dp), tint = Color.Unspecified) }
                Text("Cloud", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                onBasics?.let { action -> TextButton(onClick = action, modifier = Modifier.testTag("cloud.basics")) { Text("Cloud basics") } }
                TextButton(onClick = ::refresh, enabled = controller != null) { Text("Refresh") }
            }
            if (controller == null) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Waiting for your account", style = MaterialTheme.typography.titleMedium)
                    Text("Check your connection and account team in Settings.", color = cloudMuted)
                    TextButton(onClick = onSettings) { Text("Settings") }
                }
            } else PullToRefreshBox(isRefreshing = state.phase == CloudCatalogPhase.LOADING && state.catalog.machines.isNotEmpty(),
                onRefresh = ::refresh, modifier = Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    vpnControl?.let { controls -> item { controls() } }
                    connectionState?.takeIf { it.phase in setOf(CloudTunnelPhase.STARTING, CloudTunnelPhase.READY, CloudTunnelPhase.FAILED) }?.let { connection -> item {
                        Column(Modifier.fillMaxWidth().testTag("cloud.connection").background(cloudPanel, RoundedCornerShape(16.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (connection.phase) {
                                CloudTunnelPhase.STARTING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("Connecting to Cloud…")
                                }
                                CloudTunnelPhase.READY -> Text("Cloud network connected")
                                CloudTunnelPhase.FAILED -> {
                                    connection.failure?.let { CloudFailureText(it) }
                                    TextButton(onClick = onRetryConnection, modifier = Modifier.testTag("cloud.connection.retry")) { Text("Retry connection") }
                                }
                                else -> Unit
                            }
                        }
                    } }
                    item { Text("MACHINES", color = cloudMuted, fontSize = 12.sp) }
                    if (state.catalog.machines.isEmpty() && state.phase in setOf(CloudCatalogPhase.IDLE, CloudCatalogPhase.LOADING)) item {
                        Row(Modifier.fillMaxWidth().background(cloudPanel, RoundedCornerShape(16.dp)).padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp)); Text("Loading machines")
                        }
                    }
                    if (state.catalog.machines.isEmpty() && state.phase == CloudCatalogPhase.LOADED) item {
                        Text("No Cloud machines yet. Create one below.", Modifier.padding(12.dp), color = cloudMuted)
                    }
                    items(state.catalog.machines, key = { it.id }) { machine ->
                        NativeCloudMachineRow(machine, machine.id in state.actions,
                            actionFailure = state.actionFailure?.takeIf { it.machineId == machine.id },
                            connectionFailure = if (machine.lifecycle == CloudMachineLifecycle.RUNNING)
                                connectionFailures[machine.id] ?: machines[machine.id]?.takeIf { it.machine == machine }?.failure else null,
                            swipes = swipes, onRetry = onRetryConnections,
                            automaticallyRetrying = machines[machine.id]?.takeIf { it.machine == machine }?.failure != null,
                            onAction = { action -> if (action == CloudMachineAction.DELETE) deleteId = machine.id
                                else controller.act(machine.id, action) })
                    }
                    state.failure?.let { failure -> item {
                        Column(Modifier.fillMaxWidth().background(cloudPanel, RoundedCornerShape(16.dp)).padding(16.dp)) {
                            CloudFailureText(failure)
                            TextButton(onClick = controller::refresh) { Text("Retry") }
                        }
                    } }
                    item {
                        TextButton(onClick = { createSheet = true }, modifier = Modifier.fillMaxWidth().background(cloudPanel, RoundedCornerShape(16.dp))
                            .testTag("cloud.new")) {
                            Icon(painterResource(R.drawable.ic_workspace_plus), null, Modifier.size(18.dp))
                            Text("New cloud machine", Modifier.padding(10.dp))
                        }
                    }
                }
            }
        }
        if (createSheet && controller != null) CloudCreateSheet(controller, state, onPlans) { createSheet = false }
        val deleting = state.catalog.machines.singleOrNull { it.id == deleteId }
        if (deleting != null && controller != null) AlertDialog(onDismissRequest = { deleteId = null },
            title = { Text("Delete ${deleting.preferredName}?") },
            text = { Text("This permanently deletes the machine and its disk, including its terminals and files.") },
            confirmButton = { TextButton(onClick = { controller.act(deleting.id, CloudMachineAction.DELETE); deleteId = null },
                enabled = deleting.lifecycle.canDelete && deleting.id !in state.actions, modifier = Modifier.testTag("cloud.delete.confirm")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
    }
}

@Composable private fun CloudFailureText(failure: CloudSessionFailure) {
    Text(failure.userMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    failure.action?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CloudCreateSheet(controller: CloudMachinesController, state: CloudMachinesState,
    onPlans: (String?) -> Unit, dismiss: () -> Unit) {
    val presentation = CloudCreatePresentation(state.catalog)
    var memory by rememberSaveable { mutableIntStateOf(presentation.defaultMemory) }
    var sizesOpen by remember { mutableStateOf(false) }
    val observer = rememberCoroutineScope()
    var plansOpened by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refreshPlans by rememberUpdatedState {
        if (plansOpened) { plansOpened = false; controller.refresh() }
    }
    DisposableEffect(lifecycle, controller) {
        val resume = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPlans() }
        lifecycle.addObserver(resume)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshPlans()
        onDispose { lifecycle.removeObserver(resume) }
    }
    fun openPlans(plan: String?) { plansOpened = true; onPlans(plan) }
    LaunchedEffect(presentation.sizes) { if (memory !in presentation.sizes) memory = presentation.defaultMemory }
    ModalBottomSheet(onDismissRequest = { if (!state.creating) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { !state.creating || it != SheetValue.Hidden }), containerColor = cloudPanel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New Machine", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = dismiss, enabled = !state.creating) { Text("Cancel") }
            }
            Text("A cloud computer with devtools and coding agents preinstalled. Its home directory is reset when the machine is recreated.", color = cloudMuted)
            Text("Machine size", fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(onClick = { sizesOpen = true }, enabled = !state.creating, modifier = Modifier.testTag("cloud.create.size")) { Text(presentation.label(memory)) }
                DropdownMenu(expanded = sizesOpen, onDismissRequest = { sizesOpen = false }) {
                    presentation.sizes.forEach { size -> DropdownMenuItem(text = { Text(presentation.label(size)) }, onClick = { memory = size; sizesOpen = false }) }
                    presentation.lockedSizes.forEach { size -> DropdownMenuItem(enabled = !state.creating,
                        text = { Text("${presentation.label(size)} · Requires ${presentation.planLabel(size)}") },
                        modifier = Modifier.testTag("cloud.create.upgrade.$size"),
                        onClick = { sizesOpen = false; openPlans(presentation.upgradePlan(size)) }) }
                }
            }
            if (presentation.lockedSizes.isNotEmpty()) TextButton(onClick = { openPlans(presentation.preferredUpgradePlan) }, enabled = !state.creating) { Text("View plans") }
            presentation.machineUsage?.let { Text(it, color = cloudMuted) }
            presentation.poolUsage?.let { Text(it, color = cloudMuted) }
            state.createFailure?.let { CloudFailureText(it) }
            Button(onClick = {
                val operation = controller.create(presentation.options(memory)) ?: return@Button
                observer.launch { if (operation.await() != null) dismiss() }
            }, enabled = !state.creating && memory in presentation.sizes,
                modifier = Modifier.fillMaxWidth().testTag("cloud.create.submit")) {
                Text("Create")
                if (state.creating) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
            }
            Text(if (state.creating) "Creating your machine. This takes a moment." else "Creation continues in the Machines panel.", color = cloudMuted, fontSize = 12.sp)
        }
    }
}
