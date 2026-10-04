package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException

/** Ordinary workspace/group mutations are diagnostic-only in the iOS shell.
 * Keep these separate from connection state and actionable composer/creation failures.
 * Only fixed classifications enter diagnostics; peer messages can contain private data.
 */
internal fun recordWorkspaceActionFailure(failure: Exception) {
    if (failure is CancellationException) throw failure
    MobileDebugLog.fail(MobileDebugLog.begin(DebugOperation.RPC_WORKSPACE), failure)
}
