package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import kotlin.math.hypot

internal data class LegacySimulatorFrame(val panelId: String, val sequence: ULong, val format: String,
    val width: Int, val height: Int, val displayScale: Double, val base64: String) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_BASE64 = ((MAX_BYTES + 2) / 3) * 4
        fun read(value: JSONObject, panelId: String): LegacySimulatorFrame? = runCatching {
            require(value.get("panel_id") == panelId)
            val sequence = (value.get("seq") as? Number)?.toString()?.toULongOrNull() ?: error("Invalid sequence")
            val format = value.get("format") as? String ?: error("Invalid format")
            require(format == "jpeg" || format == "png")
            fun integer(key: String) = (value.get(key) as? Number)?.toString()?.toIntOrNull() ?: error("Invalid size")
            val width = integer("pixel_width"); val height = integer("pixel_height")
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216)
            val scale = (value.get("display_scale") as? Number)?.toDouble() ?: error("Invalid scale")
            require(scale.isFinite() && scale > 0 && scale <= 16)
            val encoded = value.get("data_base64") as? String ?: error("Invalid payload")
            require(encoded.isNotEmpty() && encoded.length <= MAX_BASE64)
            LegacySimulatorFrame(panelId, sequence, format, width, height, scale, encoded)
        }.getOrNull()
    }
}

internal enum class LegacyPointerPhase(val wire: String) { BEGAN("began"), MOVED("moved"), ENDED("ended"), TAP("tap") }
internal enum class LegacySimulatorButton(val wire: String) {
    HOME("home"), SWIPE_HOME("swipeHome"), APP_SWITCHER("appSwitcher"), LOCK("lock"), SIRI("siri"),
    SIDE_BUTTON("sideButton"), POWER("power"), VOLUME_UP("volumeUp"), VOLUME_DOWN("volumeDown"),
    ACTION("action"), WATCH_SIDE_BUTTON("watchSideButton")
}
internal sealed interface LegacySimulatorInput {
    data class Pointer(val phase: LegacyPointerPhase, val x: Float, val y: Float) : LegacySimulatorInput
    data class Text(val text: String) : LegacySimulatorInput
    data class Button(val button: LegacySimulatorButton) : LegacySimulatorInput
    fun request(workspaceId: String, panelId: String): Pair<String, JSONObject> {
        val params = JSONObject().put("workspace_id", workspaceId).put("panel_id", panelId)
        val kind = when (this) {
            is Pointer -> {
                require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
                params.put("phase", phase.wire).put("x", x.toDouble()).put("y", y.toDouble()); "pointer"
            }
            is Text -> { params.put("text", text); "text" }
            is Button -> { params.put("button", button.wire); "button" }
        }
        return "mobile.simulator.input.$kind" to params
    }
}

/** Legacy iOS waits for six points of movement, sends taps at the starting point, and clamps letterboxes. */
internal class LegacySimulatorGesture(private val thresholdPixels: Float) {
    private var pointer: Int? = null
    private var start = 0f to 0f
    private var last = 0f to 0f
    private var dragging = false
    fun begin(id: Int, x: Float, y: Float) {
        if (pointer != null || !x.isFinite() || !y.isFinite()) return
        pointer = id; start = x to y; last = start; dragging = false
    }
    fun move(id: Int, x: Float, y: Float, rect: SimVideoRect): List<LegacySimulatorInput.Pointer> {
        if (pointer != id || !x.isFinite() || !y.isFinite()) return emptyList()
        last = x to y
        if (!dragging && !isDrag(x, y)) return emptyList()
        val result = mutableListOf<LegacySimulatorInput.Pointer>()
        if (!dragging) point(LegacyPointerPhase.BEGAN, start, rect)?.let(result::add)
        dragging = true
        point(LegacyPointerPhase.MOVED, last, rect)?.let(result::add)
        return result
    }
    fun end(id: Int, x: Float, y: Float, rect: SimVideoRect): List<LegacySimulatorInput.Pointer> {
        if (pointer != id) return emptyList()
        pointer = null
        val result = mutableListOf<LegacySimulatorInput.Pointer>()
        if (dragging || isDrag(x, y)) {
            if (!dragging) point(LegacyPointerPhase.BEGAN, start, rect)?.let(result::add)
            point(LegacyPointerPhase.ENDED, x to y, rect)?.let(result::add)
        } else point(LegacyPointerPhase.TAP, start, rect)?.let(result::add)
        dragging = false
        return result
    }
    fun cancel(rect: SimVideoRect): List<LegacySimulatorInput.Pointer> {
        val result = if (pointer != null && dragging) listOfNotNull(point(LegacyPointerPhase.ENDED, last, rect)) else emptyList()
        discard(); return result
    }
    fun discard() { pointer = null; dragging = false }
    private fun isDrag(x: Float, y: Float) = hypot(x - start.first, y - start.second) > thresholdPixels
    private fun point(phase: LegacyPointerPhase, value: Pair<Float, Float>, rect: SimVideoRect) =
        rect.point(value.first, value.second, true)?.let { LegacySimulatorInput.Pointer(phase, it.first, it.second) }
}
