package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.text.SimpleDateFormat
import java.util.Date

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
private fun ChiefTimestamp(at: Instant, tag: String) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val day = at.atZone(zone).toLocalDate()
    val today = Instant.now().atZone(zone).toLocalDate()
    val age = ChronoUnit.DAYS.between(day, today)
    val date = Date.from(at)
    val label = when (age) {
        0L -> stringResource(R.string.chief_timestamp_today)
        1L -> stringResource(R.string.chief_timestamp_yesterday)
        else -> {
            val skeleton = if (age < 7) "EEEE" else "EEEMMMd"
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
            remember(date, locale, pattern, zone) { SimpleDateFormat(pattern, locale).format(date) }
        }
    }
    val time = android.text.format.DateFormat.getTimeFormat(context).format(date)
    val format = if (age >= 7) stringResource(R.string.chief_timestamp_date_at_time) else "%1\$s %2\$s"
    val marker = "\u0001"
    val template = String.format(locale, format, marker, time)
    val position = template.indexOf(marker)
    val timestamp = buildAnnotatedString {
        if (position < 0) append(template) else {
            append(template.substring(0, position))
            val start = length; append(label); addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
            append(template.substring(position + 1))
        }
    }
    Text(timestamp, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp).testTag(tag),
        color = chiefMuted, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                    Column(Modifier.fillMaxWidth().height(480.dp).padding(24.dp).testTag("ChiefPinnedOnlyInvitation"),
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(conversation.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                                if (conversation.updatedAt.epochSecond != -62135769600L) Text(
                                    android.text.format.DateUtils.getRelativeTimeSpanString(conversation.updatedAt.toEpochMilli(),
                                        System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                        android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString(), color = chiefMuted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var failedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val displayedFailure = failedMessage?.let { id -> state.outgoing.singleOrNull { it.clientMessageId == id && it.delivery.isFailed } }
    val displayedQuestion = answerMessage?.let { id -> state.messages.singleOrNull { it.id == id } }
        ?.let(NativeChiefPresentation::pendingPart)?.question
    val timeline = remember(state.messages, state.outgoing, state.localClientIds, state.working) { NativeChiefTimeline.rows(state) }
    val currentTimeline by rememberUpdatedState(timeline)
    var positioned by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(true) }
    var seenSend by rememberSaveable { mutableLongStateOf(state.localSendRevision) }
    var programmatic by remember { mutableStateOf(false) }
    var readerScrolling by remember { mutableStateOf(false) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val bottomTolerance = with(LocalDensity.current) { 44.dp.toPx() }
    val viewport = list.layoutInfo.viewportSize.height
    val historyGeometry = remember(timeline, state.hasMore, state.committed, state.actionErrors,
        LocalDensity.current.density, LocalDensity.current.fontScale, list.layoutInfo.viewportSize.width) {
        mutableStateOf<NativeChiefHistoryGeometry?>(null)
    }
    suspend fun scrollToNewest() {
        val end = timeline.size + (if (state.hasMore) 1 else 0) + (if (state.working) 1 else 0) - 1
        if (end < 0) return
        readerScrolling = false; programmatic = true
        try {
            list.scrollToItem(end)
            // A timestamp, large bubble and delivery controls can exceed a viewport.
            // Index positioning alone aligns its top and leaves the send/retry footer hidden.
            list.layoutInfo.visibleItemsInfo.lastOrNull { it.index == end }?.let { last ->
                val remaining = last.offset + last.size - list.layoutInfo.viewportEndOffset
                if (remaining > 0) list.scrollBy(remaining.toFloat())
            }
        } finally { programmatic = false }
    }
    LaunchedEffect(timeline, state.working, state.loading, state.hasMore, state.localSendRevision, viewport) {
        val sent = state.localSendRevision != seenSend
        seenSend = state.localSendRevision
        if (sent) { pinned = true; readerScrolling = false }
        if (!state.loading && (!positioned || (pinned && (!dragged && !readerScrolling || sent)))) {
            scrollToNewest(); positioned = true
        }
    }
    LaunchedEffect(list, bottomTolerance) {
        snapshotFlow {
            val info = list.layoutInfo; val last = info.visibleItemsInfo.lastOrNull()
            Triple(dragged, list.isScrollInProgress, !list.canScrollForward ||
                (last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + bottomTolerance))
        }.collect { (dragging, scrolling, nearBottom) ->
            if (dragging) readerScrolling = true
            if (readerScrolling && !programmatic) pinned = nearBottom
            if (!dragging && !scrolling) readerScrolling = false
        }
    }
    LaunchedEffect(list, positioned, pinned, dragged, readerScrolling, programmatic) {
        if (positioned && pinned && !dragged && !readerScrolling && !programmatic) snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last?.index == info.totalItemsCount - 1)
                (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0) else 0
        }.collect { overflow ->
            // Delivery controls and font/keyboard reflow can finish measuring after the
            // timeline effect. Keep a pinned tail visible using the actual new layout.
            if (overflow > 0) list.scrollBy(overflow.toFloat())
        }
    }
    LaunchedEffect(session, list, positioned, state.catchUpMarker) {
        if (positioned && state.catchUpMarker != null) snapshotFlow {
            list.layoutInfo.visibleItemsInfo.map { it.key } to currentTimeline
        }.collect { (keys, rows) ->
            val visible = keys.filterIsInstance<String>().toSet()
            val items = rows.filter { it.item.key in visible }.map { it.item }
            try { session.observeVisible(items.mapNotNull { it.message?.id }.toSet(), items.any { it.outgoing != null }) }
            catch (error: Exception) { currentCoroutineContext().ensureActive() }
        }
    }
    LaunchedEffect(session, positioned, pinned, timeline, state.loadingOlder, state.loadingCatchUp,
        state.deciding, state.committed, state.uncertain, state.catchUpMarker, state.unread, state.lastReadSeq,
        list.isScrollInProgress, programmatic, readerScrolling, displayedQuestion?.id, displayedFailure?.clientMessageId) {
        if (positioned && pinned && !list.isScrollInProgress && !programmatic && !readerScrolling &&
            displayedQuestion == null && displayedFailure == null) {
            try { session.trimHistoryIfResting() }
            catch (error: Exception) { currentCoroutineContext().ensureActive() }
        }
    }
    LaunchedEffect(session, list, historyGeometry, positioned, programmatic, state.hasMore, state.loadingCatchUp, state.readsActive) {
        if (state.readsActive && positioned && !programmatic && state.hasMore && !state.loadingCatchUp) snapshotFlow {
            val geometry = historyGeometry.value
            if (geometry == null || geometry.viewport != list.layoutInfo.viewportSize.height) null
            else geometry.distance(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)?.let {
                NativeChiefHistory.decision(it, geometry.viewport.toFloat())
            }
        }.collect { decision ->
            try {
                when (decision) {
                    NativeChiefHistory.Decision.LOAD -> session.requestOlder()
                    NativeChiefHistory.Decision.STOP -> session.olderNoLongerWanted()
                    else -> Unit
                }
            } catch (error: Exception) { currentCoroutineContext().ensureActive() }
        }
    }
    BackHandler(onBack = onBack)
    val bubbleMetrics = nativeChiefBubbleMetrics()
    val darkTranscript = MaterialTheme.colorScheme.background.luminance() < .5f
    Column(modifier.fillMaxSize().background(if (darkTranscript) Color.Black else Color.White)
        .imePadding().testTag("ChiefTranscript")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹  Home") }
            ChiefAvatar(Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(conversation.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() },
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("Runs your agents on your Mac", color = chiefMuted, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            IconButton(onClick = { act { session.stop() } }, enabled = "stop" !in state.deciding) {
                Icon(painterResource(R.drawable.ic_chief_stop), "Stop Chief", tint = chiefAccent)
            }
        }
        state.error?.let { error -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = session::refresh) { Text("Reconnect") }
        } }
        actionError?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().testTag("ChiefMessages"), state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (state.hasMore) item(key = "older") {
                ChiefOlderRow(state) { act { session.loadOlder() } }
            }
            itemsIndexed(timeline, key = { _, row -> row.item.key }) { index, row ->
                ChiefTranscriptRow(row, timeline.getOrNull(index - 1), state, bubbleMetrics,
                    onApprove = { id, choice -> act { session.approve(id, choice) } },
                    onAnswer = { id, form -> act { session.answer(id, form) } },
                    onQuestion = { answerMessage = it }, onFailure = { failedMessage = it })
            }
            if (state.working) item(key = "working") { Text("Chief is working…", color = chiefAccent) }
        }
        if (state.hasMore) NativeChiefHistoryProbe(timeline.size + 1, viewport,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp), onGeometry = { historyGeometry.value = it }) { index ->
            if (index == 0) ChiefOlderRow(state) {}
            else ChiefTranscriptRow(timeline[index - 1], timeline.getOrNull(index - 2), state, bubbleMetrics, { _, _ -> }, { _, _ -> }, {}, {})
        }
        if (positioned && state.catchUpMarker != null) FilledTonalIconButton(onClick = { act {
            val target = session.loadCatchUpTarget() ?: return@act
            val rows = snapshotFlow { currentTimeline }.first { rows -> rows.any { it.item.message?.id == target } }
            val index = rows.indexOfFirst { it.item.message?.id == target }
            if (index >= 0) {
                pinned = false; readerScrolling = false; programmatic = true
                try { list.scrollToItem(index + if (session.state.value.hasMore) 1 else 0) }
                finally { programmatic = false }
                session.dismissCatchUp()
            }
        } }, enabled = !state.loadingCatchUp, modifier = Modifier.align(Alignment.TopEnd).padding(14.dp)
            .size(48.dp).testTag("ChiefCatchUp")) {
            Icon(painterResource(R.drawable.ic_chief_catch_up), stringResource(R.string.chief_catch_up_label))
        }
        }
        NativeChiefComposer(state.draft, state.draftRevision, state.retired, onDraft = { text ->
            try { session.editDraft(text) }
            catch (error: IllegalStateException) { actionError = error.message; null }
        }, onSend = { text, revision ->
            actionError = null
            try { session.submitFromComposer(text, revision) != null }
            catch (error: IllegalStateException) { actionError = error.message; false }
        })
    }
    if (displayedFailure != null) ModalBottomSheet(onDismissRequest = { failedMessage = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("ChiefFailedSendDialog")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Text(stringResource(R.string.chief_retry_title), Modifier.padding(vertical = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            displayedFailure.error?.let { detail -> Text(detail,
                Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = {
                failedMessage = null
                act { session.retry(displayedFailure.clientMessageId) }
            }, modifier = Modifier.fillMaxWidth().testTag("ChiefRetrySend")) {
                Text(stringResource(R.string.chief_retry_try_again))
            }
            TextButton(onClick = {
                // This local store action is synchronous in the source. Leaving the view
                // immediately after a confirmed tap must not cancel the admitted deletion.
                try { session.discardFailed(displayedFailure.clientMessageId) }
                catch (error: IllegalStateException) { actionError = error.message ?: "Chief action unavailable" }
                failedMessage = null
            }, modifier = Modifier.fillMaxWidth().testTag("ChiefDiscardSend")) {
                Text(stringResource(R.string.chief_retry_delete), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { failedMessage = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chief_retry_cancel))
            }
        }
    }
    if (answerMessage != null && displayedQuestion != null) NativeChiefQuestionSheet(displayedQuestion,
        busy = answerMessage in state.deciding, error = state.actionErrors[answerMessage], onDismiss = { answerMessage = null }) { form ->
        val id = answerMessage ?: return@NativeChiefQuestionSheet
        act { if (session.answer(id, form)) answerMessage = null }
    }
}

@Composable
private fun ChiefOlderRow(state: NativeChiefTranscriptState, onLoad: () -> Unit) {
    TextButton(onClick = onLoad, enabled = !state.loadingOlder) {
        Text(if (state.historyRetryAttempt > 0) "Retrying earlier messages…"
            else if (state.loadingOlder) "Loading earlier messages…" else "Earlier messages")
    }
}


@Composable
private fun ChiefTranscriptRow(row: NativeChiefTimelineRow, previous: NativeChiefTimelineRow?,
    state: NativeChiefTranscriptState, metrics: NativeChiefBubbleMetrics,
    onApprove: (String, String) -> Unit, onAnswer: (String, NativeChiefQuestionForm) -> Unit,
    onQuestion: (String) -> Unit, onFailure: (String) -> Unit) {
    val baseGap = metrics.space(if (row.firstInRun) 10f else 4f)
    // Local outgoing rows already end in a status caption; acknowledged text ends in its tail.
    val previousOverhang = if (previous?.lastInRun == true && previous.item.message != null) metrics.drop else 0.dp
    val gap = if (row.timestamp) 0.dp else (baseGap - previousOverhang).coerceAtLeast(0.dp)
    Column(Modifier.fillMaxWidth().testTag("ChiefRow:${row.item.key}").padding(top = gap)) {
        if (row.timestamp) ChiefTimestamp(row.item.at, "ChiefTimestamp:${row.item.key}")
        val message = row.item.message
        if (message != null) {
            val part = NativeChiefPresentation.pendingPart(message)
            val actionable = message.id !in state.deciding && message.id !in state.committed
            val body = NativeChiefPresentation.receipt(message) ?: NativeChiefPresentation.displayText(message)
            NativeChiefBalloonRow(row.quote, message.authorKind == "human", row.lastInRun,
                message.id, expanded = part != null, text = body.takeIf { part == null }, metrics = metrics) {
                if (body.isNotEmpty() || part == null) SelectionContainer { NativeChiefText(body) }
                part?.approval?.let { approval ->
                    Text(approval.prompt, fontWeight = FontWeight.SemiBold)
                    approval.options.filterNot { it.startsWith("always:") }.forEach { choice ->
                        OutlinedButton(onClick = { onApprove(message.id, choice) }, enabled = actionable,
                            modifier = Modifier.testTag("ChiefApprove:${message.id}:$choice")) { Text(choice) }
                    }
                }
                part?.question?.let { question ->
                    val compact = NativeChiefPresentation.compactQuestion(question)
                    if (compact == null) OutlinedButton(onClick = { onQuestion(message.id) }, enabled = actionable,
                        modifier = Modifier.testTag("ChiefAnswer:${message.id}")) { Text("Answer questions") }
                    else compact.options.forEach { option ->
                        OutlinedButton(onClick = { onAnswer(message.id, NativeChiefQuestionForm(question).toggle(compact.id, option.id)) },
                            enabled = actionable) { Text(option.label) }
                    }
                }
                if (message.id in state.committed) Text("Answer sent. Waiting for your Mac…", color = chiefMuted)
                state.actionErrors[message.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        } else {
            val outgoing = checkNotNull(row.item.outgoing)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.AbsoluteAlignment.Right) {
                NativeChiefBubble(true, row.lastInRun, Modifier.testTag("ChiefPending:${outgoing.clientMessageId}"),
                    bodyTag = "ChiefPendingBody:${outgoing.clientMessageId}", text = outgoing.text, metrics = metrics) {
                    SelectionContainer { NativeChiefText(outgoing.text) }
                }
                Text(when (outgoing.delivery) {
                    NativeChiefDelivery.SENDING -> "Sending…"
                    NativeChiefDelivery.QUEUED -> "Queued"
                    NativeChiefDelivery.SENT -> "Sent"
                    NativeChiefDelivery.UNCONFIRMED -> "Delivery unconfirmed"
                    NativeChiefDelivery.REJECTED -> "Not sent"
                }, color = chiefMuted, style = MaterialTheme.typography.labelSmall)
                if (outgoing.delivery.isFailed) TextButton(onClick = { onFailure(outgoing.clientMessageId) },
                    modifier = Modifier.testTag("ChiefFailed:${outgoing.clientMessageId}")) {
                    Text(stringResource(R.string.chief_retry_try_again))
                }
            }
        }
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
