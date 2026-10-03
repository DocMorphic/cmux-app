package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class MobileRpcEventSessionTest {
    @Test fun releasingLeaseCancelsIdleCollectorButPermitsItsScopedCleanupWithoutClosingSharedConnection() = runBlocking<Unit> {
        val transport = PoolTestTransport(); val base = MobileRpcClient(transport, { "test-token" })
        base.connect(); val lease = base.lease { }
        val started = CompletableDeferred<Unit>(); val calls = Collections.synchronizedList(mutableListOf<JSONObject>())
        val responder = launch {
            for (request in transport.sent) {
                calls += request; transport.answer(request)
            }
        }
        val operation = launch {
            lease.useEventSession { scoped ->
                try { started.complete(Unit); scoped.events.collect { } }
                finally { withContext(NonCancellable) { scoped.unsubscribe("owned-subscription") } }
            }
        }
        try {
            withTimeout(3000) { started.await() }
            lease.close(); withTimeout(3000) { operation.join() }
            assertTrue(operation.isCancelled); assertFalse(base.isClosed); assertEquals(0, transport.closes.get())
            assertEquals("mobile.events.unsubscribe", calls.single().getString("method"))
            assertEquals("owned-subscription", calls.single().getJSONObject("params").getString("stream_id"))
            assertEquals("test-token", calls.single().getJSONObject("auth").getString("stack_access_token"))
            withTimeout(3000) { base.request("mobile.workspace.list") }
            assertTrue(runCatching { lease.useEventSession { fail("Closed lease entered") } }.isFailure)
        } finally { operation.cancelAndJoin(); responder.cancelAndJoin(); lease.close(); base.close() }
    }

    @Test fun explicitBaseCloseAlsoCancelsEventSessionEvenWithoutDisconnectedNotification() = runBlocking<Unit> {
        val base = MobileRpcClient(PoolTestTransport(), { "test-token" }); base.connect()
        val started = CompletableDeferred<Unit>(); val cleaned = CompletableDeferred<Unit>()
        val operation = launch {
            base.useEventSession { client ->
                try { started.complete(Unit); client.events.collect { } }
                finally { cleaned.complete(Unit) }
            }
        }
        try {
            withTimeout(3000) { started.await() }; base.close()
            withTimeout(3000) { operation.join(); cleaned.await() }
            assertTrue(operation.isCancelled); assertTrue(base.isClosed)
        } finally { operation.cancelAndJoin(); base.close() }
    }
}
