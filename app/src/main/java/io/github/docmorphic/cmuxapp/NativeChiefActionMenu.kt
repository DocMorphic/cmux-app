package io.github.docmorphic.cmuxapp

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.*
import kotlin.math.roundToInt

private fun chiefMenuTrace(event: String) { if (BuildConfig.DEBUG) android.util.Log.i("ChiefMenuCapture", "$event @${android.os.SystemClock.uptimeMillis()}") }

/** One composition's live row. Coordinates/recordings never survive disposal. */
internal class NativeChiefMenuSource(val owner: NativeChiefMenuController, var row: NativeChiefTimelineRow) {
    var root: LayoutCoordinates? = null
    var body: LayoutCoordinates? = null
    var quote: LayoutCoordinates? = null
    var text: LayoutCoordinates? = null
    var capture: (suspend () -> NativeChiefMenuSnapshot?)? = null
    fun area(): Rect? {
        val root = root?.takeIf { it.isAttached } ?: return null
        val body = body?.takeIf { it.isAttached } ?: return null
        val box = root.localBoundingBoxOf(body, clipBounds = false)
        return quote?.takeIf { it.isAttached }?.let {
            val other = root.localBoundingBoxOf(it, clipBounds = false)
            Rect(minOf(box.left, other.left), minOf(box.top, other.top), maxOf(box.right, other.right), maxOf(box.bottom, other.bottom))
        } ?: box
    }
}

internal data class NativeChiefMenuSnapshot(val row: NativeChiefTimelineRow, val image: ImageBitmap,
    val crop: Rect, val windowFrame: Rect, val textFrame: Rect?, val text: String)

/** All delayed captures/actions are fenced by this composition and retained owner. */
internal class NativeChiefMenuController(private val session: NativeChiefTranscriptSession,
    private val scope: CoroutineScope, private val onCommit: () -> Unit) {
    var snapshot by mutableStateOf<NativeChiefMenuSnapshot?>(null); private set
    var opened by mutableStateOf(false); private set
    var acting by mutableStateOf(false); private set
    private var generation = 0L
    private var source: NativeChiefMenuSource? = null
    private var wantedOpen = false
    private var capture: Job? = null
    fun current(rowKey: String) = try { session.actionItem(rowKey) } catch (_: IllegalStateException) { null }
    fun takeAction(frozen: NativeChiefMenuSnapshot, action: NativeChiefMenuAction): Boolean {
        if (snapshot !== frozen || !opened || acting) return false
        // Native Copy closes over message.text when presenting the menu. It
        // does not depend on that row remaining in the retained history window.
        val admitted = if (action == NativeChiefMenuAction.COPY) try {
            session.admitCapturedAction(); true
        } catch (_: IllegalStateException) { false }
        else current(frozen.row.item.key) != null
        if (!admitted) { dismiss(); return false }
        acting = true
        return true
    }
    fun selection(frozen: NativeChiefMenuSnapshot): NativeChiefSelectedText? {
        val live = source ?: return null
        if (snapshot !== frozen || current(frozen.row.item.key) == null) return null
        val text = live.text?.takeIf { it.isAttached } ?: return null
        val frame = Rect(text.positionInWindow(), androidx.compose.ui.geometry.Size(text.size.width.toFloat(), text.size.height.toFloat()))
        val body = live.row.item.message?.let { NativeChiefPresentation.receipt(it) ?: NativeChiefPresentation.displayText(it) } ?: live.row.item.outgoing!!.text
        return NativeChiefSelectedText(live.row.item.key, frame, body, live.row.item.fromMe)
    }
    fun begin(next: NativeChiefMenuSource, open: Boolean = false) {
        if (current(next.row.item.key) == null) { chiefMenuTrace("admission rejected"); return }
        chiefMenuTrace("begin open=$open capture=${next.capture != null}")
        dismiss()
        source = next; wantedOpen = open
        val epoch = generation
        capture = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val frozen = try { next.capture?.invoke() } catch (e: Exception) {
                chiefMenuTrace("capture failed: ${e.javaClass.simpleName}")
                currentCoroutineContext().ensureActive(); null
            }
            chiefMenuTrace("capture returned=${frozen != null} generationCurrent=${epoch == generation}")
            if (epoch != generation || current(next.row.item.key) == null) return@launch
            if (frozen == null) { dismiss(); return@launch }
            snapshot = frozen
            if (wantedOpen) { opened = true; onCommit() }
            chiefMenuTrace("published opened=$opened")
        }
    }
    fun commit(next: NativeChiefMenuSource) {
        if (source !== next || current(next.row.item.key) == null) { chiefMenuTrace("commit rejected"); return }
        chiefMenuTrace("commit accepted")
        wantedOpen = true
        if (snapshot != null && !opened) { opened = true; onCommit() }
    }
    fun cancelPress(next: NativeChiefMenuSource) { if (source === next && !wantedOpen) dismiss() }
    fun dismiss() { ++generation; capture?.cancel(); capture = null; snapshot = null; source = null; wantedOpen = false; opened = false; acting = false }
}

@Composable
internal fun Modifier.chiefMenuSource(source: NativeChiefMenuSource?): Modifier {
    if (source == null) return this
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current.density
    DisposableEffect(source, layer) {
        source.capture = {
            val row = source.row
            val root = source.root?.takeIf { it.isAttached }
            val area = source.area()
            chiefMenuTrace("coordinates root=${root != null} area=${area != null}")
            if (root == null || area == null || area.width <= 0 || area.height <= 0) null else {
                val origin = root.positionInWindow()
                val textFrame = source.text?.takeIf { it.isAttached }?.let {
                    Rect(it.positionInWindow(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
                }
                val text = row.item.message?.let { NativeChiefPresentation.receipt(it) ?: NativeChiefPresentation.displayText(it) }
                    ?: row.item.outgoing!!.text
                val frame = area.translate(origin)
                chiefMenuTrace("snapshot pixels begin")
                val image = layer.toImageBitmap()
                chiefMenuTrace("snapshot pixels ready")
                NativeChiefMenuSnapshot(row, image, area, frame, textFrame, text)
            }
        }
        onDispose { chiefMenuTrace("source disposed"); source.capture = null; source.root = null; source.body = null; source.quote = null; source.text = null; source.owner.cancelPress(source) }
    }
    val label = stringResource(R.string.chief_menu_actions)
    return then(Modifier.onGloballyPositioned { source.root = it }.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        // Hide only the recorded ink. Keeping the layout/input owner visible
        // lets its held pointer finish, as the source collection recognizer does.
        if (source.owner.snapshot?.row?.item?.key != source.row.item.key) drawLayer(layer)
    }.semantics { onClick(label) { source.owner.begin(source, open = true); true } }
        .pointerInput(source, density) {
            coroutineScope {
                val driver = this
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    chiefMenuTrace("pointer down hit=${source.area()?.inflate(4 * density)?.contains(down.position) == true}")
                    if (source.owner.opened || source.area()?.inflate(4 * density)?.contains(down.position) != true) return@awaitEachGesture
                    var grew = false
                    var cancelled = false
                    val liftTimer = driver.launch {
                        delay(150); chiefMenuTrace("lift clock fired"); grew = true; source.owner.begin(source)
                    }
                    // Source has two recognizers. Snapshot work at lift time
                    // must never shift the independent held-click deadline.
                    val timeoutTimer = driver.launch {
                        delay(725); chiefMenuTrace("timeout clock fired"); if (!cancelled) source.owner.commit(source)
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val held = (change.uptimeMillis - down.uptimeMillis) / 1000.0
                            if (change.isConsumed || (change.position - down.position).getDistance() > 10 * density || event.changes.count { it.pressed } > 1) break
                            if (!change.pressed) {
                                if (grew) {
                                    if (NativeChiefMessagePressTiming.opensOnRelease(held)) source.owner.commit(source)
                                    else source.owner.cancelPress(source)
                                    if (NativeChiefMessagePressTiming.opensOnRelease(held)) change.consume()
                                }
                                break
                            }
                            if (source.owner.opened) change.consume()
                        }
                    } finally {
                        cancelled = true; liftTimer.cancel(); timeoutTimer.cancel(); source.owner.cancelPress(source)
                    }
                }
            }
        })
}

internal enum class NativeChiefMenuAction(val label: Int, val icon: Int) {
    REPLY(R.string.chief_menu_reply, R.drawable.ic_chief_reply), COPY(R.string.chief_menu_copy, R.drawable.ic_chief_menu_copy),
    SELECT(R.string.chief_menu_select, R.drawable.ic_chief_menu_select), RETRY(R.string.chief_retry_try_again, R.drawable.ic_chief_menu_retry),
    POLL(R.string.chief_menu_poll_details, R.drawable.ic_chief_menu_poll), MORE(R.string.chief_menu_more, R.drawable.ic_chief_menu_more)
}

internal fun NativeChiefMenuSnapshot.actions(): List<NativeChiefMenuAction> = buildList {
    add(NativeChiefMenuAction.REPLY)
    if (row.item.failed) add(NativeChiefMenuAction.RETRY)
    add(NativeChiefMenuAction.COPY)
    if (textFrame != null && text.isNotEmpty()) add(NativeChiefMenuAction.SELECT)
    if (row.item.message?.let(NativeChiefPresentation::pendingPart) != null) add(NativeChiefMenuAction.POLL)
    add(NativeChiefMenuAction.MORE)
}

/** Actual frozen pixels at measured source geometry; the backing row remains in layout. */
@Composable
internal fun NativeChiefActionMenu(controller: NativeChiefMenuController, headerHeight: androidx.compose.ui.unit.Dp,
    dark: Boolean, onAction: (NativeChiefMenuSnapshot, NativeChiefMenuAction, Float) -> Unit) {
    val snapshot = controller.snapshot ?: return
    val opened = controller.opened
    val density = LocalDensity.current.density
    var origin by remember { mutableStateOf(Offset.Zero) }
    val growth = remember(snapshot) { Animatable(0f) }
    LaunchedEffect(snapshot, opened) {
        if (!opened) growth.animateTo(1f, tween(575, easing = CubicBezierEasing(.32f, .85f, .95f, 1f)))
    }
    val grow = growth.value
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
        .testTag(if (opened) "ChiefActionMenu" else "ChiefPressPreview")
        .then(if (opened) Modifier.semantics { paneTitle = "Message actions"; isTraversalGroup = true } else Modifier.clearAndSetSemantics {})) {
        val source = snapshot.windowFrame.translate(-origin)
        val area = NativeChiefMenuGeometry.Rect(source.left / density.toDouble(), source.top / density.toDouble(),
            source.width / density.toDouble(), source.height / density.toDouble())
        val actions = snapshot.actions()
        val pose = NativeChiefMenuGeometry.layout(maxWidth.value.toDouble(), maxHeight.value.toDouble(), 0.0, 0.0,
            headerHeight.value.toDouble(), if (maxWidth >= 440.dp) 20.0 else 16.0, area, snapshot.row.item.fromMe, actions.size)
        val scale = 1 + (NativeChiefMessagePressTiming.pressScale(area.width, area.height) - 1) * grow
        val preview = if (opened) pose.preview else NativeChiefMenuGeometry.Rect(
            area.x - area.width * (scale - 1) / 2, area.y - area.height * (scale - 1) / 2, area.width * scale, area.height * scale)
        if (opened) Box(Modifier.fillMaxSize().background(Color(.08f, .08f, .16f, .21f))
            .clickable(onClick = controller::dismiss).clearAndSetSemantics {})
        Canvas(Modifier.offset { IntOffset((preview.x * density).roundToInt(), (preview.y * density).roundToInt()) }
            .requiredSize(preview.width.toFloat().dp, preview.height.toFloat().dp).clip(RoundedCornerShape(if (pose.clipped && opened) 20.dp else 0.dp))
            .testTag("ChiefMenuPreview").clearAndSetSemantics {}) {
            val crop = snapshot.crop
            val left = crop.left.roundToInt().coerceIn(0, snapshot.image.width - 1)
            val top = crop.top.roundToInt().coerceIn(0, snapshot.image.height - 1)
            drawImage(snapshot.image, srcOffset = IntOffset(left, top),
                srcSize = IntSize(crop.width.roundToInt().coerceIn(1, snapshot.image.width - left), crop.height.roundToInt().coerceIn(1, snapshot.image.height - top)),
                dstSize = IntSize(size.width.roundToInt(), (area.height * (if (opened) pose.scale else scale) * density).roundToInt()))
        }
        if (opened) Column(Modifier.offset { IntOffset((pose.menu.x * density).roundToInt(), (pose.menu.y * density).roundToInt()) }
            .requiredWidth(250.dp).clip(RoundedCornerShape(26.dp)).background(if (dark) Color(0xF02B2B2F) else Color(0xF0EEEEF0))
            .border(.5.dp, if (dark) Color.White.copy(alpha = .14f) else Color.Black.copy(alpha = .10f), RoundedCornerShape(26.dp))
            .testTag("ChiefMenuPlatter").padding(vertical = 10.dp)) {
            actions.forEachIndexed { index, action ->
                Row(Modifier.fillMaxWidth().height(42.dp).testTag("ChiefMenu:${action.name}")
                    .clickable(enabled = !controller.acting, role = Role.Button) { onAction(snapshot, action, (preview.y * density + origin.y).toFloat()) },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(Modifier.width(64.dp), androidx.compose.ui.Alignment.Center) {
                        Icon(painterResource(action.icon), null, Modifier.offset(x = (241f / 6 - 32).dp).size(17.dp),
                            tint = if (dark) Color.White.copy(alpha = .96f) else Color.Black)
                    }
                    Text(stringResource(action.label), Modifier.weight(1f).padding(end = 28.dp), color = if (dark) Color.White.copy(alpha = .96f) else Color.Black,
                        style = nativeChiefTextStyle(17).copy(fontSize = with(LocalDensity.current) { 17.dp.toSp() }), maxLines = 1)
                }
                if (index == 0 && actions.size > 3) Box(Modifier.fillMaxWidth().height(14.dp).padding(start = 24.dp, end = 0.dp, top = 6.5.dp, bottom = 7.dp)
                    .background(if (dark) Color.White.copy(alpha = .18f) else Color.Black.copy(alpha = .15f)))
            }
        }
    }
    }
}
