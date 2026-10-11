package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class NativeSetupGuidanceTest {
    @Test fun allDurableSignalCombinationsFollowSourcePrecedence() {
        for (signed in listOf(false, true)) for (known in listOf(false, true)) for (mismatch in listOf(false, true)) {
            val expected = if (mismatch) NativeSetupGuidance.ACCOUNT_MISMATCH
                else if (!signed) NativeSetupGuidance.NOT_SIGNED_IN
                else if (known) NativeSetupGuidance.MAC_UNREACHABLE else NativeSetupGuidance.NEVER_PAIRED
            assertEquals(expected, NativeSetupGuidance.resolve(signed, known, mismatch))
        }
    }

    @Test fun highlightedGateMovesFirstWithoutLosingOtherSetupSteps() {
        assertEquals(NativeSetupGuidance.entries.toList(), NativeSetupGuidance.ordered(null))
        for (gate in NativeSetupGuidance.entries) {
            val order = NativeSetupGuidance.ordered(gate)
            assertEquals(gate, order.first())
            assertEquals(NativeSetupGuidance.entries.filter { it != gate }, order.drop(1))
            assertEquals(4, order.distinct().size)
        }
    }

    @Test fun onlyTypedAuthenticationRejectionHighlightsAccountRecovery() {
        assertTrue(nativeSetupRequiresReauth(MobileRpcException("unauthorized", "anything", true)))
        assertTrue(nativeSetupRequiresReauth(IOException("wrapped", IrohV2HttpFailure(401, null))))
        assertTrue(nativeSetupRequiresReauth(IrohV2ServerFailure("ticket_expired", false)))
        assertFalse(nativeSetupRequiresReauth(MobileRpcException("unauthorized", "local error")))
        assertFalse(nativeSetupRequiresReauth(MobileRpcException("forbidden", "permission denied", true)))
        assertFalse(nativeSetupRequiresReauth(IrohV2HttpFailure(503, null)))
        assertFalse(nativeSetupRequiresReauth(SocketTimeoutException("unauthorized account mismatch")))
        assertFalse(nativeSetupRequiresReauth(IllegalStateException("Mac is signed in to another account")))
    }

    @Test fun cyclicOrExcessivelyDeepCausesCannotLoopOrInventRejection() {
        val first = IOException("first"); val second = IOException("second")
        first.initCause(second); second.initCause(first)
        assertFalse(nativeSetupRequiresReauth(first))
        var deep: Throwable = IrohV2HttpFailure(401, null)
        repeat(8) { deep = IOException("wrapper", deep) }
        assertFalse(nativeSetupRequiresReauth(deep))
    }
}
