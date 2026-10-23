package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSignInErrorTest {
    private val privateText = "PRIVATE_PROVIDER_VALUE user@provider.invalid opaque-token"
    private fun provider(code: String, unverified: Boolean = false) = NativeAccountRequestFailure(code, unverified, privateText)

    @Test fun originalStackErrorGroupsKeepTheirSpecificDisplayRoles() {
        val groups = mapOf(
            NativeSignInError.INVALID_EMAIL to listOf("SCHEMA_ERROR"),
            NativeSignInError.EMAIL_EXISTS to listOf("USER_EMAIL_ALREADY_EXISTS"),
            NativeSignInError.INVALID_CODE to listOf("VERIFICATION_CODE_ERROR", "INVALID_OTP"),
            NativeSignInError.CODE_EXPIRED to listOf("OTP_EXPIRED"),
            NativeSignInError.RATE_LIMIT to listOf("RATE_LIMIT", "RATE_LIMITED"),
            NativeSignInError.WRONG_PASSWORD to listOf("EMAIL_PASSWORD_MISMATCH"),
            NativeSignInError.USER_NOT_FOUND to listOf("USER_NOT_FOUND"),
            NativeSignInError.PASSKEY_FAILED to listOf("PASSKEY_AUTHENTICATION_FAILED", "PASSKEY_WEBAUTHN_ERROR"),
            NativeSignInError.INVALID_MFA to listOf("INVALID_TOTP_CODE"),
            NativeSignInError.CONFIG to listOf("REDIRECT_URL_NOT_WHITELISTED", "INVALID_URL"),
            NativeSignInError.OAUTH_LINKED to listOf("OAUTH_PROVIDER_ACCOUNT_ID_ALREADY_USED_FOR_SIGN_IN"),
            NativeSignInError.APPLE_CONFIG to listOf("INVALID_APPLE_CREDENTIALS", "APPLE_SIGNIN_NOT_CONFIGURED",
                "APPLE_SIGNIN_NOT_HANDLED", "APPLE_SIGNIN_INVALID_RESPONSE", "APPLE_SIGNIN_FAILED", "APPLE_SIGNIN_NOT_INTERACTIVE", "APPLE_SIGNIN_ERROR"),
            NativeSignInError.BROWSER_SIGN_IN_FAILED to listOf("OAUTH_ERROR", "MISSING_CODE", "PARSE_ERROR", "INVALID_RESPONSE"),
            NativeSignInError.UNAUTHORIZED to listOf("UNAUTHORIZED", "INVALID_TOKEN", "TOKEN_EXPIRED"))
        for ((expected, codes) in groups) for (code in codes) {
            assertEquals(code, expected, nativeSignInError(provider(code)))
            assertEquals(code, expected, nativeSignInError(provider(code.lowercase(java.util.Locale.ROOT))))
        }
        assertEquals(NativeSignInError.EMAIL_REQUIRES_VERIFICATION, nativeSignInError(provider("USER_EMAIL_ALREADY_EXISTS", true)))
    }

    @Test fun unknownProviderAndLocalFailuresNeverBecomeProviderCopy() {
        assertEquals(NativeSignInError.SERVER_ERROR, nativeSignInError(provider("UNRECOGNIZED")))
        assertEquals(NativeSignInError.SERVER_ERROR, nativeSignInError(IllegalStateException(privateText)))
        assertEquals(NativeSignInError.NETWORK_ERROR, nativeSignInError(IOException(privateText)))
        assertEquals(NativeSignInError.NETWORK_ERROR, nativeSignInError(java.net.SocketTimeoutException(privateText)))
        assertNull(nativeSignInError(provider("OAUTH_CANCELLED")))
        assertNull(nativeSignInError(CancellationException(privateText)))
    }

    @Test fun anActivePhaseDeadlineShowsTimeoutWhileParentCancellationStaysSilent() = runTest {
        val failure = runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull() as Exception
        assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(failure))
        assertTrue(currentCoroutineContext().isActive)
        val model = NativeSignInSession(this, { withTimeout(1) { awaitCancellation() } }, {})
        try {
            model.editEmail("person@example.test"); model.requestCode(); advanceUntilIdle()
            assertEquals(NativeSignInError.TIMED_OUT, model.state.value.error); assertFalse(model.state.value.busy)
        } finally { model.close() }
    }

    @Test fun theRetainedFormKeepsOnlyTheDisplayRoleAndEditingRetiresIt() = runTest {
        val model = NativeSignInSession(this, { throw provider("UNRECOGNIZED") }, {})
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent()
            assertEquals(NativeSignInError.SERVER_ERROR, model.state.value.error)
            assertFalse(model.state.value.toString().contains(privateText)); assertFalse(model.state.value.busy)
            model.editEmail("other@example.test"); assertNull(model.state.value.error)
        } finally { model.close() }
    }

    @Test fun providerCancellationDoesNotClearTheEnteredCodeOrDisplayAnError() = runTest {
        var calls = 0
        val model = NativeSignInSession(this, {}, { if (++calls == 1) throw provider("OAUTH_CANCELLED") })
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.editCode("ABC123")
            model.submitCode(); runCurrent()
            assertEquals("ABC123", model.state.value.code); assertTrue(model.state.value.codeSent)
            assertNull(model.state.value.error); assertNull(model.state.value.completion); assertFalse(model.state.value.busy)
            model.submitCode(); runCurrent(); assertEquals(2, calls); assertNotNull(model.state.value.completion)
        } finally { model.close() }
    }

    @Test fun localValidationUsesTheOriginalRolesWithoutSendingAnInvalidRequest() = runTest {
        var requests = 0
        val flow = NativeEmailSignIn({ _, _ -> requests++; org.json.JSONObject().put("nonce", "fixture") }) { _, _ -> fail("No publication") }
        var failure = runCatching { flow.signIn("ABC123") }.exceptionOrNull() as Exception
        assertEquals(NativeSignInError.INVALID_CODE_SHORT, nativeSignInError(failure)); assertEquals(0, requests)
        failure = runCatching { flow.sendCode("invalid") }.exceptionOrNull() as Exception
        assertEquals(NativeSignInError.INVALID_EMAIL, nativeSignInError(failure)); assertEquals(0, requests)
        flow.sendCode("person@example.test")
        failure = runCatching { flow.signIn("BAD") }.exceptionOrNull() as Exception
        assertEquals(NativeSignInError.INVALID_CODE_SHORT, nativeSignInError(failure)); assertEquals(1, requests)
    }
}
