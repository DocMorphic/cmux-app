package io.github.docmorphic.cmuxapp

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    locale: Locale = Locale.getDefault()
) {
    val entries = remember(sources) { aggregateNativeAgentFeed(sources) }
    val currentEntries by rememberUpdatedState(entries.associateBy { it.key })
    val currentSession by rememberUpdatedState(session)
    val currentRead by rememberUpdatedState(readState)
    val updateRead by rememberUpdatedState(onReadState)
    val scope = rememberCoroutineScope()
    var composer by remember { mutableStateOf<AgentFeedCompose?>(null) }
    var reading by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val index = remember(entries, locale) { NativeSearchIndex(entries.map { it.key to it.item.searchFields(computerName(it.source.mac)) }, locale, notification = true) }
    val matches = remember(index, query) { index.matches(query) }
    val visible = remember(entries, matches, readState, needsInputOnly) {
        entries.filter { it.item.notable && it.key in matches && (!needsInputOnly || readState.needsInput(it)) }
    }
    val connected = sources.any { it.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY in it.capabilities }
    val updating = sources.any { it.agentFeed.loading }
    val noSupport = sources.isNotEmpty() && sources.all { it.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY !in it.capabilities }
    fun act(entry: NativeAgentFeedEntry, decision: AgentFeedDecision) {
        scope.launch {
            try {
                val live = checkNotNull(currentEntries[entry.key]) { "This Feed item is no longer available" }
                val active = checkNotNull(currentSession(live.source.mac)) { "Connect to this computer to answer" }
                if (active.decide(live.item, decision)) updateRead(currentRead.interacted(live))
            } catch (error: Exception) { if (error is CancellationException) throw error; actionError = error.message }
        }
    }
    PullToRefreshBox(isRefreshing = updating, onRefresh = onRefresh, modifier = modifier.testTag("AgentFeed")) {
        LazyColumn(Modifier.fillMaxSize().testTag("AgentFeedList")) {
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
            items(visible, key = { it.key }) { entry ->
                val item = entry.item
                val ready = entry.source.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY in entry.source.capabilities
                val pending = item.id in entry.source.agentFeed.pending
                val failure = entry.source.agentFeed.failures[item.id]
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).testTag("AgentFeedRow:${item.id}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.source.ifBlank { "Agent" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        if (readState.needsInput(entry)) Text("● ", color = agentFeedAccent)
                        Text(DateUtils.getRelativeTimeSpanString((item.createdAt * 1000).toLong(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                            color = agentFeedMuted, fontSize = 11.sp)
                    }
                    Text(listOfNotNull(computerName(entry.source.mac), item.workspaceTitle, item.surfaceTitle).joinToString(" · "),
                        color = agentFeedMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.title ?: when (item.kind) {
                        AgentFeedKind.PERMISSION -> "Permission needed${item.toolName?.let { ": $it" }.orEmpty()}"
                        AgentFeedKind.PLAN -> "Plan ready for review"
                        AgentFeedKind.QUESTION -> "Your answer is needed"
                        AgentFeedKind.STOP -> "Turn completed"
                        AgentFeedKind.TOOL_RESULT -> "Tool failed"
                        AgentFeedKind.TODOS -> "Task updates"
                        AgentFeedKind.UNSUPPORTED -> "Agent activity — update the app for this event type"
                        else -> "Agent response"
                    }, fontWeight = FontWeight.Medium)
                    item.context["last_user_message"]?.let { quoted ->
                        Text(quoted, Modifier.fillMaxWidth().background(Color(0xFF24272D)).padding(12.dp),
                            color = agentFeedMuted, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    val body = item.fullTextPreview ?: item.text ?: item.planSummary ?: item.plan ?: item.toolResult ?: item.reason
                        ?: item.context["assistant_preamble"] ?: item.toolInput
                    body?.let { SelectionContainer { Text(it, maxLines = 12, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) } }
                    if (body != null || item.fullTextTruncated) TextButton(onClick = { reading = entry.key }, enabled = ready) { Text("Read full message") }
                    item.toolName?.let { Text(it, color = agentFeedMuted, fontSize = 12.sp) }
                    if (item.status == AgentFeedStatus.RESOLVED) Text(
                        "Answered: " + (item.decision?.let { it.mode ?: it.selections.joinToString(", ").ifEmpty { it.kind } } ?: "Resolved"), color = agentFeedMuted)
                    if (item.status == AgentFeedStatus.EXPIRED) Text("Request expired", color = agentFeedMuted)
                    if (item.needsInput) {
                        when (item.kind) {
                            AgentFeedKind.PERMISSION -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("once" to "Allow Once", "always" to "Always Allow", "deny" to "Deny").forEach { (mode, label) ->
                                    OutlinedButton(onClick = { act(entry, AgentFeedDecision("permission", mode)) }, enabled = ready && !pending) { Text(label) }
                                }
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expanded = true }, enabled = ready && !pending) { Text("More") }
                                    DropdownMenu(expanded, { expanded = false }) {
                                        listOf("all" to "Allow All", "bypass" to "Bypass Permissions").forEach { (mode, label) ->
                                            DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; act(entry, AgentFeedDecision("permission", mode)) })
                                        }
                                    }
                                }
                            }
                            AgentFeedKind.PLAN -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = { composer = AgentFeedCompose(entry.key, "plan") }, enabled = ready && !pending) { Text("Review plan") }
                                TextButton(onClick = { composer = AgentFeedCompose(entry.key, "revise") }, enabled = ready && !pending) { Text("Revise") }
                                TextButton(onClick = { act(entry, AgentFeedDecision("exit_plan", "deny")) }, enabled = ready && !pending) { Text("Deny") }
                            }
                            AgentFeedKind.QUESTION -> {
                                item.questions.firstOrNull()?.let { Text(it.prompt) }
                                Button(onClick = { composer = AgentFeedCompose(entry.key, "question") }, enabled = ready && !pending && item.questions.isNotEmpty()) { Text("Answer") }
                            }
                            else -> Unit
                        }
                    }
                    item.replyText?.let { Text("You replied: $it", Modifier.fillMaxWidth().background(Color(0xFF20354B)).padding(12.dp)) }
                    failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.supportsTerminalReply && item.replyText == null) TextButton(onClick = { composer = AgentFeedCompose(entry.key, "terminal") }, enabled = ready && !pending) { Text(if (failure != null) "Review reply" else "Reply") }
                        if (item.workspaceId != null) TextButton(onClick = { updateRead(currentRead.interacted(entry)); onOpen(entry, false) }) { Text("Open workspace") }
                        if (item.surfaceId != null && item.workspaceId != null) TextButton(onClick = { updateRead(currentRead.interacted(entry)); onOpen(entry, true) }) { Text("Open tab") }
                        TextButton(onClick = { updateRead(currentRead.triage(entry, !currentRead.needsInput(entry))) }) { Text(if (readState.needsInput(entry)) "Done" else "Needs Input") }
                    }
                    if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                HorizontalDivider(color = Color(0xFF292C31))
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
    var selected by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var custom by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val answers = item.questions.map { it.answer(selected[it.id].orEmpty(), custom[it.id].orEmpty()) }
    val pending = item.id in entry.source.agentFeed.pending
    val ready = entry.source.availability == NativeFeedAvailability.CONNECTED && !pending
    AlertDialog(onDismissRequest = onDismiss, title = { Text(when (mode) { "terminal" -> "Reply to agent"; "question" -> "Answer questions"; "revise" -> "Revise plan"; else -> "Approve plan" }) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == "question") item.questions.forEach { question ->
                Text(question.header ?: question.prompt, fontWeight = FontWeight.SemiBold)
                if (question.header != null) Text(question.prompt)
                question.options.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(option.id in selected[question.id].orEmpty(), onCheckedChange = { checked ->
                            selected = selected + (question.id to if (checked) {
                                if (question.multiSelect) selected[question.id].orEmpty() + option.id else setOf(option.id)
                            } else selected[question.id].orEmpty() - option.id)
                        }, enabled = !pending)
                        Column { Text(option.label); option.description?.let { Text(it, color = agentFeedMuted) } }
                    }
                }
                OutlinedTextField(custom[question.id].orEmpty(), { custom = custom + (question.id to it) }, label = { Text("Your own answer") }, enabled = !pending)
            } else if (mode == "plan") {
                SelectionContainer { Text(item.plan ?: item.planSummary ?: "Review this plan before approving.") }
                listOf("manual" to "Approve (manual edits)", "autoAccept" to "Approve, auto-accept edits",
                    "bypassPermissions" to "Approve, bypass permissions", "ultraplan" to "Approve as ultraplan").forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(planMode == value, { planMode = value }, enabled = !pending); Text(label)
                    }
                }
            } else OutlinedTextField(draft, { draft = it }, minLines = 3, label = { Text(if (mode == "revise") "Requested changes" else "Message") }, enabled = !pending)
            entry.source.agentFeed.failures[item.id]?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = ready && when (mode) {
            "question" -> answers.isNotEmpty() && answers.all { it != null }
            "plan" -> item.needsInput
            else -> draft.isNotBlank()
        }, onClick = { when (mode) {
            "terminal" -> onSubmit(null, draft)
            "question" -> onSubmit(AgentFeedDecision("question", selections = answers.filterNotNull()), null)
            "plan" -> onSubmit(AgentFeedDecision("exit_plan", planMode), null)
            else -> onSubmit(AgentFeedDecision("exit_plan", "revise", feedback = draft.trim()), null)
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
