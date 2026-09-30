package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SimViewerLifecycleTest {
    private fun activate(model: SimViewerLifecycle) {
        assertEquals(SimViewerLifecycle.Action.None, model.handle(SimViewerLifecycle.Event.ACTIVATE))
        assertEquals(SimViewerLifecycle.Action.Open, model.handle(SimViewerLifecycle.Event.TRANSPORT_READY))
    }

    @Test fun outagesUseOneAttachPathAndBackoffResetsOnlyAfterPresentation() {
        val model = SimViewerLifecycle(); activate(model)
        for (delay in listOf(250L, 500L, 1000L, 2000L, 4000L, 4000L)) {
            model.handle(SimViewerLifecycle.Event.CONFIGURED)
            assertEquals(SimViewerLifecycle.Action.Retry(delay), model.handle(SimViewerLifecycle.Event.WEDGED))
            assertEquals(SimViewerLifecycle.Action.None, model.handle(SimViewerLifecycle.Event.TRANSPORT_READY))
            model.handle(SimViewerLifecycle.Event.RETRY_ELAPSED)
            assertEquals(SimViewerLifecycle.Action.Open, model.handle(SimViewerLifecycle.Event.TRANSPORT_READY))
        }
        model.handle(SimViewerLifecycle.Event.CONFIGURED); model.handle(SimViewerLifecycle.Event.PRESENTED)
        assertEquals(SimViewerLifecycle.Action.Retry(250), model.handle(SimViewerLifecycle.Event.TRANSPORT_LOST))
        assertEquals(SimViewerLifecycle.Action.None, model.handle(SimViewerLifecycle.Event.TRANSPORT_LOST))
    }

    @Test fun hostClosedDoesNotLoopAndExplicitRefreshCanRecoverIt() {
        val model = SimViewerLifecycle(); activate(model)
        assertEquals(SimViewerLifecycle.Action.Teardown, model.hostEnded(SimHostStatus.CLOSED, "Simulator stopped"))
        assertEquals("Simulator stopped", model.reason)
        for (event in listOf(SimViewerLifecycle.Event.RETRY_ELAPSED, SimViewerLifecycle.Event.TRANSPORT_READY, SimViewerLifecycle.Event.WEDGED))
            assertEquals(SimViewerLifecycle.Action.None, model.handle(event))
        assertEquals(SimViewerLifecycle.Phase.UNAVAILABLE, model.phase)
        assertEquals(SimViewerLifecycle.Action.Teardown, model.handle(SimViewerLifecycle.Event.REFRESH))
        assertNull(model.reason)
        assertEquals(SimViewerLifecycle.Action.Open, model.handle(SimViewerLifecycle.Event.TRANSPORT_READY))
    }

    @Test fun unavailableDeviceAndWorkerFailuresCanRecoverOnTheExistingLane() {
        for (configured in listOf(false, true)) {
            val model = SimViewerLifecycle(); activate(model)
            if (configured) model.handle(SimViewerLifecycle.Event.CONFIGURED)
            val phase = model.phase
            for (status in SimHostStatus.entries.filter { it != SimHostStatus.CLOSED }) {
                assertEquals(status.name, SimViewerLifecycle.Action.None, model.hostEnded(status, "host status"))
                assertEquals(phase, model.phase)
                assertNull(model.reason)
            }
            model.handle(SimViewerLifecycle.Event.CONFIGURED)
            model.handle(SimViewerLifecycle.Event.PRESENTED)
            assertEquals(SimViewerLifecycle.Phase.STREAMING, model.phase)
        }
    }

    @Test fun retiredAttachmentsCannotReplaceBackgroundOrRetryStateWithClosed() {
        for (event in listOf(SimViewerLifecycle.Event.BACKGROUND, SimViewerLifecycle.Event.WEDGED,
            SimViewerLifecycle.Event.REFRESH, SimViewerLifecycle.Event.DEACTIVATE)) {
            val model = SimViewerLifecycle(); activate(model)
            model.handle(event)
            val phase = model.phase
            assertEquals(SimViewerLifecycle.Action.None, model.hostEnded(SimHostStatus.CLOSED, "late close"))
            assertEquals(phase, model.phase)
            assertNull(model.reason)
        }
    }

    @Test fun backgroundAndDeactivationIgnoreStaleTimerAndReadySignals() {
        val model = SimViewerLifecycle(); activate(model)
        model.handle(SimViewerLifecycle.Event.WEDGED)
        assertEquals(SimViewerLifecycle.Action.Teardown, model.handle(SimViewerLifecycle.Event.BACKGROUND))
        model.handle(SimViewerLifecycle.Event.RETRY_ELAPSED); model.handle(SimViewerLifecycle.Event.TRANSPORT_READY)
        assertEquals(SimViewerLifecycle.Phase.BACKGROUNDED, model.phase)
        model.handle(SimViewerLifecycle.Event.FOREGROUND)
        assertEquals(SimViewerLifecycle.Action.Open, model.handle(SimViewerLifecycle.Event.TRANSPORT_READY))
        assertEquals(SimViewerLifecycle.Action.Teardown, model.handle(SimViewerLifecycle.Event.DEACTIVATE))
        assertEquals(SimViewerLifecycle.Action.None, model.hostEnded(SimHostStatus.CLOSED, "late"))
        model.handle(SimViewerLifecycle.Event.RETRY_ELAPSED); model.handle(SimViewerLifecycle.Event.TRANSPORT_READY)
        assertEquals(SimViewerLifecycle.Phase.STOPPED, model.phase)
    }
}
