package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import javax.net.ssl.SSLException

/** Stable upstream DiagnosticFailureKind codes. Never infer categories from exception text. */
internal enum class DiagnosticFailure(val code: Int) {
    NONE(0), OFFLINE(1), TIMED_OUT(2), CONNECTION_REFUSED(3), HOST_UNREACHABLE(4),
    PERMISSION_DENIED(5), DNS_FAILED(6), SECURE_CHANNEL_FAILED(7), UNSUPPORTED_ROUTE(8),
    NO_ROUTE(9), CREDENTIAL_UNAVAILABLE(10), POLICY_UNAVAILABLE(11), ENDPOINT_UNAVAILABLE(12),
    IDENTITY_MISMATCH(13), ADMISSION_DENIED(14), AUTHORIZATION_FAILED(15), ACCOUNT_MISMATCH(16),
    PROTOCOL_VIOLATION(17), CONNECTION_CLOSED(18), SUPERSEDED(19), CANCELLED(20),
    TRANSPORT_IDLE_TIMED_OUT(21), ADMISSION_LEASE_EXPIRED(22), ADMISSION_REVALIDATION_FAILED(23),
    SEND_QUEUE_OVERFLOW(24), ROUTE_GATED(25), PAYLOAD_TOO_LARGE(26), RESOURCE_LIMIT_REACHED(27),
    ATTACHMENT_COUNT_LIMIT_REACHED(28), ATTACHMENT_AGGREGATE_SIZE_LIMIT_REACHED(29),
    LOCAL_STATE_UNAVAILABLE(30), UNKNOWN(255);

    companion object {
        fun fromOutcome(outcome: DebugOutcome): DiagnosticFailure = when (outcome) {
            DebugOutcome.STARTED, DebugOutcome.SUCCESS -> NONE
            DebugOutcome.TIMEOUT -> TIMED_OUT
            DebugOutcome.CANCELLED -> CANCELLED
            else -> UNKNOWN
        }
        fun classify(error: Throwable): DiagnosticFailure = when (error) {
            is IrxWire.AdmissionRejected -> when (error.code) {
                IrxWire.CloseCode.IDENTITY_MISMATCH -> IDENTITY_MISMATCH
                IrxWire.CloseCode.MALFORMED_HELLO, IrxWire.CloseCode.PROTOCOL_MISMATCH -> PROTOCOL_VIOLATION
                IrxWire.CloseCode.INVALID_GRANT, IrxWire.CloseCode.GRANT_EXPIRED, IrxWire.CloseCode.REVOKED -> ADMISSION_DENIED
                IrxWire.CloseCode.ADMISSION_TIMEOUT -> TIMED_OUT
                IrxWire.CloseCode.KEEPALIVE_TIMEOUT -> TRANSPORT_IDLE_TIMED_OUT
                IrxWire.CloseCode.SUPERSEDED -> SUPERSEDED
                IrxWire.CloseCode.USER_REQUESTED -> CANCELLED
                IrxWire.CloseCode.HOST_SHUTDOWN, IrxWire.CloseCode.EXPLICIT_REDIAL -> CONNECTION_CLOSED
            }
            is TimeoutCancellationException, is SocketTimeoutException -> TIMED_OUT
            is CancellationException -> CANCELLED
            is UnknownHostException -> DNS_FAILED
            is NoRouteToHostException -> HOST_UNREACHABLE
            is ConnectException -> CONNECTION_REFUSED
            is SSLException -> SECURE_CHANNEL_FAILED
            is AccessDeniedException, is SecurityException -> PERMISSION_DENIED
            is ProtocolException -> PROTOCOL_VIOLATION
            is EOFException -> CONNECTION_CLOSED
            // IOException, SocketException, RPC/HTTP bodies and native library strings are
            // ambiguous. Do not inspect messages, causes or peer-provided reason substrings.
            else -> UNKNOWN
        }
    }
}
