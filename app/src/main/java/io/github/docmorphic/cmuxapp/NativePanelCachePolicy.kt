package io.github.docmorphic.cmuxapp

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

/** A transport interruption may retain already downloaded bytes, never RPC admission. */
internal object NativePanelCachePolicy {
    fun retains(failure: Throwable): Boolean = when {
        IrohV2Recovery.stops(failure) || failure is SSLException -> false
        failure is IrohV2HttpFailure -> failure.status == 408 || failure.status == 429 || failure.status in 500..599
        failure is IrohV2ServerFailure -> failure.code in setOf("unavailable", "rate_limited", "request_timed_out", "connection_recovering", "session_unavailable", "timeout")
        failure is MobileRpcException -> failure.code in setOf("unavailable", "session_unavailable",
            "connection_recovering", "request_timed_out", "request_timeout", "timeout", "mac_unreachable", "transfer_interrupted")
        failure is IOException || failure is TimeoutCancellationException -> true
        else -> false
    }
}
