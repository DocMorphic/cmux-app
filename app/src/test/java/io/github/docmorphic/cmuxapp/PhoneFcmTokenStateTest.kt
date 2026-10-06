package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneFcmTokenStateTest {
    private val project = PhoneFcmProject("fixture-project", "fixture-app", "123456")
    private fun restored(raw: JSONObject) = PhoneFcmTokenState(JSONObject(raw.toString()))

    @Test fun noConsentOrMissingConfigurationDoesNotContactProvider() = runTest {
        val raw = JSONObject(); var calls = 0
        val sdk = object : PhoneFcmTokenProvider {
            override val project = this@PhoneFcmTokenStateTest.project
            override suspend fun token(): String { calls++; return "fixture-token" }
            override suspend fun delete() { calls++ }
        }
        assertFalse(PhoneFcmTokenReconciler({ it(PhoneFcmTokenState(raw)) }, { "login" }, { true }, sdk).runPass())
        assertEquals(0, calls)
        PhoneFcmTokenState(raw).authorize("login", project)
        assertFalse(PhoneFcmTokenReconciler({ it(PhoneFcmTokenState(raw)) }, { "login" }, { true }, null).runPass())
        assertNull(restored(raw).grant)
    }
    @Test fun tokenAndStableRevisionSurviveReconstructionButRotationGetsNewRevision() {
        val raw = JSONObject(); val state = PhoneFcmTokenState(raw)
        val grant = state.authorize("login", project)
        assertTrue(state.beginFetch(grant)); assertTrue(state.accept(grant, "first"))
        val first = state.snapshot!!
        assertEquals(first, restored(raw).snapshot)
        assertEquals(grant, state.authorize("login", project))
        assertTrue(state.accept(grant, "first")); assertEquals(first, state.snapshot)
        assertTrue(state.accept(grant, "second")); assertNotEquals(first.revision, state.snapshot!!.revision)
        assertEquals("second", restored(raw).snapshot!!.token)
    }
    @Test fun logoutRetainsDeletionWithoutRetainingTokenOrAccount() {
        val raw = JSONObject(); val state = PhoneFcmTokenState(raw)
        val grant = state.authorize("private-login", project); state.beginFetch(grant); state.accept(grant, "private-token")
        state.reconcile(null, true, project)
        assertNull(state.snapshot); assertNotNull(restored(raw).deletion)
        assertFalse(raw.toString().contains("private-login")); assertFalse(raw.toString().contains("private-token"))
        assertFalse(state.accept(grant, "stale"))
        assertFalse(state.deleted("different")); assertTrue(state.deleted(state.deletion!!)); assertFalse(state.hasWork)
    }
    @Test fun tokenCallbackFencesFetchAndRequiresFreshSdkRead() {
        val state = PhoneFcmTokenState(JSONObject()); val old = state.authorize("login", project)
        state.beginFetch(old); state.accept(old, "old"); state.invalidate()
        assertNull(state.snapshot); assertNotEquals(old, state.grant)
        assertFalse(state.accept(old, "late"))
        assertTrue(state.beginFetch(state.grant!!)); assertTrue(state.accept(state.grant!!, "current"))
    }
    @Test fun accountReplacementDeletesOldProviderTokenBeforeFetchingReplacement() = runTest {
        val raw = JSONObject(); val state = PhoneFcmTokenState(raw)
        val previous = state.authorize("old-login", project); state.beginFetch(previous)
        val next = state.authorize("new-login", project)
        assertFalse(state.beginFetch(next)); assertNotNull(state.deletion)
        val calls = mutableListOf<String>()
        val sdk = object : PhoneFcmTokenProvider {
            override val project = this@PhoneFcmTokenStateTest.project
            override suspend fun token(): String { calls += "fetch"; return "new-token" }
            override suspend fun delete() { calls += "delete" }
        }
        assertFalse(PhoneFcmTokenReconciler({ it(state) }, { "new-login" }, { true }, sdk).runPass())
        assertEquals(listOf("delete", "fetch"), calls); assertEquals(next, state.snapshot!!.grant)
    }
    @Test fun revocationDuringFetchDiscardsResultAndDeletesBeforeSettling() = runTest {
        val state = PhoneFcmTokenState(JSONObject()); state.authorize("login", project)
        val fetched = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var allowed = true; var deleted = 0
        val sdk = object : PhoneFcmTokenProvider {
            override val project = this@PhoneFcmTokenStateTest.project
            override suspend fun token(): String { fetched.complete(Unit); release.await(); return "late-token" }
            override suspend fun delete() { deleted++ }
        }
        val task = async { PhoneFcmTokenReconciler({ it(state) }, { "login" }, { allowed }, sdk).runPass() }
        fetched.await(); allowed = false; release.complete(Unit)
        assertFalse(task.await()); assertEquals(1, deleted); assertNull(state.snapshot); assertFalse(state.hasWork)
    }
    @Test fun delayedCallbackDuringFetchCannotRestoreAnOldToken() = runTest {
        val state = PhoneFcmTokenState(JSONObject()); state.authorize("login", project); var reads = 0
        val sdk = object : PhoneFcmTokenProvider {
            override val project = this@PhoneFcmTokenStateTest.project
            override suspend fun token(): String {
                reads++
                if (reads == 1) { state.invalidate(); return "obsolete" }
                return "latest"
            }
            override suspend fun delete() { error("Unexpected deletion") }
        }
        assertFalse(PhoneFcmTokenReconciler({ it(state) }, { "login" }, { true }, sdk).runPass())
        assertEquals(2, reads); assertEquals("latest", state.snapshot!!.token)
    }
    @Test fun failedFetchLeavesDurableCleanupAndFailedDeleteRemainsRetryable() = runTest {
        val raw = JSONObject(); val state = PhoneFcmTokenState(raw); state.authorize("login", project)
        val sdk = object : PhoneFcmTokenProvider {
            override val project = this@PhoneFcmTokenStateTest.project
            override suspend fun token(): String = error("Offline fixture")
            override suspend fun delete() { error("Offline fixture") }
        }
        try { PhoneFcmTokenReconciler({ it(state) }, { "login" }, { true }, sdk).runPass(); fail() } catch (_: IllegalStateException) {}
        state.revoke(); val id = restored(raw).deletion; assertNotNull(id)
        try { PhoneFcmTokenReconciler({ it(state) }, { null }, { false }, sdk).runPass(); fail() } catch (_: IllegalStateException) {}
        assertEquals(id, restored(raw).deletion)
    }
    @Test fun projectMismatchNeverDeletesAnotherProjectsToken() = runTest {
        val state = PhoneFcmTokenState(JSONObject()); val owner = state.authorize("login", project); state.beginFetch(owner)
        val different = PhoneFcmProject("other", "other-app", "654321")
        val sdk = object : PhoneFcmTokenProvider {
            override val project = different
            override suspend fun token(): String = error("Wrong project")
            override suspend fun delete() { error("Wrong project") }
        }
        assertFalse(PhoneFcmTokenReconciler({ it(state) }, { "login" }, { true }, sdk).runPass())
        assertNotNull(state.deletion); assertNull(state.grant)
        assertThrows(IllegalStateException::class.java) { state.authorize("login", different) }
    }
    @Test fun reenableDuringDeletionGetsFreshTokenAndMalformedTokensAreRejected() = runTest {
        val state = PhoneFcmTokenState(JSONObject()); val old = state.authorize("login", project); state.beginFetch(old); state.revoke()
        val sdk = object : PhoneFcmTokenProvider {
            override val project = this@PhoneFcmTokenStateTest.project
            override suspend fun token() = "fresh"
            override suspend fun delete() { state.authorize("login", project) }
        }
        assertFalse(PhoneFcmTokenReconciler({ it(state) }, { "login" }, { true }, sdk).runPass())
        assertEquals("fresh", state.snapshot!!.token)
        for (invalid in listOf("", "has space", "has\nnewline", "a".repeat(4097)))
            assertThrows(IllegalArgumentException::class.java) { state.accept(state.grant!!, invalid) }
        assertFalse(state.accept(old, "obsolete"))
    }
}
