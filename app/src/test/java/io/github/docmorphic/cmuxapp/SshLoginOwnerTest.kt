package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshLoginOwnerTest {
    private class Resource(val allowed: () -> Boolean) : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }
    @Test fun tokenRefreshKeepsOwnerButNewLoginRetiresIt() = runTest {
        var login: String? = "first"
        val revisions = MutableStateFlow(0L)
        val loaded = mutableListOf<Resource>()
        val owner = SshLoginOwner(backgroundScope, revisions, { login }) { _, allowed -> Resource(allowed).also(loaded::add) }
        runCurrent()
        val first = owner.state.value.resource!!
        revisions.value++; runCurrent()
        assertSame(first, owner.state.value.resource)
        login = null; revisions.value++
        assertFalse(first.allowed()) // revocation does not wait for the collector
        runCurrent(); assertTrue(first.closed); assertNull(owner.state.value.resource)
        login = "second"; revisions.value++; runCurrent()
        assertEquals(2, loaded.size); assertNotSame(first, owner.state.value.resource)
        assertTrue(owner.state.value.resource!!.allowed())
        owner.close(); assertTrue(loaded.last().closed); assertFalse(loaded.last().allowed())
    }
    @Test fun corruptStoreStaysFailedUntilExplicitRetry() = runTest {
        val revisions = MutableStateFlow(0L)
        var attempts = 0
        val owner = SshLoginOwner(backgroundScope, revisions, { "login" }) { _, allowed ->
            if (++attempts == 1) error("corrupt metadata")
            Resource(allowed)
        }
        runCurrent(); assertTrue(owner.state.value.failed)
        revisions.value++; runCurrent(); assertEquals(1, attempts); assertTrue(owner.state.value.failed)
        owner.retry(); runCurrent(); assertEquals(2, attempts); assertNotNull(owner.state.value.resource)
        owner.close()
    }
    @Test fun lateLoadCannotPublishAcrossAccountChange() = runTest {
        var login: String? = "first"
        val revisions = MutableStateFlow(0L)
        val gate = CompletableDeferred<Unit>()
        var late: Resource? = null
        val owner = SshLoginOwner(backgroundScope, revisions, { login }) { _, allowed ->
            withContext(NonCancellable) { gate.await() }
            Resource(allowed).also { late = it }
        }
        runCurrent(); login = null; revisions.value++; runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(late!!.closed); assertFalse(late!!.allowed())
        assertNull(owner.state.value.resource); assertNull(owner.state.value.login)
        owner.close()
    }
    @Test fun parentCancellationClosesResourceAndAdmission() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val owner = SshLoginOwner(parent, MutableStateFlow(0L), { "login" }) { _, allowed -> Resource(allowed) }
        runCurrent(); val resource = owner.state.value.resource!!
        parent.cancel(); assertFalse(resource.allowed()); runCurrent()
        assertTrue(resource.closed); assertNull(owner.state.value.resource)
    }
}
