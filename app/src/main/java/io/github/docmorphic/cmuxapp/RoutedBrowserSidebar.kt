package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ColumnScope.RoutedBrowserSidebar(controller: RoutedSidebarController, ui: RoutedSidebarUi,
    onOpen: (String) -> Unit, onFinishSearch: (Boolean) -> Unit) {
    DisposableEffect(controller) { controller.visible(true); onDispose { controller.visible(false) } }
    var computers by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ui.query.notifications) "Notifications" else "Workspaces", Modifier.weight(1f).padding(18.dp), fontWeight = FontWeight.SemiBold)
        NativeWorkspaceSidebarToggle()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { computers = true }) {
                Text(ui.snapshot?.computers?.singleOrNull { it.key == ui.query.computer }?.name
                    ?: if (ui.query.computer == null) "All Computers" else "Unavailable computer")
            }
            DropdownMenu(computers, onDismissRequest = { computers = false }) {
                DropdownMenuItem(text = { Text("All Computers") }, onClick = { computers = false; controller.query(ui.query.copy(computer = null)) })
                ui.snapshot?.computers?.forEach { computer -> DropdownMenuItem(
                    text = { Text(listOfNotNull(computer.name, computer.build).joinToString(" · ")) },
                    onClick = { computers = false; controller.query(ui.query.copy(computer = computer.key)) }) }
            }
        }
        FilterChip(selected = ui.query.unread, onClick = { controller.query(ui.query.copy(unread = !ui.query.unread)) },
            label = { Text("Unread") }, modifier = Modifier.padding(end = 12.dp))
    }
    if (ui.loading || ui.navigating || ui.snapshot?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
    ui.error?.let { message -> Column(Modifier.padding(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = controller::retry) { Text("Retry") }
    } }
    ui.snapshot?.status?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    LazyColumn(Modifier.weight(1f)) {
        val rows = ui.snapshot?.rows.orEmpty()
        if (rows.isEmpty() && !ui.loading && ui.error == null) item { Text("No matches", Modifier.padding(20.dp)) }
        items(rows, key = { it.key }, contentType = { it.kind }) { row ->
            when (row.kind) {
                "workspace" -> Column(Modifier.padding(start = if (row.depth == 1) 24.dp else 0.dp)) {
                    NativeWorkspaceRow(row.workspace(), availability = row.availability,
                        onOpen = { if (row.canOpen) onOpen(row.key) }, onAction = { _, _ -> })
                }
                "group" -> NativeGroupHeaderRow(NativeGroup(row.key, row.title, !row.expanded, row.pinned, iconSymbol = row.iconSymbol),
                    row.expanded, NativeWorkspaceUnread(row.unread, row.count),
                    onOpen = if (row.canOpen) ({ onOpen(row.key) }) else null, canEdit = false,
                    onToggle = { controller.query(ui.query.copy(groupExpansion = ui.query.groupExpansion + (row.key to !row.expanded))) }, onAction = { _, _ -> })
                "footer" -> HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 5.dp))
                "heading" -> Text(runCatching { LocalDate.parse(row.title).let { date -> when (date) {
                    LocalDate.now() -> "Today"
                    LocalDate.now().minusDays(1) -> "Yesterday"
                    else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                } } }.getOrDefault(row.title), Modifier.padding(18.dp), fontWeight = FontWeight.SemiBold)
                "updates" -> TextButton(onClick = { controller.query(ui.query.copy(expanded =
                    if (row.expanded) ui.query.expanded - row.key else ui.query.expanded + row.key)) }) {
                    Text(if (row.expanded) "Hide updates" else row.title)
                }
                "notification" -> Column(Modifier.fillMaxWidth().semantics { stateDescription = if (row.unread) "Unread" else "Read" }.clickable(enabled = row.canOpen) { onOpen(row.key) }
                    .padding(start = if (row.depth == 1) 38.dp else 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.unread) Text("●", Modifier.padding(end = 6.dp).clearAndSetSemantics { }, color = Color(0xFF76B9FF), style = MaterialTheme.typography.labelSmall)
                        Text(row.title, fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9FA8)) }
                    row.preview?.let { Text(it, maxLines = 3, style = MaterialTheme.typography.bodyMedium) }
                    row.computer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9B9FA8)) }
                }
            }
        }
        if (ui.more) item { TextButton(onClick = controller::more) { Text("Load more") } }
    }
    NativePrimaryNavigation(ui.query.notifications, ui.snapshot?.unread ?: 0, ui.search,
        onTab = { onFinishSearch(false); controller.tab(it) }, onBeginSearch = controller::beginSearch,
        onEdit = controller::edit, onSubmit = { onFinishSearch(false) }, onCancel = { onFinishSearch(true) }, sidebar = true)
}
