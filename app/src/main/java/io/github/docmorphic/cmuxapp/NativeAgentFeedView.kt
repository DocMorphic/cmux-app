package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

private data class AgentFeedCompose(val key: String, val mode: String)
private val agentFeedMuted = Color(0xFF9CA3AF)
private val agentFeedAccent = Color(0xFF76B9FF)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NativeAgentFeedView(
    sources: Collection<NativeFeedSource>, query: String, needsInputOnly: Boolean,
    readState: NativeAgentFeedReadState, onReadState: (NativeAgentFeedReadState) -> Unit,
    session: (NativeCredentialStore.PairedMac) -> NativeAgentFeedSession?, computerName: (NativeCredentialStore.PairedMac) -> String,
    onOpen: (NativeAgentFeedEntry, Boolean) -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(), display: NativeDisplayPreferences = NativeDisplayPreferences()
) {
    val entries = remember(sources) { aggregateNativeAgentFeed(sources) }
    val currentEntries by rememberUpdatedState(entries.associateBy { it.key })
    val models = remember(entries.map { it.key to it.item }) { entries.associate { it.key to NativeAgentFeedPresentation.from(it.item) } }
    val now = remember { System.currentTimeMillis() / 1000.0 }
    val listState = rememberLazyListState()
    val swipes = remember { WorkspaceSwipeCoordinator() }
    val currentSession by rememberUpdatedState(session)
    val currentRead by rememberUpdatedState(readState)
    val updateRead by rememberUpdatedState(onReadState)
    val scope = rememberCoroutineScope()
    var composer by remember { mutableStateOf<AgentFeedCompose?>(null) }
    var reading by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val index = remember(entries, locale) { NativeSearchIndex(entries.map { it.key to it.item.searchFields(computerName(it.source.mac)) }, locale, notification = true) }
    val matches = remember(index, query) { index.matches(query) }
    val visible = remember(entries, models, matches, readState, needsInputOnly) {
        entries.filter { it.item.notable && models[it.key]?.visible == true && it.key in matches && (!needsInputOnly || readState.needsInput(it)) }
    }
    val held = listState.isScrollInProgress || swipes.activeKey != null
    val rendered = rememberWorkspacePresentationRows(visible, held) { it.key }
    WorkspaceViewportAnchorEffect(listState, rendered.map { it.key }, swipes.activeKey != null)
    val connected = sources.any { it.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY in it.capabilities }
    val updating = sources.any { it.agentFeed.loading }
    val noSupport = sources.isNotEmpty() && sources.all { it.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY !in it.capabilities }
    fun act(entry: NativeAgentFeedEntry, decision: AgentFeedDecision) {
        scope.launch {
            try {
                val live = checkNotNull(currentEntries[entry.key]) { "This Feed item is no longer available" }
                val active = checkNotNull(currentSession(live.source.mac)) { "Connect to this computer to answer" }
                check(live.item.requestId == entry.item.requestId && live.item.kind == entry.item.kind &&
                    (decision.kind != "question" || live.item.questions == entry.item.questions)) { "This request changed. Review it again before answering." }
                if (active.decide(entry.item, decision)) updateRead(currentRead.interacted(live))
            } catch (error: Exception) { if (error is CancellationException) throw error; actionError = error.message }
        }
    }
    PullToRefreshBox(isRefreshing = updating, onRefresh = onRefresh, modifier = modifier.testTag("AgentFeed")) {
        CompositionLocalProvider(LocalWorkspaceGeometryHeld provides held, LocalWorkspaceSwipeCoordinator provides swipes) {
        LazyColumn(Modifier.fillMaxSize().testTag("AgentFeedList"), state = listState) {
            if (!connected && entries.isNotEmpty()) item("availability") {
                Text("Reconnect to answer requests and load full messages.", Modifier.padding(16.dp), color = agentFeedMuted)
            }
            if (actionError != null) item("error") {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(actionError!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { actionError = null }) { Text("Dismiss") }
                }
            }
            if (sources.any { it.agentFeed.error != null }) item("refresh-error") {
                TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Activity could not refresh. Retry") }
            }
            if (visible.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when {
                        query.isNotBlank() -> "No matching activity"
                        noSupport -> "Update cmux on your Mac to use Feed"
                        sources.isEmpty() -> "Connect a Mac to see agent activity"
                        updating -> "Loading agent activity…"
                        !connected -> "Agent Feed is unavailable"
                        needsInputOnly -> "Nothing needs your input"
                        else -> "No agent activity yet"
                    }, fontWeight = FontWeight.SemiBold)
                    Text("Agent requests and responses appear here. Notifications have their own tab.",
                        Modifier.padding(top = 8.dp), color = agentFeedMuted)
                }
            }
            items(rendered, key = { it.key }) { entry ->
                WorkspacePresentationRow(entry.key in currentEntries, { entry.key in currentEntries }) {
                    CompositionLocalProvider(LocalWorkspaceSwipeKey provides entry.key) {
                        NativeAgentFeedRow(entry, models[entry.key] ?: NativeAgentFeedPresentation.from(entry.item),
                            readState.needsInput(entry), display, agentFeedTimeLabel(entry.item.createdAt, now, locale),
                            onRead = { needs -> currentEntries[entry.key]?.let { updateRead(currentRead.triage(it, needs)) } },
                            onOpen = { tab -> currentEntries[entry.key]?.let { updateRead(currentRead.interacted(it)); onOpen(it, tab) } },
                            onCompose = { composer = AgentFeedCompose(entry.key, it) }, onDecision = { act(entry, it) },
                            onFullText = { reading = entry.key })
                    }
                }
                HorizontalDivider(color = Color(0xFF292C31))
            }
        }
        }
    }
    composer?.let { compose ->
        val entry = currentEntries[compose.key]
        if (entry == null) LaunchedEffect(compose) { composer = null }
        else key(compose) {
            AgentFeedComposer(entry, compose.mode, onDismiss = { composer = null }, onSubmit = { decision, text ->
                if (text == null) { checkNotNull(decision); act(entry, decision); composer = null }
                else scope.launch {
                    try {
                        val live = checkNotNull(currentEntries[entry.key]) { "This Feed item is no longer available" }
                        val active = checkNotNull(currentSession(live.source.mac)) { "Connect to this computer to reply" }
                        if (active.terminalReply(live.item, text)) { updateRead(currentRead.interacted(live)); composer = null }
                    } catch (error: Exception) { if (error is CancellationException) throw error; actionError = error.message }
                }
            })
        }
    }
    reading?.let { key ->
        val entry = currentEntries[key]
        if (entry == null) LaunchedEffect(key) { reading = null }
        else key(key) { AgentFeedFullText(entry, session, { updateRead(currentRead.interacted(entry)) }, { reading = null }) }
    }
}

@Composable
private fun AgentFeedComposer(entry: NativeAgentFeedEntry, mode: String, onDismiss: () -> Unit,
    onSubmit: (AgentFeedDecision?, String?) -> Unit) {
    val item = entry.item
    var draft by rememberSaveable { mutableStateOf(entry.source.agentFeed.failures[item.id]?.draft.orEmpty()) }
    var planMode by rememberSaveable { mutableStateOf(item.defaultMode?.takeIf { it in setOf("manual", "autoAccept", "bypassPermissions", "ultraplan") } ?: "manual") }
    val pending = item.id in entry.source.agentFeed.pending
    val ready = entry.source.availability == NativeFeedAvailability.CONNECTED && !pending
    AlertDialog(onDismissRequest = onDismiss, title = { Text(when (mode) { "terminal" -> "Reply to agent"; "revise" -> "Revise plan"; else -> "Approve plan" }) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == "plan") {
                AgentFeedMarkdownText(NativeAgentFeedPresentation.planText(item.plan) ?: item.planSummary ?: "Review this plan before approving.")
                listOf("manual" to "Approve (manual edits)", "autoAccept" to "Approve, auto-accept edits",
                    "bypassPermissions" to "Approve, bypass permissions", "ultraplan" to "Approve as ultraplan").forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(planMode == value, { planMode = value }, enabled = !pending); Text(label)
                    }
                }
            } else OutlinedTextField(draft, { draft = it }, minLines = 3, label = { Text(if (mode == "revise") "Requested changes" else "Message") }, enabled = !pending)
            entry.source.agentFeed.failures[item.id]?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = ready && when (mode) {
            "plan" -> item.needsInput
            else -> draft.isNotBlank()
        }, onClick = { when (mode) {
            "terminal" -> onSubmit(null, draft)
            "plan" -> onSubmit(AgentFeedDecision("exit_plan", planMode), null)
            else -> onSubmit(AgentFeedDecision("exit_plan", "manual", feedback = draft.trim()), null)
        } }) { Text(if (pending) "Sending…" else "Send") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun AgentFeedFullText(entry: NativeAgentFeedEntry,
    session: (NativeCredentialStore.PairedMac) -> NativeAgentFeedSession?, onRead: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }; var raw by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        error = null
        try { text = checkNotNull(session(entry.source.mac)) { "Connect to this computer to read the message" }.fullText(entry.item); onRead() }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Could not load the message" }
    }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Full message", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { raw = !raw }, enabled = text != null) { Text(if (raw) "Formatted" else "Source") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                when {
                    error != null -> Column(Modifier.padding(20.dp)) { Text(error!!); TextButton(onClick = { attempt++ }) { Text("Retry") } }
                    text == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    raw -> SelectionContainer { Text(text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) }
                    else -> MarkdownWebPreview(text!!, remember { MarkdownViewportState() }, onFailure = { raw = true })
                }
            }
        }
    }
}
