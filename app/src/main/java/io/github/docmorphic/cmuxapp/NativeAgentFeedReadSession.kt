package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Cached rows may return before their retained foreground handle is fresh.
 * Only the explicitly opened reader waits for its live authorized owner.
 * Closing/replacing it cancels the wait before any RPC for an old row.
 */
internal suspend fun <T : Any> awaitNativeAgentFeedReadSession(checkCurrent: () -> Unit, session: () -> T?): T =
    withTimeoutOrNull(30_000) {
        var ready: T? = null
        while (ready == null) {
            currentCoroutineContext().ensureActive(); checkCurrent()
            ready = session()
            if (ready == null) delay(100)
        }
        checkCurrent()
        ready
    } ?: error("Connect to this computer to use Feed")
