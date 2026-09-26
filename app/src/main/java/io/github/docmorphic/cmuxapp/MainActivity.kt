package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CmuxCompanionApp()
                }
            }
        }
    }
}

private data class PreviewWorkspace(val name: String, val path: String, val status: String, val output: String)

private val previewWorkspaces = listOf(
    PreviewWorkspace("Agent session", "~/projects/app", "Needs input", "❯ codex\nReviewing changes…\nPermission required to continue"),
    PreviewWorkspace("Build", "~/projects/app", "Running", "❯ ./gradlew assembleDebug\n> Task :app:compileDebugKotlin\nBUILD SUCCESSFUL"),
    PreviewWorkspace("Server", "~/projects/api", "Idle", "❯ npm run dev\nListening on localhost:3000")
)

@Composable
private fun CmuxCompanionApp() {
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var selectedWorkspace by rememberSaveable { mutableStateOf(0) }
    var pairingText by rememberSaveable { mutableStateOf("") }
    var pairingError by rememberSaveable { mutableStateOf<String?>(null) }
    var parsedCode by remember { mutableStateOf<PairingCode?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("cmux companion", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Unofficial Android project • protocol research build", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (showPreview) {
            Text("Offline UI preview", style = MaterialTheme.typography.titleLarge)
            Text("These are sample workspaces. No Mac connection or terminal input is active yet.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPreview = false }) { Text("Back to pairing") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(previewWorkspaces.indices.toList()) { index ->
                    val workspace = previewWorkspaces[index]
                    Card(onClick = { selectedWorkspace = index }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(workspace.name, fontWeight = FontWeight.SemiBold)
                            Text(workspace.path, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(workspace.status, color = if (index == 0) Color(0xFF7AB8FF) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Terminal • ${previewWorkspaces[selectedWorkspace].name}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        previewWorkspaces[selectedWorkspace].output,
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFF101318), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        color = Color(0xFFDFE8F1),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            Text("Pair with your Mac", style = MaterialTheme.typography.titleLarge)
            Text("On your Mac, open cmux → Mobile Connect. Paste the QR code URL here to inspect its route.")
            OutlinedTextField(
                value = pairingText,
                onValueChange = { pairingText = it; pairingError = null; parsedCode = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("cmux attach URL") },
                minLines = 3,
                maxLines = 5
            )
            Button(onClick = {
                PairingCodeParser.parse(pairingText).fold(
                    onSuccess = { parsedCode = it; pairingError = null },
                    onFailure = { parsedCode = null; pairingError = it.message ?: "Invalid pairing code" }
                )
            }) { Text("Inspect pairing code") }
            pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            parsedCode?.let { code ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Code recognized", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        when (code) {
                            is PairingCode.Tailscale -> Text("Private network routes: ${code.routes.size}")
                            is PairingCode.Iroh -> Text("Iroh peer identity present")
                        }
                        Text("Connection and account authentication are the next implementation milestone.")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showPreview = true }) { Text("Explore UI preview") }
        }
    }
}
