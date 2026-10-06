package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeWhatsNewReplayUi(pages: List<WhatsNewPage>, replay: NativeWhatsNewReplay,
    owner: String, policy: NativeMacCompatibilityPolicy, webArchive: NativeNoticeArchiveOwner?) {
    var first by rememberSaveable { mutableStateOf(pages.firstOrNull()?.key.orEmpty()) }
    var last by rememberSaveable { mutableStateOf(pages.lastOrNull()?.key.orEmpty()) }
    val selected = NativeWhatsNewReplay.range(pages, first, last)
    val active by replay.state.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
        .testTag("whatsnew.replay"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Replay What's New", style = MaterialTheme.typography.headlineSmall)
        if (pages.isEmpty()) Text("No updates available")
        else {
            ReplayRangePicker("First Update", "first", pages, first) { first = it }
            ReplayRangePicker("Last Update", "last", pages, last) { last = it }
            selected.orEmpty().forEach { page ->
                Column {
                    Text(page.title)
                    Text(page.key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { replay.start(owner, pages, first, last) }, enabled = selected != null,
                modifier = Modifier.testTag("whatsnew.replay.show")) { Text("Show Sheets") }
        }
    }
    active?.takeIf { it.owner == owner }?.let { snapshot ->
        key(snapshot.token) {
            NativeWhatsNewLaunchSheet(snapshot, policy, null, onAppeared = {},
                onPage = { replay.select(snapshot.token, it) },
                onDismiss = { replay.dismiss(); webArchive?.dismiss() },
                webContent = { page -> NativeNoticeArchiveWeb(page, webArchive, Modifier.fillMaxSize()) })
        }
    }
}

@Composable
private fun ReplayRangePicker(label: String, tag: String, pages: List<WhatsNewPage>, selected: String,
    onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("whatsnew.replay.$tag")) {
                Text(pages.firstOrNull { it.key == selected }?.key ?: "Select update")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                pages.forEach { page ->
                    DropdownMenuItem(text = { Text(page.key) }, onClick = { onSelect(page.key); expanded = false },
                        modifier = Modifier.testTag("whatsnew.replay.$tag.${page.key}"))
                }
            }
        }
    }
}
