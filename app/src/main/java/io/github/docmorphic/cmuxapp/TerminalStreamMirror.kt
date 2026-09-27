package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.Base64

/** One surface and one connection generation. All calls are serialized on the UI dispatcher. */
class TerminalStreamMirror(
    val surfaceId: String, val transport: TerminalTransport, private val viewport: TerminalViewport
) {
    enum class Result { APPLIED, IGNORED, REPLAY }
    private data class Chunk(val bytes: ByteArray, val sequence: Long?) {
        val end get() = sequence?.let { Math.addExact(it, bytes.size.toLong()) }
    }
    private var authoritative = RenderGrid()
    private var raw = VtTerminal(viewport.columns, viewport.rows)
    private var hybridScreen = "primary"
    private var deliveredEnd: Long? = null
    private var pending = true
    private val buffered = ArrayDeque<Chunk>()
    private var bufferedBytes = 0
    private var overflowed = false
    private var pendingGrid: JSONObject? = null
    private val usesAlternateGrid get() = transport.mode == TerminalOutputMode.HYBRID && hybridScreen == "alternate" &&
        authoritative.activeScreen == "alternate" && authoritative.columns > 0
    val display: TerminalDisplay get() = if (transport.mode == TerminalOutputMode.GRID || usesAlternateGrid) authoritative else raw
    val historyLineCount get() = display.historyLineCount

    fun beginReplay() { pending = true }

    fun bytes(payload: JSONObject): Result {
        if (payload.optString("surface_id") != surfaceId || transport.mode == TerminalOutputMode.GRID ||
            usesAlternateGrid) return Result.IGNORED
        val chunk = Chunk(decode(payload.getString("data_b64")), sequence(payload, "seq"))
        chunk.end // Validate sequence arithmetic before buffering or mutating the parser.
        if (chunk.bytes.isEmpty()) return Result.IGNORED
        if (pending) { buffer(chunk); return Result.IGNORED }
        return append(chunk)
    }

    fun grid(value: JSONObject): Result {
        val frame = value.optJSONObject("render_grid") ?: value
        if (frame.optString("surface_id") != surfaceId || transport.mode == TerminalOutputMode.BYTES) return Result.IGNORED
        if (transport.mode == TerminalOutputMode.GRID) {
            return if (authoritative.apply(frame)) Result.APPLIED else Result.REPLAY
        }
        if (pending) { pendingGrid = frame; return Result.IGNORED }
        return hybridGrid(frame)
    }

    /** Installs a replay before releasing buffered output; overlap is trimmed in bytes, not UTF-16. */
    fun replay(value: JSONObject): Result {
        val frame = value.optJSONObject("render_grid") ?: value.takeIf { it.optString("format") == "cmux.render-grid.v1" }
        require(frame == null || frame.optString("surface_id") == surfaceId) { "cmux returned a different terminal's replay" }
        if (transport.mode == TerminalOutputMode.GRID) {
            require(frame != null && frame.optBoolean("full", true)) { "cmux did not return a full terminal render grid" }
            val result = if (authoritative.apply(frame)) Result.APPLIED else Result.REPLAY
            pending = result == Result.REPLAY
            return result
        }
        val replayEnd = frame?.let { sequence(it, "state_seq") } ?: sequence(value, "seq")
        if (replayEnd != null && deliveredEnd?.let { replayEnd < it } == true) return Result.REPLAY
        if (frame != null) {
            installGridBaseline(frame)
        } else {
            val snapshot = value.optString("snapshot_data_b64").takeIf { !value.isNull("snapshot_data_b64") && it.isNotEmpty() }
            val tail = value.optString("data_b64").takeIf { value.has("data_b64") && !value.isNull("data_b64") }
            require(snapshot != null || tail != null) { "cmux returned no terminal replay content" }
            val columns = value.optInt("columns", viewport.columns).coerceIn(2, 1000)
            val rows = value.optInt("rows", viewport.rows).coerceIn(2, 1000)
            raw = VtTerminal(columns, rows).also { it.append(decode(snapshot ?: tail!!)) }
            authoritative = RenderGrid()
            hybridScreen = raw.activeScreen
        }
        deliveredEnd = replayEnd
        val waiting = buffered.toList()
        buffered.clear(); bufferedBytes = 0
        val needsAnotherReplay = overflowed
        overflowed = false
        pending = false
        for (chunk in waiting) {
            // A sequence-less pre-replay event may already be in that replay. Never double-apply it.
            if (chunk.sequence == null) continue
            if (usesAlternateGrid) continue
            if (pending) buffer(chunk) else append(chunk)
        }
        val deferredGrid = pendingGrid.also { pendingGrid = null }
        val gridResult = deferredGrid?.let { if (pending) { pendingGrid = it; Result.REPLAY } else hybridGrid(it) }
        if (needsAnotherReplay || pending || gridResult == Result.REPLAY) {
            pending = true
            return Result.REPLAY
        }
        return Result.APPLIED
    }

    private fun hybridGrid(frame: JSONObject): Result {
        val revision = frame.optLong("render_revision")
        if (frame.optString("render_epoch").isNotEmpty() && frame.optString("render_epoch") == authoritative.epoch &&
            revision > 0 && revision <= authoritative.revision) return Result.IGNORED
        val stateSequence = sequence(frame, "state_seq")
        if (stateSequence != null && deliveredEnd?.let { stateSequence < it } == true) return Result.IGNORED
        val screen = frame.optString("active_screen", "primary")
        if (screen == "primary" && hybridScreen == "primary") {
            raw.append(GridVtReplay.theme(frame).toByteArray(Charsets.UTF_8))
            return Result.APPLIED // Bytes own primary content in hybrid mode.
        }
        if (screen != hybridScreen && !frame.optBoolean("full", true)) return Result.REPLAY
        if (!authoritative.apply(frame)) return Result.REPLAY
        if (screen == "primary") installGridBaseline(frame)
        hybridScreen = screen
        if (stateSequence != null) deliveredEnd = stateSequence
        return Result.APPLIED
    }

    private fun installGridBaseline(frame: JSONObject) {
        require(frame.optBoolean("full", true)) { "A terminal baseline cannot be a delta" }
        val replacement = GridVtReplay.replacement(frame)
        val next = RenderGrid().also { require(it.apply(frame)) }
        raw = VtTerminal(next.columns, next.rows).also { it.append(replacement) }
        authoritative = next
        hybridScreen = next.activeScreen
    }

    private fun append(chunk: Chunk): Result {
        val seq = chunk.sequence
        val end = chunk.end
        val previous = deliveredEnd
        if (seq != null && previous != null) {
            if (end!! <= previous) return Result.IGNORED
            if (seq > previous) {
                pending = true; buffer(chunk)
                return Result.REPLAY
            }
            raw.append(chunk.bytes.copyOfRange((previous - seq).toInt(), chunk.bytes.size))
        } else raw.append(chunk.bytes)
        deliveredEnd = end // A sequence-less producer cannot establish an ordered byte baseline.
        return Result.APPLIED
    }

    private fun buffer(chunk: Chunk) {
        if (bufferedBytes + chunk.bytes.size > MAX_PENDING_BYTES || buffered.size >= MAX_PENDING_CHUNKS) {
            buffered.clear(); bufferedBytes = 0; overflowed = true
        }
        if (chunk.bytes.size <= MAX_PENDING_BYTES) { buffered.addLast(chunk); bufferedBytes += chunk.bytes.size }
    }

    companion object {
        private const val MAX_PENDING_BYTES = 2 * 1024 * 1024
        private const val MAX_PENDING_CHUNKS = 256
        private fun decode(value: String): ByteArray {
            require(value.length <= MobileFrameCodec.MAX_FRAME_BYTES) { "Terminal byte chunk is too large" }
            return Base64.getDecoder().decode(value)
        }
        private fun sequence(value: JSONObject, key: String): Long? {
            if (!value.has(key) || value.isNull(key)) return null
            return value.get(key).toString().toLongOrNull()?.also { require(it >= 0) }
                ?: throw IllegalArgumentException("Invalid terminal sequence")
        }
    }
}
