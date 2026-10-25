package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import kotlin.math.ceil

/** Chief's text-only composer: no attachment affordances, an internal send capsule, and a field
 * that grows until the header rather than an arbitrary line count. Parent owns system insets. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun NativeChiefComposer(draft: String, draftRevision: Long, retired: Boolean,
    onDraft: (String) -> Long?, onSend: (String, Long) -> Boolean, replyGeneration: Long? = null,
    richEditorState: NativeChiefRichEditor? = null, draftRuns: List<NativeConversationTextRun> = emptyList(),
    onRichDraft: ((String, List<NativeConversationTextRun>) -> Long?)? = null,
    onRichSend: ((String, Long, List<NativeConversationTextRun>) -> Boolean)? = null) {
    // Deliberately memory-only: the retained owner holds the draft; no new saved-state text copy.
    val rich = richEditorState ?: remember { NativeChiefRichEditor(draft).also { if (draftRuns.isNotEmpty()) it.reset(draft, draftRuns) } }
    val editor = rich.field
    val visit = rich.visitGeneration
    val mount = remember(rich, visit) { Any() }
    val input = remember(rich, mount, visit) { rich.input(mount, visit) }
    val bridge = remember(rich) { NativeChiefDraftBridge(draft, draftRevision, draftRuns) }
    val publish by rememberUpdatedState<(String, List<NativeConversationTextRun>) -> Long?>({ text, runs ->
        if (onRichDraft != null) onRichDraft(text, runs) else onDraft(text)
    })
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val host = LocalView.current
    val platformClipboard = LocalClipboard.current
    val clipboard = remember(rich, platformClipboard, mount, visit) { NativeChiefClipboard(platformClipboard, rich) { rich.isInputMountCurrent(mount, visit) } }
    DisposableEffect(clipboard) { onDispose { clipboard.close() } }
    // Android recreates this view on configuration changes while UIKit retains
    // its controller. Restore presentation only within the same conversation visit.
    val presentation = rememberSaveable(rich, visit, saver = listSaver<NativeChiefComposerPresentation, Any>(
        save = { listOf(it.visit, it.effects.value, it.focused.value, editor.selection.start, editor.selection.end) },
        restore = { if (it.size == 5 && (it[0] as Number).toLong() == visit) NativeChiefComposerPresentation(visit, it[1] as Boolean, it[2] as Boolean,
            TextRange((it[3] as Number).toInt(), (it[4] as Number).toInt()))
            else NativeChiefComposerPresentation(visit) })) { NativeChiefComposerPresentation(visit) }
    var paletteRequested by presentation.effects
    var focusToRestore by remember(presentation) { mutableStateOf(presentation.focused.value || paletteRequested) }
    var restoringFocus by remember(presentation) { mutableStateOf(focusToRestore) }
    var composerHasFocus by remember { mutableStateOf(false) }
    var fieldHasFocus by remember { mutableStateOf(false) }
    var resumed by remember(mount) { mutableStateOf(false) }
    var resumeVersion by remember(mount) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, rich, host, mount, visit, presentation) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    focusToRestore = presentation.focused.value || paletteRequested || fieldHasFocus || composerHasFocus
                    restoringFocus = focusToRestore
                    rich.attach(host, mount, visit); resumed = true; resumeVersion++
                }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; rich.suspendInput(mount, visit) }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); rich.detach(host, mount) }
    }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(presentation, resumeVersion, resumed) {
        if (!resumed || resumeVersion == 0) return@LaunchedEffect
        restoringFocus = focusToRestore
        try {
            if (focusToRestore) {
                snapshotFlow { windowInfo.isWindowFocused }.first { it }
                withFrameNanos { }
                if (resumed && rich.isInputMountCurrent(mount, visit)) {
                    // Foundation deselects on the old node's blur. Restore the range
                    // captured at state save after the new window can accept focus.
                    presentation.selection?.takeIf { it.max <= editor.text.length }?.let { range ->
                        editor.edit { selection = range }; rich.synchronizeSelectionAndComposition()
                    }
                    presentation.selection = null
                    focus.requestFocus()
                }
            }
        } finally { restoringFocus = false }
    }
    LaunchedEffect(composerHasFocus || fieldHasFocus, restoringFocus, resumed) {
        if (resumed && !restoringFocus && !composerHasFocus && !fieldHasFocus) {
            // Focus can pass through the group while a palette button updates the
            // field's output styling. Act on settled focus, not that intermediate state.
            kotlinx.coroutines.delay(100)
            if (resumed && !composerHasFocus && !fieldHasFocus) { paletteRequested = false; rich.breakUndoGroup() }
        }
    }
    val effectsTitle = stringResource(R.string.chief_rich_title)
    val undoTitle = stringResource(R.string.chief_rich_undo)
    val redoTitle = stringResource(R.string.chief_rich_redo)
    val interceptor = remember(rich, mount, visit) { PlatformTextInputInterceptor { request, next ->
        val session = currentCoroutineContext()[Job]
        next.startInputMethod(PlatformTextInputMethodRequest { attributes -> rich.connection(request.createInputConnection(attributes)) { rich.isInputMountCurrent(mount, visit) && session?.isActive != false } })
    } }
    fun openEffects() { if (!retired) { paletteRequested = true; focus.requestFocus(); keyboard?.hide() } }
    fun closeEffects() { paletteRequested = false; focus.requestFocus(); keyboard?.show() }
    LaunchedEffect(replyGeneration) { if (replyGeneration != null) focus.requestFocus() }
    DisposableEffect(editor) {
        onDispose {
            // Navigation can dispose the collector in the same event as the last edit.
            // Flush only unpublished input, never overwrite a pending owner update.
            val text = editor.text.toString()
            val runs = rich.snapshot.runs.takeIf { rich.snapshot.text == text }.orEmpty()
            if (!bridge.matches(text, runs)) publish(text, runs)?.let { bridge.published(text, it, runs) }
        }
    }
    LaunchedEffect(editor) {
        // Keep the editor authoritative for IME selection/composition. Publish its committed
        // snapshots outside input transformations, without round-tripping every edit back into it.
        snapshotFlow { Triple(editor.text.toString(), rich.revision, editor.selection to editor.composition) }.collect { (text, _, _) ->
            // A queued pre-send snapshot must not restore text cleared by the click handler.
            if (text != editor.text.toString()) return@collect
            rich.synchronizeSelectionAndComposition()
            val runs = rich.snapshot.runs.takeIf { rich.snapshot.text == text }.orEmpty()
            if (!bridge.matches(text, runs)) {
                val revision = publish(text, runs)
                if (revision != null) bridge.published(text, revision, runs) else {
                    bridge.clearText(); rich.reset("")
                }
            }
        }
    }
    LaunchedEffect(draftRevision, retired) {
        if (retired || bridge.adopt(draft, draftRevision, editor.text.toString(), draftRuns, rich.snapshot.runs)) {
            rich.reset(if (retired) "" else draft, if (retired) emptyList() else draftRuns)
            if (retired) { paletteRequested = false; bridge.published("", draftRevision, emptyList()) }
        }
    }
    val density = LocalDensity.current
    val bodyStyle = nativeChiefTextStyle()
    val bodyPoints = with(density) { bodyStyle.fontSize.toPx() / density.density }.toDouble()
    LaunchedEffect(rich, mount, visit, resumed, density.fontScale, bodyPoints) {
        if (resumed) rich.observeTextMetrics(mount, visit, density.fontScale, bodyPoints)
    }
    val emojiSize = if (rich.hasTextMetrics) rich.emojiSize else NativeChiefEmojiPolicy.composerFontSize(editor.text.toString())
    val points = if (rich.hasTextMetrics) rich.fontPoints else emojiSize?.toDouble() ?: bodyPoints
    val style = if (kotlin.math.abs(points - bodyPoints) < .001) bodyStyle
        else bodyStyle.copy(fontSize = with(density) { points.toFloat().dp.toSp() })
    val measurer = rememberTextMeasurer()
    val lineHeight = remember(measurer, bodyStyle, density) {
        measurer.measure("Hg", bodyStyle).let { it.getLineBottom(0) - it.getLineTop(0) }
    }
    val oneLine = with(density) { lineHeight.toDp() } + 20.dp
    val ime = WindowInsets.ime.getBottom(density)
    // A focus-triggered IME show can arrive after the palette's first hide request.
    // Keep the custom keyboard requested while that asynchronous show settles.
    LaunchedEffect(paletteRequested, ime) { if (paletteRequested && ime > 0) keyboard?.hide() }
    val fullIme = maxOf(WindowInsets.imeAnimationSource.getBottom(density), WindowInsets.imeAnimationTarget.getBottom(density))
    val progress = if (paletteRequested) 1f else if (fullIme > 0) (ime.toFloat() / fullIme).coerceIn(0f, 1f) else 0f
    val inset = NativeChiefComposerPolicy.sideInset(progress.toDouble()).toFloat().dp
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val ink = if (dark) Color.White else Color.Black
    val sendLabel = stringResource(R.string.chief_composer_send)
    rich.revision
    val runs = rich.snapshot.runs.takeIf { rich.snapshot.text == editor.text.toString() }.orEmpty()
    var textLayout by remember(rich) { mutableStateOf<TextLayoutResult?>(null) }
    val composing = editor.composition != null
    val animated = nativeRichMotionEnabled()
    val overlay = if (composing) Modifier else nativeConversationEffectOverlay(editor.text.toString(), runs, style, ink,
        { textLayout }, animated, origin = { Offset(with(density) { 16.dp.toPx() }, with(density) { 10.dp.toPx() } - scroll.value) })
    key(visit) {
    CompositionLocalProvider(LocalClipboard provides clipboard) {
    InterceptPlatformTextInput(interceptor) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = (12 * progress).dp).testTag("ChiefComposerBar")) {
        val paletteVisible = paletteRequested && ime == 0
        val navigation = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val paletteHeight = if (paletteVisible) (330.dp - navigation).coerceAtMost((maxHeight - oneLine - 12.dp).coerceAtLeast(0.dp)) else 0.dp
        val cap = if (constraints.hasBoundedHeight) (maxHeight - paletteHeight - 8.dp - 3.3.dp).coerceAtLeast(oneLine) else 600.dp
        val textWidth = with(density) { (maxWidth - inset * 2 - 64.dp).toPx() }.toInt().coerceAtLeast(1)
        val text = editor.text.toString()
        // UIKit explicitly recomputes fieldHeight from sizeThatFits after restyling. Keep
        // that responsibility here too; the editor's internal minimum may retain the old
        // larger font's height. Preserve the same state/node and its IME/caret connection.
        val naturalHeight = remember(text, runs, style, textWidth, measurer, density, emojiSize, oneLine, lineHeight) {
            val styled = nativeConversationStyled(AnnotatedString(text.ifEmpty { "Hg" }), runs, style, density, ink, false)
            val layout = measurer.measure(styled, style, constraints = Constraints(maxWidth = textWidth))
            if (emojiSize != null || runs.isNotEmpty()) maxOf(oneLine, ceil(layout.size.height / density.density + 20f).toFloat().dp)
            else NativeChiefComposerPolicy.height(NativeChiefComposerPolicy.lines(layout.size.height.toDouble(), 0.0, lineHeight.toDouble()),
                oneLine.value.toDouble(), with(density) { lineHeight.toDp().value.toDouble() }).toFloat().dp
        }
        val fieldHeight = naturalHeight.coerceIn(oneLine, cap)
        val shape = RoundedCornerShape(20.dp)
        Column(Modifier.onFocusChanged {
            composerHasFocus = it.hasFocus
            if (!restoringFocus && rich.isInputMountCurrent(mount, visit)) presentation.focused.value = it.hasFocus || fieldHasFocus
        }.focusGroup()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 4.dp)) {
        Layout(modifier = Modifier.fillMaxWidth().testTag("ChiefComposerField").clip(shape)
            .background(if (dark) Color(0xFF1A1A1A) else Color(0xFFF3F3F3))
            .border(.5.dp, if (dark) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .10f), shape), content = {
            BasicTextField(editor, enabled = !retired, scrollState = scroll, lineLimits = TextFieldLineLimits.MultiLine(),
                inputTransformation = input,
                outputTransformation = { runs.forEach { run ->
                    if (run.location >= 0 && run.location + run.length <= length && run.length > 0)
                        addStyle(nativeConversationSpan(run, style, density, ink, !composing), run.location, run.location + run.length)
                } },
                onTextLayout = { result -> textLayout = result() },
                modifier = Modifier.fillMaxWidth().height(fieldHeight).focusRequester(focus)
                    .onFocusChanged {
                        fieldHasFocus = it.hasFocus
                        if (!restoringFocus && rich.isInputMountCurrent(mount, visit)) presentation.focused.value = it.hasFocus || composerHasFocus
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || retired || !resumed ||
                            !rich.isInputMountCurrent(mount, visit) || !(event.isCtrlPressed || event.isMetaPressed)) false
                        else if (event.key == Key.Z || event.key == Key.Y) {
                            rich.history(event.key == Key.Y || event.isShiftPressed); true
                        } else if (!event.isAltPressed && !event.isShiftPressed) {
                            // ComposerTextView's priority Command-B/I/U use the same
                            // semantic toggles as its palette, before system handling.
                            val command = when (event.key) {
                                Key.B -> NativeConversationTextStyle.BOLD
                                Key.I -> NativeConversationTextStyle.ITALIC
                                Key.U -> NativeConversationTextStyle.UNDERLINE
                                else -> null
                            }
                            if (command != null) { rich.toggle(command); true } else false
                        } else false
                    }
                    .appendTextContextMenuComponents {
                        separator()
                        item("chiefTextEffects", effectsTitle) { close(); openEffects() }
                        if (rich.canUndo) item("chiefRichUndo", undoTitle) { close(); rich.history(false) }
                        if (rich.canRedo) item("chiefRichRedo", redoTitle) { close(); rich.history(true) }
                    }.testTag("ChiefComposer").semantics {
                    verticalScrollAxisRange = ScrollAxisRange({ scroll.value.toFloat() }, { scroll.maxValue.toFloat() })
                    customActions = listOf(CustomAccessibilityAction(effectsTitle) { openEffects(); true })
                }
                    .then(overlay)
                    // Send uses a physical right-side frame, matching the source
                    // text view. Its reserved space must stay there in RTL too.
                    .absolutePadding(left = 16.dp, right = 48.dp, top = 10.dp, bottom = 10.dp),
                textStyle = style.copy(color = ink), cursorBrush = SolidColor(if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default,
                    showKeyboardOnFocus = !paletteRequested),
                decorator = { input ->
                    Box(Modifier.testTag("ChiefComposerTextViewport")) {
                        if (editor.text.isEmpty()) Text(stringResource(if (replyGeneration != null) R.string.chief_reply_placeholder else R.string.chief_composer_placeholder),
                            color = if (dark) Color(0xFF5D5D61) else Color(0xFFB2B2B8), style = bodyStyle)
                        input()
                    }
                })
            if (!retired && NativeChiefComposerPolicy.hasContent(editor.text.toString())) Box(Modifier.size(width = 38.dp, height = 28.dp).testTag("ChiefSend")
                .clip(RoundedCornerShape(14.dp)).background(if (dark) Color(0xFF0091FF) else Color(0xFF0088FF))
                .clickable(role = Role.Button, onClick = {
                    val text = editor.text.toString()
                    val capturedRuns = rich.snapshot.runs.takeIf { rich.snapshot.text == text }.orEmpty()
                    val revision = publish(text, capturedRuns) ?: return@clickable
                    bridge.published(text, revision, capturedRuns)
                    val accepted = if (onRichSend != null) onRichSend(text, revision, capturedRuns) else onSend(text, revision)
                    if (accepted) {
                        paletteRequested = false; rich.reset("")
                        publish("", emptyList())?.let { bridge.published("", it, emptyList()) }
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
        if (paletteVisible) NativeChiefTextEffectsPalette(rich, ::closeEffects, Modifier.heightIn(max = paletteHeight))
        }
    }
    }
    }
    }
}

private class NativeChiefComposerPresentation(val visit: Long, effects: Boolean = false, focused: Boolean = false, var selection: TextRange? = null) {
    val effects = mutableStateOf(effects)
    val focused = mutableStateOf(focused)
}
