package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

private val chiefAccent = Color(0xFF76B9FF)
private val chiefMuted = Color(0xFF9B9FA8)

@Composable
internal fun NativeChiefView(computer: NativeChiefComputerSession, active: Boolean, modifier: Modifier = Modifier) {
    LaunchedEffect(computer, active) { computer.setActive(active) }
    DisposableEffect(computer) { onDispose { computer.setActive(false) } }
    val selected = computer.selected
    val transcript = computer.transcript()
    key(computer) {
        val pages = rememberSaveableStateHolder()
        pages.SaveableStateProvider(selected?.id?.let { "conversation:$it" } ?: "home") {
        if (selected != null && transcript != null) key(selected.id) {
            NativeChiefTranscriptView(selected, transcript, computer::back, modifier)
        } else NativeChiefHomeView(computer, modifier)
        }
    }
}

@Composable
private fun ChiefAvatar(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.chief_avatar), null, modifier.clip(CircleShape).background(Color(0xFF25282E)).padding(4.dp))
}

@Composable
private fun NativeChiefHomeView(computer: NativeChiefComputerSession, modifier: Modifier) {
    val state by computer.home.state.collectAsState()
    val query = computer.query
    val rows = state.matching(query)
    val pinned = state.pinned
    val refresh = { if (computer.current()) computer.home.refresh() }
    Column(modifier.fillMaxSize().testTag("ChiefHome")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Home", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = refresh) { Text("Refresh") }
        }
        OutlinedTextField(query, computer::search, Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("ChiefSearch"),
            label = { Text("Search Chief") }, singleLine = true)
        when {
            state.loading && state.conversations.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Loading Chief") }
            }
            state.conversations.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state.error != null) "Chief unavailable" else "No Chief conversations", style = MaterialTheme.typography.titleLarge)
                Text(state.error ?: "Start a conversation from Chief on your Mac.", color = chiefMuted)
                TextButton(onClick = refresh) { Text("Try Again") }
            }
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ChiefConversations")) {
                state.error?.let { error -> item { Text(error, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.error) } }
                if (pinned != null && pinned in rows) item(key = "pinned:${pinned.id}") {
                    Column(Modifier.fillMaxWidth().clickable { computer.open(pinned) }.padding(24.dp)
                        .testTag("ChiefPinnedConversation").semantics(mergeDescendants = true) { role = Role.Button },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            ChiefAvatar(Modifier.size(88.dp))
                            if (pinned.working) CircularProgressIndicator(Modifier.size(22.dp).align(Alignment.TopEnd))
                            else if (pinned.unread > 0) Badge(Modifier.align(Alignment.TopEnd)) { Text(pinned.unread.toString()) }
                        }
                        Text(pinned.title, fontWeight = FontWeight.SemiBold)
                        Text(pinned.preview.ifEmpty { "Ask Chief anything" }, color = chiefMuted, maxLines = 2)
                        if (pinned.working) Text("Working", color = chiefAccent)
                    }
                }
                val recent = rows.filter { it.id != pinned?.id }
                if (pinned != null && pinned in rows && recent.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(24.dp).testTag("ChiefPinnedOnlyInvitation"),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Chief is ready when you are", style = MaterialTheme.typography.titleMedium)
                        Text("Start a conversation from Chief on your Mac. New messages will appear here.", color = chiefMuted)
                    }
                }
                if (recent.isNotEmpty()) item { Text("Recent conversations", Modifier.padding(18.dp).semantics { heading() }, color = chiefMuted) }
                items(recent, key = { it.id }) { conversation ->
                    Row(Modifier.fillMaxWidth().clickable { computer.open(conversation) }.padding(horizontal = 18.dp, vertical = 12.dp)
                        .testTag("ChiefConversation:${conversation.id}").semantics(mergeDescendants = true) { role = Role.Button },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChiefAvatar(Modifier.size(45.dp))
                        Column(Modifier.weight(1f)) {
                            Text(conversation.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(conversation.preview.ifEmpty { "No messages yet" }, color = chiefMuted, maxLines = 2)
                            if (conversation.working) Text("Working", color = chiefAccent)
                        }
                        if (conversation.unread > 0) Badge { Text(conversation.unread.toString()) }
                    }
                }
                if (rows.isEmpty()) item { Text("No matching conversations", Modifier.padding(24.dp), color = chiefMuted) }
            }
        }
    }
}

@Composable
private fun NativeChiefTranscriptView(conversation: NativeChiefConversation, session: NativeChiefTranscriptSession,
    onBack: () -> Unit, modifier: Modifier) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }
    fun act(action: suspend () -> Unit) { scope.launch {
        actionError = null
        try { action() } catch (error: Exception) {
            currentCoroutineContext().ensureActive(); actionError = error.message ?: "Chief action unavailable"
        }
    } }
    val list = rememberLazyListState()
    var answerMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val displayedQuestion = answerMessage?.let { id -> state.messages.singleOrNull { it.id == id } }
        ?.let(NativeChiefPresentation::pendingPart)?.question
    val currentState by rememberUpdatedState(state)
    val atBottom = !list.canScrollForward
    LaunchedEffect(state.messages.lastOrNull()?.id, state.outgoing.lastOrNull()?.clientMessageId) {
        if (atBottom && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    LaunchedEffect(session, list, state.connected) {
        if (state.connected) snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.key } }.collect { keys ->
            val visible = keys.filterIsInstance<String>().filter { it.startsWith("message:") }.map { it.removePrefix("message:") }.toSet()
            currentState.messages.filter { it.id in visible }.maxOfOrNull { it.seq }?.let {
                try { session.markRead(it) } catch (error: Exception) { currentCoroutineContext().ensureActive() }
            }
        }
    }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().imePadding().testTag("ChiefTranscript")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Home") }
            ChiefAvatar(Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(conversation.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                Text("Runs your agents on your Mac", color = chiefMuted, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { act { session.stop() } }, enabled = "stop" !in state.deciding) { Text("Stop Chief") }
        }
        state.error?.let { error -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = session::refresh) { Text("Reconnect") }
        } }
        actionError?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ChiefMessages"), state = list,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.hasMore) item(key = "older") {
                TextButton(onClick = { act { session.loadOlder() } }, enabled = !state.loadingOlder) {
                    Text(if (state.loadingOlder) "Loading earlier messages…" else "Earlier messages")
                }
            }
            items(state.messages, key = { "message:${it.id}" }) { message ->
                val part = NativeChiefPresentation.pendingPart(message)
                val actionable = message.id !in state.deciding && message.id !in state.committed
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.authorKind == "human") Alignment.End else Alignment.Start) {
                    Text(message.author.ifEmpty { message.authorId }, color = chiefMuted, style = MaterialTheme.typography.labelSmall)
                    Surface(shape = RoundedCornerShape(18.dp), color = if (message.authorKind == "human") Color(0xFF164D83) else Color(0xFF272B32),
                        modifier = Modifier.widthIn(max = 560.dp).testTag("ChiefMessage:${message.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectionContainer { AgentFeedMarkdownText(NativeChiefPresentation.receipt(message)
                                ?: NativeChiefPresentation.displayText(message), fontSize = 16) }
                            part?.approval?.let { approval ->
                                Text(approval.prompt, fontWeight = FontWeight.SemiBold)
                                approval.options.filterNot { it.startsWith("always:") }.forEach { choice ->
                                    OutlinedButton(onClick = { act { session.approve(message.id, choice) } }, enabled = actionable,
                                        modifier = Modifier.testTag("ChiefApprove:${message.id}:$choice")) { Text(choice) }
                                }
                            }
                            part?.question?.let { question ->
                                val compact = NativeChiefPresentation.compactQuestion(question)
                                if (compact == null) OutlinedButton(onClick = { answerMessage = message.id }, enabled = actionable,
                                    modifier = Modifier.testTag("ChiefAnswer:${message.id}")) { Text("Answer questions") }
                                else compact.options.forEach { option ->
                                    OutlinedButton(onClick = { act { session.answer(message.id,
                                        NativeChiefQuestionForm(question).toggle(compact.id, option.id)) } }, enabled = actionable) { Text(option.label) }
                                }
                            }
                            if (message.id in state.committed) Text("Answer sent. Waiting for your Mac…", color = chiefMuted)
                            state.actionErrors[message.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            items(state.outgoing, key = { "outgoing:${it.clientMessageId}" }) { outgoing ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF164D83)) { SelectionContainer {
                        Text(outgoing.text, Modifier.padding(12.dp))
                    } }
                    Text(when (outgoing.delivery) {
                        NativeChiefDelivery.SENDING -> "Sending…"
                        NativeChiefDelivery.QUEUED -> "Queued"
                        NativeChiefDelivery.SENT -> "Sent"
                        NativeChiefDelivery.UNCONFIRMED -> "Delivery unconfirmed"
                        NativeChiefDelivery.REJECTED -> "Not sent"
                    }, color = chiefMuted, style = MaterialTheme.typography.labelSmall)
                    outgoing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (state.working) item(key = "working") { Text("Chief is working…", color = chiefAccent) }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.draft, session::editDraft, Modifier.weight(1f).heightIn(max = 160.dp).testTag("ChiefComposer"),
                placeholder = { Text("Message Chief") }, maxLines = 5)
            Button(onClick = { act { session.send() } }, enabled = state.draft.isNotBlank() && state.connected,
                modifier = Modifier.testTag("ChiefSend")) { Text("Send") }
        }
    }
    if (answerMessage != null && displayedQuestion != null) NativeChiefQuestionSheet(displayedQuestion,
        busy = answerMessage in state.deciding, error = state.actionErrors[answerMessage], onDismiss = { answerMessage = null }) { form ->
        val id = answerMessage ?: return@NativeChiefQuestionSheet
        act { if (session.answer(id, form)) answerMessage = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeChiefQuestionSheet(question: NativeChiefQuestion, busy: Boolean, error: String?, onDismiss: () -> Unit,
    onSend: (NativeChiefQuestionForm) -> Unit) {
    val saver = remember(question) { Saver<NativeChiefQuestionForm, String>(save = { form -> JSONObject()
        .put("other", form.otherText).put("selections", JSONObject().apply { form.selections.forEach { (id, choices) -> put(id, JSONArray(choices.toList())) } }).toString()
    }, restore = { raw -> runCatching {
        val json = JSONObject(raw); val chosen = json.getJSONObject("selections")
        NativeChiefQuestionForm(question, question.items.associate { item ->
            val values = chosen.optJSONArray(item.id)
            item.id to (0 until (values?.length() ?: 0)).map { values!!.getString(it) }.filter { value -> item.options.any { it.id == value } }.toSet()
        }, json.getString("other"))
    }.getOrNull() }) }
    var form by rememberSaveable(question, stateSaver = saver) { mutableStateOf(NativeChiefQuestionForm(question)) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden }),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy),
        modifier = Modifier.testTag("ChiefQuestionSheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                Text("Answer Chief", Modifier.weight(1f).semantics { heading() }, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { onSend(form) }, enabled = form.canSubmit && !busy,
                    modifier = Modifier.testTag("ChiefQuestionSubmit")) { Text(question.submitLabel ?: "Send") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Chief needs your input", style = MaterialTheme.typography.titleMedium)
            Text("Choose an answer for each question before sending it back to your Mac.", color = chiefMuted)
            if (form.otherTarget != null) Text("You can type one custom answer; it is sent to the first question that accepts it.", color = chiefMuted)
            question.items.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.header?.takeIf(String::isNotEmpty)?.let { Text(it, color = chiefMuted) }
                    Text(item.prompt, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    if (item.multiSelect) Text("Select all that apply", color = chiefMuted)
                    item.options.forEach { option ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { form = form.toggle(item.id, option.id) }
                            .testTag("ChiefQuestionOption:${item.id}:${option.id}").semantics(mergeDescendants = true) {
                                role = if (item.multiSelect) Role.Checkbox else Role.RadioButton
                                selected = option.id in form.selections[item.id].orEmpty()
                            }, verticalAlignment = Alignment.CenterVertically) {
                            if (item.multiSelect) Checkbox(option.id in form.selections[item.id].orEmpty(), null, enabled = !busy)
                            else RadioButton(option.id in form.selections[item.id].orEmpty(), null, enabled = !busy)
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(option.label)
                                option.detail?.let { Text(it, color = chiefMuted, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (option.id in form.selections[item.id].orEmpty()) option.preview?.let { preview ->
                            SelectionContainer { AgentFeedMarkdownText(preview.text, Modifier.padding(start = 16.dp),
                                monospaced = preview.format == NativeChiefQuestion.Preview.Format.MONOSPACE) }
                        }
                    }
                    if (item.id == form.otherTarget?.id) OutlinedTextField(form.otherText, { form = form.editOther(it) },
                        Modifier.fillMaxWidth().testTag("ChiefQuestionOther:${item.id}"), enabled = !busy, label = { Text("Other answer (optional)") })
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ChiefQuestionError")) }
            Spacer(Modifier.height(28.dp))
            }
        }
    }
}
