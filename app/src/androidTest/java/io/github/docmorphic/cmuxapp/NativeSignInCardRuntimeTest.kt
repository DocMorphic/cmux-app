package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Production challenge layout under controlled languages/scales/appearance; fixture account only. */
class NativeSignInCardRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val folder get() = File(compose.activity.getExternalFilesDir(null), "signin-card").apply { mkdirs() }
    private var owner: CoroutineScope? = null
    private var model: NativeSignInSession? = null
    private var previousSoftInputMode: Int? = null
    @Before fun emulatorOnly() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        instrumentation.runOnMainSync {
            val window = compose.activity.window
            previousSoftInputMode = window.attributes.softInputMode
            // Match MainActivity's declared adjustResize instead of the generic host default.
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }
    @After fun finish() {
        instrumentation.runOnMainSync {
            model?.close(); owner?.cancel()
            previousSoftInputMode?.let { compose.activity.window.setSoftInputMode(it) }
        }
        compose.activity.finish()
    }
    private fun create(send: suspend (String) -> Unit = {}, exchange: suspend (String) -> Unit = {}) {
        instrumentation.runOnMainSync {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            model = NativeSignInSession(owner!!, send, exchange)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "$name.png")))
    }
    @Test fun codeCardUsesOriginalCopyAndKeepsControlsReachableAcross36Profiles() {
        data class Profile(val language: String, val scale: Float, val dark: Boolean)
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap { language ->
            listOf(1f, 2f).flatMap { scale -> listOf(false, true).map { Profile(language, scale, it) } } }
        var profile by mutableStateOf(Profile("en", 1f, true))
        val sends = AtomicInteger(); val exchanges = AtomicInteger()
        create({ sends.incrementAndGet() }, { exchanges.incrementAndGet(); throw NativeAccountRequestFailure("INVALID_OTP", false, "Fixture") })
        compose.setContent { CmuxTheme(darkTheme = profile.dark) {
            val base = LocalContext.current; val density = LocalDensity.current
            val config = remember(base, profile) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(profile.language)); fontScale = profile.scale
            } }
            val context = remember(base, config) { base.createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, profile.scale),
                LocalLayoutDirection provides if (profile.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) {
                    NativeSignIn({}, {}, {}, {}, onSignedIn = { error("No completion") }, retained = model)
                } }
            }
        } }
        compose.onNodeWithTag("signin.email").performTextInput("person@example.test")
        awaitNativeSignInIme(compose.activity)
        compose.onNodeWithTag("signin.request").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("signin.code").assertIsFocused(); true }.getOrDefault(false) }
        compose.onNodeWithTag("signin.code").performTextInput("BAD123")
        compose.waitUntil(5_000) { model!!.state.value.error == NativeSignInError.INVALID_CODE }
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle(2_000); compose.waitForIdle()
        val records = JSONArray()
        for (p in profiles) {
            compose.runOnIdle { profile = p }; compose.waitForIdle()
            val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(p.language)) }
            val resources = compose.activity.createConfigurationContext(configuration)
            compose.onNodeWithTag("signin.email").assertDoesNotExist()
            compose.onNodeWithTag("signin.request").assertDoesNotExist()
            compose.onNodeWithTag("signin.code.title").performScrollTo().assertIsDisplayed()
                .assertTextEquals(resources.getString(R.string.cmux_signin_code_title))
            compose.onNodeWithTag("signin.code.sentTo").assertTextEquals(resources.getString(R.string.cmux_signin_code_sent_to, "person@example.test"))
            val code = compose.onNodeWithTag("signin.code").performScrollTo().assertIsDisplayed().assertIsEnabled()
            val bounds = code.fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertEquals("Fixed source pill height", 60f, bounds.height/density, .6f)
            assertTrue("Card maximum width", bounds.width/density <= 390.6f)
            val error = compose.onNodeWithTag("signin.error").performScrollTo().assertIsDisplayed()
                .assertTextEquals(resources.getString(R.string.cmux_signin_error_invalid_code))
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            error.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Error is complete: $p", layouts.single().hasVisualOverflow)
            compose.onNodeWithTag("signin.submit").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
                .assertTextEquals(resources.getString(R.string.cmux_signin_code_verify))
            compose.onNodeWithTag("signin.code.useDifferentEmail").performScrollTo().assertIsDisplayed().assertIsEnabled()
            var keyboardTop = 0; var originY = 0
            instrumentation.runOnMainSync {
                val decor = compose.activity.window.decorView
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                assertTrue("Keyboard remains visible", insets.isVisible(WindowInsetsCompat.Type.ime()))
                keyboardTop = device.displayHeight - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val origin = IntArray(2); decor.getLocationOnScreen(origin); originY = origin[1]
            }
            val screen = compose.onNodeWithTag("signin.challenge.screen").fetchSemanticsNode().boundsInWindow
            assertEquals("Challenge viewport ends at keyboard: $p", keyboardTop.toFloat(), screen.bottom + originY, 3f)
            assertEquals(1, sends.get()); assertEquals(1, exchanges.get())
            records.put(JSONObject().put("language", p.language).put("controlledScale", p.scale).put("dark", p.dark)
                .put("codeBounds", bounds.toString()).put("screenBoundsInWindow", screen.toString())
                .put("keyboardTop", keyboardTop).put("windowOriginY", originY)
                .put("errorOverflow", false).put("controlsReachable", true))
            if ((p.language == "en" && p.scale == 1f) || (p.language in listOf("ar", "de") && p.scale == 2f && p.dark))
                capture(p.language+"-"+p.scale+"-"+if (p.dark) "dark" else "light")
        }
        File(folder, "profiles-result.json").writeText(JSONObject().put("profiles", records)
            .put("sendCalls", sends.get()).put("exchanges", exchanges.get())
            .put("scope", "Actual production code card/scroll/input/error with controlled composition locales, scales and appearance; not OS transitions, UIKit visual parity, physical device, real backend/autofill or TalkBack acceptance.").toString(2))
    }

    @Test fun unfocusedEmailSendDoesNotAutofocusCodeAndCodeBackCanRestoreEmailFocus() {
        val sends = AtomicInteger(); create({ sends.incrementAndGet() })
        instrumentation.runOnMainSync { model!!.editEmail("unfocused@example.test") }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) {
            NativeSignIn({}, {}, {}, {}, onSignedIn = {}, retained = model)
        } } } }
        compose.onNodeWithTag("signin.email").assertIsNotFocused()
        compose.onNodeWithTag("signin.request").performScrollTo().performClick()
        compose.waitUntil(5_000) { model!!.state.value.codeSent }
        compose.onNodeWithTag("signin.code").assertIsNotFocused()
        assertNull(model!!.state.value.codeFocusRequest)
        compose.onNodeWithTag("signin.code").performClick().assertIsFocused()
        compose.onNodeWithTag("signin.code.useDifferentEmail").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("signin.email").assertIsFocused(); true }.getOrDefault(false) }
        compose.onNodeWithTag("signin.request").performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("signin.code").assertIsFocused(); true }.getOrDefault(false) }
        assertEquals(2, sends.get())
        File(folder, "focus-result.json").writeText(JSONObject().put("sendCalls", sends.get())
            .put("unfocusedRequestDoesNotAutofocus", true).put("focusedRequestAutofocuses", true)
            .put("codeBackRestoresEmailFocus", true).toString(2))
    }
}
