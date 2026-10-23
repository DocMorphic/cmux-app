package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSignInRecoveryTest {
    private fun refused() = NativeAccountRequestFailure("USER_EMAIL_ALREADY_EXISTS", true, "Fixture refusal")

    @Test fun typedWireRefusalRequiresTheOriginalCodeAndBooleanOrNumericDetail() {
        for (value in listOf<Any>(true, 1, 2, false, 0, "true", JSONObject.NULL)) {
            val body = JSONObject().put("details", JSONObject().put("would_work_if_email_was_verified", value))
            val failure = runCatching { nativeAccountResponse(400, "user_email_already_exists", body.toString()) }.exceptionOrNull()
            assertTrue(failure is NativeAccountRequestFailure)
            assertEquals(value == true || value == 1 || value == 2, (failure as NativeAccountRequestFailure).requiresEmailVerification)
        }
        val unrelated = runCatching { nativeAccountResponse(400, "OTHER_ERROR", "{\"details\":{\"would_work_if_email_was_verified\":true}}") }.exceptionOrNull()
        assertFalse((unrelated as NativeAccountRequestFailure).requiresEmailVerification)
    }

    @Test fun knownErrorOnHttpSuccessIsStillARefusalAndHtmlRateLimitIsTyped() {
        assertTrue(runCatching { nativeAccountResponse(200, "INVALID_OTP", "{}") }.exceptionOrNull() is NativeAccountRequestFailure)
        val rate = runCatching { nativeAccountResponse(429, null, "<html>busy</html>") }.exceptionOrNull() as NativeAccountRequestFailure
        assertEquals("RATE_LIMITED", rate.code); assertFalse(rate.requiresEmailVerification)
        assertEquals("nonce", nativeAccountResponse(200, null, "{\"nonce\":\"nonce\"}").getString("nonce"))
    }

    @Test fun bothRecoveryEndpointsSendOnlyNormalizedEmailAndAcceptAnySuccessfulStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"must_not_infer_paid\":true}"))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = NativeEmailRecoveryClient(server.url("/").toString())
            client.verification(" PERSON@Example.test "); client.billing(" PERSON@Example.test ")
            for (path in listOf("/api/auth/email-verification", "/api/billing/recover")) {
                val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals(path, request.path); assertEquals("POST", request.method)
                assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("x-stack-access-token"))
                val body = JSONObject(request.body.readUtf8()); assertEquals(setOf("email"), body.keys().asSequence().toSet())
                assertEquals("person@example.test", body.getString("email"))
            }
        }
    }

    @Test fun providerFailuresAreSafeAndRedirectDoesNotSendEmailToAnotherEndpoint() = runBlocking {
        MockWebServer().use { server ->
            val client = NativeEmailRecoveryClient(server.url("/").toString())
            for (status in listOf(429, 503, 302)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/unexpected"))
                    .setBody("private provider details"))
                val failure = runCatching { client.verification("person@example.test") }.exceptionOrNull() as NativeSignInRecoveryFailure
                assertEquals(if (status == 429) NativeSignInRecoveryError.RATE_LIMIT else NativeSignInRecoveryError.UNAVAILABLE, failure.reason)
                assertFalse(failure.message!!.contains("private"))
                assertEquals("/api/auth/email-verification", checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).path)
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun unavailableProviderLeavesRecoveryReachableAndRetryDoesNotAutomaticallyResend() = runTest {
        var sends = 0; var verification = 0; var billing = 0
        val model = NativeSignInSession(this, { sends++; throw refused() }, {}, requestVerification = {
            if (++verification == 1) throw java.io.IOException("private provider error")
        }, requestBilling = { billing++ })
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent()
            assertTrue(model.state.value.verificationRequired); assertFalse(model.state.value.codeSent)
            assertEquals(NativeSignInRecoveryError.UNAVAILABLE, model.state.value.recoveryError)
            model.resendVerification(); runCurrent(); assertNull(model.state.value.recoveryError)
            model.requestCode(); runCurrent()
            assertEquals(2, sends); assertEquals(2, verification)
            assertEquals(NativeSignInRecoveryError.PENDING, model.state.value.recoveryError)
            model.recoverBilling(); runCurrent(); assertEquals(1, billing)
            assertTrue(model.state.value.billingRecoveryAccepted); assertNull(model.state.value.completion)
            model.useDifferentEmail(); assertEquals(NativeSignInState(email = "person@example.test"), model.state.value)
        } finally { model.close() }
    }

    @Test fun verifiedRetryEntersCodeFlowAndOnlyAnActualExchangeCompletesSignIn() = runTest {
        var sends = 0; var emails = 0; var auth = 0
        val model = NativeSignInSession(this, { if (++sends == 1) throw refused() }, { auth++ }, requestVerification = { emails++ })
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent()
            assertEquals(1, emails); assertNull(model.state.value.completion)
            model.requestCode(); runCurrent(); assertTrue(model.state.value.codeSent); assertFalse(model.state.value.verificationRequired)
            model.editCode("ABC123"); model.submitCode(); runCurrent()
            assertEquals(1, auth); assertNotNull(model.state.value.completion); assertEquals("", model.state.value.email)
        } finally { model.close() }
    }

    @Test fun retiringAnUncooperativeVerificationCannotReplaceTheNewForm() = runTest {
        val gate = CompletableDeferred<Unit>(); var sends = 0
        val model = NativeSignInSession(this, { if (++sends == 1) throw refused() }, {}, requestVerification = {
            withContext(NonCancellable) { gate.await(); throw java.io.IOException("late") }
        })
        try {
            model.editEmail("old@example.test"); model.requestCode(); runCurrent(); assertTrue(model.state.value.busy)
            model.clear(); model.editEmail("new@example.test"); model.requestCode(); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals("new@example.test", model.state.value.email); assertTrue(model.state.value.codeSent)
            assertNull(model.state.value.error); assertNull(model.state.value.recoveryError); assertFalse(model.state.value.verificationRequired)
        } finally { gate.complete(Unit); model.close() }
    }

    @Test fun closePurgesRecoveryAndLateBillingSuccessCannotRestoreIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = NativeSignInSession(this, { throw refused() }, {}, requestVerification = {},
            requestBilling = { withContext(NonCancellable) { gate.await() } })
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.recoverBilling(); runCurrent()
            model.recoverBilling(); model.editEmail("busy@example.test"); assertEquals("person@example.test", model.state.value.email)
            model.close(); gate.complete(Unit); runCurrent(); assertEquals(NativeSignInState(), model.state.value)
        } finally { gate.complete(Unit); model.close() }
    }
}
