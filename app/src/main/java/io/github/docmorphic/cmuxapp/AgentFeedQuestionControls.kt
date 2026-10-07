/* Inline question controls follow cmux AgentFeedRow.swift at
 * 186cec79781256867ad4516f0802118738bd2393. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val questionDraftSaver = Saver<AgentFeedQuestionDrafts, String>(
    save = { it.encode() }, restore = { AgentFeedQuestionDrafts.decode(it) })

@Composable
internal fun AgentFeedQuestionControls(item: NativeAgentFeedItem, enabled: Boolean, canSubmit: Boolean = enabled,
    onSubmit: (AgentFeedDecision) -> Unit) {
    // Request/content changes must never reuse answers from the preceding request.
    key(item.id, item.requestId, item.questions) {
        QuestionControls(item.questions, enabled, canSubmit, onSubmit)
    }
}

@Composable
private fun QuestionControls(questions: List<AgentFeedQuestion>, enabled: Boolean, canSubmit: Boolean,
    onSubmit: (AgentFeedDecision) -> Unit) {
    if (questions.isEmpty()) return
    var drafts by rememberSaveable(stateSaver = questionDraftSaver) { mutableStateOf(AgentFeedQuestionDrafts()) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val pager = rememberPagerState(pageCount = { questions.size })
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val page = pager.currentPage.coerceIn(questions.indices)
    val answers = drafts.answers(questions)
    fun move(index: Int) { if (enabled && index in questions.indices) {
        focus.clearFocus(); scope.launch { pager.animateScrollToPage(index) }
    } }
    @Composable fun pageContent(index: Int, modifier: Modifier = Modifier) {
        val question = questions[index]
        QuestionPage(question, drafts, editing == question.id, enabled && index == page, modifier,
            onChoose = { drafts = drafts.choose(question, it) },
            onOther = { editing = question.id }, onWrite = { drafts = drafts.write(question, it) })
    }
    Column(Modifier.fillMaxWidth().testTag("AgentFeedQuestions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (questions.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Question ${page + 1} of ${questions.size}", fontSize = 12.sp)
                Text("${questions.count { drafts.answered(it) != null }} answered", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Row {
                questions.indices.forEach { index ->
                    Box(Modifier.sizeIn(minWidth = 32.dp, minHeight = 36.dp)
                        .selectable(index == page, enabled, Role.Tab) { move(index) }
                        .semantics { contentDescription = "Question ${index + 1}" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (index == page) 26.dp else 8.dp, 6.dp).background(
                            if (index == page) Color(0xFF76B9FF) else Color(0xFF4B5058), RoundedCornerShape(3.dp)))
                    }
                }
            }
            val heights = remember { mutableStateMapOf<Int, Int>() }
            val density = LocalDensity.current
            val height = with(density) { (heights[page] ?: 0).toDp() }.coerceAtLeast(120.dp)
            HorizontalPager(pager, Modifier.fillMaxWidth().height(height).testTag("AgentFeedQuestionPager"),
                key = { questions[it].id }, userScrollEnabled = enabled, verticalAlignment = Alignment.Top) { index ->
                // Measure the natural page height independently of the pager's previous height.
                // Inactive pages retain drafts but cannot intercept taps or accessibility focus.
                val hidden = if (index == page) Modifier else Modifier.clearAndSetSemantics { }
                pageContent(index, Modifier.fillMaxWidth().wrapContentHeight(unbounded = true, align = Alignment.Top)
                    .onSizeChanged { if (heights[index] != it.height) heights[index] = it.height }.then(hidden))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page > 0) TextButton(onClick = { move(page - 1) }, enabled = enabled) { Text("Previous") }
                if (page < questions.lastIndex) Button(onClick = { move(page + 1) }, enabled = enabled) { Text("Next") }
                else Button(onClick = { answers?.let { focus.clearFocus(); onSubmit(AgentFeedDecision("question", selections = it)) } },
                    enabled = enabled && canSubmit && answers != null, modifier = Modifier.testTag("AgentFeedQuestionSubmit")) { Text("Submit all answers") }
            }
        } else {
            pageContent(0)
            Button(onClick = { answers?.let { focus.clearFocus(); onSubmit(AgentFeedDecision("question", selections = it)) } },
                enabled = enabled && canSubmit && answers != null, modifier = Modifier.fillMaxWidth().testTag("AgentFeedQuestionSubmit")) { Text("Send") }
        }
    }
}

@Composable
private fun QuestionPage(question: AgentFeedQuestion, drafts: AgentFeedQuestionDrafts, editing: Boolean,
    enabled: Boolean, modifier: Modifier, onChoose: (String) -> Unit, onOther: () -> Unit, onWrite: (String) -> Unit) {
    Column(modifier.padding(horizontal = 1.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        question.header?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp, color = Color(0xFF9CA3AF)) }
        AgentFeedMarkdownText(question.prompt)
        if (question.multiSelect) Text("Select all that apply", fontSize = 12.sp, color = Color(0xFF76B9FF))
        question.options.forEach { option ->
            val selected = option.id in drafts.selected[question.id].orEmpty()
            val shape = RoundedCornerShape(12.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
                .background(if (selected) Color(0xFF203B51) else Color(0xFF22252A))
                .then(if (selected) Modifier.border(1.dp, Color(0xFF76B9FF), shape) else Modifier)
                .selectable(selected, enabled, if (question.multiSelect) Role.Checkbox else Role.RadioButton) { onChoose(option.id) }
                .testTag("AgentFeedQuestionOption:${question.id}:${option.id}")
                .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AgentFeedMarkdownText(option.label)
                    option.description?.let { AgentFeedMarkdownText(it, color = Color(0xFF9CA3AF), fontSize = 12) }
                }
                if (question.multiSelect) Checkbox(selected, null, enabled = enabled, modifier = Modifier.size(24.dp).clearAndSetSemantics { })
                else RadioButton(selected, null, enabled = enabled, modifier = Modifier.size(24.dp).clearAndSetSemantics { })
            }
        }
        if (editing || question.options.isEmpty()) {
            val requester = remember { FocusRequester() }
            LaunchedEffect(editing, enabled) { if (editing && enabled) requester.requestFocus() }
            OutlinedTextField(drafts.custom[question.id].orEmpty(), onWrite, enabled = enabled, minLines = 2, maxLines = 5,
                placeholder = { Text("Your answer") }, modifier = Modifier.fillMaxWidth().focusRequester(requester)
                    .testTag("AgentFeedQuestionOtherText:${question.id}"))
        } else OutlinedButton(onClick = onOther, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("AgentFeedQuestionOther:${question.id}"),
            shape = RoundedCornerShape(12.dp)) { Text("Other…", Modifier.fillMaxWidth()) }
    }
}
