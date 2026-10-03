package io.github.docmorphic.cmuxapp

/** Port of TerminalArtifactChipCountState at cmux 4c5272e9, Manaflow GPL-3.0-or-later. */
internal class TerminalArtifactCount {
    data class Request(val stateGeneration: Long, val surfaceGeneration: Long, val localCount: Int)
    data class Report(val count: Int, val surfaceGeneration: Long)
    data class Trigger(val report: Report, val authoritative: Boolean = false, val request: Request? = null)
    enum class Outcome { REPORTED, DROPPED, STALE }
    data class Completion(val outcome: Outcome, val report: Report? = null, val next: Request? = null)
    private data class Pending(val surfaceGeneration: Long, val localCount: Int)
    private var stateGeneration = 0L
    private var inFlight: Request? = null
    private var trailing: Pending? = null
    private var lastLocalCount: Int? = null
    private var lastSurfaceGeneration: Long? = null
    private var consecutiveRearms = 0
    private var authoritativeTotal: Int? = null
    private var authoritativeSession: String? = null

    fun reset() {
        stateGeneration++; inFlight = null; trailing = null; lastLocalCount = null; lastSurfaceGeneration = null
        consecutiveRearms = 0; authoritativeTotal = null; authoritativeSession = null
    }
    fun trigger(localCount: Int, surfaceGeneration: Long, supportsSessionCount: Boolean): Trigger {
        consecutiveRearms = 0
        if (!supportsSessionCount) return Trigger(Report(localCount, surfaceGeneration), authoritative = true)
        val provisional = Report(authoritativeTotal ?: localCount, surfaceGeneration)
        val pending = Pending(surfaceGeneration, localCount)
        val withinWindow = lastSurfaceGeneration?.let { surfaceGeneration >= it && surfaceGeneration - it < 120 } == true
        if (lastLocalCount == localCount && withinWindow) {
            if (inFlight != null && trailing?.localCount == localCount) trailing = pending
            return Trigger(provisional)
        }
        lastLocalCount = localCount; lastSurfaceGeneration = surfaceGeneration
        if (inFlight != null) { trailing = pending; return Trigger(provisional) }
        val request = request(pending); inFlight = request
        return Trigger(provisional, request = request)
    }
    fun complete(request: Request, galleryTotal: Int? = null, sessionTotal: Int? = null, sessionId: String? = null,
        succeeded: Boolean = true, currentGeneration: Long, freshestLocalCount: Int): Completion {
        if (request.stateGeneration != stateGeneration || inFlight != request) return Completion(Outcome.STALE)
        inFlight = null
        if (sessionId != null && sessionId != authoritativeSession) {
            authoritativeTotal = null; authoritativeSession = sessionId
        } else if (succeeded && sessionId == null && authoritativeSession != null) {
            authoritativeTotal = null; authoritativeSession = null
        }
        val report = if (request.surfaceGeneration == currentGeneration) {
            if (galleryTotal != null) {
                authoritativeTotal = galleryTotal; authoritativeSession = sessionId ?: authoritativeSession
            } else if (sessionTotal != null) {
                authoritativeTotal = sessionTotal.takeIf { it > 0 }; authoritativeSession = sessionId ?: authoritativeSession
            } else if (authoritativeTotal == 0 && request.localCount > 0) authoritativeTotal = null
            consecutiveRearms = 0
            Report(authoritativeTotal ?: request.localCount, request.surfaceGeneration)
        } else null
        val outcome = if (report == null) Outcome.DROPPED else Outcome.REPORTED
        val queued = trailing; trailing = null
        if (queued != null && queued.surfaceGeneration >= request.surfaceGeneration &&
            (queued.surfaceGeneration > request.surfaceGeneration || queued.localCount != request.localCount)) {
            val next = request(Pending(maxOf(queued.surfaceGeneration, currentGeneration), queued.localCount))
            inFlight = next; lastLocalCount = next.localCount; lastSurfaceGeneration = next.surfaceGeneration
            return Completion(outcome, report, next)
        }
        if (!succeeded) lastLocalCount = null
        if (outcome != Outcome.DROPPED || consecutiveRearms >= 3) return Completion(outcome, report)
        consecutiveRearms++
        val next = request(Pending(currentGeneration, freshestLocalCount))
        inFlight = next; lastLocalCount = next.localCount; lastSurfaceGeneration = next.surfaceGeneration
        return Completion(outcome, report, next)
    }
    private fun request(pending: Pending) = Request(stateGeneration, pending.surfaceGeneration, pending.localCount)
}

internal class TerminalArtifactVisibility {
    enum class Action { NONE, MOUNT, SCHEDULE_HIDE, HIDE }
    private var count: Int? = null
    private var hidePending = false
    fun update(value: Int, enabled: Boolean): Action {
        if (!enabled) {
            val mounted = count != null; count = null; hidePending = false
            return if (mounted) Action.HIDE else Action.NONE
        }
        if (value <= 0) {
            if (count == null || hidePending) return Action.NONE
            hidePending = true; return Action.SCHEDULE_HIDE
        }
        if (count == value && !hidePending) return Action.NONE
        count = value; hidePending = false; return Action.MOUNT
    }
    fun hideCompleted() { if (hidePending) { count = null; hidePending = false } }
}
