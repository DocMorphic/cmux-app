package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NativeNetworkingSettings(runtime: NativeIrohRuntime?, state: NativeComputersState) {
    val team = state.account
    if (runtime == null || team == null) return
    key(runtime, team) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = true }, modifier = Modifier.padding(horizontal = 14.dp)) { Text("Networking  ›") }
        if (open) Dialog(onDismissRequest = { open = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            NativeNetworkingScreen(load = { refresh -> runtime.networking(team, refresh) }, onBack = { open = false })
        }
    }
}

/** One serialized reader per visible page: polling cannot race a manual refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun NativeNetworkingScreen(load: suspend (Boolean) -> NativeNetworkingSnapshot, onBack: () -> Unit,
    pollMillis: Long = 2000, timeoutMillis: Long = 15_000) {
    var snapshot by remember { mutableStateOf<NativeNetworkingSnapshot?>(null) }
    var pending by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshFailed by remember { mutableStateOf(false) }
    val requests = remember { Channel<Unit>(Channel.CONFLATED) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentLoad by rememberUpdatedState(load)
    DisposableEffect(requests) { onDispose { requests.cancel() } }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                var first = true
                while (isActive) {
                    val refresh = if (first) false else select {
                        requests.onReceive { true }
                        onTimeout(pollMillis) { false }
                    }
                    if (first || refresh) pending = true
                    try {
                        val next = withTimeout(timeoutMillis) { currentLoad(refresh) }
                        ensureActive()
                        snapshot = next
                        if (refresh) refreshFailed = false
                        if (!refreshFailed) error = null
                    } catch (_: TimeoutCancellationException) {
                        if (refresh) refreshFailed = true
                        snapshot = null
                        error = if (refresh) "Networking refresh timed out. Try again." else "Networking status timed out."
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        if (refresh) refreshFailed = true
                        snapshot = null
                        error = if (refresh) "Could not refresh networking. Try again." else "Networking is unavailable. Reconnect to your account."
                    } finally { pending = false }
                    first = false
                }
            } finally { pending = false }
        }
    }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0C0E)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  Back") }
                Text("Networking", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp)) {
                Heading("CONNECTION")
                NetworkingRow("Relay Preference", "Automatic")
                NetworkingRow("Direct Connections", "Automatic")
                Text("Direct peer-to-peer stays enabled. cmux supplies the relay addresses for your account.",
                    fontSize = 13.sp, color = muted)
                TextButton(enabled = !pending, onClick = {
                    if (!pending) { pending = true; requests.trySend(Unit) }
                }) { Text(if (pending) "Refreshing…" else "Refresh Networking") }
                error?.let { Text(it, color = Color(0xFFFFC170), fontSize = 13.sp) }
                snapshot?.let { value ->
                    Heading("STATUS")
                    NetworkingRow("Transport", when (value.runtime) {
                        NativeNetworkingSnapshot.Runtime.WAITING_FOR_MAC -> "Waiting for a Mac"
                        NativeNetworkingSnapshot.Runtime.ACTIVE -> "Active"
                        NativeNetworkingSnapshot.Runtime.STOPPED -> "Stopped"
                    })
                    NetworkingRow("Computer Discovery", when (value.discovery) {
                        NativeNetworkingSnapshot.Discovery.PUSH -> "Live Updates"
                        NativeNetworkingSnapshot.Discovery.POLLING -> "Polling"
                        NativeNetworkingSnapshot.Discovery.UNAVAILABLE -> "Unavailable"
                    })
                    NetworkingRow("Account Access", when {
                        value.permissionValid -> "Current"
                        value.permissionExpiresAt == null -> "Unavailable"
                        value.permissionExpiresAt <= value.sampledAt -> "Expired"
                        else -> "Unavailable"
                    })
                    value.revision?.let { NetworkingRow("Directory Revision", it.toString()) }
                    value.permissionExpiresAt?.let { NetworkingRow("Access Expires", networkingDate(it)) }
                    NetworkingRow("Last Checked", networkingDate(value.sampledAt))
                    Heading("CMUX RELAYS")
                    if (value.relays.isEmpty()) Text("No relay credentials available.", color = muted, fontSize = 13.sp)
                    value.relays.forEach { relay ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(relay.url, fontSize = 14.sp)
                            Text(buildString {
                                append(if (relay.usable) "Available" else "Expired")
                                if (relay.home) append(" · Home Relay")
                            }, fontSize = 12.sp, color = if (relay.usable) muted else Color(0xFFFFC170))
                            Text("Expires ${networkingDate(relay.expiresAt)}", fontSize = 12.sp, color = muted)
                        }
                    }
                    value.homeRelay?.takeIf { home -> value.relays.none { it.url == home } }?.let {
                        NetworkingRow("Home Relay", it)
                        Text("This endpoint relay is not in the current credential list.", color = muted, fontSize = 12.sp)
                    }
                    if (value.homeRelay == null) Text("No home relay reported by the endpoint.", color = muted, fontSize = 12.sp)
                    Text("A home relay does not indicate the route used by a Mac connection. Use Connection Check for the active route.",
                        fontSize = 12.sp, color = muted, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

private val muted = Color(0xFF9B9FA8)
@Composable private fun Heading(text: String) {
    Text(text, color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}
@Composable private fun NetworkingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(value, Modifier.weight(1f), fontSize = 14.sp)
    }
}
internal fun networkingDate(seconds: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(Math.multiplyExact(seconds, 1000)))
}.getOrDefault("Unavailable")
