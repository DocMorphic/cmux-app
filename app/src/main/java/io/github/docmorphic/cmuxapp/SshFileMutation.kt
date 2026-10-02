package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

internal class SshUploadUnconfirmed(cause: Exception) : IOException(
    "The upload could not be confirmed. It may already be saved on the computer. Refresh the folder before uploading again.", cause)

/** Refresh is observation, not part of the mutation's outcome. Never replay it. */
internal suspend fun <T> sshFileMutationAndRefresh(action: suspend () -> T, refresh: suspend () -> Unit): T {
    val result = try { action() }
    catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        try { refresh() }
        catch (refreshFailure: Exception) {
            currentCoroutineContext().ensureActive()
            if (refreshFailure !== failure) failure.addSuppressed(refreshFailure)
        }
        throw failure
    }
    try { refresh() }
    catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        throw IOException("The change completed, but the folder could not be refreshed. Refresh to see the current files.", failure)
    }
    return result
}
