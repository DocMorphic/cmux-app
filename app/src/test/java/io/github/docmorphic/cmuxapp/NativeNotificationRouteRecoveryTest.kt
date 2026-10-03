package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeNotificationRouteRecoveryTest {
    private data class Client(val name: String)
    @Test fun retryRejectsTheOldConnectionEvenWhenAnotherClientComparesEqual() {
        val old = Client("Mac"); val next = Client("Mac")
        val failed = NativeNotificationRouteRecovery().failed(old, "offline")
        assertFalse(failed.allows(old)); assertTrue(failed.allows(next))
        val retry = failed.retry(old)
        assertFalse(retry.allows(old)); assertTrue(retry.allows(next))
        assertEquals(1, retry.retryGeneration); assertEquals(retry, retry.retry(old))
    }
    @Test fun failureAndAutomaticRecoveryDoNotConsumeTheRoute() {
        val old = Any(); val next = Any()
        val failed = NativeNotificationRouteRecovery().failed(old, "unavailable")
        assertTrue(failed.allows(next))
        val resolving = failed.resolving()
        assertEquals(NativeNotificationRouteRecovery.Phase.RESOLVING, resolving.phase)
        assertNull(resolving.message)
        assertFalse(resolving.complete().allows(next))
    }
    @Test fun retryWithoutACurrentClientRetainsThePreviousConnectionFence() {
        val old = Any()
        val retry = NativeNotificationRouteRecovery().failed(old, "offline").retry(null)
        assertFalse(retry.allows(old)); assertTrue(retry.allows(Any()))
        assertEquals(NativeNotificationRouteRecovery.Phase.RECONNECTING, retry.phase)
    }
}
