package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*

@Composable
internal fun NativeMacPowerSettings(runtime: NativeIrohRuntime, team: NativeTeamScope, target: NativeComputerTarget, offerOnly: Boolean = false) {
    var session by remember(runtime, team, target) { mutableStateOf<NativeMacPowerSession?>(null) }
    var state by remember(runtime, team, target) { mutableStateOf(NativeMacPowerState()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(runtime, team, target, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (isActive) {
                    val active = runtime.powerSession(team, target)
                    session = active
                    if (active != null) {
                        val observer = launch { active.state.collect { state = it } }
                        try { active.run() }
                        catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
                        finally { observer.cancelAndJoin() }
                    }
                    session = null
                    state = NativeMacPowerState()
                    delay(1000)
                }
            } finally { session = null; state = NativeMacPowerState() }
        }
    }
    if (!offerOnly || (state.connected && state.supported == true && state.enabled != null))
        NativeMacPowerSection(state, { session?.setEnabled(it) }, { session?.refresh() })
}

@Composable
internal fun NativeMacPowerSection(state: NativeMacPowerState, change: (Boolean) -> Unit, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(22.dp)) {
        Text("MAC POWER", color = Color(0xFF9B9FA8), fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_keep_awake), contentDescription = null,
                modifier = Modifier.padding(end = 12.dp).size(22.dp), tint = Color(0xFF9B9FA8))
            Text("Keep Mac Awake", Modifier.weight(1f))
            if (state.connected && state.supported != false && state.enabled == null) {
                if (!state.busy && state.error != null) TextButton(onClick = retry) { Text("Retry") }
                else CircularProgressIndicator(Modifier.size(24.dp).semantics {
                    contentDescription = "Loading Mac Power"
                }, strokeWidth = 2.dp)
            } else Switch(checked = state.enabled == true, onCheckedChange = change,
                enabled = state.connected && state.supported == true && state.enabled != null && !state.busy,
                modifier = Modifier.semantics { contentDescription = "Keep Mac Awake" })
        }
        val explanation = when {
            !state.connected -> "Connect to this Mac to control Keep Mac Awake."
            state.supported == false -> "Update cmux on this Mac to control Keep Mac Awake from Android."
            else -> state.error
        }
        if (explanation != null) Text(explanation, Modifier.padding(top = 8.dp), color = Color(0xFF9B9FA8), fontSize = 13.sp)
        Text("Prevents this Mac from sleeping while cmux is open. Its display can still turn off.",
            Modifier.padding(top = 8.dp), color = Color(0xFF9B9FA8), fontSize = 13.sp)
    }
}
