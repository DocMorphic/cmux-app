package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudMachineConnectionsTest {
    private class Session : AutoCloseable { var closes = 0; var retired = false; override fun close() { closes++ } }
    private class Api : CloudTerminalService {
        var attaches = 0
        override suspend fun attach(id: String, fingerprint: String, capabilities: List<String>): CloudAttachEndpoint {
            attaches++; return CloudAttachEndpoint("ws://fixture", id, null, true)
        }
        override suspend fun approve(id: String, invitationId: String) = error("trusted route needs no approval")
    }
    @Test fun attemptsAreLazySharedAndRetiredIndependently() = runTest {
        val api = Api(); val sessions = mutableListOf<Session>()
        val pool = CloudMachineConnections(this, api, "fingerprint", { true }, { Session().also(sessions::add) },
            StandardTestDispatcher(testScheduler), { it.retired = true })
        try {
            val one = pool.connection("one")!!; val two = pool.connection("two")!!
            assertSame(one, pool.connection("one")); runCurrent(); assertEquals(0, api.attaches)
            one.start(); two.start(); runCurrent(); assertEquals(2, api.attaches)
            val first = one.awaitSession(); val second = two.awaitSession()
            pool.retire(setOf("one"))
            assertTrue(first.retired); assertEquals(0, first.closes); assertFalse(second.retired)
            assertNotSame(one, pool.connection("one")); assertSame(two, pool.connection("two"))
            runCurrent(); assertEquals(1, first.closes)
        } finally { pool.close(); runCurrent() }
        assertTrue(sessions.all { it.retired && it.closes == 1 }); assertNull(pool.connection("later"))
    }
    @Test fun aStaleAccountCannotAcquireAnAttempt() = runTest {
        var current = true
        val pool = CloudMachineConnections(this, Api(), "fingerprint", { current }, { Session() }, StandardTestDispatcher(testScheduler))
        try {
            assertNotNull(pool.connection("one")); current = false; assertNull(pool.connection("two"))
        } finally { pool.close(); runCurrent() }
    }
    @Test fun failedInitialDialIsReplacedForTheNextCatalogRetry() = runTest {
        var attempts = 0
        val pool = CloudMachineConnections(this, Api(), "fingerprint", { true }, {
            if (++attempts == 1) error("transient dial failure") else Session()
        }, StandardTestDispatcher(testScheduler))
        try {
            val failed = pool.connection("one")!!; failed.start(); runCurrent()
            assertEquals(CloudLinkPhase.FAILED, failed.state.value.phase)
            val replacement = pool.connection("one")!!
            assertNotSame(failed, replacement); replacement.start(); runCurrent()
            assertEquals(CloudLinkPhase.READY, replacement.state.value.phase); assertEquals(2, attempts)
        } finally { pool.close(); runCurrent() }
    }
    @Test fun staleCatalogFailureCannotRetireAReplacementConnection() = runTest {
        val pool = CloudMachineConnections(this, Api(), "fingerprint", { true }, { Session() }, StandardTestDispatcher(testScheduler))
        try {
            val old = pool.connection("one")!!; old.start(); runCurrent()
            pool.retire(setOf("one")); runCurrent()
            val replacement = pool.connection("one")!!; replacement.start(); runCurrent()
            pool.retire("one", old); runCurrent()
            assertSame(replacement, pool.connection("one"))
            assertEquals(CloudLinkPhase.READY, replacement.state.value.phase)
        } finally { pool.close(); runCurrent() }
    }
}
