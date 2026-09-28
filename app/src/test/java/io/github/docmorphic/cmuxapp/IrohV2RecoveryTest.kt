package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class IrohV2RecoveryTest {
    @Test fun challengeExpiryCanStartFreshDespiteNonretryableOperation() {
        assertFalse(IrohV2Recovery.stops(IrohV2ServerFailure("challenge_expired", false)))
        assertFalse(IrohV2Recovery.stops(IrohV2ServerFailure("proof_replayed", false)))
        assertTrue(IrohV2Recovery.stops(IrohV2ServerFailure("device_revoked", true)))
        assertTrue(IrohV2Recovery.stops(IrohV2ServerFailure("identity_mismatch", false)))
        assertTrue(IrohV2Recovery.stops(IrohV2HttpFailure(403, null)))
    }

    @Test fun exhaustedAuthenticationStopsButNetworkFailuresBackOff() {
        assertTrue(IrohV2Recovery.stops(IrohV2HttpFailure(401, null)))
        assertTrue(IrohV2Recovery.stops(IrohV2ServerFailure("ticket_expired", false)))
        assertFalse(IrohV2Recovery.stops(IrohV2Unavailable()))
        assertEquals(2000L, IrohV2Recovery.delayMillis(null, 0, 2000, 0))
        assertEquals(8000L, IrohV2Recovery.delayMillis(null, 2, 2000, 0))
        assertEquals(60_000L, IrohV2Recovery.delayMillis(null, 100, 2000, 0))
    }

    @Test fun serverDelayIsAFloorNotCappedByOrdinaryBackoff() {
        assertEquals(125_000L, IrohV2Recovery.delayMillis(IrohV2HttpFailure(429, "125"), 0, 2000, 0))
        assertEquals(95_000L, IrohV2Recovery.delayMillis(IrohV2ServerFailure("rate_limited", true, 95_000), 0, 2000, 0))
        assertEquals(60_000L, IrohV2Recovery.delayMillis(IrohV2HttpFailure(429, "invalid"), 0, 2000, 0))
        assertEquals(60_000L, IrohV2Recovery.delayMillis(IrohV2HttpFailure(429, "-1"), 0, 2000, 0))
    }

    @Test fun httpDateAndLargeRetryAfterCannotCauseImmediateRetryOrOverflow() {
        val now = java.time.Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
        assertEquals(120_000L, IrohV2Recovery.delayMillis(IrohV2HttpFailure(429, "Mon, 28 Sep 2026 12:02:00 GMT"), 0, 2000, now))
        assertTrue(IrohV2Recovery.delayMillis(IrohV2HttpFailure(429, Long.MAX_VALUE.toString()), 0, 2000, now) > 0)
    }
}
