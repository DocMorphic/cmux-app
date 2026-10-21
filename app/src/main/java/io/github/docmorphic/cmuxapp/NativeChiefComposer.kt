package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import kotlin.math.ceil

/** Chief's text-only composer: no attachment affordances, an internal send capsule, and a field
 * that grows until the header rather than an arbitrary line count. Parent owns system insets. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun NativeChiefComposer(draft: String, draftRevision: Long, retired: Boolean,
    onDraft: (String) -> Long?, onSend: (String, Long) -> Boolean, replyGeneration: Long? = null) {
    // Deliberately memory-only: the retained owner holds the draft; no new saved-state text copy.
    val editor = remember { TextFieldState(draft, TextRange(draft.length)) }
    val bridge = remember { NativeChiefDraftBridge(draft, draftRevision) }
    val publish by rememberUpdatedState(onDraft)
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(replyGeneration) { if (replyGeneration != null) focus.requestFocus() }
    DisposableEffect(editor) {
        onDispose {
            // Navigation can dispose the collector in the same event as the last edit.
            // Flush only unpublished input, never overwrite a pending owner update.
            val text = editor.text.toString()
            if (!bridge.matches(text)) publish(text)?.let { bridge.published(text, it) }
        }
    }
    LaunchedEffect(editor) {
        // Keep the editor authoritative for IME selection/composition. Publish its committed
        // snapshots outside input transformations, without round-tripping every edit back into it.
        snapshotFlow { editor.text.toString() }.collect { text ->
            // A queued pre-send snapshot must not restore text cleared by the click handler.
            if (text != editor.text.toString()) return@collect
            if (!bridge.matches(text)) {
                val revision = publish(text)
                if (revision != null) bridge.published(text, revision) else {
                    bridge.clearText(); editor.edit { replace(0, length, "") }; editor.undoState.clearHistory()
                }
            }
        }
    }
    LaunchedEffect(draftRevision, retired) {
        if (retired || bridge.adopt(draft, draftRevision, editor.text.toString())) {
            editor.edit { replace(0, length, if (retired) "" else draft); selection = TextRange(if (retired) 0 else draft.length) }
            editor.undoState.clearHistory()
            if (retired) bridge.published("", draftRevision)
        }
    }
    val density = LocalDensity.current
    val bodyStyle = nativeChiefTextStyle()
    val emojiDraftStyle = remember { NativeChiefEmojiDraftStyle() }
    val emojiSize = emojiDraftStyle.update(editor.composition != null) { NativeChiefEmojiPolicy.composerFontSize(editor.text.toString()) }
    val style = emojiSize?.let { bodyStyle.copy(fontSize = with(density) { it.dp.toSp() }) } ?: bodyStyle
    val measurer = rememberTextMeasurer()
    val lineHeight = remember(measurer, bodyStyle, density) {
        measurer.measure("Hg", bodyStyle).let { it.getLineBottom(0) - it.getLineTop(0) }
    }
    val oneLine = with(density) { lineHeight.toDp() } + 20.dp
    val ime = WindowInsets.ime.getBottom(density)
    val fullIme = maxOf(WindowInsets.imeAnimationSource.getBottom(density), WindowInsets.imeAnimationTarget.getBottom(density))
    val progress = if (fullIme > 0) (ime.toFloat() / fullIme).coerceIn(0f, 1f) else 0f
    val inset = NativeChiefComposerPolicy.sideInset(progress.toDouble()).toFloat().dp
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val ink = if (dark) Color.White else Color.Black
    val sendLabel = stringResource(R.string.chief_composer_send)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = (12 * progress).dp).testTag("ChiefComposerBar")) {
        val cap = if (constraints.hasBoundedHeight) (maxHeight - 8.dp - 3.3.dp).coerceAtLeast(oneLine) else 600.dp
        val textWidth = with(density) { (maxWidth - inset * 2 - 64.dp).toPx() }.toInt().coerceAtLeast(1)
        val text = editor.text.toString()
        // UIKit explicitly recomputes fieldHeight from sizeThatFits after restyling. Keep
        // that responsibility here too; the editor's internal minimum may retain the old
        // larger font's height. Preserve the same state/node and its IME/caret connection.
        val naturalHeight = remember(text, style, textWidth, measurer, density, emojiSize, oneLine, lineHeight) {
            val layout = measurer.measure(text.ifEmpty { "Hg" }, style, constraints = Constraints(maxWidth = textWidth))
            if (emojiSize != null) maxOf(oneLine, ceil(layout.size.height / density.density + 20f).toFloat().dp)
            else oneLine + with(density) { ((layout.lineCount - 1).coerceAtLeast(0) * lineHeight).toDp() }
        }
        val fieldHeight = naturalHeight.coerceIn(oneLine, cap)
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 4.dp)) {
        Layout(modifier = Modifier.fillMaxWidth().testTag("ChiefComposerField").clip(shape)
            .background(if (dark) Color(0xFF1A1A1A) else Color(0xFFF3F3F3))
            .border(.5.dp, if (dark) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .10f), shape), content = {
            BasicTextField(editor, enabled = !retired, scrollState = scroll, lineLimits = TextFieldLineLimits.MultiLine(),
                modifier = Modifier.fillMaxWidth().height(fieldHeight).focusRequester(focus).testTag("ChiefComposer").semantics {
                    verticalScrollAxisRange = ScrollAxisRange({ scroll.value.toFloat() }, { scroll.maxValue.toFloat() })
                }
                    .padding(start = 16.dp, end = 48.dp, top = 10.dp, bottom = 10.dp),
                textStyle = style.copy(color = ink), cursorBrush = SolidColor(if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                decorator = { input ->
                    Box {
                        if (editor.text.isEmpty()) Text(stringResource(if (replyGeneration != null) R.string.chief_reply_placeholder else R.string.chief_composer_placeholder),
                            color = if (dark) Color(0xFF5D5D61) else Color(0xFFB2B2B8), style = style)
                        input()
                    }
                })
            if (!retired && NativeChiefComposerPolicy.hasContent(editor.text.toString())) Box(Modifier.size(width = 38.dp, height = 28.dp).testTag("ChiefSend")
                .clip(RoundedCornerShape(14.dp)).background(if (dark) Color(0xFF0091FF) else Color(0xFF0088FF))
                .clickable(role = Role.Button, onClick = {
                    val text = editor.text.toString()
                    val revision = publish(text) ?: return@clickable
                    bridge.published(text, revision)
                    if (onSend(text, revision)) {
                        editor.edit { replace(0, length, "") }
                        editor.undoState.clearHistory()
                        publish("")?.let { bridge.published("", it) }
                    }
                }).semantics { contentDescription = sendLabel },
                contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_chief_send), null, Modifier.size(13.dp, 16.dp), tint = Color.White)
            }
        }) { children, c ->
            val field = children[0].measure(c.copy(minHeight = 0))
            val send = children.getOrNull(1)?.measure(Constraints.fixed(38.dp.roundToPx(), 28.dp.roundToPx()))
            val frame = NativeChiefComposerPolicy.row(field.width / density.density.toDouble(), field.height / density.density.toDouble(),
                field.height / density.density.toDouble(), oneLine.value.toDouble(), 0.0, density.density.toDouble()).send
            layout(field.width, field.height) {
                field.place(0, 0)
                send?.place((frame.x * density.density).roundToInt(), (frame.y * density.density).roundToInt())
            }
        }
        }
    }
}
