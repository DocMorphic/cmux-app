package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

@Composable
internal fun PhoneMacPushSettings(client: MobileRpcClient?, mac: NativeCredentialStore.PairedMac?, team: NativeTeamScope?,
    isCurrent: (MobileRpcClient, NativeCredentialStore.PairedMac, NativeTeamScope) -> Boolean, onConnect: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(isCurrent)
    key(client, mac, team) {
        if (client == null || mac == null || team == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text("MAC FORWARDING", style = MaterialTheme.typography.labelSmall)
                Text("Connect to a Mac to view its forwarding and privacy settings.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onConnect) { Text("Connect a Mac") }
            }
        } else {
            var attached by remember { mutableStateOf(true) }
            DisposableEffect(Unit) { attached = true; onDispose { attached = false } }
            fun admitted() = attached && !client.isClosed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                current(client, mac, team)
            val controller = remember(client, mac, team, lifecycle) {
                PhoneMacPushController(::admitted, read = {
                    client.phonePushHostStatus(::admitted).also { mac.requireMatchingHost(it) }
                }, write = { client.changePhonePushSettings(it, ::admitted) })
            }
            val model by controller.state.collectAsState()
            val scope = rememberCoroutineScope()
            LaunchedEffect(controller, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    coroutineScope {
                        // Every foreground entry refetches; event loss/older hosts are covered by polling.
                        val polling = launch { while (isActive) { controller.refresh(); delay(15_000) } }
                        val events = launch { client.events.collect { event ->
                            if (event.topic == "phone_push.status.changed") controller.refresh()
                        } }
                        val stream = UUID.randomUUID().toString()
                        try {
                            try { client.subscribe(listOf("phone_push.status.changed"), stream) }
                            catch (_: Exception) { currentCoroutineContext().ensureActive() }
                            awaitCancellation()
                        } finally {
                            polling.cancel(); events.cancel()
                            withContext(NonCancellable) { withTimeoutOrNull(1000) {
                                try { client.unsubscribe(stream) } catch (_: Exception) { }
                            } }
                        }
                    }
                }
            }
            PhoneMacPushSettingsContent(mac.name, model, onChange = { change -> scope.launch { controller.change(change) } },
                onRefresh = { scope.launch { controller.refresh() } })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneMacPushSettingsContent(name: String, model: PhoneMacPushState,
    onChange: (PhoneMacPushChange) -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MAC FORWARDING", style = MaterialTheme.typography.labelSmall)
        Text(name, style = MaterialTheme.typography.titleSmall)
        val status = model.status
        if (model.loading) Text("Loading Mac settings…", style = MaterialTheme.typography.bodySmall)
        else if (status == null) Text("Forwarding settings are unavailable. Check that this Mac runs a current cmux version and uses the same account.",
            style = MaterialTheme.typography.bodySmall)
        if (status != null) {
            if (model.stale) Text("Last confirmed settings", style = MaterialTheme.typography.bodySmall)
            MacPushSwitch("Forward Alerts from This Mac", "push.mac.enabled", status.enabled, model.canChange) {
                onChange(PhoneMacPushChange.Enabled(it))
            }
            Text("Forwarding Mode", style = MaterialTheme.typography.bodyMedium)
            // Wrapping keeps both choices usable with enlarged Android text.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneMacPushMode.entries.forEach { mode ->
                    FilterChip(selected = status.mode == mode, enabled = model.canChange,
                        onClick = { if (status.mode != mode) onChange(PhoneMacPushChange.Mode(mode)) },
                        label = { Text(mode.title) }, modifier = Modifier.testTag("push.mac.mode.${mode.wire}"))
                }
            }
            if (status.mode == PhoneMacPushMode.AWAY) Text("Only When Away sends after the Mac is locked, asleep, or inactive.",
                style = MaterialTheme.typography.bodySmall)
            MacPushSwitch("Hide Notification Content", "push.mac.hidden", status.hideContent, model.canChange) {
                onChange(PhoneMacPushChange.HideContent(it))
            }
            if (!model.stale) Text(when (status.admission) {
                "allowed" -> "The Mac currently allows forwarding."
                "forwarding_disabled" -> "Forwarding is turned off on the Mac."
                "suppressed_mac_active" -> "Alerts are paused while you’re active on the Mac."
                else -> "The Mac’s current forwarding decision is unavailable."
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("push.mac.admission"))
            if (status.queuePersistence.endsWith("_failed")) Text("cmux reported a problem saving its notification queue on the Mac.",
                style = MaterialTheme.typography.bodySmall)
            if (!model.supportsSettings) Text("Update cmux on this Mac to change forwarding from this phone.", style = MaterialTheme.typography.bodySmall)
        }
        if (model.busy) Text("Saving on Mac…", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("push.mac.error").semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!model.loading && (model.stale || status == null)) TextButton(enabled = !model.busy, onClick = onRefresh) { Text("Refresh Mac Settings") }
        Text("These are this Mac’s cmux forwarding preferences. Android push delivery also requires the notification helper and phone setup above.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MacPushSwitch(label: String, tag: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = change, enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = label })
    }
}
