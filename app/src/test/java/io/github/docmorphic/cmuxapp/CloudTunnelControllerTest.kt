package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CloudTunnelControllerTest {
    private class Resource : AutoCloseable {
        val closes = AtomicInteger()
        var retired = false
        override fun close() { closes.incrementAndGet() }
    }
    @Test fun observersSeeAReplacementEvenWhenIntermediatePhasesAreConflated() = runTest {
        val controller = CloudTunnelController(this, { true }, { Resource() },
            nativeDispatcher = UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<CloudTunnelState>()
        try {
            controller.setWanted(true)
            backgroundScope.launch { controller.state.collect { seen += it } }
            runCurrent()
            val first = controller.resource()
            controller.setWanted(false); controller.setWanted(true)
            assertNotSame(first, controller.resource())
            runCurrent()
            assertEquals(2, seen.size)
            assertTrue(seen.all { it.phase == CloudTunnelPhase.READY })
            assertTrue(seen.last().generation > seen.first().generation)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun leaseStartsOnceAndRetiresSynchronouslyBeforeAsynchronousClose() = runTest {
        val resources = mutableListOf<Resource>()
        val controller = CloudTunnelController(this, { true }, { Resource().also(resources::add) },
            { it.retired = true }, StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); assertTrue(resources.isEmpty())
            controller.setWanted(true); controller.setWanted(true); runCurrent()
            val first = resources.single()
            assertSame(first, controller.resource()); assertEquals(CloudTunnelPhase.READY, controller.state.value.phase)
            controller.setWanted(false)
            assertTrue(first.retired); assertNull(controller.resource()); assertEquals(0, first.closes.get())
            runCurrent(); assertEquals(1, first.closes.get())
            controller.setWanted(true); runCurrent()
            assertEquals(2, resources.size); assertSame(resources.last(), controller.resource())
        } finally { controller.close(); runCurrent() }
        assertTrue(resources.all { it.closes.get() == 1 })
    }
    @Test fun failureSurvivesVisibilityChangesUntilExplicitRetry() = runTest {
        var attempts = 0
        val controller = CloudTunnelController(this, { true }, {
            attempts++; if (attempts == 1) throw IOException("offline") else Resource()
        }, nativeDispatcher = StandardTestDispatcher(testScheduler))
        try {
            controller.setWanted(true); runCurrent()
            assertEquals(CloudTunnelPhase.FAILED, controller.state.value.phase)
            controller.setWanted(false); controller.setWanted(true); runCurrent(); assertEquals(1, attempts)
            controller.retry(); runCurrent()
            assertEquals(2, attempts); assertEquals(CloudTunnelPhase.READY, controller.state.value.phase)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun startupTimeoutCancelsSuspendingEnrollment() = runTest {
        var allocations = 0
        val controller = CloudTunnelController(this, { true }, { delay(60_000); allocations++; Resource() },
            nativeDispatcher = StandardTestDispatcher(testScheduler))
        try {
            controller.setWanted(true); runCurrent(); advanceTimeBy(30_000); runCurrent()
            assertEquals(CloudTunnelPhase.FAILED, controller.state.value.phase)
            assertEquals("Cloud tunnel startup timed out", controller.state.value.failure?.detail)
            advanceTimeBy(60_000); runCurrent(); assertEquals(0, allocations)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun unexpectedEnrollmentCancellationOffersExplicitRetry() = runTest {
        var attempts = 0
        val controller = CloudTunnelController(this, { true }, {
            if (++attempts == 1) throw CancellationException("upstream request ended")
            Resource()
        }, nativeDispatcher = StandardTestDispatcher(testScheduler))
        try {
            controller.setWanted(true); runCurrent()
            assertEquals(CloudTunnelPhase.FAILED, controller.state.value.phase)
            controller.retry(); runCurrent()
            assertEquals(2, attempts); assertEquals(CloudTunnelPhase.READY, controller.state.value.phase)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun timeoutDoesNotWaitForBlockingNativeStartupAndDisposesItsLateHandle() = runTest {
        val workers = Executors.newSingleThreadExecutor(); val dispatcher = workers.asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val resource = Resource()
        val controller = CloudTunnelController(this, { true }, {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); resource
        }, nativeDispatcher = dispatcher)
        try {
            controller.setWanted(true); runCurrent(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(30_000); runCurrent()
            assertEquals(CloudTunnelPhase.FAILED, controller.state.value.phase)
            assertEquals(1, release.count); assertNull(controller.resource())
            release.countDown(); workers.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(1, resource.closes.get())
        } finally { release.countDown(); controller.close(); dispatcher.close(); runCurrent() }
    }
    @Test fun foregroundReplacementNeverAdoptsThePreviousGenerationsLateHandle() = runTest {
        val workers = Executors.newSingleThreadExecutor(); val dispatcher = workers.asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val old = Resource(); val new = Resource()
        val controller = CloudTunnelController(this, { true }, {
            if (calls.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); old } else new
        }, nativeDispatcher = dispatcher)
        try {
            controller.setWanted(true); runCurrent(); assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.setWanted(false); controller.setWanted(true)
            release.countDown(); workers.submit {}.get(5, TimeUnit.SECONDS)
            assertSame(new, controller.resource()); assertEquals(1, old.closes.get()); assertEquals(0, new.closes.get())
        } finally {
            release.countDown(); controller.close(); workers.submit {}.get(5, TimeUnit.SECONDS); dispatcher.close(); runCurrent()
        }
        assertEquals(1, new.closes.get())
    }
    @Test fun parentAccountCancellationRetiresAReadyTunnelAndPreventsRestart() = runTest {
        val account = Job(); val parent = CoroutineScope(StandardTestDispatcher(testScheduler) + account)
        val resource = Resource()
        val controller = CloudTunnelController(parent, { true }, { resource }, { it.retired = true }, StandardTestDispatcher(testScheduler))
        controller.setWanted(true); runCurrent(); account.cancel(); runCurrent()
        assertEquals(CloudTunnelPhase.CLOSED, controller.state.value.phase)
        assertTrue(resource.retired); assertEquals(1, resource.closes.get())
        controller.setWanted(true); controller.retry(); runCurrent(); assertNull(controller.resource())
    }
    @Test fun unavailableNativeRuntimeIsRecoverableWithoutAnAutomaticRetryLoop() = runTest {
        val controller = CloudTunnelController<Resource>(this, { true }, { throw UnsatisfiedLinkError("detail") },
            nativeDispatcher = StandardTestDispatcher(testScheduler))
        try {
            controller.setWanted(true); runCurrent()
            assertEquals(CloudTunnelPhase.FAILED, controller.state.value.phase)
            assertEquals("Cloud native runtime is unavailable", controller.state.value.failure?.detail)
        } finally { controller.close(); runCurrent() }
    }
    @Test fun daemonIdentityScopeSurvivesLoginAndGenerationButSeparatesUsersAndTeams() {
        val root = File("/private/app")
        val owner = NativeTeamScope("login", "user", "team", 1)
        val first = cloudDaemonStateDirectory(root, owner)
        assertEquals(first, cloudDaemonStateDirectory(root, owner.copy(login = "replacement", generation = 2)))
        assertNotEquals(first, cloudDaemonStateDirectory(root, owner.copy(userId = "another")))
        assertNotEquals(first, cloudDaemonStateDirectory(root, owner.copy(teamId = "another")))
        assertEquals(root, first.parentFile); assertFalse(first.name.contains("user"))
    }
}
