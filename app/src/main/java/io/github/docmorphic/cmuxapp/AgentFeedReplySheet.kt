/* Reply presentation follows cmux AgentFeedReplyComposer.swift at
 * 186cec79781256867ad4516f0802118738bd2393. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

internal val agentFeedViewportSaver = listSaver<MarkdownViewportState, Any>(
    save = { it.capture?.invoke(); it.save() }, restore = { MarkdownViewportState().apply { restore(it) } })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentFeedWaitingSheet(onDismiss: () -> Unit, onRefresh: () -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Waiting for this Mac’s Feed", fontWeight = FontWeight.SemiBold)
            Text("Your draft will reopen when this computer reconnects and the message is available.")
            Row { TextButton(onRefresh) { Text("Retry") }; TextButton(onDismiss) { Text("Cancel") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentFeedReplySheet(entry: NativeAgentFeedEntry, modal: AgentFeedModal,
    onChange: (AgentFeedModal) -> Unit, load: suspend () -> String, onDismiss: () -> Unit,
    onSubmit: (AgentFeedDecision?, String?) -> Unit) {
    val item = entry.item
    val model = remember(item) { NativeAgentFeedPresentation.from(item) }
    val pending = item.id in entry.source.agentFeed.pending
    val ready = entry.source.availability == NativeFeedAvailability.CONNECTED && AGENT_FEED_CAPABILITY in entry.source.capabilities
    var fullText by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val viewport = rememberSaveable(saver = agentFeedViewportSaver) { MarkdownViewportState() }
    val focus = remember { FocusRequester() }
    val canSend = ready && !pending && modal.draft.isNotBlank() && modal.matches(entry)
    fun send() { if (canSend) {
        if (modal.mode == "terminal") onSubmit(null, modal.draft.trim())
        else onSubmit(AgentFeedDecision("exit_plan", "manual", feedback = modal.draft.trim()), null)
    } }
    LaunchedEffect(modal.expanded, attempt) {
        if (!modal.expanded) return@LaunchedEffect
        failed = false
        try { fullText = load() }
        catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().testTag("AgentFeedReplySheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDismiss) { Text("Cancel") }
                Button(::send, enabled = canSend, modifier = Modifier.testTag("AgentFeedComposeSend")) {
                    Text(if (pending) "Sending…" else if (modal.mode == "terminal") "Reply" else "Send")
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp)) {
                // Draw the thread after ordinary measurement. Intrinsic sizing cannot
                // measure width-aware previews (or future virtualized full messages).
                Row(Modifier.heightIn(min = 58.dp).drawBehind {
                    drawLine(Color(0xFF45494F), Offset(20.dp.toPx(), 40.dp.toPx()),
                        Offset(20.dp.toPx(), size.height), strokeWidth = 2.dp.toPx())
                }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeedComposerAvatar(item.source, model.author)
                    Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(model.author, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            model.headline?.let { Text(it, color = Color(0xFF9CA3AF), fontSize = 14.sp) }
                        }
                        model.output?.let { preview ->
                            when {
                                !modal.expanded -> AgentFeedInlinePreview(preview, item.fullTextTruncated, 6, ready,
                                    { onChange(modal.copy(expanded = true)) }, color = Color(0xFF9CA3AF))
                                failed -> TextButton(onClick = { attempt++ }, enabled = ready) { Text("Couldn't load the full message. Try again") }
                                fullText == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                                else -> {
                                    // Keep the entire loaded message available, including content beyond
                                    // the row renderer's 8 KiB cap, without saving it into Activity state.
                                    Box(Modifier.fillMaxWidth().height(320.dp).testTag("AgentFeedExpandedQuote")) {
                                        if (modal.raw) SelectionContainer { Text(fullText!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) }
                                        else MarkdownWebPreview(fullText!!, viewport) { onChange(modal.copy(raw = true)) }
                                    }
                                }
                            }
                        }
                        Text("Replying to ${model.author}", color = Color(0xFF76B9FF), fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    FeedComposerAvatar(null, "You")
                    TextField(modal.draft, { onChange(modal.copy(draft = it)) }, enabled = !pending,
                        placeholder = { Text(if (modal.mode == "terminal") "Reply to agent…" else "What should change?") },
                        minLines = 3, maxLines = 12, modifier = Modifier.weight(1f).focusRequester(focus).testTag("AgentFeedComposeDraft"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                }
                if (!ready) Text("Reconnect to send your reply.", color = Color(0xFF9CA3AF), modifier = Modifier.padding(top = 8.dp))
                entry.source.agentFeed.failures[item.id]?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun FeedComposerAvatar(source: String?, author: String) {
    Box(Modifier.size(40.dp).background(Color(0xFF25292E), CircleShape), contentAlignment = Alignment.Center) {
        if (source?.lowercase() in setOf("claude", "codex", "opencode")) TaskTemplateIcon("agent:${source!!.lowercase()}")
        else Text(author.take(1), fontWeight = FontWeight.SemiBold, color = Color(0xFF9CA3AF))
    }
}
