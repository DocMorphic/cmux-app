package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
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
    var filters by remember { mutableStateOf(false) }
    var showOrder by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val actions = ui.snapshot?.actions.orEmpty().associateBy { it.kind }
    val filter = NativeWorkspaceFilter(ui.query.workspaceUnread, ui.query.machines)
    LaunchedEffect(ui.query.notifications, ui.query.computer) { filters = false; showOrder = false }
    if (showOrder) key(ui.orderGeneration) {
        NativeComputerOrderSheet(ui.snapshot?.computers.orEmpty().map { NativeSortComputer(it.key, it.name, buildLabel = it.build) },
            onDismiss = { showOrder = false }, error = ui.actionError, saving = ui.saving,
            save = { ids -> scope.launch { controller.sort(RoutedSidebarSort.Order(ids)) } })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ui.query.notifications) "Notifications" else "Workspaces", Modifier.weight(1f).padding(18.dp), fontWeight = FontWeight.SemiBold)
        NativeWorkspaceSidebarToggle()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        actions[RoutedSidebarActionKind.SETTINGS]?.let { action ->
            IconButton(onClick = { onOpen(action.key) }, enabled = !ui.navigating) {
                Image(painterResource(R.drawable.cmux_logo), "cmux settings", Modifier.size(24.dp))
            }
        }
        actions[RoutedSidebarActionKind.COMPUTERS]?.let { action ->
            IconButton(onClick = { onOpen(action.key) }, enabled = !ui.navigating) {
                Icon(painterResource(R.drawable.ic_computer_desktop), "Manage computers", Modifier.size(22.dp))
            }
        }
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
        if (ui.query.notifications) Box {
            IconButton(onClick = { filters = true }) {
                Icon(painterResource(if (ui.query.notificationUnread) R.drawable.ic_feed_filter_active else R.drawable.ic_feed_filter), "Notification filter")
            }
            DropdownMenu(filters, onDismissRequest = { filters = false }) {
                DropdownMenuItem(text = { Text("All Notifications") }, leadingIcon = { Text(if (!ui.query.notificationUnread) "✓" else " ") },
                    onClick = { filters = false; controller.query(ui.query.withUnread(false)) })
                DropdownMenuItem(text = { Text("Unread") }, leadingIcon = { Text(if (ui.query.notificationUnread) "✓" else " ") },
                    onClick = { filters = false; controller.query(ui.query.withUnread(true)) })
            }
        } else NativeWorkspaceFilterMenu(filter, ui.snapshot?.filterMachines.orEmpty().map { NativeWorkspaceFilterMachine(it.key, it.name, it.build) },
            filters, { filters = it }, onChange = { controller.query(ui.query.copy(workspaceUnread = it.unread, machines = it.machines)) },
            sortMode = ui.snapshot?.sortMode, onSort = { mode -> scope.launch {
                if (controller.sort(RoutedSidebarSort.Mode(mode)) && mode == NativeWorkspaceSortMode.PRIORITY) showOrder = true
            } }, onOrder = { showOrder = true })
    }
    if (ui.loading || ui.navigating || ui.saving || ui.snapshot?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
    (ui.actionError ?: ui.error)?.let { message -> Column(Modifier.padding(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = controller::retry) { Text("Retry") }
    } }
    ui.snapshot?.status?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
    LazyColumn(Modifier.weight(1f)) {
        val rows = ui.snapshot?.rows.orEmpty()
        if (rows.isEmpty() && !ui.loading && ui.error == null) item {
            Text(when {
                ui.query.text.isNotBlank() -> "No matches"
                ui.query.notifications -> if (ui.query.notificationUnread) "No unread notifications." else "No notifications yet."
                filter.unread && filter.machines.isNotEmpty() -> "No unread workspaces on the selected machines"
                filter.unread -> "No unread workspaces"
                filter.machines.isNotEmpty() -> "No workspaces on the selected machines"
                else -> "No workspaces yet."
            }, Modifier.padding(20.dp))
        }
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
        onEdit = controller::edit, onSubmit = { onFinishSearch(false) }, onCancel = { onFinishSearch(true) }, sidebar = true,
        onNewTask = actions[RoutedSidebarActionKind.NEW_TASK]?.let { action -> { onOpen(action.key) } })
}
