package io.github.docmorphic.cmuxapp

import android.graphics.Typeface
import android.text.Selection
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** Source circle stays in the cell while only incoming content shifts 42 pt. */
@Composable
internal fun Modifier.chiefSelecting(selected: Boolean?, outgoing: Boolean, text: String,
    copy: CustomAccessibilityAction?, copiedState: String?, onToggle: () -> Unit): Modifier {
    if (selected == null) return this
    val toggle by rememberUpdatedState(onToggle)
    return then(Modifier.drawWithContent {
        translate(left = if (outgoing) 0f else 42.dp.toPx()) { this@drawWithContent.drawContent() }
        // The transcript already has the cell's 16 pt horizontal inset.
        val center = Offset(7.dp.toPx(), size.height / 2)
        drawCircle(if (selected) Color(0xFF007AFF) else Color.Gray.copy(alpha = .6f), 11.dp.toPx(), center,
            style = if (selected) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.2.dp.toPx()))
        if (selected) {
            val path = Path().apply {
                moveTo(center.x - 5.dp.toPx(), center.y)
                lineTo(center.x - 1.dp.toPx(), center.y + 4.dp.toPx())
                lineTo(center.x + 6.dp.toPx(), center.y - 4.dp.toPx())
            }
            drawPath(path, Color.White, style = Stroke(1.7.dp.toPx()))
        }
    }.clearAndSetSemantics {
        this.selected = selected; role = Role.Checkbox; contentDescription = text
        customActions = listOfNotNull(copy)
        copiedState?.let { stateDescription = it }
        onClick { toggle(); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(false, pass = PointerEventPass.Initial)
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                if (!change.pressed) { change.consume(); toggle(); break }
            }
        }
    })
}

@Composable
internal fun NativeChiefSelectionToolbar(enabled: Boolean, dark: Boolean, modifier: Modifier, onDelete: () -> Unit) {
    Box(modifier.fillMaxWidth().height(48.dp).absolutePadding(left = 28.dp).testTag("ChiefSelectionToolbar")) {
        Box(Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft).size(48.dp).clip(CircleShape).background(if (dark) Color(0xFF202020) else Color(0xFFEAEAEA))
            .border(.5.dp, if (dark) Color.White.copy(alpha = .1f) else Color.Black.copy(alpha = .1f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onDelete)
            .semantics { contentDescription = "Delete" }.testTag("ChiefSelectionDelete"), Alignment.Center) {
            Icon(painterResource(R.drawable.ic_workspace_delete), null, Modifier.size(19.dp, (62f / 3).dp),
                tint = (if (dark) Color.White else Color.Black).copy(alpha = if (enabled) 1f else .3f))
        }
    }
}

/** Native Android handles/edit actions over the actual rendered text frame. */
private class ChiefSelectableTextView(context: android.content.Context) : TextView(context) {
    var allowed: () -> Boolean = { false }
    var end: (() -> Unit)? = null
    var started = false
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (!allowed()) { end?.invoke(); return true }
        if (id == android.R.id.copy) {
            val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
            val finish = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("", text.subSequence(start, finish).toString()))
            return true
        }
        return super.onTextContextMenuItem(id)
    }
    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if (started && start == end) post { this.end?.invoke() }
    }
}

internal data class NativeChiefSelectedText(val rowKey: String, val frame: androidx.compose.ui.geometry.Rect,
    val text: String, val outgoing: Boolean)

@Composable
internal fun NativeChiefTextSelection(frozen: NativeChiefSelectedText, permitted: () -> Boolean, onEnd: () -> Unit) {
    val frame = frozen.frame
    val density = LocalDensity.current.density
    val finishSelection by rememberUpdatedState(onEnd)
    val permitAction by rememberUpdatedState(permitted)
    var origin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }.testTag("ChiefTextSelection")) {
        Box(Modifier.fillMaxSize().clickable { finishSelection() }.clearAndSetSemantics {})
        AndroidView(factory = { context ->
            ChiefSelectableTextView(context).apply {
                this.allowed = { permitAction() }; this.end = { finishSelection() }
                setPadding(0, 0, 0, 0); includeFontPadding = false; typeface = Typeface.DEFAULT
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setText(frozen.text, TextView.BufferType.SPANNABLE)
                setTextColor(android.graphics.Color.TRANSPARENT); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setTextIsSelectable(true); setSelectAllOnFocus(true)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                highlightColor = if (frozen.outgoing) 0x66FFFFFF else 0x663B82F6
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val tint = if (frozen.outgoing) android.graphics.Color.WHITE else 0xFF007AFF.toInt()
                    textSelectHandleLeft?.mutate()?.let { it.setTint(tint); setTextSelectHandleLeft(it) }
                    textSelectHandleRight?.mutate()?.let { it.setTint(tint); setTextSelectHandleRight(it) }
                    textSelectHandle?.mutate()?.let { it.setTint(tint); setTextSelectHandle(it) }
                }
                onFocusChangeListener = View.OnFocusChangeListener { _, focused -> if (!focused) finishSelection() }
                post {
                    if (isAttachedToWindow) {
                        requestFocus(); performLongClick()
                        (text as? android.text.Spannable)?.let { Selection.setSelection(it, 0, it.length) }
                        started = true
                    }
                }
            }
        }, modifier = Modifier.offset { IntOffset((frame.left - origin.x).roundToInt(), (frame.top - origin.y).roundToInt()) }
            .requiredSize((frame.width / density).dp, (frame.height / density).dp).testTag("ChiefSelectedText"))
    }
    }
}

private data class NativeChiefPollContents(val question: String, val options: List<String>)
private fun pollContents(message: NativeChiefMessage): NativeChiefPollContents? {
    val part = NativeChiefPresentation.pendingPart(message) ?: return null
    part.approval?.let { return NativeChiefPollContents(it.prompt, it.options.filterNot { choice -> choice.startsWith("always:") }) }
    val question = part.question ?: return null
    val compact = NativeChiefPresentation.compactQuestion(question)
    return NativeChiefPollContents(compact?.prompt ?: NativeChiefPresentation.summary(question),
        compact?.options?.map { it.label } ?: listOf("Answer questions"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeChiefPollDetails(id: String, state: NativeChiefTranscriptState, onClose: () -> Unit) {
    var content by remember(id) { mutableStateOf(state.messages.firstOrNull { it.id == id }?.let(::pollContents)) }
    val updated = state.messages.firstOrNull { it.id == id }?.let(::pollContents)
    SideEffect { if (updated != null) content = updated }
    val shown = content ?: return
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("ChiefPollDetails")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chief_menu_poll_details), Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onClose) { Text("Done") }
            }
            Text(shown.question, Modifier.padding(vertical = 12.dp))
            shown.options.forEach { option ->
                Text(option, Modifier.padding(top = 16.dp).semantics { heading() })
                Text("No votes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0 votes", style = MaterialTheme.typography.labelSmall)
            }
            Text("No Votes", Modifier.padding(top = 16.dp).semantics { heading() })
            state.messages.distinctBy { it.authorId }.forEach { participant ->
                Text(if (participant.authorKind == "human") "You" else participant.author.ifEmpty { participant.authorId })
            }
            if (state.messages.none { it.authorKind == "human" }) Text("You")
            if (state.messages.none { it.authorKind == "chief" }) Text("Chief")
        }
    }
}
