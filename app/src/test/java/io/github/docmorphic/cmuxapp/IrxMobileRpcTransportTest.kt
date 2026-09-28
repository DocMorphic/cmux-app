package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IrxMobileRpcTransportTest {
    @Test fun closingDuringDialCancelsOnlyTheDialAndCannotPublishItLater() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val transport = IrxMobileRpcTransport(establish = {
            started.complete(Unit)
            awaitCancellation()
        }, permits = { true })
        val attempt = async { runCatching { transport.connect() } }
        withTimeout(2000) { started.await() }
        transport.close()
        assertTrue(withTimeout(1000) { attempt.await() }.exceptionOrNull() is CancellationException)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(runCatching { transport.connect() }.isFailure)
    }

    @Test fun nativeSubscriptionsOptIntoIndependentSurfaceEventLanes() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        val transport = object : MobileRpcTransport by wire { override val surfaceEventLanes = true }
        MobileRpcClient(transport, { "test-token" }).use { base ->
            base.connect()
            val lease = base.lease { }
            val request = async { lease.subscribe(listOf("terminal.render_grid")) }
            val sent = withTimeout(2000) { wire.sent.receive() }
            assertEquals("v1", sent.getJSONObject("params").getString("surface_event_lanes"))
            wire.answer(sent)
            request.await()
            lease.close()
        }
    }
}
