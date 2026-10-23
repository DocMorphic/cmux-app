package io.github.docmorphic.cmuxapp

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.Locale

private val agentFeedModalSaver = Saver<AgentFeedModal?, String>(save = { it?.encode() }, restore = AgentFeedModal::decode)
private val agentFeedMuted: Color @Composable get() = NativeAppColors.muted
private val agentFeedAccent: Color @Composable get() = NativeAppColors.accent

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NativeAgentFeedView(
    sources: Collection<NativeFeedSource>, query: String, needsInputOnly: Boolean,
    readState: NativeAgentFeedReadState, onReadState: (NativeAgentFeedReadState) -> Unit,
    session: (NativeCredentialStore.PairedMac) -> NativeAgentFeedSession?, computerName: (NativeCredentialStore.PairedMac) -> String,
    onOpen: (NativeAgentFeedEntry, Boolean) -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    locale: Locale = Locale.getDefault(), display: NativeDisplayPreferences = NativeDisplayPreferences(),
    scopeKey: String = "feed", allowedMacs: Collection<NativeCredentialStore.PairedMac> = sources.map { it.mac }
) {
    val stopReasons = remember(scopeKey) { NativeAgentFeedStopReasonCache() }
    val nativeEntries = remember(sources, stopReasons) { aggregateNativeAgentFeed(sources, stopReasons) }
    val currentNativeEntries by rememberUpdatedState(nativeEntries.associateBy { it.key })
    val currentRead by rememberUpdatedState(readState)
    val updateRead by rememberUpdatedState(onReadState)
    val currentSession by rememberUpdatedState(session)
    val currentOpen by rememberUpdatedState(onOpen)
    val snapshot = agentFeedUiSnapshot(sources, nativeEntries, allowedMacs, readState, computerName)
    val actions = remember {
        object : AgentFeedTimelineActions {
            private fun live(entry: AgentFeedUiEntry): NativeAgentFeedEntry {
                val current = checkNotNull(currentNativeEntries[entry.key]) { "This Feed item is no longer available" }
                check(entry.matchesIdentity(current)) { "This Feed item changed. Review it again before acting." }
                return current
            }
            private fun session(entry: NativeAgentFeedEntry) = checkNotNull(currentSession(entry.source.mac)) {
                "Connect to this computer to use Feed"
            }
            override suspend fun decide(entry: AgentFeedUiEntry, decision: AgentFeedDecision): Boolean =
                live(entry).let { session(it).decide(entry.item, decision) }
            override suspend fun reply(entry: AgentFeedUiEntry, text: String): Boolean =
                live(entry).let { session(it).terminalReply(entry.item, text) }
            override suspend fun fullText(entry: AgentFeedUiEntry): String {
                val ready = awaitNativeAgentFeedReadSession(checkCurrent = { live(entry) },
                    session = { currentSession(live(entry).source.mac) })
                return ready.fullText(live(entry).item)
            }
            override fun read(entry: AgentFeedUiEntry, needsInput: Boolean?) {
                val current = currentNativeEntries[entry.key]?.takeIf(entry::matchesIdentity) ?: return
                updateRead(if (needsInput == null) currentRead.interacted(current) else currentRead.triage(current, needsInput))
            }
            override fun open(entry: AgentFeedUiEntry, tab: Boolean) { currentOpen(live(entry), tab) }
        }
    }
    AgentFeedTimeline(snapshot, query, needsInputOnly, actions, onRefresh, modifier, locale, display, scopeKey)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AgentFeedTimeline(
    snapshot: AgentFeedUiSnapshot, query: String, needsInputOnly: Boolean, actions: AgentFeedTimelineActions,
    onRefresh: () -> Unit, modifier: Modifier = Modifier, locale: Locale = Locale.getDefault(),
    display: NativeDisplayPreferences = NativeDisplayPreferences(), scopeKey: String = "feed"
) {
    val entries = snapshot.entries
    val currentEntries by rememberUpdatedState(entries.associateBy { it.key })
    val models = remember(entries.map { it.key to it.item }) { entries.associate { it.key to NativeAgentFeedPresentation.from(it.item) } }
    val now = remember { System.currentTimeMillis() / 1000.0 }
    val listState = rememberLazyListState()
    val swipes = remember { WorkspaceSwipeCoordinator() }
    val currentActions by rememberUpdatedState(actions)
    val scope = rememberCoroutineScope()
    var modal by rememberSaveable(stateSaver = agentFeedModalSaver) { mutableStateOf<AgentFeedModal?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val index = remember(entries, locale) { NativeSearchIndex(entries.map { it.key to it.item.searchFields(it.computerName) }, locale, notification = true) }
    val matches = remember(index, query) { index.matches(query) }
    val visible = remember(entries, models, matches, needsInputOnly) {
        entries.filter { it.item.notable && models[it.key]?.visible == true && it.key in matches && (!needsInputOnly || it.needsInput) }
    }
    val held = listState.isScrollInProgress || swipes.activeKey != null
    val rendered = rememberWorkspacePresentationRows(visible, held) { it.key }
    WorkspaceViewportAnchorEffect(listState, rendered.map { it.key }, swipes.activeKey != null)
    val connected = snapshot.connected
    val updating = snapshot.updating
    val noSupport = snapshot.unsupported
    fun act(entry: AgentFeedUiEntry, decision: AgentFeedDecision) {
        scope.launch {
            try {
                val live = checkNotNull(currentEntries[entry.key]) { "This Feed item is no longer available" }
                check(live.item.requestId == entry.item.requestId && live.item.kind == entry.item.kind &&
                    (decision.kind != "question" || live.item.questions == entry.item.questions)) { "This request changed. Review it again before answering." }
                if (currentActions.decide(entry, decision)) currentActions.read(live)
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
                    TextButton(onClick = { actionError = null }) { Text(stringResource(R.string.cmux_ui_mobile_terminal_outputrecovery_dismiss_c572c42a)) }
                }
            }
            if (snapshot.refreshFailed) item("refresh-error") {
                TextButton(onClick = onRefresh, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Activity could not refresh. Retry") }
            }
            if (visible.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when {
                        query.isNotBlank() -> "No matching activity"
                        noSupport -> "Update cmux on your Mac to use Feed"
                        !snapshot.hasSources -> "Connect a Mac to see agent activity"
                        updating -> "Loading agent activity…"
                        !connected -> "Agent Feed is unavailable"
                        needsInputOnly -> "Nothing needs your input"
                        else -> "No agent activity yet"
                    }, fontWeight = FontWeight.SemiBold)
                    Text("Agent requests and responses appear here.",
                        Modifier.padding(top = 8.dp), color = agentFeedMuted)
                }
            }
            items(rendered, key = { it.key }) { entry ->
                WorkspacePresentationRow(entry.key in currentEntries, { entry.key in currentEntries }) {
                    CompositionLocalProvider(LocalWorkspaceSwipeKey provides entry.key) {
                        NativeAgentFeedRow(entry, models[entry.key] ?: NativeAgentFeedPresentation.from(entry.item),
                            entry.needsInput, display, agentFeedTimeLabel(entry.item.createdAt, now, locale),
                            onRead = { needs -> currentEntries[entry.key]?.let { currentActions.read(it, needs) } },
                            onOpen = { tab -> currentEntries[entry.key]?.let { currentActions.read(it); currentActions.open(it, tab) } },
                            onCompose = { modal = AgentFeedModal.from(scopeKey, entry, it) }, onDecision = { act(entry, it) },
                            onFullText = { modal = AgentFeedModal.from(scopeKey, entry, "read") })
                    }
                }
                HorizontalDivider(color = NativeAppColors.line)
            }
        }
        }
    }
    modal?.let { target ->
        key(target.scope, target.key, target.mode) {
            // Keep only this open modal's saved UI while an authorized snapshot
            // reloads. Closing it or changing accounts destroys the holder, so a
            // later opening starts fresh. Message bodies and load jobs are not saved.
            val modalState = rememberSaveableStateHolder()
            when (target.status(scopeKey, snapshot)) {
                AgentFeedModalStatus.GONE -> LaunchedEffect(target) { modal = null }
                AgentFeedModalStatus.WAITING -> AgentFeedWaitingSheet({ modal = null }, onRefresh, reader = target.mode == "read")
                AgentFeedModalStatus.READY -> modalState.SaveableStateProvider("content") {
                    val entry = checkNotNull(currentEntries[target.key])
                    fun update(next: AgentFeedModal) {
                        val current = modal
                        if (current?.scope == target.scope && current.key == target.key && current.mode == target.mode &&
                            next.scope == current.scope && next.key == current.key && next.mode == current.mode) modal = next
                    }
                    suspend fun load(): String {
                        val live = checkNotNull(currentEntries[target.key]) { "This Feed item is no longer available" }
                        check(target.matches(live)) { "This Feed item changed" }
                        return currentActions.fullText(live)
                    }
                    if (target.mode == "read") AgentFeedFullText(::load,
                        { currentActions.read(entry) }, { modal = null })
                    else if (target.mode == "question") AgentFeedQuestionSheet(entry, target, ::update, { modal = null }) {
                        act(entry, it); modal = null
                    }
                    else AgentFeedReplySheet(entry, target, ::update, ::load, { modal = null }) { decision, text ->
                        if (text == null) { checkNotNull(decision); act(entry, decision); modal = null }
                        else scope.launch {
                            try {
                                val live = checkNotNull(currentEntries[target.key]) { "This Feed item is no longer available" }
                                check(target.matches(live)) { "This Feed item changed. Review it before replying." }
                                if (currentActions.reply(live, text)) {
                                    currentActions.read(live)
                                    if (modal?.key == target.key && modal?.mode == target.mode) modal = null
                                }
                            } catch (error: Exception) { if (error is CancellationException) throw error; actionError = error.message }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentFeedFullText(load: suspend () -> String, onRead: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }; var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val formattedPosition = rememberSaveable(saver = NativeMarkdownPosition.Saver) { NativeMarkdownPosition() }
    LaunchedEffect(attempt) {
        failed = false
        try {
            val loaded = load()
            // A transport callback may finish after dismissal even when its
            // underlying read cannot be cancelled. Never mark a retired sheet read.
            currentCoroutineContext().ensureActive()
            text = loaded; onRead()
        }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            currentCoroutineContext().ensureActive()
            failed = true
        }
    }
    AgentFeedFullTextTheme {
        AgentFeedFullTextPresentation(onDismiss) {
            AgentFeedFullTextHeader(onDismiss)
            if (text == null) AgentFeedFullTextStatus(failed) { attempt++ }
            else NativeMarkdownReader(text!!, formattedPosition)
        }
    }
}

@Composable
internal fun AgentFeedFullTextTheme(content: @Composable () -> Unit) {
    // Measured in the original presented reader's UIKit traits and compositor:
    // elevated dark system background 28/28/30 with white labels; light is white/black.
    val dark = NativeAppColors.dark
    val background = if (dark) Color(0xFF1C1C1E) else Color.White
    val foreground = if (dark) Color.White else Color.Black
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = background, surface = background,
        onBackground = foreground, onSurface = foreground), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentFeedFullTextPresentation(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    if (configuration.screenWidthDp >= 600 && configuration.screenHeightDp >= 600) {
        NativeDialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.widthIn(max = 580.dp).fillMaxWidth().heightIn(max = 650.dp).fillMaxHeight()
                .testTag("AgentFeedFullTextPanel"), shape = RoundedCornerShape(32.dp),
                color = NativeAppColors.page, contentColor = NativeAppColors.text) {
                Column {
                    AgentFeedFullTextDragIndicator(wide = true)
                    Column(Modifier.fillMaxWidth().weight(1f).testTag("AgentFeedFullTextSheet"), content = content)
                }
            }
        }
    } else {
        val landscape = configuration.screenWidthDp > configuration.screenHeightDp
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Material 1.3.2 doesn't consume the sheet offset from top insets.
        // Subtract the space above the sheet, retaining any remaining camera/
        // status inset; requireOffset is initialized by its first measurement.
        val sheetTop = try { sheetState.requireOffset().toInt().coerceAtLeast(0) }
            catch (_: IllegalStateException) { 0 }
        NativeModalBottomSheet(onDismiss, modifier = Modifier.testTag("AgentFeedFullTextCompactPanel")
                .consumeWindowInsets(WindowInsets(top = sheetTop)),
            sheetState = sheetState, sheetMaxWidth = Dp.Unspecified,
            containerColor = NativeAppColors.page, contentColor = NativeAppColors.text,
            // The original compact sheet spans the window even in landscape.
            // Keep its controls and native text inside this device's actual bars/cutout.
            contentWindowInsets = { WindowInsets.safeDrawing },
            dragHandle = { AgentFeedFullTextDragIndicator() }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(if (landscape) 1f else .96f)
                .testTag("AgentFeedFullTextSheet"), content = content)
        }
    }
}

@Composable
private fun AgentFeedFullTextDragIndicator(wide: Boolean = false) {
    val configuration = LocalConfiguration.current
    val height = if (wide) 10.dp else if (configuration.screenWidthDp > configuration.screenHeightDp) 24.dp else 16.dp
    Box(Modifier.fillMaxWidth().height(height).testTag("AgentFeedFullTextDragArea"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 5.dp).background(NativeAppColors.text.copy(alpha = .3f), RoundedCornerShape(50)))
    }
}

/** Center when space permits, then shift away from Close as the original bar does. */
@Composable
internal fun AgentFeedFullTextHeader(onDismiss: () -> Unit) {
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 600 && configuration.screenHeightDp >= 600
    val titleStyle = with(LocalDensity.current) {
        // Source UIKit measurements: 17pt at ordinary text, capped at 21pt
        // while the body reaches 28pt. Convert through the actual Android font
        // scaler so nonlinear accessibility scaling cannot exceed that role cap.
        val size = minOf(17.sp.toDp(), 21.dp)
        TextStyle(fontSize = size.toSp(), lineHeight = (size * 1.2f).toSp(), letterSpacing = 0.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false))
    }
    // The original Close UIFont is not exposed. This Android role follows its
    // measured glyph size/growth while retaining the fixed 44dp painted capsule.
    val closeStyle = with(LocalDensity.current) {
        val size = minOf(17.sp.toDp(), 21.dp)
        TextStyle(fontSize = size.toSp(), lineHeight = (size * 1.2f).toSp(), letterSpacing = 0.sp,
            fontWeight = FontWeight.Normal, platformStyle = PlatformTextStyle(includeFontPadding = false))
    }
    val dark = NativeAppColors.dark
    androidx.compose.ui.layout.Layout(modifier = Modifier.fillMaxWidth().testTag("AgentFeedFullTextHeader")
        .padding(horizontal = if (wide) 10.dp else 16.dp), content = {
        Surface(onClick = onDismiss, modifier = Modifier.height(44.dp).testTag("AgentFeedFullTextCloseCapsule")
            .semantics { role = Role.Button }, shape = CircleShape,
            color = if (dark) Color(0xFF222225) else Color.White, contentColor = NativeAppColors.text,
            border = BorderStroke(1.dp, if (dark) Color(0xFF424248) else Color.White),
            shadowElevation = if (dark) 2.dp else 10.dp) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.cmux_ui_mobile_agentfeed_fulltext_close_4053e619),
                    Modifier.testTag("AgentFeedFullTextCloseLabel"), style = closeStyle,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(stringResource(R.string.cmux_ui_mobile_agentfeed_fulltext_title_257807f0), Modifier.testTag("AgentFeedFullTextTitle"), fontWeight = FontWeight.SemiBold,
            style = titleStyle,
            maxLines = 2, softWrap = true, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }) { children, constraints ->
        val close = children[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val titleWidth = children[1].maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        // Glass paints 4pt outside the original 36pt AX frame on each edge.
        // Its 12pt visual gap therefore preserves the measured 16pt AX gap.
        val gap = 12.dp.roundToPx()
        val trailing = 4.dp.roundToPx()
        // The original bar reserves space only on the cancellation side. A
        // symmetric reservation needlessly wraps translated titles. Retain a
        // second row only for Android windows where the complete pair cannot fit.
        val stacked = close.width.toLong() + gap + titleWidth + trailing > constraints.maxWidth
        if (stacked) {
            val rowGap = 8.dp.roundToPx()
            val title = children[1].measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxHeight = (constraints.maxHeight - close.height - rowGap - 10.dp.roundToPx()).coerceAtLeast(0)))
            layout(constraints.maxWidth, close.height + rowGap + title.height + 10.dp.roundToPx()) {
                close.placeRelative(0, 0)
                title.place((constraints.maxWidth - title.width) / 2, close.height + rowGap)
            }
        } else {
            val title = children[1].measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxWidth = (constraints.maxWidth - close.width - gap - trailing).coerceAtLeast(0)))
            val rowHeight = 44.dp.roundToPx()
            val titleStart = maxOf((constraints.maxWidth - title.width) / 2, close.width + gap)
            layout(constraints.maxWidth, 54.dp.roundToPx()) {
                close.placeRelative(0, (rowHeight - close.height) / 2)
                title.placeRelative(titleStart, (rowHeight - title.height) / 2)
            }
        }
    }
}
