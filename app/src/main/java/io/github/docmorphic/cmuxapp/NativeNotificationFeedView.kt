package io.github.docmorphic.cmuxapp

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
    val unavailable = sources.filter { it.availability == NativeFeedAvailability.OFFLINE }
    val loading = sources.any { it.availability == NativeFeedAvailability.CONNECTING }
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (unavailable.isNotEmpty() && sources.any { it.items.isNotEmpty() }) item("availability") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Unavailable: ${unavailable.joinToString { it.mac.name }}. Showing the last received updates.",
                        Modifier.weight(1f), color = Color(0xFFFFB86C), fontSize = 12.sp)
                    TextButton(onClick = onRefresh) { Text("Retry") }
                }
            }
            projection.days.forEach { day ->
                item("day:${day.date}") {
                    val heading = when (day.date) {
                        null -> "Earlier"
                        today -> "Today"
                        today.minusDays(1) -> "Yesterday"
                        else -> day.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale))
                    }
                    Text(heading, Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp)
                        .semantics { this.heading() }, color = Color(0xFF9B9FA8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                day.groups.forEach { group ->
                    val expanded = group.id in projection.expanded
                    val visible = if (expanded) group.entries else group.entries.take(1)
                    visible.forEachIndexed { index, entry ->
                        item(entry.id) {
                            NativeFeedRow(entry, if (index > 0) group.entries.first() else null,
                                now, locale, onOpen, onRead)
                            if (index == 0 && group.entries.size > 1) {
                                Row(Modifier.fillMaxWidth().padding(end = 18.dp), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onToggle(group.id) }, modifier = Modifier.semantics {
                                        contentDescription = if (expanded) "Hide earlier notifications" else "Show earlier notifications"
                                        stateDescription = "${group.entries.size} updates"
                                    }) { Text("${group.entries.size} ${if (expanded) "⌄" else "›"}", color = Color(0xFF9B9FA8)) }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF292C31))
                        }
                    }
                }
            }
            if (projection.hasMore) item("more") {
                TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("Load more notifications") }
            }
            if (projection.days.isEmpty()) item("empty") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                    Text(when {
                        loading -> "Loading notifications…"
                        searching -> "No matching notifications."
                        unreadOnly -> "No unread notifications."
                        unavailable.isNotEmpty() -> "Notifications are unavailable. Reconnect to a computer and try again."
                        else -> "No notifications yet."
                    }, color = Color(0xFF9B9FA8))
                    if (unavailable.isNotEmpty()) TextButton(onClick = onRefresh) { Text("Retry") }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun NativeFeedRow(entry: NativeFeedEntry, parent: NativeFeedEntry?, now: Long, locale: Locale,
    onOpen: (NativeFeedEntry) -> Unit, onRead: (NativeFeedEntry, Boolean) -> Unit) {
    val latest by rememberUpdatedState(entry)
    val readAction by rememberUpdatedState(onRead)
    var menu by remember(entry.id) { mutableStateOf(false) }
    val item = entry.notification
    val row = remember(entry, locale) { entry.presentation(locale) }
    val parentRow = remember(parent, locale) { parent?.presentation(locale) }
    fun same(a: String?, b: String?) = a != null && b != null &&
        NativeSearchText.fold(a.trim(), locale, true) == NativeSearchText.fold(b.trim(), locale, true)
    val hideHeadline = same(row.headline, parentRow?.headline)
    val hideSource = same(row.source, parentRow?.source)
    val hideComputer = parent?.computer == entry.computer
    val connected = entry.source.availability == NativeFeedAvailability.CONNECTED
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.StartToEnd && latest.source.availability == NativeFeedAvailability.CONNECTED)
            readAction(latest, !latest.notification.isRead)
        false
    })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = connected, enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Color(0xFF2779C8)).padding(18.dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) {
                Text(if (item.isRead) "Mark as Unread" else "Mark as Read", color = Color.White)
            }
        }) {
        Box(Modifier.fillMaxWidth().background(Color(0xFF0B0C0E))) {
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(entry) }, onLongClick = { menu = true })
                .semantics {
                    stateDescription = if (item.isRead) "Read" else "Unread"
                    customActions = listOf(CustomAccessibilityAction(if (item.isRead) "Mark as Unread" else "Mark as Read") {
                        if (connected) onRead(entry, !item.isRead)
                        connected
                    })
                }.padding(start = if (parent != null) 38.dp else 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(if (item.isRead) "  " else "●", Modifier.width(14.dp).clearAndSetSemantics { },
                        color = Color(0xFF76B9FF), fontSize = 9.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (hideHeadline) (if (!hideSource) row.source else null) ?: row.preview.orEmpty() else row.headline,
                                Modifier.weight(1f).padding(end = 8.dp),
                                fontWeight = if (!hideHeadline && !item.isRead) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = if (hideHeadline) 14.sp else 15.sp, maxLines = if (hideHeadline) 3 else 2,
                                overflow = TextOverflow.Ellipsis)
                            item.createdAt?.let { seconds ->
                                val time = remember(seconds, now) { runCatching { DateUtils.getRelativeTimeSpanString(
                                    (seconds * 1000).toLong(), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() }.getOrDefault("") }
                                Text(time, Modifier.widthIn(max = 105.dp), color = Color(0xFF9B9FA8), fontSize = 11.sp, maxLines = 1)
                            }
                        }
                        if ((!hideSource && !hideHeadline && row.source != null) || !hideComputer) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Row(Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!hideSource && !hideHeadline) row.source?.let {
                                        Icon(painterResource(R.drawable.ic_feed_bell), null, Modifier.size(12.dp), tint = Color(0xFF9B9FA8))
                                        Spacer(Modifier.width(4.dp))
                                        Text(it, color = Color(0xFF9B9FA8), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (!hideComputer) Row(Modifier.widthIn(max = 150.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val color = if (connected) Color(0xFF9B9FA8) else Color(0xFFFFB86C)
                                    Icon(painterResource(R.drawable.ic_feed_computer), null, Modifier.size(12.dp), tint = color)
                                    Spacer(Modifier.width(4.dp))
                                    Text(entry.computer, color = color, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (!hideHeadline || (!hideSource && row.source != null)) row.preview?.let {
                            Text(it, color = Color(0xFF9B9FA8), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen(entry) })
                DropdownMenuItem(text = { Text(if (item.isRead) "Mark as Unread" else "Mark as Read") }, enabled = connected,
                    onClick = { menu = false; onRead(entry, !item.isRead) })
            }
        }
    }
}
