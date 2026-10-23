package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSignInSessionTest {
    @Test fun oneRetainedRequestOwnsTheFormAndCompletionIsConsumedOnce() = runTest {
        val codeGate = CompletableDeferred<Unit>(); val authGate = CompletableDeferred<Unit>()
        val emails = mutableListOf<String>(); val codes = mutableListOf<String>()
        val model = NativeSignInSession(this, { emails += it; codeGate.await() }, { codes += it; authGate.await() })
        try {
            model.editEmail("draft@example.test"); model.requestCode(); model.requestCode()
            model.editEmail("must-not-replace@example.test"); runCurrent()
            assertEquals(listOf("draft@example.test"), emails); assertTrue(model.state.value.busy)
            assertEquals("draft@example.test", model.state.value.email)
            codeGate.complete(Unit); runCurrent(); assertTrue(model.state.value.codeSent)
            model.editCode("A1 B2C3-more"); assertEquals("A1B2C3", model.state.value.code)
            model.submitCode(); model.submitCode(); runCurrent(); assertEquals(listOf("A1B2C3"), codes)
            authGate.complete(Unit); runCurrent()
            val completion = model.state.value.completion!!
            assertEquals("", model.state.value.code); assertEquals("", model.state.value.email)
            assertFalse(model.consumeCompletion(completion + 1)); assertTrue(model.consumeCompletion(completion))
            assertFalse(model.consumeCompletion(completion)); assertFalse(model.state.value.busy)
        } finally { model.close() }
    }
    @Test fun flowRetirementCancelsEvenAnUncooperativeOldResult() = runTest {
        val gate = CompletableDeferred<Unit>(); var cleared = 0; var started = 0
        val model = NativeSignInSession(this, { started++; withContext(NonCancellable) { gate.await() } }, {}, { cleared++ })
        try {
            model.editEmail("cancel@example.test"); model.requestCode(); runCurrent()
            assertEquals(1, started); val before = cleared
            model.clear(); assertTrue(cleared > before); assertEquals(NativeSignInState(), model.state.value)
            gate.complete(Unit); runCurrent()
            assertEquals(NativeSignInState(), model.state.value)
        } finally { model.close() }
    }
    @Test fun failedAuthenticationClearsTheCodeAndRequiresExplicitInputBeforeRetry() = runTest {
        var calls = 0
        val model = NativeSignInSession(this, {}, { if (++calls == 1) throw java.io.IOException("Fixture unavailable") })
        try {
            model.editEmail("retry@example.test"); model.requestCode(); runCurrent(); model.editCode("a1b2c3")
            model.submitCode(); runCurrent()
            assertEquals("Fixture unavailable", model.state.value.error); assertEquals("", model.state.value.code)
            assertTrue(model.state.value.codeSent); assertFalse(model.state.value.busy)
            model.submitCode(); runCurrent(); assertEquals("An empty code cannot replay the failed request", 1, calls)
            model.editCode("d4e5f6"); model.submitCode(); runCurrent()
            assertEquals(2, calls); assertNotNull(model.state.value.completion)
            model.close(); assertEquals(NativeSignInState(), model.state.value)
            model.editEmail("late@example.test"); model.requestCode(); model.submitCode(); runCurrent()
            assertEquals(NativeSignInState(), model.state.value); assertEquals(2, calls)
        } finally { model.close() }
    }
    @Test fun changingTheEmailInvalidatesThePreviousChallengeAndCode() = runTest {
        var cleared = 0; var signed = 0
        val model = NativeSignInSession(this, {}, { signed++ }, { cleared++ })
        try {
            model.editEmail("first@example.test"); model.requestCode(); runCurrent(); model.editCode("123456")
            val before = cleared; model.editEmail("second@example.test")
            assertTrue(cleared > before); assertEquals(NativeSignInState(email = "second@example.test"), model.state.value)
            model.submitCode(); runCurrent(); assertEquals(0, signed)
        } finally { model.close() }
    }
}
