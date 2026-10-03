package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** A monotonic deadline survives backgrounding; resuming never starts another create. */
@Composable
internal fun NativeTerminalStartupExpiry(startup: NativeTerminalStartup,
    pending: NativeTerminalStartup.Pending?, onExpired: () -> Unit) {
    val expired by rememberUpdatedState(onExpired)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pending?.id, lifecycle) {
        val ticket = pending ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(startup.remaining(ticket))
            if (startup.expire(ticket)) expired()
        }
    }
}

@Composable
internal fun NativeStartingTerminalPane(terminal: NativeTerminal, workspace: NativeWorkspace?, workspaceCount: Int,
    onBack: () -> Unit, onTerminal: (NativeTerminal) -> Unit, onSurface: (NativeSurface) -> Unit,
    onBrowser: (NativeBrowser) -> Unit, onNewBrowser: () -> Unit,
    onNewWorkspace: (() -> Unit)? = null, onNewTerminal: (() -> Unit)? = null) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("TerminalStarting")) {
        NativeTerminalHeader(terminal, workspace, workspaceCount, emptySet(), false, false,
            onBack, onSurface, {}, {}, onNewBrowser, {}, onBrowser, onTerminal,
            onNewWorkspace = onNewWorkspace, onNewTerminal = onNewTerminal)
        NativeTerminalTabs(workspace?.terminals.orEmpty(), terminal, onTerminal)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Starting terminal…")
        }
    }
}

@Composable
internal fun NativeTerminalCreationRecovery(creating: Boolean, canCreate: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF3C2B19)).padding(horizontal = 16.dp, vertical = 10.dp)
        .testTag("MobileTerminalCreationRecovery"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("⚠", color = Color(0xFFFFB65B))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(NativeTerminalStartup.TIMEOUT_MESSAGE, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry, enabled = canCreate && !creating, modifier = Modifier.testTag("MobileTerminalCreationRetry")) {
                Text(if (creating) "Creating…" else "Retry")
            }
        }
    }
}
