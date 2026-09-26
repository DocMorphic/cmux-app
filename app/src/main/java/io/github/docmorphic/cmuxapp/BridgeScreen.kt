package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BridgeScreen(onBack: () -> Unit) {
    var pairingText by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<BridgeClient?>(null) }
    var workspaces by remember { mutableStateOf<List<BridgeWorkspace>>(emptyList()) }
    var selectedSurface by remember { mutableStateOf<String?>(null) }
    var terminalText by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(client) {
        val active = client ?: return@LaunchedEffect
        runCatching { active.workspaces() }.fold(
            onSuccess = { list ->
                workspaces = list
                selectedSurface = list.firstNotNullOfOrNull { it.terminals.firstOrNull()?.id }
                error = if (list.isEmpty()) "No cmux workspaces were returned" else null
            },
            onFailure = { error = it.message ?: "Could not reach the Mac helper" }
        )
    }

    LaunchedEffect(client, selectedSurface) {
        val active = client ?: return@LaunchedEffect
        val surface = selectedSurface ?: return@LaunchedEffect
        while (true) {
            runCatching { active.screen(surface) }.fold(
                onSuccess = { terminalText = it; error = null },
                onFailure = { error = it.message ?: "Terminal disconnected" }
            )
            delay(1_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedButton(onClick = onBack) { Text("Back") }
    Text("Mac helper connection", style = MaterialTheme.typography.titleLarge)
    if (client == null) {
        Text("Start the helper in a cmux terminal on the Mac, then paste its private pairing URL here.")
        OutlinedTextField(
            value = pairingText,
            onValueChange = { pairingText = it; error = null },
            label = { Text("cmux-app://pair URL") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Button(onClick = {
            runCatching { BridgePairing.parse(pairingText) }.fold(
                onSuccess = { pairing -> client = BridgeClient(pairing); pairingText = "" },
                onFailure = { error = it.message ?: "Invalid helper pairing URL" }
            )
        }, enabled = pairingText.isNotBlank()) { Text("Connect") }
    } else {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { client?.workspaces().orEmpty() }.fold(
                        onSuccess = { workspaces = it },
                        onFailure = { error = it.message }
                    )
                }
            }) { Text("Refresh workspaces") }
            OutlinedButton(onClick = { client = null; workspaces = emptyList(); selectedSurface = null }) { Text("Disconnect") }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(workspaces) { workspace ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(workspace.title)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            workspace.terminals.forEachIndexed { index, terminal ->
                                OutlinedButton(onClick = { selectedSurface = terminal.id }) {
                                    Text(terminal.title.ifBlank { "Terminal ${index + 1}" })
                                }
                            }
                        }
                    }
                }
            }
        }
        SelectionContainer {
            Text(
                terminalText.ifBlank { "Waiting for terminal output…" },
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .background(Color(0xFF101318), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFDFE8F1)
            )
        }
        OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Terminal input") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                val active = client ?: return@Button
                val surface = selectedSurface ?: return@Button
                val text = input
                input = ""
                scope.launch {
                    runCatching { active.sendText(surface, text); active.sendKey(surface, "enter") }
                        .onFailure { error = it.message ?: "Input failed" }
                }
            }, enabled = input.isNotBlank() && selectedSurface != null) { Text("Send") }
            listOf("ctrl+c", "tab", "escape", "up", "down").forEach { key ->
                OutlinedButton(onClick = {
                    val active = client ?: return@OutlinedButton
                    val surface = selectedSurface ?: return@OutlinedButton
                    scope.launch { runCatching { active.sendKey(surface, key) }.onFailure { error = it.message } }
                }, enabled = selectedSurface != null) { Text(key) }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
