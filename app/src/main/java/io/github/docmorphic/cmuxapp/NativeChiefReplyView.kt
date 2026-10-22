package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.scrollByOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** One-open thread copies retained through the source entry/return/fade lifecycle. */
@Composable
internal fun NativeChiefReplyOverlay(reply: NativeChiefReply, motion: NativeChiefReplyMotionState, headerHeight: Dp, composerHeight: Dp,
    metrics: NativeChiefBubbleMetrics, dark: Boolean, onClose: () -> Unit, onReply: (String) -> Boolean,
    onCopy: (String) -> Boolean, onFinished: () -> Unit) {
    val transcriptLabel = stringResource(R.string.chief_reply_transcript)
    val density = LocalDensity.current
    val view = LocalView.current
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(motion, motion.closing) {
        val initialDuration = 1.0 / (view.display?.refreshRate?.takeIf { it > 0 } ?: 60f)
        while (!motion.resting) withFrameNanos { motion.frame(it, initialDuration) }
        if (motion.closing) finish()
    }
    val captionFont = nativeChiefCaptionFont()
    val senderGeometry = NativeChiefCaptionGeometry.sender(captionFont.lineHeight, captionFont.spacing, density.density.toDouble())
    val senderStyle = nativeChiefTextStyle(11).copy(fontSize = with(density) {
        captionFont.pointSize.toFloat().dp.toSp()
    })
    Box(Modifier.fillMaxSize().testTag(if (motion.closing) "ChiefReplyClosing" else "ChiefReplyThread")
        .then(if (motion.closing) Modifier.clearAndSetSemantics { if (BuildConfig.DEBUG) {
            this[ChiefReplyCopyBounds] = { motion.copyBounds() }
            this[ChiefReplyDisplayProbe] = { motion.displayState() }
        } } else Modifier.semantics {
            contentDescription = transcriptLabel; paneTitle = transcriptLabel
            if (BuildConfig.DEBUG) this[ChiefReplyCopyBounds] = { motion.copyBounds() }
        })
        .drawBehind { drawRect((if (dark) Color.Black else Color.White).copy(alpha = (if (dark) .41f else .58f) * motion.veil)) }
        .then(if (motion.closing) Modifier else Modifier.pointerInput(reply.generation) {
            detectTapGestures(onTap = { onClose() })
        })) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = headerHeight, bottom = composerHeight + 6.dp)) {
            NativeChiefReplyScroll(Modifier.fillMaxSize().testTag("ChiefReplyTranscript"), motion) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).graphicsLayer { alpha = motion.contentAlpha }) {
                reply.rows.firstOrNull()?.let { first ->
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { motion.row(NativeChiefReplyMotionState.stampKey, it) }) {
                        ChiefTimestamp(first.at, "ChiefThreadTimestamp", Modifier.offset { IntOffset(0, motion.verticalPixels(NativeChiefReplyMotionState.stampKey)) }
                            .graphicsLayer { alpha = motion.rowAlpha(NativeChiefReplyMotionState.stampKey) })
                    }
                }
                reply.rows.forEachIndexed { index, item ->
                    if (index > 0) Spacer(Modifier.height(if (reply.rows[index - 1].senderId == item.senderId) 4.dp else 10.dp))
                    val message = item.quoteMessage()
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { motion.row(item.key, it) }) {
                    Column(Modifier.fillMaxWidth().offset { IntOffset(0, motion.verticalPixels(item.key)) }
                        .graphicsLayer { alpha = motion.rowAlpha(item.key) }
                        .onGloballyPositioned { motion.row(item.key, it, moving = true) }.testTag("ChiefThreadRow:${item.messageID}")) {
                        if (!item.fromMe) {
                            NativeChiefCaptionLabel(AnnotatedString(message.author.ifEmpty { message.authorId }),
                                captionFont, senderGeometry.height,
                                (senderGeometry.height - captionFont.lineHeight) / 2 + captionFont.ascender,
                                Color(0xFF8E8E93), "ChiefThreadSender:${item.messageID}",
                                Modifier.fillMaxWidth().padding(start = 14.dp).graphicsLayer { alpha = if (item.messageID == reply.targetID) motion.anchorChromeAlpha else 1f })
                            Spacer(Modifier.height((senderGeometry.bubbleOffset - senderGeometry.height).toFloat().dp))
                        }
                        val text = NativeChiefPresentation.receipt(message) ?: NativeChiefPresentation.displayText(message)
                        val part = NativeChiefPresentation.pendingPart(message)
                        val emoji = NativeChiefEmojiPolicy.fontSize(text).takeIf { part == null }
                        val accessibility = chiefMessageAccessibility(text,
                            if (motion.closing) null else { { onReply(item.messageID) } },
                            if (motion.closing) null else onCopy)
                        val bubbleModifier = Modifier.align(if (item.fromMe) AbsoluteAlignment.Right else AbsoluteAlignment.Left)
                            .offset { IntOffset(motion.horizontalPixels(item.key), 0) }.onGloballyPositioned { motion.bubble(item.key, it) }
                            .testTag("ChiefThreadMessage:${item.messageID}").semantics {
                                customActions = accessibility.actions
                                accessibility.copiedState?.let { stateDescription = it }
                            }
                            .then(if (motion.closing) Modifier else Modifier.pointerInput(reply.generation, item.key) {
                                detectTapGestures(onTap = {})
                            })
                        if (emoji != null) NativeChiefEmojiBalloon(text, emoji, "thread:${item.messageID}", bubbleModifier, metrics)
                        else NativeChiefBubble(item.fromMe, true, bubbleModifier, "ChiefThreadBody:${item.messageID}",
                            expanded = part != null, text = text.takeIf { part == null }, metrics = metrics) {
                            ChiefReplySelection(!motion.closing) { Column {
                                if (part == null) NativeChiefText(text, interactive = !motion.closing)
                                part?.approval?.let { approval ->
                                    Text(approval.prompt)
                                    approval.options.filterNot { it.startsWith("always:") }.forEach { Text(it) }
                                }
                                part?.question?.let { question ->
                                    val compact = NativeChiefPresentation.compactQuestion(question)
                                    Text(compact?.prompt ?: NativeChiefPresentation.summary(question))
                                    if (compact != null) compact.options.forEach { Text(it.label) }
                                    else Text(stringResource(R.string.chief_reply_answer_questions))
                                }
                            } }
                        }
                        if (item.outgoing?.delivery?.isFailed == true) Text(stringResource(R.string.chief_retry_title),
                            color = Color(0xFFFF453A), style = senderStyle, modifier = Modifier.align(AbsoluteAlignment.Right))
                    }
                    }
                }
            }
            }
        }
    }
}

/** Source UIScrollView is unclipped, bottom-aligned, and reset to the tail on geometry changes.
 * Keep the same layout behavior without putting a clipping verticalScroll around the copies. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NativeChiefReplyScroll(modifier: Modifier, motion: NativeChiefReplyMotionState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val scrollLabel = stringResource(R.string.chief_reply_transcript)
    val currentIme = WindowInsets.ime
    val targetIme = WindowInsets.imeAnimationTarget
    val navigation = WindowInsets.navigationBars
    var offset by remember { mutableFloatStateOf(0f) }
    var maximum by remember { mutableFloatStateOf(0f) }
    var geometry by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    val scroll = rememberScrollableState { delta ->
        val before = offset
        offset = (offset - delta).coerceIn(0f, maximum)
        before - offset
    }
    Layout(content = { Box(Modifier.onGloballyPositioned { motion.content(it, geometry) }) { content() } }, modifier = modifier.then(if (motion.closing) Modifier else Modifier.semantics {
        verticalScrollAxisRange = ScrollAxisRange({ offset }, { maximum })
        // Own these actions before scrollable's built-ins: this state's delta is
        // finger movement, while accessibility offsets describe content movement.
        scrollBy(label = scrollLabel) { _, y ->
            val canScroll = if (y > 0) offset < maximum else if (y < 0) offset > 0 else false
            if (canScroll) scope.launch { scroll.scrollBy(-y) }
            canScroll
        }
        scrollByOffset { requested ->
            val consumed = scroll.scrollBy(-requested.y)
            Offset(0f, -consumed)
        }
    }.scrollable(scroll, Orientation.Vertical))) { children, constraints ->
        val child = children.single().measure(Constraints.fixedWidth(constraints.maxWidth))
        // UIKit model frames already describe the keyboard's final position while
        // its presentation animates. Match that final layout instead of repeatedly
        // resetting the thread's scroll offset on every Android IME frame.
        // Read insets in layout, alongside the parent's current inset measurement.
        val nav = navigation.getBottom(this)
        val finalHeightDelta = maxOf(nav, currentIme.getBottom(this)) - maxOf(nav, targetIme.getBottom(this))
        val finalHeight = (constraints.maxHeight + finalHeightDelta).coerceAtLeast(0)
        val next = Triple(constraints.maxWidth, finalHeight, child.height)
        val limit = (child.height - finalHeight).coerceAtLeast(0).toFloat()
        if (geometry != next) { geometry = next; maximum = limit; offset = limit }
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.place(0, if (child.height < finalHeight) finalHeight - child.height else -offset.roundToInt())
        }
    }
}

@Composable
private fun ChiefReplySelection(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) SelectionContainer(content = content) else content()
}
