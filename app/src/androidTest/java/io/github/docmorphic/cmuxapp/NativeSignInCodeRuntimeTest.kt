package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual focused code field, platform clipboard paste and admitted owner; fixture account only. */
class NativeSignInCodeRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val folder get() = File(compose.activity.getExternalFilesDir(null), "signin-code").apply { mkdirs() }
    private var owner: CoroutineScope? = null
    private var model: NativeSignInSession? = null
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var clipboard: ClipboardManager? = null
    private var previousClip: ClipData? = null
    private val completions = AtomicInteger()
    private val publications = mutableListOf<String>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() {
        instrumentation.runOnMainSync {
            model?.close(); owner?.cancel(); gates.forEach { it.complete(Unit) }
            clipboard?.let { manager -> previousClip?.let(manager::setPrimaryClip) ?: manager.clearPrimaryClip() }
        }
        compose.activity.finish()
    }
    private fun tokens(value: String) = JSONObject().put("access_token", value).put("refresh_token", value+"-refresh")
    private fun show(timeout: Long = 60_000, exchange: suspend (JSONObject) -> JSONObject) {
        val flow = NativeEmailSignIn({ path, body -> if (path.endsWith("send-sign-in-code")) JSONObject().put("nonce", "Opaque-CASE")
            else exchange(body) }) { access, _ -> publications += access }
        instrumentation.runOnMainSync {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            model = NativeSignInSession(owner!!, flow::sendCode, flow::signIn, flow::clear, verificationTimeoutMillis = timeout)
        }
        compose.setContent { CmuxTheme(darkTheme = true) { Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) {
            NativeSignIn(flow::sendCode, flow::signIn, {}, {}, onSignedIn = { completions.incrementAndGet() }, retained = model)
        } } } }
        compose.onNodeWithTag("signin.email").performTextInput("person@example.test")
        compose.onNodeWithTag("signin.request").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("signin.code").assertIsFocused(); true }.getOrDefault(false) }
        compose.onNodeWithTag("signin.code").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.SmsOtpCode))
        assertNull(model!!.state.value.codeFocusRequest)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "$name.png")))
    }

    @Test fun clipboardPasteAndBusyReplacementsAutomaticallyPublishOnlyTheNewestCode() {
        val gate = CompletableDeferred<Unit>().also { gates += it }
        val calls = AtomicInteger(); val codes = mutableListOf<String>()
        show { body ->
            codes += body.getString("code")
            if (calls.incrementAndGet()==1) withContext(NonCancellable) { gate.await(); tokens("old") } else tokens("new")
        }
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle(2_000)
        File(folder, "focused-code-ime.txt").writeText(device.executeShellCommand("dumpsys input_method"))
        instrumentation.runOnMainSync {
            clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            previousClip = clipboard!!.primaryClip
            clipboard!!.setPrimaryClip(ClipData.newPlainText("Fixture code", " ab-c1234 \n"))
        }
        compose.onNodeWithTag("signin.code").performSemanticsAction(SemanticsActions.PasteText) { assertTrue(it()) }
        compose.waitUntil(10_000) { calls.get()==1 }
        compose.onNodeWithTag("signin.code").assertTextContains("ABC123").assertIsEnabled().assertIsFocused()
        compose.onNodeWithTag("signin.submit").assertIsNotEnabled()
        capture("pasted-code-pending")
        compose.onNodeWithTag("signin.code").performTextReplacement("def456")
        compose.waitUntil(5_000) { model!!.state.value.code=="DEF456" }
        compose.onNodeWithTag("signin.code").performTextReplacement("ghi789")
        compose.waitUntil(5_000) { model!!.state.value.code=="GHI789" }
        assertEquals(1, calls.get()); assertTrue(model!!.state.value.busy)
        capture("replacement-waits-for-retired-exchange")
        gate.complete(Unit); compose.waitUntil(10_000) { completions.get()==1 }
        assertEquals(listOf("abc123Opaque-CASE", "ghi789Opaque-CASE"), codes)
        assertEquals(listOf("new"), publications)
        File(folder, "paste-result.json").writeText(JSONObject().put("wireCodes", JSONArray(codes))
            .put("publications", JSONArray(publications)).put("completionCalls", completions.get())
            .put("platformClipboardPaste", true).put("codeFocusedAutomatically", true).put("otpAutofillSemantic", true)
            .put("scope", "Actual Compose/platform clipboard and busy field edits using fixture OTP requests; no real account, physical keyboard/phone, actual autofill service or full source coordinator acceptance.").toString(2))
    }

    @Test fun returningFromFocusedCodeDefersEmailFocusUntilTheExistingExchangeFails() {
        val gate = CompletableDeferred<Unit>().also { gates += it }; val calls = AtomicInteger()
        show { calls.incrementAndGet(); gate.await(); throw NativeAccountRequestFailure("INVALID_OTP", false, "Fixture") }
        compose.onNodeWithTag("signin.code").performTextInput("abc123")
        compose.waitUntil(5_000) { calls.get()==1 }
        compose.onNodeWithTag("signin.code.useDifferentEmail").performScrollTo().performClick()
        compose.onNodeWithTag("signin.code").assertDoesNotExist()
        compose.onNodeWithTag("signin.email").assertIsNotEnabled()
        assertNotNull(model!!.state.value.emailFocusRequest); assertEquals(1, calls.get())
        gate.complete(Unit)
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("signin.email").assertIsFocused().assertIsEnabled(); true }.getOrDefault(false) }
        compose.onNodeWithTag("signin.error").assertTextEquals(compose.activity.getString(R.string.cmux_signin_error_invalid_code))
        assertNull(model!!.state.value.emailFocusRequest); assertEquals(1, calls.get()); assertEquals(0, completions.get())
        capture("email-focus-after-code-back")
        File(folder, "back-result.json").writeText(JSONObject().put("exchangeCalls", calls.get())
            .put("continuedWhileReturningToEmail", true).put("emailFocusDeferredUntilIdle", true)
            .put("completionCalls", completions.get()).put("scope", "Actual focused field/mode transition with retained fixture exchange; no full source scaffold or real account acceptance.").toString(2))
    }

    @Test fun aTimedOutAutomaticExchangeReleasesTheFormAndLateCredentialsStayRetired() {
        val gate = CompletableDeferred<Unit>().also { gates += it }; val calls = AtomicInteger(); val returned = AtomicInteger()
        show(timeout = 500) { if (calls.incrementAndGet()==1) {
            try { withContext(NonCancellable) { gate.await(); tokens("old") } } finally { returned.incrementAndGet() }
        } else tokens("new") }
        compose.onNodeWithTag("signin.code").performTextInput("abc123")
        compose.waitUntil(10_000) { model!!.state.value.error==NativeSignInError.TIMED_OUT }
        compose.onNodeWithTag("signin.code").assert(hasText("")).assertIsEnabled()
        compose.onNodeWithTag("signin.error").performScrollTo().assertTextEquals(compose.activity.getString(R.string.cmux_signin_error_timed_out))
        assertEquals(0, completions.get()); assertFalse(model!!.state.value.busy)
        capture("automatic-exchange-timeout")
        gate.complete(Unit); compose.waitUntil(5_000) { returned.get()==1 }; compose.waitForIdle()
        assertTrue(publications.isEmpty()); assertEquals(0, completions.get())
        compose.onNodeWithTag("signin.code").performScrollTo().performTextInput("def456")
        compose.waitUntil(10_000) { completions.get()==1 }
        assertEquals(2, calls.get()); assertEquals(listOf("new"), publications)
        File(folder, "timeout-result.json").writeText(JSONObject().put("fixtureDeadlineMillis", 500).put("exchangeCalls", calls.get())
            .put("latePublicationBlocked", true).put("publications", JSONArray(publications)).put("completionCalls", completions.get())
            .put("scope", "Actual Android timeout/form recovery with shortened injected deadline and uncooperative fixture exchange; production bound60s is source-mapped, not wall-clock-tested on a real backend/device.").toString(2))
    }
}
