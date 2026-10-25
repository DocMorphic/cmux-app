package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

/** ChiefQuestionSheet's captured form, source controls and medium/large presentation. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun NativeChiefQuestionSheet(question: NativeChiefQuestion, busy: Boolean, error: NativeChiefQuestionError?, onDismiss: () -> Unit,
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
    var otherEditor by rememberSaveable(question, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(form.otherText, TextRange(form.otherText.length)))
    }
    val displayedOther = if (otherEditor.text == form.otherText) otherEditor
        else TextFieldValue(form.otherText, TextRange(form.otherText.length))
    val currentBusy by rememberUpdatedState(busy)
    // Material remembers this predicate as a state key. Keep its identity stable
    // when submitting changes, while reading the latest interactive-dismiss gate.
    val confirm = remember { { value: SheetValue -> !currentBusy || value != SheetValue.Hidden } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false, confirmValueChange = confirm)
    val otherViewport = remember { BringIntoViewRequester() }
    var otherFocused by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val ink = if (dark) Color.White else Color.Black
    val muted = if (dark) Color(0xFF98989E) else Color(0xFF6C6C70)
    val blue = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val leading = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    val otherLabel = stringResource(R.string.chief_question_other)
    NativeModalBottomSheet(onDismissRequest = { if (!currentBusy) onDismiss() }, sheetState = sheet,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy),
        containerColor = if (dark) Color(0xFF1C1C1E) else Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.testTag("ChiefQuestionDragHandle")) },
        modifier = Modifier.testTag("ChiefQuestionSheet")) {
        val ime = WindowInsets.ime
        val density = LocalDensity.current
        LaunchedEffect(sheet, ime, density) {
            snapshotFlow { Triple(otherFocused, ime.getBottom(density), sheet.currentValue to sheet.targetValue) }.collect { (focused, bottom, _) ->
                if (focused && bottom > 0) try {
                    // Initial show() may choose partial after focus restores. A
                    // sequential collector does not cancel its own expansion.
                    if (sheet.currentValue != SheetValue.Expanded || sheet.targetValue != SheetValue.Expanded) sheet.expand()
                    withFrameNanos { }
                    if (otherFocused && ime.getBottom(density) > 0) otherViewport.bringIntoView()
                } catch (interrupted: CancellationException) {
                    // A competing sheet mutation can interrupt expansion; keep
                    // observing unless this presentation itself was canceled.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding()) {
            Layout(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), content = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("ChiefQuestionCancel"), colors = ButtonDefaults.textButtonColors(contentColor = blue)) {
                    Text(stringResource(R.string.chief_question_cancel), style = nativeChiefTextStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(stringResource(R.string.chief_question_title), Modifier.testTag("ChiefQuestionTitle").semantics { heading() },
                    color = ink, style = nativeChiefTextStyle().copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                TextButton(onClick = { onSend(form) }, enabled = form.canSubmit && !busy, modifier = Modifier.testTag("ChiefQuestionSubmit"),
                    colors = ButtonDefaults.textButtonColors(contentColor = blue)) {
                    Text(question.submitLabel ?: stringResource(R.string.chief_question_send), style = nativeChiefTextStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }) { children, constraints ->
                val actions = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth / 3)
                val cancel = children[0].measure(actions); val send = children[2].measure(actions)
                val title = children[1].measure(constraints.copy(minWidth = 0, minHeight = 0,
                    maxWidth = (constraints.maxWidth - 2 * maxOf(cancel.width, send.width)).coerceAtLeast(0)))
                val height = maxOf(44.dp.roundToPx(), cancel.height, send.height, title.height)
                layout(constraints.maxWidth, height) {
                    cancel.placeRelative(0, (height - cancel.height) / 2)
                    title.placeRelative((constraints.maxWidth - title.width) / 2, (height - title.height) / 2)
                    send.placeRelative(constraints.maxWidth - send.width, (height - send.height) / 2)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_chief_home_sparkles), null, Modifier.size(20.dp), tint = ink)
                        Text(stringResource(R.string.chief_question_needs_input), color = ink, style = nativeChiefTextStyle().copy(fontWeight = FontWeight.SemiBold))
                    }
                    Text(stringResource(R.string.chief_question_instructions), Modifier.fillMaxWidth(), color = muted, style = nativeChiefTextStyle(15), textAlign = leading)
                    if (form.otherTarget != null) Text(stringResource(R.string.chief_question_other_hint), Modifier.fillMaxWidth(), color = muted,
                        style = nativeChiefTextStyle(13), textAlign = leading)
                }
                question.items.forEach { item -> key(item.id) {
                    Column(Modifier.fillMaxWidth().testTag("ChiefQuestionItem:${item.id}"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item.header?.takeIf(String::isNotEmpty)?.let { Text(it, Modifier.fillMaxWidth(), color = muted,
                            style = nativeChiefTextStyle(12).copy(fontWeight = FontWeight.SemiBold), textAlign = leading) }
                        Text(item.prompt, Modifier.fillMaxWidth().semantics { heading() }, color = ink,
                            style = nativeChiefTextStyle(20).copy(fontWeight = FontWeight.SemiBold), textAlign = leading)
                        if (item.multiSelect) Text(stringResource(R.string.chief_question_select_all), Modifier.fillMaxWidth(), color = muted,
                            style = nativeChiefTextStyle(13), textAlign = leading)
                        Column(Modifier.fillMaxWidth().background(ink.copy(alpha = .045f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp)) {
                            item.options.forEachIndexed { index, option ->
                                val chosen = option.id in form.selections[item.id].orEmpty()
                                Row(Modifier.fillMaxWidth().clickable { form = form.toggle(item.id, option.id) }
                                    .testTag("ChiefQuestionOption:${item.id}:${option.id}").semantics(mergeDescendants = true) {
                                        role = if (item.multiSelect) Role.Checkbox else Role.RadioButton; selected = chosen
                                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ChiefQuestionChoiceGlyph(chosen, item.multiSelect, if (chosen) blue else muted)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(option.label, Modifier.fillMaxWidth(), color = ink, style = nativeChiefTextStyle(), textAlign = leading)
                                        option.detail?.takeIf(String::isNotEmpty)?.let { Text(it, Modifier.fillMaxWidth(), color = muted,
                                            style = nativeChiefTextStyle(12), textAlign = leading) }
                                    }
                                }
                                if (index < item.options.lastIndex) HorizontalDivider(Modifier.padding(start = 32.dp), color = muted.copy(alpha = .3f), thickness = .5.dp)
                            }
                        }
                        if (item.id == form.otherTarget?.id) BasicTextField(displayedOther, { next ->
                            val textChanged = next.text != form.otherText
                            // CoreTextField collapses selection when its window loses focus.
                            // Keep the editing range across Activity pause/recreation.
                            val acceptsSelection = otherFocused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            otherEditor = if (textChanged || acceptsSelection) next else next.copy(selection = otherEditor.selection)
                            if (textChanged) form = form.editOther(next.text)
                        },
                            Modifier.fillMaxWidth().heightIn(min = 34.dp).testTag("ChiefQuestionOther:${item.id}")
                                .bringIntoViewRequester(otherViewport).onFocusChanged { otherFocused = it.isFocused }
                                .semantics { contentDescription = otherLabel }.background(if (dark) Color(0xFF2C2C2E) else Color.White, RoundedCornerShape(6.dp))
                                .border(.5.dp, muted.copy(alpha = .5f), RoundedCornerShape(6.dp)).padding(8.dp),
                            textStyle = nativeChiefTextStyle().copy(color = ink), cursorBrush = SolidColor(blue),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            decorationBox = { input -> Box { if (form.otherText.isEmpty()) Text(otherLabel, color = muted, style = nativeChiefTextStyle()); input() } })
                    }
                } }
                if (error != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.ic_task_warning), null, Modifier.size(15.dp), tint = Color(0xFFFF3B30))
                    Text(chiefQuestionErrorText(error), Modifier.testTag("ChiefQuestionError"), color = Color(0xFFFF3B30), style = nativeChiefTextStyle(13))
                }
            }
        }
    }
}

@Composable
private fun chiefQuestionErrorText(error: NativeChiefQuestionError): String = when (error) {
    is NativeChiefQuestionError.Source -> stringResource(when (error.problem) {
        NativeChiefQuestionProblem.NOT_WAITING -> R.string.chief_question_error_not_waiting
        NativeChiefQuestionProblem.ANSWER_REQUIRED -> R.string.chief_question_error_answer_required
        NativeChiefQuestionProblem.ANSWER_MISSING -> R.string.chief_question_error_answer_missing
    })
    is NativeChiefQuestionError.Message -> error.value
}

@Composable
private fun ChiefQuestionChoiceGlyph(chosen: Boolean, multi: Boolean, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 20
        if (multi) drawRoundRect(color, Offset(unit, unit), Size(18 * unit, 18 * unit), CornerRadius(3 * unit),
            style = if (chosen) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.5f * unit))
        else drawCircle(color, 9 * unit, style = if (chosen) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.5f * unit))
        if (chosen) drawPath(Path().apply { moveTo(5 * unit, 10 * unit); lineTo(8.5f * unit, 13.5f * unit); lineTo(15 * unit, 6.5f * unit) },
            Color.White, style = Stroke(2 * unit))
    }
}
