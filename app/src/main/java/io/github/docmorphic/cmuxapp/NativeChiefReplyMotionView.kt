package io.github.docmorphic.cmuxapp

import android.animation.ValueAnimator
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt

/** Coordinates belong to this transcript composition only; detached/prefetched rows cannot
 * supply a visible twin. Freeze entry origins before requesting the keyboard or changing reply. */
internal class NativeChiefReplyOrigins(private val visibleRows: () -> Set<String>) {
    data class Bubble(val rowKey: String, val coordinates: LayoutCoordinates)
    val bubbles = mutableMapOf<String, Bubble>()
    val timestamps = mutableMapOf<String, LayoutCoordinates>()
    var viewport: LayoutCoordinates? = null

    data class Snapshot(val bubbles: Map<String, Float>, val timestamps: Map<String, Float>)
    fun snapshot(): Snapshot {
        val frame = viewport?.takeIf { it.isAttached } ?: return Snapshot(emptyMap(), emptyMap())
        val top = frame.positionInWindow().y
        val bottom = top + frame.size.height
        val visible = visibleRows()
        fun y(coordinates: LayoutCoordinates): Float? {
            if (!coordinates.isAttached) return null
            val y = coordinates.positionInWindow().y
            return y.takeIf { it + coordinates.size.height > top && it < bottom }
        }
        // Lazy layouts may keep coordinates attached in their reuse/prefetch
        // pool. Match their row identity to the current visible layout as well.
        val bubbleYs = bubbles.mapNotNull { (id, bubble) ->
            if (bubble.rowKey !in visible) null else y(bubble.coordinates)?.let { id to it }
        }.toMap()
        val stampYs = timestamps.mapNotNull { (key, coordinates) ->
            if (key !in visible) null else y(coordinates)?.let { key to it }
        }.toMap()
        return Snapshot(bubbleYs, stampYs)
    }
}

/** Debug capture reads placed coordinates after layout, independently of the trip's
 * numeric model. It carries no text and is never exposed by release semantics. */
internal val ChiefReplyVisibleRows = SemanticsPropertyKey<() -> Set<String>>("ChiefReplyVisibleRows")
internal val ChiefReplyCopyBounds = SemanticsPropertyKey<() -> Map<String, Rect>>("ChiefReplyCopyBounds")

internal data class NativeChiefReplyPresentation(val reply: NativeChiefReply,
    val motion: NativeChiefReplyMotionState, val sendRevision: Long)

/** Compose adapter for the source frame equations. Positions/offsets are in source points;
 * only placement converts to pixels. No state survives this generation's composition. */
internal class NativeChiefReplyMotionState(
    private val reply: NativeChiefReply,
    private val origins: NativeChiefReplyOrigins.Snapshot,
    private val timestampKey: String?,
    private val density: Float,
    private val viewHeight: Double,
    val reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    private val expected = listOf(stampKey) + reply.rows.map { it.key }
    private val frames = mutableMapOf<String, LayoutCoordinates>()
    private val movingFrames = mutableMapOf<String, LayoutCoordinates>()
    private val bubbles = mutableMapOf<String, LayoutCoordinates>()
    private var content: LayoutCoordinates? = null
    private var geometry: Triple<Int, Int, Int>? = null
    private var contentY: Double? = null
    private val trips = mutableMapOf<String, NativeChiefReplyTrip>()
    private val bases = mutableMapOf<String, Double>()
    private var revision by mutableIntStateOf(0)
    var ready by mutableStateOf(false)
        private set
    var veil by mutableFloatStateOf(0f)
        private set
    var blur by mutableFloatStateOf(0f)
        private set
    var closing by mutableStateOf(false)
        private set
    var settles by mutableStateOf(false)
        private set
    private var backgroundDone = false
    private var closingDim = 1f
    private var closingContent = 1f
    private var contentFade by mutableFloatStateOf(1f)
    private var chrome by mutableFloatStateOf(0f)
    private val fading = mutableSetOf<String>()
    private var firstFrame: Long? = null
    private var previousFrame: Long? = null
    val resting get(): Boolean {
        revision
        val motionDone = closing && !settles || trips.filterKeys { it !in fading }.values.all { it.isResting }
        return ready && motionDone && backgroundDone
    }
    val contentAlpha get() = if (!ready) 0f else if (closing) { if (settles) 1f else contentFade } else if (reduceMotion) veil else 1f
    val anchorChromeAlpha get() = chrome
    val hiddenKeys get() = if (closing && !settles) emptySet() else reply.hiddenKeys
    fun rowAlpha(key: String): Float { revision; return if (key in fading) contentFade else 1f }

    /** Clear the logical reply immediately, but retain these copies until BOTH the
     * source return dynamics and the background finish. Sends never return rows. */
    fun close(targets: NativeChiefReplyOrigins.Snapshot, settling: Boolean) {
        if (closing) return
        closingDim = veil
        closingContent = contentAlpha
        closing = true
        settles = settling && !reduceMotion && ready
        firstFrame = null
        previousFrame = null
        backgroundDone = false
        blur = 1f // The source ReplyBlurDriver explicitly starts its exit at one.
        if (settles) {
            val origins = counterpartOrigins(targets)
            for (key in expected) {
                val trip = trips[key] ?: continue
                val target = origins[key]
                if (target == null) fading += key
                else {
                    val height = frames[key]?.size?.height?.div(density.toDouble()) ?: 0.0
                    trips[key] = NativeChiefReplyTrip(trip.y, target, 0.0,
                        NativeChiefReplyMotion.threadEasing(checkNotNull(contentY) + trip.y + height / 2,
                            height, viewHeight, true), NativeChiefReplyMotion.snapDistance(density.toDouble(), true))
                }
            }
        }
        // Before first layout there is nothing to travel; still permit the
        // closing fade to finish instead of waiting forever for removed views.
        ready = true
        contentFade = if (settles) 1f else closingContent
        revision++
    }

    fun row(key: String, coordinates: LayoutCoordinates, moving: Boolean = false) {
        (if (moving) movingFrames else frames)[key] = coordinates
        prepare()
    }
    fun bubble(key: String, coordinates: LayoutCoordinates) { bubbles[key] = coordinates; prepare() }

    fun content(coordinates: LayoutCoordinates, layout: Triple<Int, Int, Int>?) {
        content = coordinates
        val nextY = coordinates.positionInWindow().y / density.toDouble()
        val before = contentY
        // User scrolling changes the content origin too. Only a real layout change
        // invokes source containerMoved; ordinary scroll must remain under the finger.
        if (ready && geometry != layout && before != null) {
            trips.values.forEach { it.containerMoved(nextY - before) }
            revision++
        }
        contentY = nextY
        geometry = layout
        prepare()
    }

    private fun prepare() {
        if (ready || closing || content?.isAttached != true || expected.any { frames[it]?.isAttached != true } ||
            reply.rows.any { movingFrames[it.key]?.isAttached != true || bubbles[it.key]?.isAttached != true }) return
        val origin = checkNotNull(contentY)
        val slots = expected.map { key ->
            val frame = checkNotNull(frames[key])
            NativeChiefReplyMotion.Slot(key, reply.rows.firstOrNull { it.key == key }?.messageID,
                frame.positionInWindow().y / density - origin, frame.size.height / density.toDouble())
        }
        val starts = if (reduceMotion) emptyMap() else counterpartOrigins(origins)
        val plans = NativeChiefReplyMotion.plan(slots, starts, reply.targetID, if (reduceMotion) 0.0 else reply.releaseOffset.toDouble())
        plans.forEach { plan ->
            bases[plan.slot.key] = plan.slot.y
            trips[plan.slot.key] = NativeChiefReplyTrip(plan.startY, plan.targetY, plan.initialOffsetX,
                NativeChiefReplyMotion.threadEasing(origin + plan.startY + plan.slot.height / 2,
                    plan.slot.height, viewHeight, false), NativeChiefReplyMotion.snapDistance(density.toDouble(), false))
        }
        ready = true
        revision++
    }

    private fun counterpartOrigins(origins: NativeChiefReplyOrigins.Snapshot): Map<String, Double> {
        val origin = contentY ?: return emptyMap()
        val result = mutableMapOf<String, Double>()
        reply.rows.forEach { item ->
            val row = movingFrames[item.key]?.takeIf { it.isAttached }
            val bubble = bubbles[item.key]?.takeIf { it.isAttached }
            val sourceY = origins.bubbles[item.messageID]
            if (row != null && bubble != null && sourceY != null) {
                val localBubbleY = row.localPositionOf(bubble, Offset.Zero).y / density
                result[item.key] = sourceY / density - localBubbleY - origin
            }
        }
        timestampKey?.let { origins.timestamps[it] }?.let { result[stampKey] = it / density - origin }
        return result
    }

    fun copyBounds(): Map<String, Rect> = reply.rows.mapNotNull { item ->
        bubbles[item.key]?.takeIf { it.isAttached }?.let { coordinates ->
            val point = coordinates.positionInWindow()
            item.messageID to Rect(point.x, point.y, point.x + coordinates.size.width, point.y + coordinates.size.height)
        }
    }.toMap()

    fun verticalPixels(key: String): Int { revision; return ((trips[key]?.let { it.y - checkNotNull(bases[key]) } ?: 0.0) * density).toIntRounded() }
    fun horizontalPixels(key: String): Int { revision; return ((trips[key]?.offsetX ?: 0.0) * density).toIntRounded() }

    fun frame(nanos: Long, initialDuration: Double) {
        if (!ready) return
        val start = firstFrame ?: nanos.also { firstFrame = it }
        val elapsed = previousFrame?.let { (nanos - it) / 1e9 } ?: initialDuration
        previousFrame = nanos
        if (!closing || settles) trips.forEach { (key, trip) -> if (key !in fading && !trip.isResting) trip.step(elapsed) }
        // ReplyBlurDriver samples the upcoming display frame, rather than the
        // timestamp of the frame that just finished.
        val progress = ((nanos - start) / 1e9 + initialDuration) / NativeChiefReplyMotion.backgroundDuration
        val eased = NativeChiefReplyMotion.easeInOut(progress).toFloat()
        backgroundDone = progress >= 1
        if (closing) {
            veil = closingDim * (1 - eased)
            blur = 1 - eased
            contentFade = if (settles) 1 - eased else closingContent * (1 - eased)
        } else {
            veil = eased
            blur = eased
            chrome = eased
        }
        revision++
    }

    companion object { const val stampKey = "reply:timestamp" }
}

private fun Double.toIntRounded() = roundToInt()
