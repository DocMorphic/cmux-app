package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Instant

/** ChiefHomeView's pinned/recent/empty branches. The retained Mac owner remains authoritative. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeChiefHomeView(computer: NativeChiefComputerSession, modifier: Modifier = Modifier) {
    val state by computer.home.state.collectAsState()
    val leading = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    val query = computer.query
    val rows = state.matching(query)
    val pinned = state.pinned
    val recent = rows.filter { it.id != pinned?.id }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val background = if (dark) Color.Black else Color(0xFFF2F2F7)
    val ink = if (dark) Color.White else Color.Black
    val muted = if (dark) Color(0xFF98989E) else Color(0xFF6C6C70)
    val blue = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val orange = if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    var refreshRequested by remember(computer) { mutableStateOf(false) }
    LaunchedEffect(refreshRequested, state.loading) {
        if (refreshRequested && !state.loading) {
            // An immediate snapshot can coalesce loading=true/false. Material's
            // pull indicator needs to observe its true state before it can hide.
            withFrameNanos { }
            refreshRequested = false
        }
    }
    val refresh = { if (computer.current() && !state.loading && !refreshRequested) {
        refreshRequested = true
        computer.home.refresh()
    } }
    val searchLabel = nativeChiefMainString(R.string.chief_home_search)
    Column(modifier.fillMaxSize().background(background).testTag("ChiefHome")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(nativeChiefMainString(R.string.chief_home_title), Modifier.weight(1f).testTag("ChiefHomeTitle").semantics { heading() },
                color = ink, textAlign = leading, style = nativeChiefTextStyle(34).copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = refresh, modifier = Modifier.testTag("ChiefHomeRefresh")) {
                Icon(painterResource(R.drawable.ic_browser_reload), nativeChiefMainString(R.string.chief_home_refresh), Modifier.size(20.dp), tint = blue)
            }
        }
        OutlinedTextField(query, computer::search, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("ChiefSearch")
            .semantics { contentDescription = searchLabel },
            placeholder = { Text(searchLabel, style = nativeChiefTextStyle()) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_primary_search), null, Modifier.size(18.dp)) },
            singleLine = true, textStyle = nativeChiefTextStyle(), shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFE3E3E8),
                unfocusedContainerColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFE3E3E8),
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
        when {
            state.loading && state.conversations.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = muted)
                    Text(nativeChiefMainString(R.string.chief_home_loading), color = muted, style = nativeChiefTextStyle())
                }
            }
            state.conversations.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp).testTag("ChiefHomeUnavailable"),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_menu_chat), null, Modifier.size(48.dp), tint = muted)
                Spacer(Modifier.height(12.dp))
                Text(nativeChiefMainString(if (state.error != null) R.string.chief_home_unavailable else R.string.chief_home_empty),
                    style = nativeChiefTextStyle(22).copy(fontWeight = FontWeight.Bold), color = ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(state.error ?: nativeChiefMainString(R.string.chief_home_empty_message), color = muted, style = nativeChiefTextStyle(15), textAlign = TextAlign.Center)
            }
            else -> PullToRefreshBox(state.loading || refreshRequested, refresh, Modifier.weight(1f).fillMaxWidth().testTag("ChiefHomePullRefresh")) {
                LazyColumn(Modifier.fillMaxSize().testTag("ChiefConversations")) {
                    if (pinned != null && pinned in rows) {
                        item(key = "pinned:${pinned.id}") {
                            ChiefHomePinned(pinned, ink, muted, blue, orange, dark) { computer.open(pinned) }
                        }
                        if (recent.isEmpty()) item(key = "invitation") {
                            Column(Modifier.fillMaxWidth().height(480.dp).padding(horizontal = 24.dp).testTag("ChiefPinnedOnlyInvitation")
                                .semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(painterResource(R.drawable.ic_chief_home_sparkles), null, Modifier.size(28.dp), tint = blue)
                                Text(nativeChiefMainString(R.string.chief_home_ready_title), color = ink,
                                    style = nativeChiefTextStyle().copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                                Text(nativeChiefMainString(R.string.chief_home_ready_message), Modifier.widthIn(max = 280.dp), color = muted,
                                    style = nativeChiefTextStyle(15), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    if (recent.isNotEmpty()) item(key = "recent") {
                        Text(nativeChiefMainString(R.string.chief_home_recent), Modifier.fillMaxWidth().testTag("ChiefHomeRecentHeading").padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                            .semantics { heading() }, color = muted, textAlign = leading, style = nativeChiefTextStyle(15).copy(fontWeight = FontWeight.SemiBold))
                    }
                    items(recent, key = { "recent:${it.id}" }) { conversation ->
                        ChiefHomeRow(conversation, ink, muted, blue, orange, background, dark) { computer.open(conversation) }
                    }
                }
            }
        }
    }
}

@Composable
private fun chiefHomeSummary(conversation: NativeChiefConversation, stamp: String? = null): String {
    val status = if (conversation.working) nativeChiefMainString(R.string.chief_home_working)
        else if (conversation.unread > 0) nativeChiefHomeUnread(conversation.unread) else null
    return listOfNotNull(conversation.title, conversation.preview.takeIf { it.isNotEmpty() }, status, stamp).joinToString(", ")
}

@Composable
private fun ChiefHomeAvatar(size: Dp, dark: Boolean) {
    Image(painterResource(R.drawable.chief_avatar), null, Modifier.size(size).shadow(5.dp, CircleShape)
        .clip(CircleShape).background(if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)).padding(size * .08f))
}

@Composable
private fun ChiefHomePinned(conversation: NativeChiefConversation, ink: Color, muted: Color, blue: Color, orange: Color, dark: Boolean, open: () -> Unit) {
    val summary = chiefHomeSummary(conversation)
    // Original SwiftUI integer interpolation localizes the visible badge's
    // digits and grouping; its separate L10n accessibility summary uses Western digits.
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    val badge = remember(conversation.unread, locale) { java.text.NumberFormat.getIntegerInstance(locale).format(conversation.unread) }
    Column(Modifier.fillMaxWidth().testTag("ChiefPinnedConversation").clickable(onClick = open)
        .clearAndSetSemantics { contentDescription = summary; role = Role.Button; onClick { open(); true } }
        .padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.testTag("ChiefPinnedAvatar"), contentAlignment = Alignment.TopEnd) {
            ChiefHomeAvatar(88.dp, dark)
            if (conversation.working) Box(Modifier.align(Alignment.TopEnd).absoluteOffset(x = 4.dp, y = (-4).dp)
                .background(orange, CircleShape).padding(7.dp).testTag("ChiefPinnedWorking")) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            } else if (conversation.unread > 0) Text(badge, Modifier.align(Alignment.TopEnd)
                .absoluteOffset(x = 8.dp, y = (-4).dp).background(blue, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 4.dp)
                .testTag("ChiefPinnedUnread"), color = Color.White, style = nativeChiefTextStyle(11).copy(fontWeight = FontWeight.Bold))
        }
        Text(conversation.title, color = ink, style = nativeChiefTextStyle(13).copy(fontWeight = FontWeight.SemiBold))
        Text(conversation.preview.ifEmpty { nativeChiefMainString(R.string.chief_home_ask) }, Modifier.widthIn(max = 180.dp).testTag("ChiefPinnedPreview"),
            color = muted, style = nativeChiefTextStyle(12), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ChiefHomeRow(conversation: NativeChiefConversation, ink: Color, muted: Color, blue: Color, orange: Color,
    background: Color, dark: Boolean, open: () -> Unit) {
    // HStack/VStack leading alignment is the interface edge even for an English
    // conversation inside Arabic UI; paragraph direction still follows its content.
    val leading = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    val stamp = if (conversation.updatedAt == Instant.ofEpochSecond(-62135769600L)) null else
        android.text.format.DateUtils.getRelativeTimeSpanString(conversation.updatedAt.toEpochMilli(), System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    val summary = chiefHomeSummary(conversation, stamp)
    Row(Modifier.fillMaxWidth().testTag("ChiefConversation:${conversation.id}").clickable(onClick = open)
        .clearAndSetSemantics { contentDescription = summary; role = Role.Button; onClick { open(); true } }
        .padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box {
            ChiefHomeAvatar(45.dp, dark)
            if (conversation.working) Box(Modifier.align(Alignment.BottomEnd).background(orange, CircleShape)
                .border(2.dp, background, CircleShape).padding(4.dp).testTag("ChiefRecentWorking:${conversation.id}")) {
                Icon(painterResource(R.drawable.ic_workspace_zap), null, Modifier.size(10.dp), tint = Color.White)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(conversation.title, Modifier.weight(1f).testTag("ChiefRecentTitle:${conversation.id}"), color = ink, style = nativeChiefTextStyle().copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = leading)
                if (stamp != null) Text(stamp, color = muted, style = nativeChiefTextStyle(15))
                Icon(painterResource(R.drawable.ic_workspace_chevron_right), null, Modifier.size(10.dp, 14.dp), tint = muted.copy(alpha = .6f))
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(conversation.preview.ifEmpty { nativeChiefMainString(R.string.chief_home_no_messages) }, Modifier.weight(1f).testTag("ChiefRecentPreview:${conversation.id}"),
                    color = muted, style = nativeChiefTextStyle(15), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = leading)
                if (conversation.unread > 0) Box(Modifier.padding(top = 6.dp).size(7.dp).background(blue, CircleShape).testTag("ChiefRecentUnread:${conversation.id}"))
            }
        }
    }
}
