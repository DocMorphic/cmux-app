package io.github.docmorphic.cmuxapp

/** Pinned SimStreamViewerLifecycle policy. The owner executes actions and fences stale callbacks. */
internal class SimViewerLifecycle {
    enum class Phase { IDLE, WAITING, STARTING, STREAMING, RETRYING, BACKGROUNDED, UNAVAILABLE, STOPPED }
    enum class Event { ACTIVATE, DEACTIVATE, TRANSPORT_READY, TRANSPORT_LOST, CONFIGURED, PRESENTED,
        WEDGED, REFRESH, RETRY_ELAPSED, BACKGROUND, FOREGROUND }
    sealed interface Action {
        data object None : Action
        data object Open : Action
        data object Teardown : Action
        data class Retry(val delayMillis: Long) : Action
    }
    var phase = Phase.IDLE; private set
    var reason: String? = null; private set
    private var attempt = 0

    fun hostEnded(status: SimHostStatus, detail: String): Action {
        // SimulatorStreamV2Store keeps the lane alive for unavailable devices and
        // failed/crashed workers: the host can recover without a new attachment.
        // Closed is terminal only for the current, running attachment. The owner
        // must additionally fence callbacks by its attachment generation.
        if (status != SimHostStatus.CLOSED || phase !in setOf(Phase.STARTING, Phase.STREAMING))
            return Action.None
        phase = Phase.UNAVAILABLE; reason = detail.ifEmpty { status.name.lowercase() }
        return Action.Teardown
    }

    fun handle(event: Event): Action {
        when (event) {
            Event.ACTIVATE -> if (phase in setOf(Phase.IDLE, Phase.STOPPED, Phase.UNAVAILABLE)) {
                phase = Phase.WAITING; reason = null
            }
            Event.TRANSPORT_READY -> if (phase == Phase.WAITING) { phase = Phase.STARTING; return Action.Open }
            Event.CONFIGURED -> if (phase == Phase.STARTING) phase = Phase.STREAMING
            Event.PRESENTED -> if (phase == Phase.STREAMING) attempt = 0
            Event.TRANSPORT_LOST, Event.WEDGED -> if (phase == Phase.STARTING || phase == Phase.STREAMING) {
                val delay = (250L shl attempt.coerceAtMost(4)).coerceAtMost(4000)
                attempt = (attempt + 1).coerceAtMost(4); phase = Phase.RETRYING
                return Action.Retry(delay)
            }
            Event.RETRY_ELAPSED -> if (phase == Phase.RETRYING) phase = Phase.WAITING
            Event.REFRESH -> if (phase in setOf(Phase.WAITING, Phase.STARTING, Phase.STREAMING, Phase.RETRYING, Phase.UNAVAILABLE)) {
                attempt = 0; phase = Phase.WAITING; reason = null; return Action.Teardown
            }
            Event.BACKGROUND -> if (phase != Phase.IDLE && phase != Phase.STOPPED) {
                phase = Phase.BACKGROUNDED; return Action.Teardown
            }
            Event.FOREGROUND -> if (phase == Phase.BACKGROUNDED) { attempt = 0; phase = Phase.WAITING; reason = null }
            Event.DEACTIVATE -> {
                val active = phase != Phase.IDLE && phase != Phase.STOPPED
                phase = Phase.STOPPED; reason = null
                return if (active) Action.Teardown else Action.None
            }
        }
        return Action.None
    }
}
