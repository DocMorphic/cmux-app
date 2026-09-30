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

    @Test fun deviceUnavailableDoesNotLoopAndExplicitRefreshCanRecoverIt() {
        val model = SimViewerLifecycle(); activate(model)
        assertEquals(SimViewerLifecycle.Action.Teardown, model.hostEnded(SimHostStatus.DEVICE_UNAVAILABLE, "Simulator stopped"))
        assertEquals("Simulator stopped", model.reason)
        for (event in listOf(SimViewerLifecycle.Event.RETRY_ELAPSED, SimViewerLifecycle.Event.TRANSPORT_READY, SimViewerLifecycle.Event.WEDGED))
            assertEquals(SimViewerLifecycle.Action.None, model.handle(event))
        assertEquals(SimViewerLifecycle.Phase.UNAVAILABLE, model.phase)
        assertEquals(SimViewerLifecycle.Action.Teardown, model.handle(SimViewerLifecycle.Event.REFRESH))
        assertNull(model.reason)
        assertEquals(SimViewerLifecycle.Action.Open, model.handle(SimViewerLifecycle.Event.TRANSPORT_READY))
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
