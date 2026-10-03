package io.github.docmorphic.cmuxapp

/** Connection identity, not a delay, fences a retried notification lookup. */
internal data class NativeNotificationRouteRecovery(
    val phase: Phase = Phase.RESOLVING, val blockedClient: Any? = null,
    val message: String? = null, val retryGeneration: Int = 0
) {
    enum class Phase { RESOLVING, FAILED, RECONNECTING, COMPLETE }
    fun allows(client: Any): Boolean = when (phase) {
        Phase.COMPLETE -> false
        Phase.RESOLVING -> true
        Phase.FAILED, Phase.RECONNECTING -> client !== blockedClient
    }
    fun failed(client: Any?, message: String) = copy(phase = Phase.FAILED, blockedClient = client, message = message)
    fun retry(client: Any?) = if (phase != Phase.FAILED) this else
        copy(phase = Phase.RECONNECTING, blockedClient = client ?: blockedClient, message = null, retryGeneration = retryGeneration + 1)
    fun resolving() = copy(phase = Phase.RESOLVING, message = null)
    fun complete() = copy(phase = Phase.COMPLETE, blockedClient = null, message = null)
}
