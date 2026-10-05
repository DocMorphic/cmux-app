package io.github.docmorphic.cmuxapp

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
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
}
