package io.github.docmorphic.cmuxapp

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import org.junit.Assert.*
import org.junit.Test

class NativePanelCachePolicyTest {
    @Test fun transportLossRetainsCompletedBytes() {
        for (error in listOf(EOFException(), SocketTimeoutException(), IOException(),
            MobileRpcException("connection_recovering", "recovering"), IrohV2HttpFailure(503, null)))
            assertTrue(error.toString(), NativePanelCachePolicy.retains(error))
    }
    @Test fun authorizationIdentityAndTrustFailuresInvalidateEvenWhenServerSaysRetryable() {
        for (error in listOf(IllegalArgumentException("Different Mac"), SecurityException(), SSLHandshakeException("Bad certificate"),
            MobileRpcException("unauthorized", "Unauthorized"), MobileRpcException("forbidden", "Forbidden"),
            MobileRpcException("team_access_revoked", "Revoked"), MobileRpcException("authentication_expired", "Expired"),
            IrohV2ServerFailure("forbidden", true, 1), IrohV2ServerFailure("permission_denied", true, 1),
            IrohV2ServerFailure("device_revoked", true, 1), IrohV2ServerFailure("identity_mismatch", true, 1),
            IrohV2ServerFailure("ticket_expired", true, 1), IrohV2HttpFailure(401, null), IrohV2HttpFailure(403, null)))
            assertFalse(error.toString(), NativePanelCachePolicy.retains(error))
    }

    @Test fun nativeAdmissionDenialsInvalidateWhileLifecycleClosesRetainCompletedBytes() {
        val transient = setOf(IrxWire.CloseCode.ADMISSION_TIMEOUT, IrxWire.CloseCode.HOST_SHUTDOWN,
            IrxWire.CloseCode.KEEPALIVE_TIMEOUT, IrxWire.CloseCode.EXPLICIT_REDIAL,
            IrxWire.CloseCode.SUPERSEDED, IrxWire.CloseCode.USER_REQUESTED)
        IrxWire.CloseCode.entries.forEach { code ->
            assertEquals(code.wire, code in transient, NativePanelCachePolicy.retains(IrxWire.AdmissionRejected(code)))
        }
    }

    @Test fun wrappedRejectionsOverrideGenericIoWhileOrdinaryDisconnectsStillRetain() {
        for (rejection in listOf(IrxWire.AdmissionRejected(IrxWire.CloseCode.REVOKED),
            IrxWire.AdmissionRejected(IrxWire.CloseCode.GRANT_EXPIRED),
            IrohV2ServerFailure("device_revoked", true), MobileRpcException("account_mismatch", "private"),
            SSLHandshakeException("private certificate details"), SecurityException())) {
            assertFalse(rejection.toString(), NativePanelCachePolicy.retains(IOException("wrapper", rejection)))
            assertFalse(rejection.toString(), NativePanelCachePolicy.retains(IrohV2Unavailable(rejection)))
        }
        assertTrue(NativePanelCachePolicy.retains(IOException("wrapper", EOFException())))
        assertTrue(NativePanelCachePolicy.retains(IrohV2Unavailable(IOException())))
        // No known rejection is manufactured from arbitrary message text.
        assertTrue(NativePanelCachePolicy.retains(IOException("irx:revoked device_revoked")))
        val first = IOException(); val second = IOException("cycle", first); first.initCause(second)
        assertFalse(NativePanelCachePolicy.retains(first))
        var deep: Throwable = EOFException()
        repeat(20) { deep = IOException("wrapper", deep) }
        assertFalse(NativePanelCachePolicy.retains(deep))
    }
}
