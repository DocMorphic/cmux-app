package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only provider preparation is recoverable. Ownership, cancellation and draft writes must stop. */
internal suspend fun <T : Any> readComposerAttachment(
    guard: () -> Unit, failed: (String) -> Unit, read: suspend () -> T
): T? {
    currentCoroutineContext().ensureActive(); guard()
    val value = try { read() }
    catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        currentCoroutineContext().ensureActive(); guard()
        failed(failure.message ?: "An attachment couldn't be read. Try copying it again.")
        return null
    }
    currentCoroutineContext().ensureActive(); guard()
    return value
}
