package io.github.docmorphic.cmuxapp

internal enum class ArtifactFocusEvent { GAIN, LOSS, TRANSIENT_LOSS, DUCK }
internal enum class ArtifactFocusPermission { GRANTED, SUSPENDED, DENIED }

internal interface ArtifactAudioFocusPort {
    fun request(listener: (ArtifactFocusEvent) -> Unit): Boolean
    fun abandon()
}

/** A focus lease belongs to one player. Retired callbacks cannot resume its replacement. */
internal class ArtifactAudioFocusOwner(
    private val port: ArtifactAudioFocusPort,
    private val changed: (ArtifactFocusEvent) -> Unit,
) : AutoCloseable {
    private var generation = 0L
    private var requested = false
    private var granted = false
    private var closed = false
    var volumeMultiplier = 1f; private set
    val canPlay get() = !closed && requested && granted

    fun acquire(): ArtifactFocusPermission {
        if (closed) return ArtifactFocusPermission.DENIED
        if (requested) return if (granted) ArtifactFocusPermission.GRANTED else ArtifactFocusPermission.SUSPENDED
        val token = ++generation
        requested = true
        val accepted = port.request { event ->
            if (closed || !requested || token != generation) return@request
            when (event) {
                ArtifactFocusEvent.GAIN -> { granted = true; volumeMultiplier = 1f }
                ArtifactFocusEvent.DUCK -> { volumeMultiplier = .2f }
                ArtifactFocusEvent.TRANSIENT_LOSS -> granted = false
                ArtifactFocusEvent.LOSS -> release()
            }
            changed(event)
        }
        if (token != generation || !requested) return ArtifactFocusPermission.DENIED
        if (!accepted) { release(); return ArtifactFocusPermission.DENIED }
        granted = true
        volumeMultiplier = 1f
        return ArtifactFocusPermission.GRANTED
    }

    fun release() {
        ++generation
        val owned = requested
        requested = false; granted = false; volumeMultiplier = 1f
        if (owned) port.abandon()
    }

    override fun close() { closed = true; release() }
}
