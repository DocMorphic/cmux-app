package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val feedMuted = Color(0xFF9CA3AF)
private val feedAccent = Color(0xFF76B9FF)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeAgentFeedRow(entry: AgentFeedUiEntry, model: NativeAgentFeedPresentation, needsInput: Boolean,
    display: NativeDisplayPreferences, time: String, onRead: (Boolean) -> Unit, onOpen: (Boolean) -> Unit,
    onCompose: (String) -> Unit, onDecision: (AgentFeedDecision) -> Unit, onFullText: () -> Unit) {
    val item = entry.item
    val ready = entry.connected
    val pending = entry.pending
    val failure = entry.failure
    var menu by remember { mutableStateOf(false) }
    NativeWorkspaceSwipeActions(entry.key, needsInput, true, false, onRead, {}, readLabel = "Done", unreadLabel = "Needs Input",
        leadingColor = if (needsInput) Color(0xFF007AFF) else Color(0xFFFF9500)) { dismiss, _ ->
        Row(Modifier.fillMaxWidth().testTag("AgentFeedRow:${item.id}").combinedClickable(
            onClick = { if (!dismiss() && ready && item.workspaceId != null) onOpen(false) },
            onLongClick = { dismiss(); menu = true }).semantics {
                customActions = buildList {
                    add(CustomAccessibilityAction(if (needsInput) "Done" else "Needs Input") { dismiss(); onRead(!needsInput); true })
                    if (ready && item.workspaceId != null) add(CustomAccessibilityAction("Open workspace") { dismiss(); onOpen(false); true })
                    if (ready && item.surfaceId != null && item.workspaceId != null) add(CustomAccessibilityAction("Open tab") { dismiss(); onOpen(true); true })
                }
            }.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(Color(0xFF202329), CircleShape), contentAlignment = Alignment.Center) {
                if (item.source.lowercase() in setOf("claude", "codex", "opencode")) TaskTemplateIcon("agent:${item.source.lowercase()}")
                else Text(model.author.take(1), fontWeight = FontWeight.Bold, color = feedMuted)
                if (needsInput) Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(feedAccent, CircleShape)
                    .border(2.dp, Color(0xFF0B0C0E), CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.author, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    val context = listOfNotNull(model.headline, model.location(display.feedShowsTab)?.let { "· $it" }).joinToString(" ")
                    Text(context, Modifier.weight(1f), fontSize = 14.sp, color = feedMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time, fontSize = 11.sp, color = feedMuted, maxLines = 1)
                }
                model.quote?.let { FeedQuote(it, display.feedBubbleQuotes, user = true, lineLimit = 3) }
                model.output?.let { AgentFeedInlinePreview(it, item.fullTextTruncated, 8, ready, onFullText) }
                model.tool?.let { tool ->
                    if (item.kind == AgentFeedKind.TOOL_RESULT) AgentFeedInlinePreview(tool,
                        item.fullTextTruncated || item.fullTextPreview?.let { it != tool } == true, 2, ready, onFullText,
                        color = if (item.toolResultIsError) Color(0xFFFF6666) else feedMuted, monospaced = true, fontSize = 12)
                    else Text(tool, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = feedMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (model.resolution != null) Text(model.resolution, fontSize = 12.sp, color = feedMuted)
                else if (item.needsInput) {
                    when (item.kind) {
                        AgentFeedKind.PERMISSION -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("once" to "Allow Once", "always" to "Always Allow", "deny" to "Deny").forEach { (mode, label) ->
                                OutlinedButton(onClick = { dismiss(); onDecision(AgentFeedDecision("permission", mode)) }, enabled = ready && !pending) { Text(label, fontSize = 12.sp) }
                            }
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { dismiss(); expanded = true }, enabled = ready && !pending) { Text("More", fontSize = 12.sp) }
                                DropdownMenu(expanded, { expanded = false }) {
                                    listOf("all" to "Allow All", "bypass" to "Bypass Permissions").forEach { (mode, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onDecision(AgentFeedDecision("permission", mode)) })
                                    }
                                }
                            }
                        }
                        AgentFeedKind.PLAN -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { dismiss(); onDecision(item.planApproval()) }, enabled = ready && !pending) { Text("Approve") }
                            TextButton(onClick = { dismiss(); onCompose("revise") }, enabled = ready && !pending) { Text("Revise") }
                            TextButton(onClick = { dismiss(); onDecision(AgentFeedDecision("exit_plan", "deny")) }, enabled = ready && !pending) { Text("Deny") }
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { dismiss(); expanded = true }, enabled = ready && !pending,
                                    modifier = Modifier.semantics { contentDescription = "More approval modes" }) { Text("More") }
                                DropdownMenu(expanded, { expanded = false }) {
                                    agentFeedPlanModes.forEach { (mode, label) ->
                                        DropdownMenuItem(text = { Text(label) }, enabled = ready && !pending,
                                            onClick = { expanded = false; onDecision(AgentFeedDecision("exit_plan", mode)) })
                                    }
                                }
                            }
                        }
                        AgentFeedKind.QUESTION -> AgentFeedQuestionControls(item, !pending) {
                            dismiss(); onCompose("question")
                        }
                        else -> Unit
                    }
                } else if (item.supportsTerminalReply) {
                    item.replyText?.let { FeedReplyMarker(it, model.replyReference, display.feedBubbleQuotes) }
                    if (failure != null && item.replyText == null && !pending) {
                        FeedReplyFailure(failure, ready, item.workspaceId != null, {
                            dismiss(); onCompose("terminal")
                        }, { dismiss(); onOpen(item.surfaceId != null) })
                    } else TextButton(onClick = { dismiss(); onCompose("terminal") }, enabled = ready && !pending && item.replyText == null,
                        modifier = Modifier.testTag("AgentFeedReplyButton"), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                        if (pending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = feedMuted)
                        else Icon(if (item.replyText == null) painterResource(R.drawable.ic_feed_reply) else painterResource(R.drawable.ic_menu_check),
                            contentDescription = null, tint = feedMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (pending) "Sending…" else if (item.replyText != null) "Replied" else "Reply", color = feedMuted, fontSize = 12.sp)
                    }
                }
                if (!item.supportsTerminalReply) failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                if (pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                DropdownMenu(menu, { menu = false }) {
                    if (ready && item.workspaceId != null) DropdownMenuItem(text = { Text("Open workspace") }, onClick = { menu = false; onOpen(false) })
                    if (ready && item.surfaceId != null && item.workspaceId != null) DropdownMenuItem(text = { Text("Open tab") }, onClick = { menu = false; onOpen(true) })
                    DropdownMenuItem(text = { Text(if (needsInput) "Done" else "Needs Input") }, onClick = { menu = false; onRead(!needsInput) })
                }
            }
        }
    }
}

@Composable
private fun FeedQuote(text: String, bubble: Boolean, user: Boolean, lineLimit: Int) {
    if (bubble) FeedBubble(user, filled = false) {
        AgentFeedMarkdownText(text, color = if (user) feedAccent else feedMuted, lineLimit = lineLimit, fontSize = 12)
    } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(feedMuted.copy(alpha = .35f), RoundedCornerShape(1.5.dp)))
        AgentFeedMarkdownText(text, color = feedMuted, lineLimit = lineLimit, fontSize = 12)
    }
}

@Composable
internal fun FeedBubble(user: Boolean, filled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val direction = LocalLayoutDirection.current
    // Border's outline cache must be replaced when direction changes in-place.
    val shape = remember(user, direction) { AgentFeedBubbleShape(trailing = user) }
    val tint = if (user) feedAccent else feedMuted
    Box(Modifier.fillMaxWidth().padding(start = if (user) 40.dp else 0.dp, end = if (user) 0.dp else 40.dp),
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(modifier.widthIn(min = 32.dp).then(if (filled) Modifier.background(Color(0xFF007AFF), shape)
            else Modifier.border(1.dp, tint.copy(alpha = if (user) .55f else .45f), shape))
            .padding(start = if (user) 12.dp else 16.dp, end = if (user) 16.dp else 12.dp, top = 7.dp, bottom = 7.dp)) { content() }
    }
}

@Composable
internal fun FeedReplyMarker(reply: String, reference: String?, bubble: Boolean) {
    if (bubble) FeedBubble(user = true, filled = true,
        modifier = Modifier.testTag("AgentFeedSentBubble").clearAndSetSemantics { contentDescription = "You: $reply" }) {
        AgentFeedMarkdownText(reply, color = Color.White, fontSize = 12)
    } else Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        reference?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(R.drawable.ic_feed_reply), contentDescription = null, tint = feedMuted, modifier = Modifier.size(12.dp))
            Text("Replying to “$it”", style = TextStyle(textDirection = TextDirection.Content), color = feedMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }
        Row(Modifier.background(feedAccent.copy(alpha = .12f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("You", Modifier.alignByBaseline(), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            AgentFeedMarkdownText(reply, Modifier.weight(1f, fill = false).alignByBaseline(), fontSize = 12)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedReplyFailure(failure: AgentFeedFailure, ready: Boolean, canOpen: Boolean, onRetry: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.padding(top = 2.dp).testTag("AgentFeedReplyFailure"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(painterResource(R.drawable.ic_task_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Text(if (failure.delivery == AgentFeedDelivery.NOT_SENT) "Reply not sent."
                else "Couldn’t confirm your reply was sent. Check the terminal before retrying.",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextButton(onClick = onRetry, enabled = ready && failure.draft != null) { Text("Try Again", fontSize = 12.sp) }
            if (failure.delivery == AgentFeedDelivery.UNCONFIRMED && canOpen)
                TextButton(onClick = onOpen, enabled = ready) { Text("Open Terminal", fontSize = 12.sp) }
        }
    }
}
