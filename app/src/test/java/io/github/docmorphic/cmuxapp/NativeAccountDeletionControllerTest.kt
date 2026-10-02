package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAccountDeletionControllerTest {
    private class Store {
        var value = JSONObject().put("task_session", "login").put("refresh_token", "fixture-refresh")
        var fail = false
        @Synchronized fun load() = JSONObject(value.toString())
        @Synchronized fun update(block: (JSONObject) -> Unit) {
            if (fail) error("fixture storage failure")
            val next = load(); block(next); NativeAccountDeletionRecord.prune(next); value = next
        }
    }
    private suspend fun NativeAccountDeletionController.done() = withTimeout(3000) {
        state.first { it != null && it.result != NativeAccountDeletionResult.PROCESSING }!!
    }

    @Test fun persistsBeforeSendingAndMultipleOwnersCannotDuplicateAnInFlightDeletion() = runBlocking<Unit> {
        val store = Store(); val reply = CompletableDeferred<NativeAccountDeletionResult>(); var calls = 0
        val controller = NativeAccountDeletionController(this, store::load, store::update) { owner ->
            assertEquals("login", owner)
            assertEquals(NativeAccountDeletionResult.PROCESSING, NativeAccountDeletionRecord.read(store.load())!!.result)
            calls++; reply.await()
        }
        assertTrue(controller.begin("login")); assertFalse(controller.begin("login"))
        yield(); controller.reconcile()
        assertEquals(NativeAccountDeletionResult.PROCESSING, controller.state.value!!.result)
        reply.complete(NativeAccountDeletionResult.COMPLETED)
        val completed = controller.done(); assertEquals(1, calls)
        assertTrue(completed.result.signsOut)
        assertFalse(controller.begin("login")) // Receipt must be handled before another confirmation.
        val restored = NativeAccountDeletionController(this, store::load, store::update) { error("No resend") }
        assertEquals(completed, restored.state.value)
    }

    @Test fun interruptedProcessRestoresUncertaintyAndOnlyFreshConfirmationCanRetry() = runBlocking<Unit> {
        val store = Store(); val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val old = NativeAccountDeletionController(oldScope, store::load, store::update) { calls++; awaitCancellation() }
        old.begin("login"); assertEquals(1, calls)
        // Capture the durable state while the original process is still sending.
        val afterKill = Store().also { it.value = store.load() }
        oldScope.cancel()
        val restored = NativeAccountDeletionController(this, afterKill::load, afterKill::update) { calls++; NativeAccountDeletionResult.COMPLETED }
        val unknown = restored.state.value!!
        assertEquals(NativeAccountDeletionResult.UNKNOWN, unknown.result)
        assertFalse(restored.begin("login")); assertEquals(1, calls)
        assertTrue(restored.acknowledge(unknown)); assertEquals(1, calls)
        assertTrue(restored.begin("login")); assertEquals(NativeAccountDeletionResult.COMPLETED, restored.done().result)
        assertEquals(2, calls)
    }

    @Test fun cancellationPersistsUncertaintyWithoutResending() = runBlocking<Unit> {
        val store = Store(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = NativeAccountDeletionController(scope, store::load, store::update) { awaitCancellation() }
        controller.begin("login"); scope.cancel()
        assertEquals(NativeAccountDeletionResult.UNKNOWN, controller.done().result)
        assertEquals(NativeAccountDeletionResult.UNKNOWN, NativeAccountDeletionRecord.read(store.load())!!.result)
    }

    @Test fun accountReplacementDropsReceiptAndLateCompletionCannotRecreateIt() = runBlocking<Unit> {
        val store = Store(); val response = CompletableDeferred<NativeAccountDeletionResult>()
        val controller = NativeAccountDeletionController(this, store::load, store::update) {
            withContext(NonCancellable) { response.await() }
        }
        controller.begin("login"); yield()
        store.update { it.put("task_session", "replacement") }
        controller.reconcile(); assertNull(controller.state.value)
        response.complete(NativeAccountDeletionResult.COMPLETED); yield()
        assertNull(NativeAccountDeletionRecord.read(store.load())); assertNull(controller.state.value)
        assertEquals("replacement", store.load().getString("task_session"))
    }

    @Test fun storageFailurePreventsDeleteAndFailedSettlementRestoresUnknown() = runBlocking<Unit> {
        val store = Store(); var calls = 0
        val controller = NativeAccountDeletionController(this, store::load, store::update) {
            calls++; store.fail = true; NativeAccountDeletionResult.COMPLETED
        }
        store.fail = true
        assertFalse(controller.begin("login")); assertEquals(0, calls)
        assertEquals(NativeAccountDeletionResult.STORAGE, controller.state.value!!.result)
        assertFalse(controller.acknowledge(controller.state.value!!))
        store.fail = false; assertTrue(controller.acknowledge(controller.state.value!!))
        assertTrue(controller.begin("login")); assertEquals(NativeAccountDeletionResult.UNKNOWN, controller.done().result)
        assertEquals(1, calls)
        assertEquals(NativeAccountDeletionResult.PROCESSING, NativeAccountDeletionRecord.read(store.load())!!.result)
    }

    @Test fun distinctBackendResultsSurviveRestartAndOldAcknowledgementsCannotEraseNewResults() = runBlocking<Unit> {
        for (result in listOf(NativeAccountDeletionResult.PARTIAL, NativeAccountDeletionResult.CLEANUP_INCOMPLETE,
            NativeAccountDeletionResult.UNAUTHORIZED, NativeAccountDeletionResult.UNKNOWN)) {
            val store = Store()
            val controller = NativeAccountDeletionController(this, store::load, store::update) { result }
            controller.begin("login"); val first = controller.done()
            val next = NativeAccountDeletionController(this, store::load, store::update) { result }
            assertEquals(first, next.state.value); assertTrue(next.acknowledge(first))
            next.begin("login"); val second = next.done()
            assertFalse(next.acknowledge(first)); assertEquals(second, next.state.value)
            assertEquals(second, NativeAccountDeletionRecord.read(store.load()))
        }
    }
}
