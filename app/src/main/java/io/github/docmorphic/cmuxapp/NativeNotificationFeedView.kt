package io.github.docmorphic.cmuxapp

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeNotificationFeedView(
    projection: NativeFeedProjection, sources: Collection<NativeFeedSource>, unreadOnly: Boolean,
    searching: Boolean, refreshing: Boolean, now: Long, locale: Locale, modifier: Modifier = Modifier,
    onOpen: (NativeFeedEntry) -> Unit, onRead: (NativeFeedEntry, Boolean) -> Unit,
    onToggle: (String) -> Unit, onMore: () -> Unit, onRefresh: () -> Unit
) {
    val target = nativeFeedListRows(projection, sources, unreadOnly, searching, now, locale)
    val currentRows by rememberUpdatedState(target.associateBy { it.key })
    val openAction by rememberUpdatedState(onOpen)
    val readAction by rememberUpdatedState(onRead)
    val toggleAction by rememberUpdatedState(onToggle)
    val moreAction by rememberUpdatedState(onMore)
    val refreshAction by rememberUpdatedState(onRefresh)
    val list = rememberLazyListState()
    val swipes = remember { WorkspaceSwipeCoordinator() }
    val held = list.isScrollInProgress || swipes.activeKey != null
    val rendered = rememberWorkspacePresentationRows(target, held) { it.key }
    LaunchedEffect(currentRows.keys) {
        if (swipes.activeKey?.let { it !in currentRows } == true) swipes.activeKey = null
    }
    WorkspaceViewportAnchorEffect(list, rendered.map { it.key }, swipes.activeKey != null)
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) {
        CompositionLocalProvider(LocalWorkspaceGeometryHeld provides held, LocalWorkspaceSwipeCoordinator provides swipes) {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                items(rendered, key = { it.key }) { row ->
                    WorkspacePresentationRow(row.key in currentRows, { row.key in currentRows }) {
                        val admitted by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
                        when (row) {
                            is NativeFeedListRow.Notification -> Column {
                                CompositionLocalProvider(LocalWorkspaceSwipeKey provides row.key) {
                                    NativeFeedRow(row.entry.rowValue(locale), row.context, now,
                                        onOpen = { if (admitted()) (currentRows[row.key] as? NativeFeedListRow.Notification)?.let { openAction(it.entry) } },
                                        onRead = { read -> if (admitted()) (currentRows[row.key] as? NativeFeedListRow.Notification)?.let {
                                            if (it.entry.source.availability == NativeFeedAvailability.CONNECTED) readAction(it.entry, read)
                                        } })
                                }
                                NativeFeedHistoryToggle(row.disclosure) { expanded ->
                                    if (admitted()) (currentRows[row.key] as? NativeFeedListRow.Notification)?.disclosure?.let {
                                        if (it.expanded != expanded) toggleAction(it.group)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF292C31))
                            }
                            is NativeFeedListRow.Heading -> WorkspaceMeasuredContent(row.text, held) { text, _ ->
                                Text(text, Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp)
                                    .semantics { heading() }, color = Color(0xFF9B9FA8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            else -> WorkspaceMeasuredContent(row, held) { shown, measuring ->
                                NativeFeedStatusBody(shown, measuring) {
                                    if (admitted()) when (currentRows[row.key]) {
                                        NativeFeedListRow.More -> moreAction()
                                        is NativeFeedListRow.Notice -> refreshAction()
                                        is NativeFeedListRow.Empty -> if ((currentRows[row.key] as? NativeFeedListRow.Empty)?.retry == true) refreshAction()
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NativeFeedRow(value: NativeFeedRowValue, context: NativeFeedRowContext, now: Long,
    canOpen: Boolean = true, canRead: Boolean = value.availability == NativeFeedAvailability.CONNECTED,
    onOpen: () -> Unit, onRead: (Boolean) -> Unit) {
    val latestOpen by rememberUpdatedState(onOpen)
    val latestRead by rememberUpdatedState(onRead)
    val openEnabled by rememberUpdatedState(canOpen)
    val readEnabled by rememberUpdatedState(canRead)
    val admitted by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
    fun open() { if (admitted() && openEnabled) latestOpen() }
    fun read(desired: Boolean): Boolean {
        if (!admitted() || !readEnabled) return false
        latestRead(desired)
        return true
    }
    var menu by remember(value.key) { mutableStateOf(false) }
    val present = admitted()
    LaunchedEffect(present, canOpen, canRead) { if (!present || (!canOpen && !canRead)) menu = false }
    val time = remember(value.createdAt, now) { value.createdAt?.let { seconds -> runCatching {
        DateUtils.getRelativeTimeSpanString((seconds * 1000).toLong(), now,
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }.getOrDefault("") }.orEmpty() }
    NativeWorkspaceSwipeActions(value.key, !value.isRead, canRead, false,
        onRead = { unread -> read(!unread) }, onClose = {}) { dismissSwipe, swipeActions ->
        Box(Modifier.fillMaxWidth().background(Color(0xFF0B0C0E))) {
            WorkspaceMeasuredContent(NativeFeedVisual(value, context, time), LocalWorkspaceGeometryHeld.current) { shown, measuring ->
                NativeFeedRowBody(shown, if (measuring) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().testTag("feed.row:${value.key}")
                    .combinedClickable(enabled = canOpen || canRead,
                        onClick = { if (!dismissSwipe()) open() },
                        onLongClick = { if (admitted() && (openEnabled || readEnabled)) { dismissSwipe(); menu = true } })
                    .semantics {
                        stateDescription = if (value.isRead) "Read" else "Unread"
                        customActions = if (swipeActions.canRead) listOf(CustomAccessibilityAction(
                            if (swipeActions.hasUnread) "Mark as Read" else "Mark as Unread") {
                            dismissSwipe(); read(swipeActions.hasUnread)
                        }) else emptyList()
                    })
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, enabled = canOpen, onClick = { menu = false; open() })
                DropdownMenuItem(text = { Text(if (value.isRead) "Mark as Unread" else "Mark as Read") }, enabled = canRead,
                    onClick = { menu = false; read(!value.isRead) })
            }
        }
    }
}
