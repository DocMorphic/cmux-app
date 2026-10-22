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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
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
            NativeChiefTranscriptView(selected, transcript, computer::back, modifier, computer.translationConfiguration)
        } else NativeChiefHomeView(computer, modifier)
        }
    }
}

@Composable
private fun ChiefAvatar(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.chief_avatar), null, modifier.clip(CircleShape).background(Color(0xFF25282E)).padding(4.dp))
}

@Composable
internal fun ChiefTimestamp(at: Instant, tag: String, modifier: Modifier = Modifier) {
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
            val start = length; append(label); addStyle(SpanStyle(fontWeight = FontWeight.Medium), start, length)
            append(template.substring(position + 1))
        }
    }
    val density = LocalDensity.current
    val font = nativeChiefCaptionFont()
    val geometry = NativeChiefCaptionGeometry.timestamp(font.lineHeight, font.ascender, font.spacing,
        font.pointSize, density.density.toDouble())
    // Both transcript containers already supply the source's 16-point margins.
    Layout(modifier = modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}, content = {
        NativeChiefCaptionLabel(timestamp, font, geometry.labelHeight, geometry.baselineOffset - 10,
            chiefMuted, tag, centered = true)
    }) { children, constraints ->
        val label = children.single().measure(Constraints(maxWidth = constraints.maxWidth))
        layout(constraints.maxWidth, (geometry.rowHeight * density.density).roundToInt()) {
            label.place(0, with(density) { 10.dp.roundToPx() })
        }
    }
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
    onBack: () -> Unit, modifier: Modifier, translationConfiguration: NativeChiefTranslationConfiguration?) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    val translationContext = LocalContext.current.applicationContext
    val preferredTranslationLocale = LocalConfiguration.current.locales[0]
    LaunchedEffect(session, translationConfiguration) {
        if (translationConfiguration != null) try {
            installNativeChiefTranslation(session, translationContext, translationConfiguration, preferredTranslationLocale)
        } catch (error: Exception) { currentCoroutineContext().ensureActive() }
    }
    val translationModel by session.translations.collectAsState()
    val translationState by (translationModel?.state ?: remember(session) {
        kotlinx.coroutines.flow.MutableStateFlow(NativeChiefTranslationState())
    }).collectAsState()
    val translationReserve = if (translationState.indicator != null) 38.dp else 0.dp
    val composerFocus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val copyCapturedText = rememberChiefCopy(session)
    val menu = remember(session) { NativeChiefMenuController(session, scope) { composerFocus.clearFocus(force = true); keyboard?.hide() } }
    DisposableEffect(menu) { onDispose { menu.dismiss() } }
    var selectedRows by remember(session) { mutableStateOf<Set<String>?>(null) }
    var confirmedDelete by remember(session) { mutableStateOf<Set<String>?>(null) }
    var selectText by remember(session) { mutableStateOf<NativeChiefSelectedText?>(null) }
    var pollDetails by remember(session) { mutableStateOf<String?>(null) }
    val selecting = selectedRows != null && !state.retired
    SideEffect { if (state.retired) {
        menu.dismiss(); selectedRows = null; confirmedDelete = null; selectText = null; pollDetails = null
    } }
    var actionError by remember { mutableStateOf<String?>(null) }
    val translationUi = remember(session, translationModel, preferredTranslationLocale) { translationModel?.let {
        NativeChiefTranslationUi(session, it, scope, preferredTranslationLocale) { error -> actionError = error }
    } }
    DisposableEffect(translationUi) { onDispose { translationUi?.close() } }
    SideEffect { if (state.retired) translationUi?.dismiss() }
    fun act(action: suspend () -> Unit) { scope.launch {
        actionError = null
        try { action() } catch (error: Exception) {
            currentCoroutineContext().ensureActive(); actionError = error.message ?: "Chief action unavailable"
        }
    } }
    val list = rememberLazyListState()
    var answerMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var failedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val displayedFailure = failedMessage?.let { id -> state.outgoing.singleOrNull { it.clientMessageId == id && it.delivery.isFailed } }
    val displayedQuestion = answerMessage?.let { id -> state.messages.singleOrNull { it.id == id } }
        ?.let(NativeChiefPresentation::pendingPart)?.question
    val timeline = remember(state.messages, state.outgoing, state.localClientIds, state.working) { NativeChiefTimeline.rows(state) }
    val leadingCount = if (state.hasMore || state.hasLoadedInfo) 1 else 0
    val currentTimeline by rememberUpdatedState(timeline)
    var positioned by rememberSaveable { mutableStateOf(false) }
    var pinned by rememberSaveable { mutableStateOf(true) }
    var seenSend by rememberSaveable { mutableLongStateOf(state.localSendRevision) }
    var programmatic by remember { mutableStateOf(false) }
    var readerScrolling by remember { mutableStateOf(false) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val bottomTolerance = with(LocalDensity.current) { 44.dp.toPx() }
    val fontScale = LocalDensity.current.fontScale
    fun nearNewest(): Boolean {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        return !list.canScrollForward || (last != null && last.index == info.totalItemsCount - 1 &&
            last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + bottomTolerance)
    }
    suspend fun <T> accessibilityScroll(action: suspend () -> T): T {
        // AX scrolling expresses the same reading intent as dragging. Unpin
        // before layout so tail-follow cannot undo an admitted scroll action.
        pinned = false; readerScrolling = true
        return try { action() } finally { pinned = nearNewest(); readerScrolling = false }
    }
    val viewport = list.layoutInfo.viewportSize.height
    val historyGeometry = remember(timeline, translationState, state.hasMore, state.committed, state.actionErrors,
        LocalDensity.current.density, LocalDensity.current.fontScale, list.layoutInfo.viewportSize.width) {
        mutableStateOf<NativeChiefHistoryGeometry?>(null)
    }
    suspend fun scrollToNewest() {
        val end = timeline.size + leadingCount + (if (state.working) 1 else 0) - 1
        if (end < 0) return
        readerScrolling = false; programmatic = true
        try {
            list.scrollToItem(end)
            // A timestamp, large bubble and delivery controls can exceed a viewport.
            // Index positioning alone aligns its top and leaves the send/retry footer hidden.
            list.layoutInfo.visibleItemsInfo.lastOrNull { it.index == end }?.let { last ->
                val remaining = last.offset + last.size - (list.layoutInfo.viewportEndOffset - list.layoutInfo.afterContentPadding)
                if (remaining > 0) list.scrollBy(remaining.toFloat())
            }
        } finally { programmatic = false }
    }
    // A font reflow can push the last row entirely outside the lazy viewport,
    // where the overflow collector below can no longer observe it. Reposition
    // a following transcript explicitly, while preserving history-reading intent.
    LaunchedEffect(timeline, translationState, state.working, state.loading, state.hasMore, state.localSendRevision, viewport, fontScale) {
        val sent = state.localSendRevision != seenSend
        seenSend = state.localSendRevision
        if (sent) { pinned = true; readerScrolling = false }
        if (!state.loading && (!positioned || (pinned && (!dragged && !readerScrolling || sent)))) {
            scrollToNewest(); positioned = true
        }
    }
    LaunchedEffect(list, bottomTolerance) {
        snapshotFlow {
            Triple(dragged, list.isScrollInProgress, nearNewest())
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
                (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)).coerceAtLeast(0) else 0
        }.collect { overflow ->
            // Delivery controls and font/keyboard reflow can finish measuring after the
            // timeline effect. Keep a pinned tail visible using the actual new layout.
            // The input event may change reading intent before recomposition
            // cancels this old collector. Recheck the live flags at admission.
            if (overflow > 0 && pinned && !dragged && !readerScrolling && !programmatic)
                list.scrollBy(overflow.toFloat())
        }
    }
    LaunchedEffect(session, list, positioned, state.catchUpMarker) {
        if (positioned && state.catchUpMarker != null) snapshotFlow {
            list.layoutInfo.visibleItemsInfo.map { it.key to (it.offset + it.size > 0) } to currentTimeline
        }.collect { (keys, rows) ->
            // The source target must extend below its adjusted top inset. Outgoing
            // rows use the full collection's visible cells, including under bars.
            val visible = keys.mapNotNull { it.first as? String }.toSet()
            val belowHeader = keys.filter { it.second }.mapNotNull { it.first as? String }.toSet()
            val items = rows.filter { it.item.key in visible }.map { it.item }
            val ids = rows.filter { it.item.key in belowHeader }.mapNotNull { it.item.message?.id }.toSet()
            val outgoingVisible = items.any { it.outgoing != null || it.message?.let { message ->
                message.authorKind == "human" && message.seq > (state.catchUpMarker ?: ULong.MAX_VALUE) } == true }
            try { session.observeVisible(ids, outgoingVisible) }
            catch (error: Exception) { currentCoroutineContext().ensureActive() }
        }
    }
    LaunchedEffect(session, positioned, pinned, timeline, state.loadingOlder, state.loadingCatchUp,
        state.deciding, state.committed, state.uncertain, state.catchUpMarker, state.unread, state.lastReadSeq,
        list.isScrollInProgress, programmatic, readerScrolling, displayedQuestion?.id, displayedFailure?.clientMessageId, state.reply, selecting) {
        if (positioned && pinned && !list.isScrollInProgress && !programmatic && !readerScrolling &&
            displayedQuestion == null && displayedFailure == null && state.reply == null && !selecting) {
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
    val replyState = session.state.value
    val reply = replyState.reply
    val headerMotion = remember(session) { NativeChiefHeaderMotionState(session.state.value.reply?.generation) }
    // Retirement and non-UI state changes still reconcile from the live owner;
    // a collected snapshot may be older than an already admitted UI action.
    SideEffect { headerMotion.admit(session.state.value.reply?.generation) }
    val replyOrigins = remember(session, list) { NativeChiefReplyOrigins {
        list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet()
    } }
    val density = LocalDensity.current.density
    val hostView = LocalView.current
    var shownReply by remember(session) { mutableStateOf<NativeChiefReplyPresentation?>(null) }
    var entryOrigins by remember(session) { mutableStateOf(replyOrigins.snapshot()) }
    fun replyPresentation(active: NativeChiefReply, snapshot: NativeChiefTranscriptState,
        origins: NativeChiefReplyOrigins.Snapshot): NativeChiefReplyPresentation {
        val rows = NativeChiefTimeline.rows(snapshot)
        val rootIndex = rows.indexOfFirst { it.item.messageID == active.rootID }
        val stamp = rows.take(rootIndex + 1).lastOrNull { it.timestamp }?.item?.key
        return NativeChiefReplyPresentation(active, NativeChiefReplyMotionState(active, origins,
            stamp, density, hostView.rootView.height / density.toDouble()), snapshot.localSendRevision)
    }
    fun captureReply(anchor: Pair<String, Float>? = null, action: () -> Boolean): Boolean {
        val liveOrigins = replyOrigins.snapshot()
        val captured = if (anchor == null) liveOrigins else liveOrigins.copy(bubbles = liveOrigins.bubbles + anchor)
        val admitted = action()
        if (admitted) {
            entryOrigins = captured
            val live = session.state.value
            // Replace a closing root at admission, even when a later close is
            // conflated into the same frame and this open is never composed.
            live.reply?.let { shownReply = replyPresentation(it, live, captured) }
            headerMotion.admit(live.reply?.generation)
        }
        return admitted
    }
    if (replyState.retired) shownReply = null
    else if (reply != null && shownReply?.reply?.generation != reply.generation) {
        shownReply = replyPresentation(reply, replyState, entryOrigins)
    }
    val presentation = shownReply
    val replyMotion = presentation?.motion
    if (reply == null && presentation != null && !presentation.motion.closing) {
        presentation.motion.close(replyOrigins.snapshot(), settling = replyState.localSendRevision == presentation.sendRevision)
    }
    fun closeReply() {
        // Stop entry on the input event, before an already-awaiting display frame
        // can advance it while StateFlow's new logical state reaches Compose.
        try {
            val closingPresentation = shownReply
            session.closeReplyThread() // Reject stale ownership before changing any presentation.
            closingPresentation?.motion?.close(replyOrigins.snapshot(), settling = true)
            headerMotion.admit(null)
        } catch (_: IllegalStateException) {}
    }
    fun replyTo(id: String): Boolean = try { captureReply { session.replyTo(id) } } catch (_: IllegalStateException) { false }
    fun swipeReply(id: String, offset: Float): Boolean = try { captureReply { session.replyTo(id, offset) } } catch (_: IllegalStateException) { false }
    fun openThread(id: String) { try { captureReply { session.openReplyThread(id) } } catch (_: IllegalStateException) {} }
    BackHandler {
        when {
            menu.snapshot != null -> menu.dismiss()
            selectText != null -> selectText = null
            selecting -> selectedRows = null
            session.state.value.reply != null -> closeReply()
            else -> onBack()
        }
    }
    val bubbleMetrics = nativeChiefBubbleMetrics()
    val darkTranscript = MaterialTheme.colorScheme.background.luminance() < .5f
    val compactHeader = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && LocalConfiguration.current.screenHeightDp < 480
    val last = timeline.lastOrNull()
    val lastMessage = last?.item?.message
    val trailingEmoji = lastMessage?.let { NativeChiefPresentation.pendingPart(it) == null &&
        NativeChiefEmojiPolicy.isEmojiOnly(NativeChiefPresentation.receipt(it) ?: NativeChiefPresentation.displayText(it)) } == true
    val trailingOverhang = if (!state.working && last?.lastInRun == true && lastMessage != null && !trailingEmoji && translationState.presentations[last.item.key] == null) bubbleMetrics.drop else 0.dp
    NativeChiefTranscriptFrame(modifier.fillMaxSize().then(if (detailsOpen && !state.retired) Modifier.blur(16.dp) else Modifier)
        .background(if (darkTranscript) Color.Black else Color.White).imePadding().testTag("ChiefTranscript")
        .onPreviewKeyEvent { if (session.state.value.reply != null && it.key == Key.Escape && it.type == KeyEventType.KeyDown) { closeReply(); true } else false },
        compactHeader, darkTranscript, trailingOverhang, translationReserve, header = {
        Box(Modifier.then(if (menu.opened) Modifier.clearAndSetSemantics {} else Modifier)) {
        NativeChiefHeader(conversation, "stop" in state.deciding, onBack,
            { if (state.hasLoadedInfo && !state.retired) {
                // Source openInfo ends editing before presenting the details controller.
                composerFocus.clearFocus(force = true); detailsOpen = true
            } }, { act { session.stop() } }, motion = headerMotion, onCloseReply = ::closeReply,
            selecting = selecting, onCancelSelection = { selectedRows = null })
        }
    }, composer = {
        Box(Modifier.then(if (selecting) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {}
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } }
            else if (menu.opened) Modifier.clearAndSetSemantics {} else Modifier)) {
        NativeChiefComposer(state.draft, state.draftRevision, state.retired, onDraft = { text ->
            try { session.editDraft(text) }
            catch (error: IllegalStateException) { actionError = error.message; null }
        }, onSend = { text, revision ->
            actionError = null
            try {
                val closingPresentation = shownReply
                val admitted = session.submitFromComposer(text, revision) != null
                if (admitted) {
                    closingPresentation?.motion?.close(replyOrigins.snapshot(), settling = false)
                    headerMotion.admit(null)
                }
                admitted
            } catch (error: IllegalStateException) { actionError = error.message; false }
        }, replyGeneration = reply?.generation)
        }
    }, overlay = { headerHeight, composerHeight ->
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = headerHeight)) {
        state.error?.let { error -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = session::refresh) { Text("Reconnect") }
        } }
        actionError?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (positioned && state.catchUpMarker != null) FilledTonalIconButton(onClick = { act {
            val target = session.loadCatchUpTarget() ?: return@act
            val rows = snapshotFlow { currentTimeline }.first { rows -> rows.any { it.item.message?.id == target } }
            val index = rows.indexOfFirst { it.item.message?.id == target }
            if (index >= 0) {
                pinned = false; readerScrolling = false; programmatic = true
                try { list.scrollToItem(index + if (session.state.value.hasMore || session.state.value.hasLoadedInfo) 1 else 0) }
                finally { programmatic = false }
                session.dismissCatchUp()
            }
        } }, enabled = !state.loadingCatchUp, modifier = Modifier.align(Alignment.TopEnd).padding(top = headerHeight + 14.dp, end = 14.dp)
            .size(48.dp).testTag("ChiefCatchUp")) {
            Icon(painterResource(R.drawable.ic_chief_catch_up), stringResource(R.string.chief_catch_up_label))
        }
        if (translationModel != null && translationState.indicator != null && !state.retired) {
            NativeChiefTranslationIndicator(translationState, translationModel!!,
                Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 6.dp)
                    .then(if (menu.opened) Modifier.clearAndSetSemantics {} else Modifier))
        }
        if (selecting) NativeChiefSelectionToolbar(!selectedRows.isNullOrEmpty(), darkTranscript,
            Modifier.align(Alignment.BottomStart).padding(bottom = (composerHeight / 2 - 20.dp).coerceAtLeast(0.dp))) {
            confirmedDelete = selectedRows?.toSet()
        }
        if (selectText != null && !state.retired) NativeChiefTextSelection(selectText!!,
            permitted = { selectText?.rowKey?.let { menu.current(it) } != null }) { selectText = null }
        if (menu.snapshot != null && !state.retired) NativeChiefActionMenu(menu, headerHeight, darkTranscript) { frozen, action, anchorY ->
            if (!menu.takeAction(frozen, action)) return@NativeChiefActionMenu
            if (action == NativeChiefMenuAction.COPY) {
                hostView.context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("", frozen.text))
                menu.dismiss()
                return@NativeChiefActionMenu
            }
            val current = menu.current(frozen.row.item.key)
            if (current == null) menu.dismiss() else when (action) {
                NativeChiefMenuAction.COPY -> Unit // Captured text is handled before live-row admission.
                NativeChiefMenuAction.REPLY -> scope.launch {
                    kotlinx.coroutines.delay(120)
                    val live = menu.current(frozen.row.item.key)
                    if (menu.snapshot === frozen && live != null) {
                        menu.dismiss()
                        try { captureReply(live.messageID to anchorY) { session.replyTo(live.messageID) } }
                        catch (_: IllegalStateException) {}
                    }
                }.let { Unit }
                NativeChiefMenuAction.RETRY -> {
                    menu.dismiss(); current.outgoing?.let { outgoing -> act { session.retry(outgoing.clientMessageId) } }
                }
                NativeChiefMenuAction.MORE -> { selectedRows = setOf(current.key); menu.dismiss() }
                NativeChiefMenuAction.SELECT -> { selectText = menu.selection(frozen); menu.dismiss() }
                NativeChiefMenuAction.POLL -> { pollDetails = current.message?.id; menu.dismiss() }
                NativeChiefMenuAction.TRANSLATE -> { menu.dismiss(); translationUi?.showChoice(current.key) }
                NativeChiefMenuAction.SHOW_ORIGINAL, NativeChiefMenuAction.SHOW_TRANSLATION -> { menu.dismiss(); translationUi?.toggle(current.key) }
            }
        }
        }
    }) { padding, headerHeight, composerHeight ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("ChiefMessages").onGloballyPositioned { replyOrigins.viewport = it }.then(if (replyMotion != null) Modifier.blur((10 * replyMotion.blur).dp) else Modifier)
            .then(if (reply != null || menu.opened) Modifier.clearAndSetSemantics {
                if (BuildConfig.DEBUG) {
                    this[ChiefReplyVisibleRows] = { replyOrigins.snapshot().bubbles.keys }
                    this[ChiefReplyOriginsProbe] = { replyOrigins.snapshot() }
                }
            } else Modifier.semantics {
                if (BuildConfig.DEBUG) this[ChiefReplyOriginsProbe] = { replyOrigins.snapshot() }
                scrollBy { _, y ->
                    val canScroll = if (y > 0) list.canScrollForward else if (y < 0) list.canScrollBackward else false
                    if (canScroll) scope.launch(start = CoroutineStart.UNDISPATCHED) { accessibilityScroll { list.scrollBy(y) } }
                    canScroll
                }
                scrollByOffset { requested ->
                    val consumed = accessibilityScroll { list.scrollBy(requested.y) }
                    Offset(0f, consumed)
                }
                scrollToIndex { index ->
                    val valid = index in 0 until list.layoutInfo.totalItemsCount
                    if (valid) scope.launch(start = CoroutineStart.UNDISPATCHED) { accessibilityScroll { list.scrollToItem(index) } }
                    valid
                }
            }), state = list,
            contentPadding = padding, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (state.hasMore) item(key = "older") {
                ChiefOlderRow(state) { act { session.loadOlder() } }
            } else if (state.hasLoadedInfo) item(key = "start") {
                NativeChiefServiceIntro(conversation.title)
            }
            itemsIndexed(timeline, key = { _, row -> row.item.key }) { index, row ->
                ChiefTranscriptRow(row, timeline.getOrNull(index - 1), state, bubbleMetrics, origins = replyOrigins,
                    onApprove = { id, choice -> act { session.approve(id, choice) } },
                    onAnswer = { id, form -> act { session.answer(id, form) } },
                    onQuestion = { answerMessage = it }, onFailure = { failedMessage = it }, onQuote = ::openThread, onReply = ::replyTo,
                    onSwipeReply = if (reply == null && !selecting && !state.retired) ::swipeReply else null,
                    onCopy = copyCapturedText,
                    translation = translationState.presentations[row.item.key],
                    previousTranslation = timeline.getOrNull(index - 1)?.item?.key?.let(translationState.presentations::get),
                    onTranslation = { translationUi?.toggle(row.item.key) },
                    bodyAlpha = if (row.item.key in (replyMotion?.hiddenKeys ?: emptySet())) 0f
                        else if (row.item.fromMe) 1f - .3f * (replyMotion?.veil ?: 0f) else 1f,
                    menu = if (reply == null && !selecting && !state.retired) menu else null,
                    selected = selectedRows?.contains(row.item.key), onToggleSelection = {
                        if (menu.current(row.item.key) != null) selectedRows = selectedRows?.let { if (row.item.key in it) it - row.item.key else it + row.item.key }
                    })
            }
            if (state.working) item(key = "working") { Text("Chief is working…", color = chiefAccent) }
        }
        if (state.hasMore) NativeChiefHistoryProbe(timeline.size + 1, viewport,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp), onGeometry = { historyGeometry.value = it }) { index ->
            if (index == 0) ChiefOlderRow(state) {}
            else ChiefTranscriptRow(timeline[index - 1], timeline.getOrNull(index - 2), state, bubbleMetrics, { _, _ -> }, { _, _ -> }, {}, {},
                translation = translationState.presentations[timeline[index - 1].item.key],
                previousTranslation = timeline.getOrNull(index - 2)?.item?.key?.let(translationState.presentations::get))
        }
        if (presentation != null && !state.retired) key(presentation.reply.generation) {
            NativeChiefReplyOverlay(presentation.reply, presentation.motion, headerHeight, composerHeight + translationReserve,
                bubbleMetrics, darkTranscript, ::closeReply, ::replyTo,
                onCopy = { text ->
                    !presentation.motion.closing && session.state.value.reply?.generation == presentation.reply.generation && copyCapturedText(text)
                }, translations = translationState.presentations,
                onTranslation = { if (!presentation.motion.closing && session.state.value.reply?.generation == presentation.reply.generation) translationUi?.toggle(it) }, onFinished = {
                    // A previous generation's completion can never retire a reopened thread.
                    if (shownReply === presentation) shownReply = null
                })
        }
        }

    }
    if (!state.retired) {
        translationUi?.let { NativeChiefTranslationSheets(it) }
        val host = session.translationHost
        if (host != null && translationModel != null) NativeChiefTranslationDownloadHost(host, translationModel!!)
    }
    if (detailsOpen && !state.retired && state.hasLoadedInfo) NativeChiefDetails(conversation, state.listState,
        onClose = { detailsOpen = false }, onChange = { muted, receipts ->
            try { session.updateDetails(muted, receipts) }
            catch (error: IllegalStateException) { detailsOpen = false; actionError = error.message ?: "Chief action unavailable" }
        })
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
    if (pollDetails != null && !state.retired) NativeChiefPollDetails(pollDetails!!, state) { pollDetails = null }
    val doomed = confirmedDelete
    if (doomed != null && doomed.isNotEmpty() && !state.retired) ModalBottomSheet(onDismissRequest = { confirmedDelete = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("ChiefDeleteConfirmation")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)) {
            TextButton(onClick = {
                selectedRows = null; confirmedDelete = null
                try { session.deleteLocally(doomed) } catch (_: IllegalStateException) {}
            }, modifier = Modifier.fillMaxWidth().testTag("ChiefConfirmLocalDelete")) {
                Text(if (doomed.size == 1) stringResource(R.string.chief_selection_delete_one)
                    else stringResource(R.string.chief_selection_delete_many, doomed.size), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { confirmedDelete = null }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chief_retry_cancel)) }
        }
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
    onQuestion: (String) -> Unit, onFailure: (String) -> Unit, onQuote: (String) -> Unit = {},
    onReply: ((String) -> Boolean)? = null, onSwipeReply: ((String, Float) -> Boolean)? = null, bodyAlpha: Float = 1f,
    origins: NativeChiefReplyOrigins? = null, menu: NativeChiefMenuController? = null,
    selected: Boolean? = null, onCopy: ((String) -> Boolean)? = null, onToggleSelection: () -> Unit = {},
    translation: NativeChiefTranslationPresentation? = null, previousTranslation: NativeChiefTranslationPresentation? = null,
    onTranslation: () -> Unit = {}) {
    val menuSource = remember(menu, row.item.key) { menu?.let { NativeChiefMenuSource(it, row) } }
    SideEffect { menuSource?.row = row; menuSource?.displayedText = translation?.text }
    DisposableEffect(origins, row.item.key, row.timestamp) { onDispose {
        origins?.bubbles?.remove(row.item.messageID); origins?.cells?.remove(row.item.messageID)
        origins?.timestamps?.remove(row.item.key)
    } }
    val cellPosition = Modifier.onGloballyPositioned { origins?.cells?.set(row.item.messageID, it) }
    val baseGap = metrics.space(if (row.firstInRun) 10f else 4f)
    // Local outgoing rows already end in a status caption; acknowledged text ends in its tail.
    val previousMessage = previous?.item?.message
    val previousEmoji = remember(previousMessage) {
        previousMessage?.let { message -> NativeChiefPresentation.pendingPart(message) == null &&
            NativeChiefEmojiPolicy.isEmojiOnly(NativeChiefPresentation.receipt(message) ?: NativeChiefPresentation.displayText(message)) } == true
    }
    val previousOverhang = if (previous?.lastInRun == true && previousMessage != null && !previousEmoji && previousTranslation == null) metrics.drop else 0.dp
    val gap = if (row.timestamp) 0.dp else (baseGap - previousOverhang).coerceAtLeast(0.dp)
    val capturedText = translation?.text ?: row.item.message?.let { NativeChiefPresentation.receipt(it) ?: NativeChiefPresentation.displayText(it) }
        ?: row.item.outgoing!!.text
    val accessibility = chiefMessageAccessibility(capturedText, onReply?.let { { it(row.item.messageID) } }, onCopy)
    val replyAction = if (onReply != null) Modifier.testTag("ChiefReplyActions:${row.item.messageID}").semantics {
        customActions = accessibility.actions
        accessibility.copiedState?.let { stateDescription = it }
    } else Modifier
    val selectionModifier = Modifier.chiefSelecting(selected, row.item.fromMe, capturedText,
        accessibility.copy, accessibility.copiedState, onToggleSelection)
    val captionVisible = bodyAlpha > 0 && menu?.snapshot?.row?.item?.key != row.item.key
    val captionModifier = Modifier.graphicsLayer {
        alpha = if (captionVisible) bodyAlpha else 0f
        translationX = if (selected != null && !row.item.fromMe) 42.dp.toPx() else 0f
    }.then(if (captionVisible) Modifier else Modifier.clearAndSetSemantics {})
    Column(Modifier.fillMaxWidth().testTag("ChiefRow:${row.item.key}").padding(top = gap)) {
        if (row.timestamp) {
            ChiefTimestamp(row.item.at, "ChiefTimestamp:${row.item.key}", Modifier.onGloballyPositioned { origins?.timestamps?.set(row.item.key, it) })
            // Source collection-view spacing after TimestampCell; the copied
            // reply transcript intentionally places its sender row directly.
            Spacer(Modifier.height(4.dp))
        }
        val message = row.item.message
        if (message != null) {
            val part = NativeChiefPresentation.pendingPart(message)
            val actionable = message.id !in state.deciding && message.id !in state.committed
            val body = translation?.text ?: capturedText
            val emojiSize = remember(body, part) { if (part == null) NativeChiefEmojiPolicy.fontSize(body) else null }
            NativeChiefBalloonRow(row.quote, message.authorKind == "human", row.lastInRun,
                message.id, expanded = part != null, text = body.takeIf { part == null }, metrics = metrics, emojiSize = emojiSize,
                modifier = Modifier.alpha(bodyAlpha).then(replyAction).then(cellPosition).then(selectionModifier), onQuote = row.quote?.let { { onQuote(it.id) } },
                onSwipeReply = onSwipeReply?.let { action -> { offset -> action(message.id, offset); Unit } },
                menuSource = menuSource,
                onBubblePositioned = { origins?.bubbles?.set(row.item.messageID, NativeChiefReplyOrigins.Bubble(row.item.key, it)) }) {
                if (part == null) {
                    if (menuSource != null) NativeChiefText(body, Modifier.onGloballyPositioned { if (emojiSize == null) menuSource.text = it })
                    else SelectionContainer { NativeChiefText(body) }
                }
                part?.approval?.let { approval ->
                    Text(approval.prompt, fontWeight = FontWeight.SemiBold)
                    approval.options.filterNot { it.startsWith("always:") }.forEach { choice ->
                        OutlinedButton(onClick = { onApprove(message.id, choice) }, enabled = actionable,
                            modifier = Modifier.testTag("ChiefApprove:${message.id}:$choice")) { Text(choice) }
                    }
                }
                part?.question?.let { question ->
                    val compact = NativeChiefPresentation.compactQuestion(question)
                    Text(compact?.prompt ?: NativeChiefPresentation.summary(question))
                    if (compact == null) OutlinedButton(onClick = { onQuestion(message.id) }, enabled = actionable,
                        modifier = Modifier.testTag("ChiefAnswer:${message.id}")) { Text(stringResource(R.string.chief_reply_answer_questions)) }
                    else compact.options.forEach { option ->
                        OutlinedButton(onClick = { onAnswer(message.id, NativeChiefQuestionForm(question).toggle(compact.id, option.id)) },
                            enabled = actionable) { Text(option.label) }
                    }
                }
                if (message.id in state.committed) Text("Answer sent. Waiting for your Mac…", color = chiefMuted)
                state.actionErrors[message.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            translation?.let { NativeChiefTranslationCaption(it, row.item.fromMe,
                if (row.lastInRun && emojiSize == null) metrics.drop else 0.dp, "ChiefTranslationCaption:${row.item.key}",
                modifier = captionModifier, enabled = selected == null && captionVisible, onClick = onTranslation) }
        } else {
            val outgoing = checkNotNull(row.item.outgoing)
            val body = translation?.text ?: outgoing.text
            val emojiSize = remember(body) { NativeChiefEmojiPolicy.fontSize(body) }
            Column(Modifier.fillMaxWidth().then(cellPosition), horizontalAlignment = androidx.compose.ui.AbsoluteAlignment.Right) {
                NativeChiefBalloonRow(row.quote, true, row.lastInRun, outgoing.clientMessageId, false, body, metrics,
                    emojiSize = emojiSize, modifier = Modifier.alpha(bodyAlpha).then(replyAction).then(selectionModifier), pending = true,
                    onQuote = row.quote?.let { { onQuote(it.id) } },
                    menuSource = menuSource,
                    onBubblePositioned = { origins?.bubbles?.set(row.item.messageID, NativeChiefReplyOrigins.Bubble(row.item.key, it)) }) {
                    if (menuSource != null) NativeChiefText(body, Modifier.onGloballyPositioned { if (emojiSize == null) menuSource.text = it })
                    else SelectionContainer { NativeChiefText(body) }
                }
                translation?.let { NativeChiefTranslationCaption(it, true,
                    if (row.lastInRun && emojiSize == null) metrics.drop else 0.dp, "ChiefTranslationCaption:${row.item.key}",
                    modifier = captionModifier, enabled = selected == null && captionVisible, onClick = onTranslation) }
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
