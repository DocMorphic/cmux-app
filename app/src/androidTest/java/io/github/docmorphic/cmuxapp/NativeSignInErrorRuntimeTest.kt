package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.util.concurrent.atomic.AtomicInteger

/** Production error form under controlled locale/scale; no OS-language or real-account claim. */
class NativeSignInErrorRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var owner: CoroutineScope? = null
    private var model: NativeSignInSession? = null
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun finish() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model?.close(); owner?.cancel() }
        compose.activity.finish()
    }

    @Test fun retainedErrorUsesOriginalLocalizedCopyAcrossNineLocalesWithoutReplayingTheRequest() {
        data class Profile(val language: String, val scale: Float)
        val profiles = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant").flatMap {
            listOf(Profile(it, 1f), Profile(it, 2f)) }
        var profile by mutableStateOf(profiles.first())
        val requests = AtomicInteger(); val marker = "PRIVATE_PROVIDER_VALUE"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            model = NativeSignInSession(owner!!, { requests.incrementAndGet()
                throw NativeAccountRequestFailure("UNRECOGNIZED", false, marker) }, { error("No authentication") })
        }
        compose.setContent { CmuxTheme(darkTheme = true) {
            val base = LocalContext.current; val density = LocalDensity.current
            val config = remember(base, profile) { Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(profile.language)); fontScale = profile.scale
            } }
            val context = remember(base, config) { base.createConfigurationContext(config) }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, profile.scale),
                LocalLayoutDirection provides if (profile.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) {
                    NativeSignIn({}, {}, {}, {}, onSignedIn = { error("No completion") }, retained = model!!)
                } }
            }
        } }
        compose.onNodeWithTag("signin.email").performTextInput("person@example.test")
        compose.onNodeWithTag("signin.request").performScrollTo().performClick()
        compose.waitUntil(5_000) { model!!.state.value.error == NativeSignInError.SERVER_ERROR }
        val folder = File(compose.activity.getExternalFilesDir(null), "signin-error").apply { mkdirs() }
        val records = JSONArray()
        for (p in profiles) {
            compose.runOnIdle { profile = p }; compose.waitForIdle()
            val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(p.language)) }
            val expected = compose.activity.createConfigurationContext(configuration).getString(R.string.cmux_signin_error_server_error)
            val node = compose.onNodeWithTag("signin.error").performScrollTo().assertIsDisplayed().assertTextEquals(expected)
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            val text = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
            assertEquals(1, text.size); assertFalse("Complete localized error: $p", text.single().hasVisualOverflow)
            compose.onAllNodes(hasText(marker, substring = true)).assertCountEquals(0)
            assertEquals(1, requests.get()); assertEquals(NativeSignInError.SERVER_ERROR, model!!.state.value.error)
            records.put(JSONObject().put("language", p.language).put("controlledScale", p.scale)
                .put("error", expected).put("lineCount", text.single().lineCount).put("visualOverflow", false)
                .put("requests", requests.get()).put("bounds", node.fetchSemanticsNode().boundsInRoot.toString()))
            if (p.scale == 2f && p.language in listOf("ar", "de")) {
                compose.waitForIdle()
                assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(folder, p.language+"-large-error.png")))
            }
        }
        compose.runOnIdle { model!!.editEmail("other@example.test") }; compose.waitForIdle()
        compose.onNodeWithTag("signin.error").assertDoesNotExist(); assertEquals(1, requests.get())
        File(folder, "result.json").writeText(JSONObject().put("profiles", records).put("requestCalls", requests.get())
            .put("providerTextAbsent", true).put("editClearsError", true)
            .put("scope", "Actual production form/error semantics under controlled composition locale/font scale, not actual OS-language/font changes, real account, physical device or TalkBack acceptance.").toString(2))
    }
}
