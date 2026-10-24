package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeOAuthSessionTest {
    @Test fun providerBusyIsExclusiveAndSuccessfulCompletionIsConsumedOnce() = runTest {
        val gate = CompletableDeferred<Unit>(); val calls = mutableListOf<NativeOAuthProvider>()
        val owner = NativeSignInSession(this, { error("No email request") }, {}, authenticateProvider = { calls += it; gate.await() })
        try {
            owner.signInProvider(NativeOAuthProvider.GOOGLE); runCurrent(); owner.signInProvider(NativeOAuthProvider.GITHUB)
            owner.editEmail("edited@example.test"); owner.requestCode(); runCurrent()
            assertEquals(listOf(NativeOAuthProvider.GOOGLE), calls); assertTrue(owner.state.value.busy)
            assertEquals(NativeOAuthProvider.GOOGLE, owner.state.value.provider); assertEquals("edited@example.test", owner.state.value.email)
            gate.complete(Unit); runCurrent(); val completion = owner.state.value.completion!!
            assertTrue(owner.consumeCompletion(completion)); assertFalse(owner.consumeCompletion(completion)); assertFalse(owner.state.value.busy)
        } finally { gate.complete(Unit); owner.close() }
    }
    @Test fun cancellationAndFailureReleaseProviderProgressUsingExistingSafeRoles() = runTest {
        var calls = 0
        val owner = NativeSignInSession(this, {}, {}, authenticateProvider = {
            if (++calls == 1) throw NativeAccountRequestFailure("OAUTH_CANCELLED", false, "private")
            else throw NativeAccountRequestFailure("OAUTH_ERROR", false, "private")
        })
        try {
            owner.signInProvider(NativeOAuthProvider.APPLE); runCurrent(); assertNull(owner.state.value.error); assertFalse(owner.state.value.busy)
            owner.signInProvider(NativeOAuthProvider.APPLE); runCurrent(); assertEquals(NativeSignInError.BROWSER_SIGN_IN_FAILED, owner.state.value.error)
            assertNull(owner.state.value.provider); assertNull(owner.state.value.completion)
        } finally { owner.close() }
    }
    @Test fun interactiveDeadlineCancelsLateWorkAndLeavesARetryableForm() = runTest {
        val gate = CompletableDeferred<Unit>(); var published = 0
        val flow = NativeOAuthSignIn({ _, _ -> "https://provider.example/" }, { _, _ -> "callback" },
            { _, _ -> withContext(NonCancellable) { gate.await(); "late" to "refresh" } }, { _, _ -> published++ })
        val owner = NativeSignInSession(this, {}, {}, authenticateProvider = flow::signIn, interactiveTimeoutMillis = 50)
        try {
            owner.signInProvider(NativeOAuthProvider.GITHUB); runCurrent(); advanceTimeBy(51); runCurrent()
            assertFalse(owner.state.value.busy); assertEquals(NativeSignInError.TIMED_OUT, owner.state.value.error)
            gate.complete(Unit); runCurrent(); assertEquals(0, published); assertNull(owner.state.value.completion)
        } finally { gate.complete(Unit); owner.close() }
    }
}
