package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Parcel
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real OS appearance/configuration and actual production views on a fresh disposable emulator. */
class NativeAppearanceRuntimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private var main: ActivityScenario<MainActivity>? = null
    private var form: ActivityScenario<NativeSignInLifecycleTestActivity>? = null
    private var owner: CoroutineScope? = null
    private var model: NativeSignInSession? = null
    private var originalNight = "auto"
    @Before fun prepare() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Use owned emulator only" }
        val mode = device.executeShellCommand("cmd uimode night")
        originalNight = checkNotNull(Regex("Night mode: (auto|yes|no)\\b").find(mode)?.groupValues?.get(1)) { mode }
    }
    @After fun cleanup() {
        main?.close(); form?.close()
        instrumentation.runOnMainSync { model?.close(); owner?.cancel() }
        NativeSignInLifecycleTestActivity.session = null; NativeSignInLifecycleTestActivity.completed = null
        NativeSignInLifecycleTestActivity.saved = null
        device.executeShellCommand("cmd uimode night $originalNight")
    }
    private fun night(dark: Boolean) {
        device.executeShellCommand("cmd uimode night ${if (dark) "yes" else "no"}")
        compose.waitUntil(15_000) {
            runCatching {
                var current: Boolean? = null
                main?.onActivity { current = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
                form?.onActivity { current = it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
                current == dark
            }.getOrDefault(false)
        }
        compose.waitForIdle()
    }
    private fun hideKeyboard() {
        fun hide(activity: androidx.activity.ComponentActivity) {
            activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        main?.onActivity(::hide); form?.onActivity(::hide)
        compose.waitForIdle()
    }
    private fun capture(name: String, dark: Boolean) {
        // Configuration/composition can settle before the compositor's transition.
        // Keep the final full screenshot on failure so a system overlay is visible.
        val expected = if (dark) 0xFF0B0C0E.toInt() else 0xFFF2F2F7.toInt()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        runCatching { compose.waitUntil(5_000) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            val ready = frame.getPixel(2, frame.height / 2) == expected
            frame.recycle(); ready
        } }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val background = bitmap.getPixel(2, bitmap.height / 2)
        assertEquals("Actual shell background", expected, background)
        val result = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Sign in to cmux").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(result) }
        assertEquals(1, result.size)
        val foreground = result.single().layoutInput.style.color
        assertEquals("Header follows readable foreground", dark, foreground.luminance() > .5f)
        main?.onActivity {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            assertEquals(!dark, bars.isAppearanceLightStatusBars); assertEquals(!dark, bars.isAppearanceLightNavigationBars)
        }
        bitmap.recycle()
        File(folder, "$name.json").writeText(JSONObject().put("dark", dark).put("backgroundArgb", background)
            .put("headerLuminance", foreground.luminance()).put("scope", "Actual system appearance and production sign-in/root; not matched full iOS UI, authenticated screens or physical-device acceptance.").toString(2))
    }
    @Test fun actualMainActivityFollowsSystemAppearanceAndRetainsItsEmailThroughRecreation() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra(SUPPRESS_WHATS_NEW_EXTRA, true)
        main = ActivityScenario.launch(intent)
        compose.onNodeWithTag("signin.email").assertIsDisplayed().performTextInput("appearance@example.test")
        hideKeyboard()
        for ((index, dark) in listOf(false, true, false).withIndex()) {
            night(dark)
            compose.onNodeWithTag("signin.email").assertTextContains("appearance@example.test")
            capture("appearance-main-$index", dark)
        }
        main!!.recreate()
        compose.onNodeWithTag("signin.email").assertTextContains("appearance@example.test")
        capture("appearance-main-recreated", false)
    }
    @Test fun pendingCodeAndAuthenticationSurviveRecreationWithoutSavingTheCodeInABundle() {
        val sendGate = CompletableDeferred<Unit>(); val authGate = CompletableDeferred<Unit>()
        val sends = AtomicInteger(); val signs = AtomicInteger(); val completions = AtomicInteger()
        instrumentation.runOnMainSync {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            model = NativeSignInSession(owner!!, { sends.incrementAndGet(); sendGate.await() }, { signs.incrementAndGet(); authGate.await() })
            NativeSignInLifecycleTestActivity.session = model
            NativeSignInLifecycleTestActivity.completed = { completions.incrementAndGet() }
        }
        form = ActivityScenario.launch(NativeSignInLifecycleTestActivity::class.java)
        compose.onNodeWithTag("signin.email").performTextInput("fixture@example.test")
        compose.onNodeWithTag("signin.request").performScrollTo().performClick()
        compose.waitUntil(5_000) { sends.get() == 1 }
        form!!.recreate()
        compose.onNodeWithTag("signin.email").assertTextContains("fixture@example.test").assertIsNotEnabled()
        assertEquals(1, sends.get()); sendGate.complete(Unit)
        compose.waitUntil(5_000) { model!!.state.value.codeSent }
        compose.onNodeWithTag("signin.code").performScrollTo().performTextInput("A1B2C3")
        hideKeyboard(); form!!.recreate()
        compose.onNodeWithTag("signin.code").assertTextContains("A1B2C3")
        val saved = checkNotNull(NativeSignInLifecycleTestActivity.saved)
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writeBundle(saved); parcel.marshall() } finally { parcel.recycle() }
        fun contains(pattern: ByteArray) = (0..bytes.size - pattern.size).any { start -> pattern.indices.all { bytes[start + it] == pattern[it] } }
        assertFalse("Code must stay out of saved state", contains("A1B2C3".toByteArray(Charsets.UTF_16LE)) || contains("A1B2C3".toByteArray()))
        compose.onNodeWithTag("signin.submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { signs.get() == 1 }; form!!.recreate()
        compose.onNodeWithTag("signin.submit").assertIsNotEnabled()
        assertEquals(1, signs.get()); authGate.complete(Unit)
        compose.waitUntil(5_000) { completions.get() == 1 }
        assertEquals("", model!!.state.value.code); assertEquals("", model!!.state.value.email)
        form!!.recreate(); compose.waitForIdle(); assertEquals(1, completions.get())
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "signin-retained-result.json").writeText(JSONObject().put("sendCalls", sends.get()).put("signInCalls", signs.get())
            .put("completionCalls", completions.get()).put("codeAbsentFromSavedState", true).put("formPurgedAfterSuccess", true).toString(2))
    }
}
