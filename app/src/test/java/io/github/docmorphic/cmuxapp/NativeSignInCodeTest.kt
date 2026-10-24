package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSignInCodeTest {
    private fun tokens(value: String) = JSONObject().put("access_token", value).put("refresh_token", value+"-refresh")

    @Test fun normalizationAndCompletionMatch514ActualOriginalSwiftCases() {
        val root = listOf(File("."), File("..")).first { File(it, "tests/fixtures/signin-code-input-reference.json").isFile }
        val data = JSONObject(File(root, "tests/fixtures/signin-code-input-reference.json").readText())
        assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009", data.getString("upstream"))
        val prefix = "Packages/iOS/CmuxMobileWorkspace/Sources/CmuxMobileWorkspace/"
        assertEquals("7ac93e945b430aa7409e575a1f7e6169de5ba35cd0a41191cc7a54cf3dab148f", data.getJSONObject("sourceFiles").getString(prefix+"SignInCodeInputPolicy.swift"))
        assertEquals("0591b382aa7bac4b3f57618389e85c8f19f990cad3eb12e203bef04287e281bc", data.getJSONObject("sourceFiles").getString(prefix+"SignInCodeInputChangeAction.swift"))
        val digest = MessageDigest.getInstance("SHA-256").digest(File(root, "scripts/generate-signin-code-reference.py").readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(digest, data.getString("generatorSha256"))
        val cases = data.getJSONArray("cases"); assertEquals(514, cases.length())
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i); val normalized = nativeSignInCode(row.getString("input"))
            assertEquals("Original case $i", row.getString("normalized"), normalized)
            assertEquals("Original completion $i", row.getString("stableAction")=="verify", normalized.length==6)
        }
    }

    @Test fun sixthCharacterAutomaticallyExchangesNormalizedCodeAndOnlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>(); val codes = mutableListOf<String>(); val saved = mutableListOf<String>()
        val flow = NativeEmailSignIn({ path, body -> if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "Opaque-CASE")
            else { codes += body.getString("code"); gate.await(); tokens("new") } }) { access, _ -> saved += access }
        val model = NativeSignInSession(this, flow::sendCode, flow::signIn, flow::clear)
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent()
            model.editCode("ab c-12"); runCurrent(); assertEquals("ABC12", model.state.value.code); assertTrue(codes.isEmpty())
            model.editCode("ab c-123 trailing"); runCurrent()
            assertEquals(listOf("abc123Opaque-CASE"), codes); assertEquals("ABC123", model.state.value.code)
            assertTrue(model.state.value.busy); assertTrue(model.state.value.authenticating)
            model.submitCode(); model.editCode("ABC123"); runCurrent(); assertEquals(1, codes.size)
            gate.complete(Unit); runCurrent(); assertEquals(listOf("new"), saved); assertNotNull(model.state.value.completion)
        } finally { gate.complete(Unit); model.close() }
    }

    @Test fun partialEditingDuringVerificationDoesNotStartOrCancelAnExchange() = runTest {
        val gate = CompletableDeferred<Unit>(); val codes = mutableListOf<String>()
        val model = NativeSignInSession(this, {}, { codes += it; gate.await() })
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.editCode("abc123"); runCurrent()
            model.editCode("xy123"); runCurrent(); assertEquals("XY123", model.state.value.code)
            assertEquals(listOf("ABC123"), codes); assertTrue(model.state.value.busy)
            gate.complete(Unit); runCurrent(); assertNotNull(model.state.value.completion)
        } finally { gate.complete(Unit); model.close() }
    }

    @Test fun multipleReplacementsJoinTheOriginalUncooperativeExchangeBeforePublishingTheNewest() = runTest {
        val gate = CompletableDeferred<Unit>(); val codes = mutableListOf<String>(); val saved = mutableListOf<String>()
        val flow = NativeEmailSignIn({ path, body -> if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "Opaque") else {
            val code = body.getString("code"); codes += code
            if (codes.size==1) withContext(NonCancellable) { gate.await(); tokens("old") } else tokens("new")
        } }) { access, _ -> saved += access }
        val model = NativeSignInSession(this, flow::sendCode, flow::signIn, flow::clear)
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.editCode("aaaaaa"); runCurrent()
            model.editCode("bbbbbb"); runCurrent(); model.editCode("cccccc"); runCurrent()
            assertEquals(listOf("aaaaaaOpaque"), codes); assertEquals("CCCCCC", model.state.value.code)
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("aaaaaaOpaque", "ccccccOpaque"), codes); assertEquals(listOf("new"), saved)
            assertNotNull(model.state.value.completion)
        } finally { gate.complete(Unit); model.close() }
    }

    @Test fun timedOutWorkKeepsLaterExchangesWaitingAndCannotPublishAfterItsLateReturn() = runTest {
        val gate = CompletableDeferred<Unit>(); val codes = mutableListOf<String>(); val saved = mutableListOf<String>()
        val flow = NativeEmailSignIn({ path, body -> if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "Opaque") else {
            codes += body.getString("code")
            if (codes.size==1) withContext(NonCancellable) { gate.await(); tokens("old") } else tokens("new")
        } }) { access, _ -> saved += access }
        val model = NativeSignInSession(this, flow::sendCode, flow::signIn, flow::clear, verificationTimeoutMillis = 50)
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.editCode("aaaaaa"); runCurrent()
            advanceTimeBy(51); runCurrent(); assertEquals(NativeSignInError.TIMED_OUT, model.state.value.error); assertFalse(model.state.value.busy)
            model.editCode("bbbbbb"); runCurrent(); advanceTimeBy(51); runCurrent()
            assertEquals(1, codes.size); assertEquals(NativeSignInError.TIMED_OUT, model.state.value.error)
            gate.complete(Unit); runCurrent(); assertTrue(saved.isEmpty()); assertNull(model.state.value.completion)
            model.editCode("cccccc"); runCurrent(); assertEquals(listOf("aaaaaaOpaque", "ccccccOpaque"), codes)
            assertEquals(listOf("new"), saved); assertNotNull(model.state.value.completion)
        } finally { gate.complete(Unit); model.close() }
    }

    @Test fun closingAnAutomaticExchangePreventsLateCredentialPublication() = runTest {
        val gate = CompletableDeferred<Unit>(); var publications = 0
        val flow = NativeEmailSignIn({ path, _ -> if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "Opaque")
            else withContext(NonCancellable) { gate.await(); tokens("old") } }) { _, _ -> publications++ }
        val model = NativeSignInSession(this, flow::sendCode, flow::signIn, flow::clear)
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.editCode("ABC123"); runCurrent()
            model.close(); gate.complete(Unit); runCurrent(); assertEquals(0, publications); assertEquals(NativeSignInState(), model.state.value)
        } finally { gate.complete(Unit); model.close() }
    }

    @Test fun codeBackReturnsToBusyEmailEntryWithoutRepeatingOrCancellingTheAdmittedExchange() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0; var clears = 0
        val model = NativeSignInSession(this, {}, { calls++; gate.await() }, { clears++ })
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.editCode("ABC123"); runCurrent()
            val before = clears; model.returnFromCode(focusEmail = true); model.editEmail("edited@example.test")
            assertFalse(model.state.value.codeSent); assertTrue(model.state.value.busy); assertEquals("edited@example.test", model.state.value.email)
            assertFalse(model.consumeEmailFocus(checkNotNull(model.state.value.emailFocusRequest)))
            assertEquals(before, clears); assertEquals(1, calls)
            gate.complete(Unit); runCurrent(); assertNotNull(model.state.value.completion)
        } finally { gate.complete(Unit); model.close() }
    }

    @Test fun focusIsRequestedOnceForOrdinaryCodeEntryAndNotAfterVerificationRecovery() = runTest {
        var sends = 0
        val model = NativeSignInSession(this, { if (++sends==2) throw NativeAccountRequestFailure("USER_EMAIL_ALREADY_EXISTS", true, "Fixture") }, {}, requestVerification = {})
        try {
            model.editEmail("person@example.test"); model.requestCodeFromEmail(focused = true); runCurrent()
            val focus = checkNotNull(model.state.value.codeFocusRequest); assertTrue(model.consumeCodeFocus(focus)); assertFalse(model.consumeCodeFocus(focus))
            model.useDifferentEmail(); model.requestCode(); runCurrent(); assertTrue(model.state.value.verificationRequired)
            model.requestCode(); runCurrent(); assertTrue(model.state.value.codeSent); assertNull(model.state.value.codeFocusRequest)
        } finally { model.close() }
    }

    @Test fun requestingFromAnUnfocusedEmailDoesNotOpenTheCodeKeyboard() = runTest {
        val model = NativeSignInSession(this, {}, {})
        try {
            model.editEmail("person@example.test"); model.requestCodeFromEmail(focused = false); runCurrent()
            assertTrue(model.state.value.codeSent); assertNull(model.state.value.codeFocusRequest)
        } finally { model.close() }
    }

    @Test fun returningFromFocusedCodeDefersEmailFocusUntilTheAdmittedExchangeFails() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = NativeSignInSession(this, {}, { gate.await(); throw NativeAccountRequestFailure("INVALID_OTP", false, "Fixture") })
        try {
            model.editEmail("person@example.test"); model.requestCode(); runCurrent(); model.editCode("ABC123"); runCurrent()
            model.returnFromCode(focusEmail = true); val focus = checkNotNull(model.state.value.emailFocusRequest)
            assertFalse(model.consumeEmailFocus(focus)); gate.complete(Unit); runCurrent()
            assertFalse(model.state.value.codeSent); assertFalse(model.state.value.busy); assertEquals(NativeSignInError.INVALID_CODE, model.state.value.error)
            assertTrue(model.consumeEmailFocus(focus)); assertFalse(model.consumeEmailFocus(focus))
        } finally { gate.complete(Unit); model.close() }
    }
}
