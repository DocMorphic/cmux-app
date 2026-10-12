package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class NativeComputerDetailsLifetimeTest {
    private val owner = NativeTeamScope("fixture", "user", "original", 1)
    private val target = NativeComputerTarget("device", "default", "Original")

    @Test fun staleDetachAndUpdateCannotReplaceRecreatedViewCallbacks() {
        val lifetime = NativeComputerDetailsLifetime()
        val old = Any(); val fresh = Any(); var delivered = ""
        lifetime.attach(old, NativeComputerForgetCallbacks(started = { _, _, _ -> delivered = "old" }))
        lifetime.attach(fresh, NativeComputerForgetCallbacks(started = { _, _, _ -> delivered = "fresh" }))
        lifetime.detach(old); lifetime.update(old, NativeComputerForgetCallbacks(started = { _, _, _ -> delivered = "stale" }))
        lifetime.prepare(owner, target, emptyList()) { delivered = "fallback" }
        assertEquals("fresh", delivered)
        lifetime.detach(fresh)
        lifetime.prepare(owner, target, emptyList()) { delivered = "fallback" }
        assertEquals("fallback", delivered)
    }

    @Test fun confirmedActionSurvivesViewDetachAndOnlyNewViewReceivesCompletion() = runBlocking<Unit> {
        val lifetime = NativeComputerDetailsLifetime(); val entered = CompletableDeferred<Unit>(); val answer = CompletableDeferred<Unit>()
        var oldFinished = 0; var freshFinished = 0; var revokes = 0
        val flow = lifetime.forget { NativeComputerForgetFlow({ true }, { emptyList() }, {}, {
            revokes++; entered.complete(Unit); answer.await()
        }, {}) }
        val old = Any(); lifetime.attach(old, NativeComputerForgetCallbacks(finished = { oldFinished++ }))
        lifetime.confirm(this); entered.await(); lifetime.confirm(this)
        lifetime.detach(old)
        assertTrue(lifetime.canHost(false, false, owner.login, owner))
        assertFalse(lifetime.canHost(false, false, "another-login", owner))
        answer.complete(Unit)
        withTimeout(2000) { flow.state.first { it.finished } }
        assertFalse(lifetime.deliverFinished())
        assertTrue(lifetime.canHost(false, false, owner.login, owner))
        lifetime.attach(Any(), NativeComputerForgetCallbacks(finished = { freshFinished++ }))
        assertTrue(lifetime.deliverFinished()); assertFalse(lifetime.deliverFinished())
        assertEquals(0, oldFinished); assertEquals(1, freshFinished); assertEquals(1, revokes)
        lifetime.close()
    }

    @Test fun confirmedLocalCleanupRetryRetainsFlowAndNeverRepeatsRevoke() = runBlocking<Unit> {
        val lifetime = NativeComputerDetailsLifetime(); var cleanup = 0; var revokes = 0
        val flow = lifetime.forget { NativeComputerForgetFlow({ true }, { emptyList() }, {}, { revokes++ }, {
            if (++cleanup == 1) error("Fixture local save")
        }) }
        lifetime.confirm(this)
        withTimeout(2000) { flow.state.first { it.remoteConfirmed && !it.busy } }
        assertTrue(lifetime.canHost(false, false, owner.login, owner))
        assertSame(flow, lifetime.forget { error("Must not create another removal") })
        lifetime.confirm(this)
        withTimeout(2000) { flow.state.first { it.finished } }
        assertEquals(1, revokes); assertEquals(2, cleanup); lifetime.close()
    }

    @Test fun replacingPresentationCancelsItsActionAndDoesNotUseItsOldCallbacks() = runBlocking<Unit> {
        val state = NativeComputerDetailsState(); val first = NativeComputerDetailsPresentation(owner, target)
        state.value = first
        val entered = CompletableDeferred<Unit>(); var cleanup = false
        val flow = first.lifetime.forget { NativeComputerForgetFlow({ true }, { emptyList() }, {}, {
            entered.complete(Unit); awaitCancellation()
        }, { cleanup = true }) }
        first.lifetime.confirm(this); entered.await()
        val next = NativeComputerDetailsPresentation(owner.copy(teamId = "picker"), target)
        state.value = next
        withTimeout(2000) { flow.state.first { !it.busy } }
        assertSame(next, state.value); assertFalse(cleanup)
        assertFalse(first.lifetime.canHost(true, true, owner.login, owner))
        state.value = null
    }
}
