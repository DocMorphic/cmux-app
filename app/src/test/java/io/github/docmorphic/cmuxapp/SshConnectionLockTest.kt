package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SshConnectionLockTest {
    @Test fun inlineHostPublicationAndConcurrentTransportGuardCannotInvertLocks() {
        val hosts = SshHostStore({ null }, {})
        val host = SshHostRecord(name = "Fixture", endpoint = SshEndpoint("fixture.invalid", username = "test"))
        hosts.upsert(host)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var valid: () -> Boolean
        val manager = SshConnections(hosts, owner, { true }) { id, _, current, _ ->
            valid = current
            object : SshManagedConnection {
                override val plan = hosts.dialPlan(id)
                override val disconnected = MutableStateFlow(false)
                override val isConnected get() = !disconnected.value
                override fun close() { disconnected.value = true }
            }
        }
        runBlocking { manager.open(host.id) }
        val finished = CountDownLatch(1)
        val checked = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        // Daemon threads ensure a regression fails with a bounded assertion instead of
        // trapping the test JVM in the exact deadlock captured on Android.
        val guard = Thread({ try { assertTrue(valid()) } catch (t: Throwable) { error.set(t) }
            finally { checked.countDown() } }, "ssh-transport-guard").apply { isDaemon = true }
        val publisher = Thread({
            try {
                synchronized(hosts) {
                    guard.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (guard.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, guard.state)
                    // StateFlow resumes the Unconfined observer inline, as Main.immediate
                    // does when markUsed is called by a completed transport handshake.
                    hosts.markUsed(host.id)
                }
            } catch (t: Throwable) { error.set(t) }
            finally { finished.countDown() }
        }, "ssh-host-publication").apply { isDaemon = true }
        publisher.start()
        val completed = finished.await(3, TimeUnit.SECONDS)
        if (completed) {
            assertTrue(checked.await(2, TimeUnit.SECONDS))
            manager.close(); owner.cancel()
        }
        assertTrue("Host publication deadlocked against the transport guard", completed)
        error.get()?.let { throw AssertionError("Concurrent SSH check failed", it) }
    }
}
