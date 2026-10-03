package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeEmailSignInTest {
    private fun session() = JSONObject().put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")

    @Test fun emailCodeUsesReturnedNonceAndNormalizesOnlyVisiblePrefix() = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        var saved: Pair<String, String>? = null
        val flow = NativeEmailSignIn({ path, body ->
            requests += path to body
            if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "Opaque-CASE") else session()
        }) { access, refresh -> saved = access to refresh }
        flow.sendCode(" person@example.test ")
        flow.signIn(" AbC123 ")
        assertEquals("person@example.test", requests[0].second.getString("email"))
        assertEquals("https://cmux.com/auth/callback", requests[0].second.getString("callback_url"))
        assertEquals("/auth/otp/sign-in", requests[1].first)
        assertEquals("abc123Opaque-CASE", requests[1].second.getString("code"))
        assertEquals("fixture-access" to "fixture-refresh", saved)
        assertTrue(runCatching { flow.signIn("AbC123") }.isFailure)
        assertEquals(2, requests.size)
    }

    @Test fun missingNonceAndCodeWithoutRequestCannotExchangeCredentials() = runBlocking {
        var calls = 0
        val flow = NativeEmailSignIn({ _, _ -> calls++; JSONObject() }) { _, _ -> fail("Must not publish") }
        assertTrue(runCatching { flow.signIn("ABC123") }.isFailure)
        assertEquals(0, calls)
        assertTrue(runCatching { flow.sendCode("person@example.test") }.isFailure)
        assertTrue(runCatching { flow.signIn("ABC123") }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun wrongCodeCanRetrySameChallengeAndMalformedInputIsNotSent() = runBlocking {
        val codes = mutableListOf<String>()
        var saved = false
        val flow = NativeEmailSignIn({ path, body ->
            if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "Challenge")
            else {
                codes += body.getString("code")
                if (codes.size == 1) error("Invalid code")
                session()
            }
        }) { _, _ -> saved = true }
        flow.sendCode("person@example.test")
        assertTrue(runCatching { flow.signIn("bad") }.isFailure)
        assertTrue(runCatching { flow.signIn("AAAAAA") }.isFailure)
        flow.signIn("ABC123")
        assertEquals(listOf("aaaaaaChallenge", "abc123Challenge"), codes)
        assertTrue(saved)
    }

    @Test fun latestResendWinsAndLateEarlierResponseCannotRestoreOldChallenge() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sends = 0
        var submitted = ""
        val flow = NativeEmailSignIn({ path, body ->
            if (path.endsWith("send-sign-in-code")) {
                if (++sends == 1) { first.complete(Unit); release.await(); JSONObject().put("nonce", "old") }
                else JSONObject().put("nonce", "new")
            } else { submitted = body.getString("code"); session() }
        }) { _, _ -> }
        val old = async { runCatching { flow.sendCode("person@example.test") } }
        first.await()
        flow.sendCode("person@example.test")
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        flow.signIn("ABC123")
        assertEquals("abc123new", submitted)
    }

    @Test fun signOutRejectsLateExchangeWithoutSavingSession() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved = false
        val flow = NativeEmailSignIn({ path, _ ->
            if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "challenge")
            else { started.complete(Unit); release.await(); session() }
        }) { _, _ -> saved = true }
        flow.sendCode("person@example.test")
        val exchange = async { runCatching { flow.signIn("ABC123") } }
        started.await()
        flow.clear()
        release.complete(Unit)
        assertTrue(exchange.await().isFailure)
        assertFalse(saved)
    }

    @Test fun cancelledBlockingExchangeCannotSaveLateCredentials() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved = false
        val flow = NativeEmailSignIn({ path, _ ->
            if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "challenge")
            else withContext(NonCancellable) { started.complete(Unit); release.await(); session() }
        }) { _, _ -> saved = true }
        flow.sendCode("person@example.test")
        val exchange = launch { flow.signIn("ABC123") }
        started.await()
        exchange.cancel()
        release.complete(Unit)
        exchange.join()
        assertFalse(saved)
    }

    @Test fun conditionalSignOutRetiresOnlyTheMatchingSessionsChallenge() = runBlocking {
        for (matching in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var saved = false
            val flow = NativeEmailSignIn({ path, _ ->
                if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "challenge")
                else { started.complete(Unit); release.await(); session() }
            }) { _, _ -> saved = true }
            flow.sendCode("person@example.test")
            val exchange = async { runCatching { flow.signIn("ABC123") } }
            started.await()
            assertEquals(matching, flow.clearIf { matching })
            release.complete(Unit)
            assertEquals(matching, exchange.await().isFailure)
            assertEquals(!matching, saved)
        }
    }

    @Test fun sessionRetirementAndChallengeInvalidationExcludeConcurrentPublication() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val responseReturned = CompletableDeferred<Unit>()
        val retiring = CompletableDeferred<Unit>()
        val finishRetiring = java.util.concurrent.CountDownLatch(1)
        val published = CompletableDeferred<Unit>()
        val flow = NativeEmailSignIn({ path, _ ->
            if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "challenge")
            else { started.complete(Unit); response.await(); responseReturned.complete(Unit); session() }
        }) { _, _ -> published.complete(Unit) }
        flow.sendCode("person@example.test")
        val exchange = async(Dispatchers.IO) { runCatching { flow.signIn("ABC123") } }
        started.await()
        val signOut = async(Dispatchers.IO) { flow.clearIf {
            retiring.complete(Unit)
            check(finishRetiring.await(5, java.util.concurrent.TimeUnit.SECONDS))
            true
        } }
        try {
            retiring.await(); response.complete(Unit); responseReturned.await()
            assertNull(withTimeoutOrNull(150) { published.await() })
        } finally { finishRetiring.countDown() }
        assertTrue(signOut.await()); assertTrue(exchange.await().isFailure); assertFalse(published.isCompleted)
    }

    @Test fun incompleteSessionCannotBePublished() = runBlocking {
        val flow = NativeEmailSignIn({ path, _ ->
            if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "challenge")
            else JSONObject().put("access_token", "fixture-access")
        }) { _, _ -> fail("Must not publish incomplete session") }
        flow.sendCode("person@example.test")
        assertTrue(runCatching { flow.signIn("ABC123") }.isFailure)
    }
}
