package io.github.docmorphic.cmuxapp

import java.io.IOException
import javax.net.ssl.SSLException
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** A transport interruption may retain already downloaded bytes, never RPC admission. */
internal object NativePanelCachePolicy {
    fun retains(failure: Throwable): Boolean {
        if (decision(failure) != true) return false
        // Native wrappers can retain an explicit rejection as their cause.
        // Such rejection wins over the outer IOException's transport category.
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        repeat(16) {
            val value = current ?: return true
            if (!seen.add(value) || decision(value) == false) return false
            current = value.cause
        }
        return current == null
    }

    private fun decision(failure: Throwable): Boolean? = when {
        IrohV2Recovery.stops(failure) || failure is SSLException -> false
        failure is IrxWire.AdmissionRejected -> when (failure.code) {
            IrxWire.CloseCode.INVALID_GRANT, IrxWire.CloseCode.GRANT_EXPIRED, IrxWire.CloseCode.REVOKED,
            IrxWire.CloseCode.IDENTITY_MISMATCH, IrxWire.CloseCode.MALFORMED_HELLO, IrxWire.CloseCode.PROTOCOL_MISMATCH -> false
            // Superseded/user-requested prevent automatic redial, but remain
            // lifecycle closes rather than revoked admission in IrxProtocol.
            IrxWire.CloseCode.ADMISSION_TIMEOUT, IrxWire.CloseCode.SUPERSEDED, IrxWire.CloseCode.USER_REQUESTED,
            IrxWire.CloseCode.HOST_SHUTDOWN, IrxWire.CloseCode.KEEPALIVE_TIMEOUT, IrxWire.CloseCode.EXPLICIT_REDIAL -> true
        }
        failure is IrohV2HttpFailure -> failure.status == 408 || failure.status == 429 || failure.status in 500..599
        failure is IrohV2ServerFailure -> failure.code in setOf("unavailable", "rate_limited", "request_timed_out", "connection_recovering", "session_unavailable", "timeout")
        failure is MobileRpcException -> failure.code in setOf("unavailable", "session_unavailable",
            "connection_recovering", "request_timed_out", "request_timeout", "timeout", "mac_unreachable", "transfer_interrupted")
        failure is IOException || failure is TimeoutCancellationException -> true
        failure is CancellationException -> false
        failure is SecurityException || failure is IllegalArgumentException -> false
        else -> null
    }
}
