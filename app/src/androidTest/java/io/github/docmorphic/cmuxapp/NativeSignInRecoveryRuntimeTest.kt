package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual production form/session/client/challenge; emulator-local provider, no real account. */
class NativeSignInRecoveryRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() { compose.activity.finish() }

    @Test fun recoveryRemainsReachableAfterFailureAndVerifiedRetryRequiresAnActualCodeExchange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("private provider error"))
            val client = NativeEmailRecoveryClient(server.url("/").toString())
            val verified = AtomicBoolean(false); val sends = AtomicInteger(); val exchanges = AtomicInteger()
            val publications = AtomicInteger(); val completions = AtomicInteger()
            val otp = NativeEmailSignIn({ path, body ->
                if (path.endsWith("send-sign-in-code")) {
                    sends.incrementAndGet()
                    if (!verified.get()) nativeAccountResponse(400, "USER_EMAIL_ALREADY_EXISTS",
                        "{\"details\":{\"would_work_if_email_was_verified\":true}}")
                    JSONObject().put("nonce", "FixtureChallenge")
                } else {
                    if (exchanges.incrementAndGet() == 1) nativeAccountResponse(400, "INVALID_OTP", "{}")
                    assertEquals("abc123FixtureChallenge", body.getString("code"))
                    JSONObject().put("access_token", "fixture-access").put("refresh_token", "fixture-refresh")
                }
            }) { _, _ -> publications.incrementAndGet() }
            compose.setContent { CmuxTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) {
                    NativeSignIn(otp::sendCode, otp::signIn, {}, {}, onSignedIn = { completions.incrementAndGet() },
                        requestEmailVerification = client::verification, requestBillingRecovery = client::billing,
                        clearChallenge = otp::clear)
                } }
            } }
            fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
            fun awaitIdleRecovery() = compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("signin.emailVerification").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("signin.recovery.busy").fetchSemanticsNodes().isEmpty()
            }
            val folder = File(compose.activity.getExternalFilesDir(null), "signin-recovery").apply { mkdirs() }
            fun screenshot(name: String) {
                compose.waitForIdle()
                assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(folder, "$name.png")))
                val image = android.graphics.BitmapFactory.decodeFile(File(folder, "$name.png").path)
                try { assertEquals("Actual production dark Surface wraps the form", 0xFF0B0C0E.toInt(), image.getPixel(2, image.height/2)) }
                finally { image.recycle() }
            }
            compose.onNodeWithTag("signin.email").performTextInput("PERSON@Example.test")
            tap("signin.request"); awaitIdleRecovery()
            compose.onNodeWithTag("signin.recovery.error").assertTextEquals(compose.activity.getString(R.string.cmux_signin_recovery_unavailable))
            compose.onNodeWithTag("signin.code").assertDoesNotExist()
            compose.onNodeWithTag("signin.emailVerificationResend").assertIsEnabled()
            compose.onNodeWithTag("signin.billingRecovery").assertIsEnabled()
            screenshot("provider-unavailable")
            server.enqueue(MockResponse().setResponseCode(204))
            tap("signin.emailVerificationResend"); awaitIdleRecovery()
            compose.onNodeWithTag("signin.recovery.error").assertDoesNotExist()
            tap("signin.emailVerificationContinue"); awaitIdleRecovery()
            compose.onNodeWithTag("signin.recovery.error").assertTextEquals(compose.activity.getString(R.string.cmux_signin_recovery_pending))
            assertEquals("Retry does not automatically resend verification", 2, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            tap("signin.billingRecovery"); awaitIdleRecovery()
            compose.onNodeWithTag("signin.billingRecoveryMessage").assertTextEquals(compose.activity.getString(R.string.cmux_signin_recovery_accepted))
            assertEquals(0, publications.get()); assertEquals(0, completions.get())
            screenshot("privacy-neutral-recovery")
            verified.set(true); tap("signin.emailVerificationContinue")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("signin.code").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle(); compose.onNodeWithTag("signin.code").assertIsNotFocused()
            compose.onNodeWithTag("signin.code").performScrollTo().performTextInput("BAD123")
            compose.waitUntil(10_000) { exchanges.get() == 1 && compose.onAllNodesWithTag("signin.error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("signin.code").assert(hasText(""))
            compose.onNodeWithTag("signin.error").assertTextEquals(compose.activity.getString(R.string.cmux_signin_error_invalid_code))
            compose.onNodeWithTag("signin.submit").assertIsNotEnabled()
            assertEquals(0, publications.get())
            compose.onNodeWithTag("signin.code").performScrollTo().performTextInput("abc123")
            compose.waitUntil(10_000) { completions.get() == 1 }
            assertEquals(1, publications.get()); assertEquals(3, sends.get()); assertEquals(2, exchanges.get())
            val requests = (1..3).map { checkNotNull(server.takeRequest()) }
            assertEquals(listOf("/api/auth/email-verification", "/api/auth/email-verification", "/api/billing/recover"), requests.map { it.path })
            assertTrue(requests.all { JSONObject(it.body.readUtf8()).getString("email") == "person@example.test" })
            File(folder, "result.json").writeText(JSONObject().put("sendCalls", sends.get()).put("exchanges", exchanges.get())
                .put("publications", publications.get()).put("completions", completions.get()).put("recoveryHttpRequests", requests.size)
                .put("scope", "Actual Compose input and production form/session/challenge/recovery HTTP with emulator-local provider; no real account, physical device or TalkBack acceptance.").toString(2))
            otp.clear()
        }
    }
}
