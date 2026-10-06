package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal const val COMPOSER_ATTACHMENT_UNREADABLE = "This attachment couldn't be read. Try selecting or copying it again."

/** Only provider preparation is recoverable. Ownership, cancellation and draft writes must stop. */
internal suspend fun <T : Any> readComposerAttachment(
    guard: () -> Unit, failed: (String) -> Unit, read: suspend () -> T
): T? {
    currentCoroutineContext().ensureActive(); guard()
    val value = try { read() }
    catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        currentCoroutineContext().ensureActive(); guard()
        failed(when (failure) {
            is IOException -> COMPOSER_ATTACHMENT_UNREADABLE
            is SecurityException -> "Access to this attachment was denied. Select or copy it again to grant access."
            else -> failure.message ?: COMPOSER_ATTACHMENT_UNREADABLE
        })
        return null
    }
    currentCoroutineContext().ensureActive(); guard()
    return value
}
