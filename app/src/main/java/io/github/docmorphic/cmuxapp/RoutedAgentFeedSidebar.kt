package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ColumnScope.RoutedAgentFeedSidebar(controller: RoutedSidebarController, ui: RoutedSidebarUi,
    feed: RoutedAgentFeedController, scopeKey: String, onOpen: (String) -> Unit, onNavigate: (String) -> Unit,
    finishSearch: (Boolean) -> Unit) {
    val state by feed.state.collectAsState()
    val currentNavigate by rememberUpdatedState(onNavigate)
    val actions = remember(feed) { object : AgentFeedTimelineActions {
        override suspend fun decide(entry: AgentFeedUiEntry, decision: AgentFeedDecision) = feed.decide(entry, decision)
        override suspend fun reply(entry: AgentFeedUiEntry, text: String) = feed.reply(entry, text)
        override suspend fun fullText(entry: AgentFeedUiEntry) = feed.fullText(entry)
        override fun read(entry: AgentFeedUiEntry, needsInput: Boolean?) = feed.read(entry, needsInput)
        override fun open(entry: AgentFeedUiEntry, tab: Boolean) = feed.open(entry, tab) { currentNavigate(it) }
    } }
    var computers by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    DisposableEffect(feed) { onDispose { feed.visible(false) } }
    LaunchedEffect(feed, ui.query) { feed.visible(true, ui.query) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Feed", Modifier.weight(1f).padding(18.dp), fontWeight = FontWeight.SemiBold)
        NativeWorkspaceSidebarToggle()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ui.snapshot?.actions?.firstOrNull { it.kind == RoutedSidebarActionKind.SETTINGS }?.let { action ->
            IconButton(onClick = { onOpen(action.key) }, enabled = !ui.navigating) {
                Image(painterResource(R.drawable.cmux_logo), "cmux settings", Modifier.size(24.dp))
            }
        }
        ui.snapshot?.actions?.firstOrNull { it.kind == RoutedSidebarActionKind.COMPUTERS }?.let { action ->
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
        Box {
            IconButton(onClick = { filters = true }) {
                Icon(painterResource(if (ui.query.feedNeedsInputOnly) R.drawable.ic_feed_filter_active else R.drawable.ic_feed_filter), "Feed filter")
            }
            DropdownMenu(filters, onDismissRequest = { filters = false }) {
                DropdownMenuItem(text = { Text("All Activity") }, onClick = { filters = false; controller.query(ui.query.copy(feedNeedsInputOnly = false)) })
                DropdownMenuItem(text = { Text("Needs Input (${ui.snapshot?.feedNeedsInput ?: 0})") }, onClick = { filters = false; controller.query(ui.query.copy(feedNeedsInputOnly = true)) })
            }
        }
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    (state.error ?: ui.error ?: ui.actionError)?.let { error -> Row(Modifier.padding(horizontal = 18.dp)) {
        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = feed::refresh) { Text("Retry") }
    } }
    if (state.hasSnapshot) AgentFeedTimeline(state.snapshot, ui.query.feedQuery, ui.query.feedNeedsInputOnly, actions, feed::refresh,
        Modifier.weight(1f), display = NativeDisplayPreferences(feedShowsTab = ui.snapshot?.feedShowsTab ?: false,
            feedBubbleQuotes = ui.snapshot?.feedBubbleQuotes ?: false), scopeKey = "$scopeKey:${ui.query.computer}")
    else Spacer(Modifier.weight(1f))
    NativePrimaryNavigation(false, ui.snapshot?.unread ?: 0, ui.search,
        onTab = { finishSearch(false); controller.tab(it) }, onBeginSearch = controller::beginSearch,
        onEdit = controller::edit, onSubmit = { finishSearch(false) }, onCancel = { finishSearch(true) }, sidebar = true,
        agentFeedTab = true, agentFeedCount = ui.snapshot?.feedNeedsInput ?: 0,
        onAgentFeed = { finishSearch(false); controller.feedTab() }, showsNotifications = ui.snapshot?.showsNotifications ?: true)
}
