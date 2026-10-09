/* Answer sheet/cards follow cmux AgentFeedQuestionComposer/Card/CustomAnswerRow at
 * f4b1509054949eaad5d695569ad443c4c18ed68d. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentFeedQuestionSheet(entry: AgentFeedUiEntry, modal: AgentFeedModal,
    onChange: (AgentFeedModal) -> Unit, onDismiss: () -> Unit, onSubmit: (AgentFeedDecision) -> Unit) {
    val questions = entry.item.questions
    var drafts by remember(modal.scope, modal.key, modal.requestId, modal.questionFingerprint) {
        mutableStateOf(AgentFeedQuestionDrafts.decode(modal.draft) ?: AgentFeedQuestionDrafts())
    }
    val answers = drafts.answers(questions)
    val enabled = !entry.pending && modal.matches(entry)
    // Save in the owning composition: the sheet's separate window is recreated on restore.
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }
    fun update(reduce: (AgentFeedQuestionDrafts) -> AgentFeedQuestionDrafts) {
        // Focus and IME callbacks can arrive before recomposition. Reduce against
        // the latest local state, then persist; a no-op callback cannot restore
        // an older answer mode captured by another question's field.
        val next = reduce(drafts)
        if (next != drafts) { drafts = next; onChange(modal.copy(draft = next.encode())) }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF111317), contentColor = Color(0xFFE9E9EC)) {
        val focus = LocalFocusManager.current
        Column(Modifier.fillMaxWidth().fillMaxHeight(.96f).imePadding().testTag("AgentFeedQuestionSheet")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onDismiss) { Text("Cancel") }
                Text("Answer questions", Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Button(onClick = {
                    val currentAnswers = drafts.answers(questions)
                    if (enabled && entry.connected && currentAnswers != null) {
                        focus.clearFocus(); onSubmit(AgentFeedDecision("question", selections = currentAnswers))
                    }
                }, enabled = enabled && entry.connected && answers != null,
                    modifier = Modifier.testTag("AgentFeedQuestionSubmit"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF176FC1), contentColor = Color.White,
                        disabledContainerColor = Color(0xFF282C32), disabledContentColor = Color(0xFF737983)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text("Submit") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportHeight = it.height }.verticalScroll(scroll).testTag("AgentFeedQuestionScroll")
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Needs your input", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("Choose an option or write an answer for each prompt.", color = questionMuted, fontSize = 14.sp)
                    val answered = questions.count { drafts.answered(it) != null }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(progress = { answered.toFloat() / questions.size.coerceAtLeast(1) },
                            modifier = Modifier.weight(1f).height(4.dp).clearAndSetSemantics { }, color = questionAccent,
                            trackColor = Color(0xFF343940), drawStopIndicator = {})
                        Text("$answered/${questions.size}", fontSize = 12.sp, color = questionMuted,
                            modifier = Modifier.semantics { contentDescription = "$answered of ${questions.size} answered" })
                    }
                    if (!entry.connected) Text("Reconnect to submit your answers.", color = questionMuted, fontSize = 12.sp)
                    entry.failure?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                }
                questions.forEachIndexed { index, question -> key(question.id) {
                    QuestionCard(question, index, drafts, enabled, viewportHeight,
                        onChoose = { option -> focus.clearFocus(); update { it.choose(question, option) } },
                        onCustom = { update { it.selectCustom(question) } },
                        onWrite = { text -> update { it.write(question, text) } })
                } }
            }
        }
    }
}

@Composable
private fun QuestionCard(question: AgentFeedQuestion, index: Int, drafts: AgentFeedQuestionDrafts,
    enabled: Boolean, viewportHeight: Int, onChoose: (String) -> Unit, onCustom: () -> Unit, onWrite: (String) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().background(Color(0xFF1B1E23), shape)
        .border(1.dp, Color.White.copy(alpha = .12f), shape).padding(14.dp)
        .testTag("AgentFeedQuestionCard:${question.id}"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Question ${index + 1}", fontSize = 12.sp, color = questionMuted)
            if (drafts.answered(question) != null) Row(Modifier.background(Color(0xFF69CE8D).copy(alpha = .12f), RoundedCornerShape(50))
                .padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = Color(0xFF69CE8D))
                Text("Answered", color = Color(0xFF69CE8D), fontSize = 12.sp)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            question.header?.takeIf(String::isNotBlank)?.let { Text(it, color = questionMuted, fontSize = 12.sp) }
            ProvideTextStyle(LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold)) {
                AgentFeedMarkdownText(question.prompt, fontSize = 20)
            }
            if (question.multiSelect) Text("Select all that apply", color = questionMuted, fontSize = 12.sp)
        }
        if (question.options.isNotEmpty()) Column(Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = .035f), RoundedCornerShape(14.dp)).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(if (question.multiSelect) 8.dp else 0.dp)) {
            question.options.forEachIndexed { optionIndex, option ->
                val selected = question.id !in drafts.customSelected && option.id in drafts.selected[question.id].orEmpty()
                Row(Modifier.fillMaxWidth().questionSelection(selected)
                    .selectable(selected, enabled, if (question.multiSelect) Role.Checkbox else Role.RadioButton) { onChoose(option.id) }
                    .testTag("AgentFeedQuestionOption:${question.id}:${option.id}")
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuestionSelectionGlyph(question.multiSelect, selected, enabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        AgentFeedMarkdownText(option.label)
                        option.description?.let { AgentFeedMarkdownText(it, color = questionMuted, fontSize = 12) }
                    }
                }
                if (!question.multiSelect && optionIndex < question.options.lastIndex)
                    HorizontalDivider(Modifier.padding(start = 44.dp), color = Color.White.copy(alpha = .1f))
            }
        }
        QuestionCustomAnswer(question, drafts.custom[question.id].orEmpty(), question.id in drafts.customSelected,
            enabled, viewportHeight, onCustom, onWrite)
    }
}

private fun Modifier.questionSelection(selected: Boolean): Modifier {
    val shape = RoundedCornerShape(11.dp)
    return heightIn(min = 44.dp).clip(shape)
        .background(if (selected) questionAccent.copy(alpha = .13f) else Color.Transparent)
        .then(if (selected) Modifier.border(1.dp, questionAccent.copy(alpha = .52f), shape) else Modifier)
}

@Composable
private fun QuestionSelectionGlyph(multi: Boolean, selected: Boolean, enabled: Boolean) {
    if (multi) Checkbox(selected, null, enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = questionAccent, checkmarkColor = Color(0xFF111317)), modifier = Modifier.size(20.dp).clearAndSetSemantics { })
    else RadioButton(selected, null, enabled = enabled, modifier = Modifier.size(20.dp).clearAndSetSemantics { })
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QuestionCustomAnswer(question: AgentFeedQuestion, text: String, selected: Boolean,
    enabled: Boolean, viewportHeight: Int, onCustom: () -> Unit, onWrite: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(0) }
    // The modal consumes IME insets before this child sees them. Track the
    // actual scroll viewport so focus follows both keyboard and sheet resizing.
    LaunchedEffect(focused, height, viewportHeight) {
        if (focused) { withFrameNanos { }; bring.bringIntoView() }
    }
    Row(Modifier.fillMaxWidth().questionSelection(selected)
        .background(if (selected) Color.Transparent else Color.White.copy(alpha = .055f))
        .bringIntoViewRequester(bring).onSizeChanged { height = it.height }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).selectable(selected, enabled,
            if (question.multiSelect) Role.Checkbox else Role.RadioButton) { onCustom(); focus.requestFocus() }
            .semantics { contentDescription = "Your answer" }.testTag("AgentFeedQuestionOther:${question.id}"),
            contentAlignment = Alignment.Center) { QuestionSelectionGlyph(question.multiSelect, selected, enabled) }
        BasicTextField(text, onWrite, enabled = enabled, minLines = 1, maxLines = 4,
            textStyle = LocalTextStyle.current.copy(color = Color(0xFFE9E9EC), fontSize = 16.sp),
            cursorBrush = SolidColor(questionAccent), modifier = Modifier.weight(1f)
                .focusRequester(focus).onFocusChanged { focused = it.isFocused; if (it.isFocused) onCustom() }
                .testTag("AgentFeedQuestionOtherText:${question.id}").padding(top = 10.dp, bottom = 10.dp, end = 12.dp),
            decorationBox = { field -> Box { if (text.isEmpty()) Text("Your answer", color = questionMuted, fontSize = 16.sp); field() } })
    }
}
