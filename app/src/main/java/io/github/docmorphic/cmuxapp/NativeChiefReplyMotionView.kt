package io.github.docmorphic.cmuxapp

import android.animation.ValueAnimator
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt

/** Coordinates belong to this transcript composition only; detached/prefetched rows cannot
 * supply a visible twin. Freeze entry origins before requesting the keyboard or changing reply. */
internal class NativeChiefReplyOrigins {
    val bubbles = mutableMapOf<String, LayoutCoordinates>()
    val timestamps = mutableMapOf<String, LayoutCoordinates>()
    var viewport: LayoutCoordinates? = null

    data class Snapshot(val bubbles: Map<String, Float>, val timestamps: Map<String, Float>)
    fun snapshot(): Snapshot {
        val frame = viewport?.takeIf { it.isAttached } ?: return Snapshot(emptyMap(), emptyMap())
        val top = frame.positionInWindow().y
        val bottom = top + frame.size.height
        fun visible(items: Map<String, LayoutCoordinates>) = items.mapNotNull { (key, coordinates) ->
            if (!coordinates.isAttached) null else {
                val y = coordinates.positionInWindow().y
                if (y + coordinates.size.height > top && y < bottom) key to y else null
            }
        }.toMap()
        return Snapshot(visible(bubbles), visible(timestamps))
    }
}

/** Compose adapter for the source frame equations. Positions/offsets are in source points;
 * only placement converts to pixels. No state survives this generation's composition. */
internal class NativeChiefReplyEntryMotion(
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
    private var firstFrame: Long? = null
    private var previousFrame: Long? = null
    val resting get(): Boolean { revision; return ready && trips.values.all { it.isResting } && veil == 1f }
    val contentAlpha get() = if (!ready) 0f else if (reduceMotion) veil else 1f

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
        if (ready || content?.isAttached != true || expected.any { frames[it]?.isAttached != true } ||
            reply.rows.any { movingFrames[it.key]?.isAttached != true || bubbles[it.key]?.isAttached != true }) return
        val origin = checkNotNull(contentY)
        val slots = expected.map { key ->
            val frame = checkNotNull(frames[key])
            NativeChiefReplyMotion.Slot(key, reply.rows.firstOrNull { it.key == key }?.messageID,
                frame.positionInWindow().y / density - origin, frame.size.height / density.toDouble())
        }
        val starts = mutableMapOf<String, Double>()
        if (!reduceMotion) {
            reply.rows.forEach { item ->
                origins.bubbles[item.messageID]?.let { sourceY ->
                    val row = checkNotNull(movingFrames[item.key])
                    val bubble = checkNotNull(bubbles[item.key])
                    val localBubbleY = row.localPositionOf(bubble, Offset.Zero).y / density
                    starts[item.key] = sourceY / density - localBubbleY - origin
                }
            }
            timestampKey?.let { origins.timestamps[it] }?.let { starts[stampKey] = it / density - origin }
        }
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

    fun verticalPixels(key: String): Int { revision; return ((trips[key]?.let { it.y - checkNotNull(bases[key]) } ?: 0.0) * density).toIntRounded() }
    fun horizontalPixels(key: String): Int { revision; return ((trips[key]?.offsetX ?: 0.0) * density).toIntRounded() }

    fun frame(nanos: Long, initialDuration: Double) {
        if (!ready) return
        val start = firstFrame ?: nanos.also { firstFrame = it }
        val elapsed = previousFrame?.let { (nanos - it) / 1e9 } ?: initialDuration
        previousFrame = nanos
        trips.values.forEach { if (!it.isResting) it.step(elapsed) }
        veil = NativeChiefReplyMotion.easeInOut((nanos - start) / 1e9 / NativeChiefReplyMotion.backgroundDuration).toFloat()
        revision++
    }

    companion object { const val stampKey = "reply:timestamp" }
}

private fun Double.toIntRounded() = roundToInt()
