package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorTransitionsTest {
    @Test fun replacementWaitsForCancelledLegacyCleanupAndCancelledWaiterDoesNotBreakTheGate() = runTest {
        val cleanup = CompletableDeferred<Unit>(); val entered = mutableListOf<String>()
        val legacy = launch {
            SimulatorTransitions.use("mac", "panel") {
                try { entered += "legacy"; awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await(); entered += "stopped" } }
            }
        }
        runCurrent(); legacy.cancel(); runCurrent()
        val abandoned = launch { SimulatorTransitions.use("mac", "panel") { entered += "abandoned" } }
        val replacement = launch { SimulatorTransitions.use("mac", "panel") { entered += "v2" } }
        runCurrent(); abandoned.cancelAndJoin(); runCurrent()
        assertEquals(listOf("legacy"), entered)
        cleanup.complete(Unit); legacy.join(); replacement.join()
        assertEquals(listOf("legacy", "stopped", "v2"), entered)
        SimulatorTransitions.use("mac", "panel") { entered += "remount" }
        assertEquals("remount", entered.last())
    }

    @Test fun independentMacsAndPanelsDoNotWaitOnAnUnrelatedViewer() = runTest {
        val held = launch { SimulatorTransitions.use("mac1", "panel1") { awaitCancellation() } }; runCurrent()
        SimulatorTransitions.use("mac2", "panel1") { }
        SimulatorTransitions.use("mac1", "panel2") { }
        held.cancelAndJoin()
    }

    @Test fun borrowedLeasesSharePhysicalConnectionIdentityButDifferentConnectionsDoNot() {
        val base = MobileRpcClient(PoolTestTransport(), { "test-token" })
        val other = MobileRpcClient(PoolTestTransport(), { "test-token" })
        val a = base.lease {}; val b = base.lease {}
        try {
            assertEquals(a.simulatorConnectionId, b.simulatorConnectionId)
            assertEquals(base.simulatorConnectionId, a.simulatorConnectionId)
            assertNotEquals(other.simulatorConnectionId, a.simulatorConnectionId)
        } finally { a.close(); b.close(); base.close(); other.close() }
    }
}
