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
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Locale

/** Real production UI with controlled locale/font/paint and in-memory auth fixtures only. */
class NativeSignInMethodsRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val folder get() = File(compose.activity.getExternalFilesDir(null), "signin-methods").apply { mkdirs() }
    private var owner: CoroutineScope? = null
    private var model: NativeSignInSession? = null
    private val stateHistory = mutableListOf<NativeSignInState>()
    private var previousSoftInputMode: Int? = null
    @Before fun emulatorOnly() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        instrumentation.runOnMainSync {
            val window = compose.activity.window
            previousSoftInputMode = window.attributes.softInputMode
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
    private fun create(send: suspend (String) -> Unit = {}, provider: suspend (NativeOAuthProvider) -> Unit = {}) {
        instrumentation.runOnMainSync {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            model = NativeSignInSession(owner!!, send, {}, authenticateProvider = provider)
            owner!!.launch { model!!.state.collect { stateHistory += it } }
        }
    }
    private fun content(helper: () -> Unit = {}, licenses: () -> Unit = {}) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) {
            NativeSignIn({}, {}, helper, licenses, onSignedIn = { error("No completion expected") }, retained = model)
        } } } }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "$name.png")))
    }
    @Test fun originalLabelsAndEmailPillRemainReachableAcross36ControlledProfiles() {
        data class Profile(val language: String, val scale: Float, val dark: Boolean)
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap { language ->
            listOf(1f, 2f).flatMap { scale -> listOf(false, true).map { Profile(language, scale, it) } } }
        var profile by mutableStateOf(Profile("en", 1f, true)); create()
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
                    NativeSignIn({}, {}, {}, {}, onSignedIn = {}, retained = model)
                } }
            }
        } }
        val rows = JSONArray()
        for (p in profiles) {
            compose.runOnIdle { profile = p }; compose.waitForIdle()
            val resources = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(p.language))
            })
            val bounds = JSONArray()
            for ((wire, resource) in listOf("apple" to R.string.cmux_signin_provider_apple,
                "google" to R.string.cmux_signin_provider_google, "github" to R.string.cmux_signin_provider_github)) {
                val button = compose.onNodeWithTag("signin.$wire").performScrollTo().assertIsDisplayed().assertIsEnabled()
                    .assertTextEquals(resources.getString(resource))
                bounds.put(button.fetchSemanticsNode().boundsInRoot.toString())
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithText(resources.getString(resource), useUnmergedTree = true)
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                // Intrinsic Text can retain a wider paragraph container in the
                // layout-result cache. Check shaped lines against capsule space.
                val capsuleWidth = button.fetchSemanticsNode().boundsInRoot.width
                assertFalse("Provider height overflows: $p/$wire", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("Provider is ellipsized: $p/$wire", layout.isLineEllipsized(line))
                    assertTrue("Provider line exceeds its capsule: $p/$wire",
                        layout.getLineRight(line)-layout.getLineLeft(line) <= capsuleWidth)
                }
            }
            val dividerLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("signin.email.divider").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(dividerLayouts) }
            assertFalse("Divider overflows: $p", dividerLayouts.single().hasVisualOverflow)
            val email = compose.onNodeWithTag("signin.email").performScrollTo().assertIsDisplayed().assertIsEnabled()
                .assertContentDescriptionEquals(resources.getString(R.string.cmux_signin_provider_email_placeholder))
            val emailBounds = email.fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            assertEquals(50f, emailBounds.height/density, .6f)
            assertTrue(emailBounds.width/density <= 390.6f)
            compose.onNodeWithTag("signin.request").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
                .assertTextEquals(resources.getString(R.string.cmux_signin_provider_email_code))
            compose.onNodeWithTag("signin.options").assertIsDisplayed()
            rows.put(JSONObject().put("language", p.language).put("controlledScale", p.scale).put("dark", p.dark)
                .put("providerBounds", bounds).put("emailBounds", emailBounds.toString()).put("controlsReachable", true))
            if ((p.language == "en" && p.scale == 1f) || (p.language in listOf("ar", "de") && p.scale == 2f && p.dark))
                capture(p.language+"-"+p.scale+"-"+if (p.dark) "dark" else "light")
        }
        File(folder, "profiles.json").writeText(JSONObject().put("profiles", rows)
            .put("scope", "Production methods UI; composition-controlled locales/scales/appearance, not actual OS transitions, native glass, original pixel equality, real account, phone or TalkBack acceptance.").toString(2))
    }
    @Test fun tappingBackgroundDismissesFocusedEmailWithoutChangingTheDraftOrSendingCode() {
        var calls = 0; create(send = { calls++ }); content()
        compose.onNodeWithTag("signin.email").performTextInput("draft@example.test")
        awaitNativeSignInIme(compose.activity)
        compose.onNodeWithTag("signin.email").assertIsFocused()
        compose.onNodeWithTag("signin.methods.screen").performTouchInput { click(androidx.compose.ui.geometry.Offset(center.x, 10f)) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("signin.email").assertIsNotFocused(); true }.getOrDefault(false) }
        compose.onNodeWithTag("signin.email").assertTextEquals("draft@example.test")
        compose.runOnIdle { assertEquals(0, calls) }
        capture("background-dismissed-email")
    }

    @Test fun busySendKeepsEmailEditableAndRetainsItsAdmittedRequestUntilFailure() {
        val gate = CompletableDeferred<Unit>(); val sent = mutableListOf<String>()
        create(send = { sent += it; gate.await(); throw NativeSignInFailure(NativeSignInError.SERVER_ERROR) }); content()
        compose.onNodeWithTag("signin.email").performTextInput("first@example.test")
        awaitNativeSignInIme(compose.activity)
        compose.onNodeWithTag("signin.request").performScrollTo().performClick()
        try { compose.waitUntil(5_000) { model!!.state.value.busy } }
        catch (failure: Throwable) {
            capture("busy-send-not-started")
            File(folder, "busy-send-diagnostic.txt").writeText("history="+stateHistory+"\nstate="+model!!.state.value+"\n"+compose.onRoot().printToString())
            throw failure
        }
        compose.onNodeWithTag("signin.email").performScrollTo().assertIsEnabled().performTextReplacement("edited@example.test")
        compose.onNodeWithTag("signin.request").assertIsNotEnabled()
        NativeOAuthProvider.entries.forEach { compose.onNodeWithTag("signin.${it.wire}").assertIsNotEnabled() }
        compose.runOnIdle { assertEquals(listOf("first@example.test"), sent); assertEquals("edited@example.test", model!!.state.value.email); gate.complete(Unit) }
        compose.waitUntil(5_000) { !model!!.state.value.busy }
        compose.onNodeWithTag("signin.email").assertTextEquals("edited@example.test")
        compose.onNodeWithTag("signin.error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("signin.request").assertIsEnabled()
        capture("busy-send-failed-retained-email")
    }
    @Test fun eachBusyProviderKeepsEmailEditableWithoutLaunchingAnotherProvider() {
        var gate = CompletableDeferred<Unit>(); val calls = mutableListOf<NativeOAuthProvider>()
        create(provider = { calls += it; gate.await(); throw NativeSignInFailure(NativeSignInError.BROWSER_SIGN_IN_FAILED) }); content()
        for (provider in NativeOAuthProvider.entries) {
            compose.runOnIdle { gate = CompletableDeferred() }
            compose.onNodeWithTag("signin.${provider.wire}").performScrollTo().performClick()
            compose.waitUntil(5_000) { model!!.state.value.provider == provider }
            compose.onNodeWithTag("signin.email").performScrollTo().assertIsEnabled().performTextReplacement("${provider.wire}@example.test")
            NativeOAuthProvider.entries.forEach { compose.onNodeWithTag("signin.${it.wire}").assertIsNotEnabled() }
            compose.runOnIdle { assertEquals(provider, calls.last()); gate.complete(Unit) }
            compose.waitUntil(5_000) { !model!!.state.value.busy }
            compose.onNodeWithTag("signin.email").assertTextEquals("${provider.wire}@example.test")
        }
        assertEquals(NativeOAuthProvider.entries.toList(), calls)
        capture("provider-error-retained-email")
    }
    @Test fun androidOptionsPreserveDraftForLicensesAndClearItOnlyWhenChoosingHelper() {
        var helper = 0; var licenses = 0; create(); content({ helper++ }, { licenses++ })
        compose.onNodeWithTag("signin.email").performTextInput("draft@example.test")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("signin.options").performClick()
        compose.onNodeWithTag("signin.licenses").performClick()
        compose.onNodeWithTag("signin.email").assertTextEquals("draft@example.test")
        compose.runOnIdle { assertEquals(1, licenses); assertEquals(0, helper) }
        compose.onNodeWithTag("signin.options").performClick()
        compose.onNodeWithTag("signin.helper").performClick()
        compose.runOnIdle { assertEquals(1, licenses); assertEquals(1, helper); assertEquals("", model!!.state.value.email) }
    }
}
