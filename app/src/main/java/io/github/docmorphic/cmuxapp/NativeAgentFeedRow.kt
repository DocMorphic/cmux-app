package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val feedMuted = Color(0xFF9CA3AF)
private val feedAccent = Color(0xFF76B9FF)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeAgentFeedRow(entry: NativeAgentFeedEntry, model: NativeAgentFeedPresentation, needsInput: Boolean,
    display: NativeDisplayPreferences, time: String, onRead: (Boolean) -> Unit, onOpen: (Boolean) -> Unit,
    onCompose: (String) -> Unit, onDecision: (AgentFeedDecision) -> Unit, onFullText: () -> Unit) {
    val item = entry.item
    val ready = entry.source.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY in entry.source.capabilities
    val pending = item.id in entry.source.agentFeed.pending
    val failure = entry.source.agentFeed.failures[item.id]
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
                        AgentFeedKind.QUESTION -> AgentFeedQuestionControls(item, !pending, canSubmit = ready) {
                            dismiss(); onDecision(it)
                        }
                        else -> Unit
                    }
                } else if (item.supportsTerminalReply) {
                    item.replyText?.let { reply ->
                        model.replyReference?.let { FeedQuote(it, display.feedBubbleQuotes, user = false, lineLimit = 2) }
                        Text("You replied", color = feedMuted, fontSize = 11.sp)
                        AgentFeedMarkdownText(reply, Modifier.fillMaxWidth().background(Color(0xFF20354B), RoundedCornerShape(12.dp)).padding(12.dp), fontSize = 12)
                    }
                    if (item.replyText == null) TextButton(onClick = { dismiss(); onCompose("terminal") }, enabled = ready && !pending) {
                        Text(if (failure != null) "Review reply" else "Reply", color = feedMuted, fontSize = 12.sp)
                    }
                }
                failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
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
    if (bubble) Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        val tint = if (user) feedAccent else feedMuted
        AgentFeedMarkdownText(text, Modifier.fillMaxWidth(.9f).border(1.dp, tint.copy(alpha = .5f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
            color = tint, lineLimit = lineLimit, fontSize = 12)
    } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(feedMuted.copy(alpha = .35f), RoundedCornerShape(1.5.dp)))
        AgentFeedMarkdownText(text, color = feedMuted, lineLimit = lineLimit, fontSize = 12)
    }
}
