package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal interface ComposerSpeechEngine : AutoCloseable {
    interface Listener {
        fun ready()
        fun transcript(text: String?, final: Boolean)
        fun ended()
        fun failed(message: String)
    }
    fun start(listener: Listener)
    fun stop()
}

/** Main-dispatcher confined. Every permission/recognizer callback belongs to one attempt. */
internal class ComposerDictation(
    private val scope: CoroutineScope,
    private val createEngine: () -> ComposerSpeechEngine,
    private val isCurrent: () -> Boolean,
    private val readText: () -> String,
    private val writeText: (String) -> Boolean,
) : AutoCloseable {
    enum class Phase { IDLE, PERMISSION, STARTING, LISTENING, STOPPING, CLOSED }
    data class State(val phase: Phase = Phase.IDLE, val error: String? = null) {
        val locksField get() = phase in setOf(Phase.PERMISSION, Phase.STARTING, Phase.LISTENING, Phase.STOPPING)
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var engine: ComposerSpeechEngine? = null
    private var timeout: Job? = null
    private var base = ""
    private var lastText = ""

    /** Capture before asking permission so typing cannot race a delayed recognition start. */
    fun request(): Long? {
        if (mutable.value.phase != Phase.IDLE || !isCurrent()) return null
        generation++
        base = readText(); lastText = base
        mutable.value = State(Phase.PERMISSION)
        return generation
    }

    fun permission(token: Long, granted: Boolean) {
        if (!accepts(token) || mutable.value.phase != Phase.PERMISSION) return
        if (!granted) { finish("Allow microphone access in Android Settings to use dictation."); return }
        mutable.value = State(Phase.STARTING)
        try {
            engine = createEngine()
            deadline(token, 10_000, "Dictation did not start. Try again.")
            engine?.start(object : ComposerSpeechEngine.Listener {
                override fun ready() {
                    if (!accepts(token) || mutable.value.phase != Phase.STARTING) return
                    timeout?.cancel(); timeout = null
                    mutable.value = State(Phase.LISTENING)
                }
                override fun transcript(text: String?, final: Boolean) {
                    if (!accepts(token) || mutable.value.phase !in setOf(Phase.STARTING, Phase.LISTENING, Phase.STOPPING)) return
                    // Empty final results must not erase already committed partial words.
                    if (!text.isNullOrBlank()) {
                        lastText = merge(base, text)
                        if (!writeText(lastText)) { cancel(); return }
                    }
                    if (final) finish()
                }
                override fun ended() {
                    if (!accepts(token) || mutable.value.phase == Phase.STOPPING) return
                    mutable.value = State(Phase.STOPPING)
                    deadline(token, 2_500)
                }
                override fun failed(message: String) { if (accepts(token)) finish(message) }
            })
        } catch (_: Exception) { if (token == generation) finish("Could not start dictation. Check your speech recognition settings.") }
    }

    /** Explicit Stop keeps the last partial and allows one final refinement. */
    fun stop() {
        if (mutable.value.phase != Phase.LISTENING) { cancel(); return }
        val token = generation
        mutable.value = State(Phase.STOPPING)
        deadline(token, 2_500)
        try { engine?.stop() } catch (_: Exception) { if (token == generation) finish() }
    }

    /** Send/navigation invalidates callbacks BEFORE releasing the microphone. */
    fun cancel() { if (mutable.value.phase != Phase.CLOSED) finish() }
    override fun close() { finish(); mutable.value = State(Phase.CLOSED) }
    private fun accepts(token: Long): Boolean {
        if (token != generation || !mutable.value.locksField) return false
        if (!isCurrent() || readText() != lastText) { cancel(); return false }
        return true
    }
    private fun deadline(token: Long, millis: Long, error: String? = null) {
        timeout?.cancel()
        timeout = scope.launch { delay(millis); if (accepts(token)) finish(error) }
    }
    private fun finish(error: String? = null) {
        generation++
        timeout?.cancel(); timeout = null
        val previous = engine; engine = null
        base = ""; lastText = ""
        if (mutable.value.phase != Phase.CLOSED) mutable.value = State(error = error)
        runCatching { previous?.close() }
    }
    companion object {
        fun merge(base: String, transcript: String): String {
            val tail = transcript.trimStart()
            if (tail.isEmpty()) return base
            return base + if (base.isEmpty() || base.last().isWhitespace()) tail else " $tail"
        }
    }
}
