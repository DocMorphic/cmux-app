package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalBrowserCreationProgress(creating: Boolean, onCancel: () -> Unit) {
    if (creating) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("Opening browser…", Modifier.weight(1f).padding(horizontal = 12.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
internal fun LocalBrowserWorkspaceView(destination: LocalBrowserDestination, navigation: LocalBrowserNavigation,
    workspace: NativeWorkspace = destination.workspace, onRoute: (NativeWorkspaceRoute) -> Unit) {
    val page by destination.surface.state.collectAsState()
    fun open(terminal: String? = null, browser: String? = null, surface: String? = null) {
        navigation.leave(close = true)
        onRoute(NativeWorkspaceRoute(destination.key.computerId, workspace.id, terminalId = terminal,
            browserId = browser, surfaceId = surface))
    }
    BackHandler { navigation.leave(close = false) }
    Column(Modifier.fillMaxSize()) {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { navigation.leave(close = false) },
                modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) { Text("‹  Workspaces") }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text((page.title ?: "Browser") + " ▾", Modifier.clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF191B1F)).clickable { menu = true }.padding(horizontal = 15.dp, vertical = 7.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    workspace.terminals.forEach { item -> DropdownMenuItem(text = { Text(item.title.ifBlank { "Terminal" }) },
                        onClick = { menu = false; open(terminal = item.id) }) }
                    workspace.browsers.forEach { item -> DropdownMenuItem(text = { Text(item.title.ifBlank { "Browser" }) },
                        onClick = { menu = false; open(browser = item.id) }) }
                    workspace.macSurfaces.forEach { item -> DropdownMenuItem(text = { Text(item.displayTitle) },
                        onClick = { menu = false; open(surface = item.id) }) }
                    DropdownMenuItem(text = { Text("✓  New Browser") }, onClick = { menu = false })
                }
            }
        }
        LocalBrowserPane(destination.surface) {
            open(terminal = destination.terminalId?.takeIf { id -> workspace.terminals.any { it.id == id } }
                ?: workspace.terminals.firstOrNull()?.id)
        }
    }
}
