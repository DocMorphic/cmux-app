package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled component locales/scales; does not change the user's OS settings. */
class AccountPlanLocaleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val languages = listOf("en", "ar", "de", "es", "fr", "ja", "ko", "zh-Hans", "zh-Hant")
    private val native by lazy {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        JSONObject(instrumentation.context.assets.open("parity/account-plan-locales.json").bufferedReader().use { it.readText() })
            .getJSONArray("strings")
    }
    private fun nativeText(suffix: String, language: String): String = (0 until native.length())
        .map { native.getJSONObject(it) }.single { it.getString("key") == "mobile.billing.$suffix" }
        .getJSONObject("translations").getString(language)

    private fun assertFits(node: SemanticsNodeInteraction, expectedScale: Float): TextLayoutResult {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Expected actual plan text layout", layouts.isNotEmpty())
        assertEquals("The actual text layout must use the caller's scale", expectedScale, layouts.single().layoutInput.density.fontScale, .001f)
        if (layouts.any { it.hasVisualOverflow }) {
            capture("overflow-${System.nanoTime()}")
            val details = layouts.joinToString { "text=${it.layoutInput.text.text}, size=${it.size}, paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, exceededLines=${it.multiParagraph.didExceedMaxLines}" }
            fail("Plan text clipped or ellipsized: $details")
        }
        return layouts.single()
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val painted = CountDownLatch(1)
        compose.runOnUiThread {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            window.postOnAnimation { window.postOnAnimation { painted.countDown() } }
        }
        assertTrue(painted.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val folder = File(compose.activity.getExternalFilesDir(null), "account-plan-locales").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun nativeLabelsAndHeaderActionsStayUsableInNineLocalesAtNormalAndLargeText() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        var language by mutableStateOf("en")
        var scale by mutableStateOf(1f)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var reads = 0
        val links = mutableListOf<String>()
        val records = org.json.JSONArray()
        val initialLoad = CompletableDeferred<Unit>()
        AccountPlanController(scope, { reads++; initialLoad.await(); AccountPlan("free", "stripe", "stripe", true) }, { true }).use { controller ->
            compose.setContent {
                val configuration = remember(language, scale) { Configuration(compose.activity.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language)); fontScale = scale
                } }
                val context = remember(configuration) { android.view.ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(configuration) } }
                val density = context.resources.displayMetrics.density
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, scale),
                    LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        AccountPlanSettings(controller, links::add)
                    } } }
                }
            }
            for (locale in languages) for (fontScale in listOf(1f, 2f)) {
                compose.runOnIdle { language = locale; scale = fontScale }
                val title = compose.onNodeWithTag("settings.plan.title", useUnmergedTree = true)
                val summary = compose.onNodeWithTag("settings.plan.summary", useUnmergedTree = true)
                title.assertTextEquals(nativeText("settingsRow", locale))
                summary.assertTextEquals(nativeText("loading", locale))
                assertFits(title, fontScale); assertFits(summary, fontScale)
            }
            compose.runOnIdle { language = "en"; scale = 1f; initialLoad.complete(Unit) }
            compose.onNodeWithTag("settings.plan").performClick()
            compose.onNodeWithTag("plan.current").assertTextEquals(nativeText("plan.free", "en"))
            val initialReads = reads
            for (locale in languages) for (fontScale in listOf(1f, 2f)) {
                compose.runOnIdle { language = locale; scale = fontScale }
                // A density change replaces Compose's separate dialog window;
                // Android can register its new semantics root after Compose idles.
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("plan.title").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("plan.title").assertTextEquals(nativeText("title", locale))
                compose.onNodeWithTag("plan.current").assertTextEquals(nativeText("plan.free", locale))
                compose.onNodeWithTag("plan.currentLabel").assertTextEquals(nativeText("currentPlan", locale))
                val currentLabel = assertFits(compose.onNodeWithTag("plan.currentLabel"), fontScale)
                assertFits(compose.onNodeWithTag("plan.current"), fontScale)
                if (locale == "de") assertTrue("German current-plan label must not fragment into narrow letter stacks", currentLabel.lineCount <= 2)
                compose.onNodeWithTag("plan.manage").assertTextEquals(nativeText("manage", locale))
                compose.onNodeWithTag("plan.manage").performScrollTo()
                val sourceLayout = assertFits(compose.onNodeWithText(nativeText("source.web", locale)), fontScale)
                assertFits(compose.onNodeWithTag("plan.title"), fontScale)
                val title = compose.onNodeWithTag("plan.title").fetchSemanticsNode().boundsInRoot
                val refresh = compose.onNodeWithTag("plan.refresh").assertHasClickAction().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                val done = compose.onNodeWithTag("plan.done").assertHasClickAction().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                compose.onNodeWithContentDescription(nativeText("done", locale)).assertIsDisplayed()
                val density = compose.activity.resources.displayMetrics.density
                capture("$locale-$fontScale")
                assertTrue("$locale/$fontScale refresh bounds $refresh at density $density", refresh.width >= 48 * density - 1 && refresh.height >= 48 * density - 1)
                assertTrue("$locale/$fontScale done bounds $done at density $density", done.width >= 48 * density - 1 && done.height >= 48 * density - 1)
                if (locale == "ar") assertTrue("RTL header overlap: $done $refresh $title", done.right <= refresh.left + 1 && refresh.right <= title.left + 1)
                else assertTrue("LTR header overlap: $title $refresh $done", title.right <= refresh.left + 1 && refresh.right <= done.left + 1)
                compose.runOnIdle { assertEquals("Locale updates must not reload the account", initialReads, reads) }
                records.put(JSONObject().put("locale", locale).put("fontScale", fontScale).put("reads", reads)
                    .put("actualLayoutFontScale", sourceLayout.layoutInput.density.fontScale)
                    .put("sourceLineHeightPixels", sourceLayout.getLineBottom(0) - sourceLayout.getLineTop(0))
                    .put("currentPlanLabelLines", currentLabel.lineCount)
                    .put("title", nativeText("title", locale)).put("plan", nativeText("plan.free", locale)))
            }
            compose.onNodeWithTag("plan.done").performClick()
            compose.onNodeWithTag("settings.plan").assertIsDisplayed()
            compose.runOnIdle { assertTrue(links.isEmpty()) }
            File(compose.activity.getExternalFilesDir(null), "account-plan-locales/receipt.json").writeText(
                JSONObject().put("scope", "Production component, controlled local contexts and scales on a 260dp owned emulator; no OS locale change, physical account or native purchase acceptance")
                    .put("records", records).toString(2))
        }
        scope.cancel()
    }

    @Test fun existingFailureAndBrowserRecoveryUseTheCurrentLocaleWithoutReloading() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        var language by mutableStateOf("en")
        var fail = false
        var reads = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        AccountPlanController(scope, { reads++; if (fail) throw java.io.IOException("private detail")
            AccountPlan("pro", "stripe", "stripe", true) }, { true }).use { controller ->
            compose.setContent {
                val configuration = remember(language) { Configuration(compose.activity.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language)); fontScale = 2f
                } }
                val context = remember(configuration) { android.view.ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(configuration) } }
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides Density(context.resources.displayMetrics.density, 2f),
                    LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        AccountPlanSettings(controller) { throw android.content.ActivityNotFoundException("private browser detail") }
                    } } }
                }
            }
            compose.onNodeWithTag("settings.plan").performClick()
            compose.onNodeWithTag("plan.current").assertTextEquals("Pro")
            compose.onNodeWithTag("plan.manage").performScrollTo().performClick()
            compose.onNodeWithTag("plan.browserFailure").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { fail = true; controller.refresh() }
            compose.onNodeWithTag("plan.failure").performScrollTo().assertIsDisplayed()
            val initialReads = reads
            for (locale in listOf("de", "ar", "ja")) {
                compose.runOnIdle { language = locale }
                val configuration = Configuration(compose.activity.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(locale)) }
                val resources = compose.activity.createConfigurationContext(configuration).resources
                compose.onNodeWithTag("plan.failure").performScrollTo()
                    .assertTextEquals(resources.getString(R.string.cmux_billing_android_load_failed))
                assertFits(compose.onNodeWithTag("plan.failure"), 2f)
                compose.onNodeWithTag("plan.browserFailure").performScrollTo()
                    .assertTextEquals(resources.getString(R.string.cmux_billing_android_browser_failed))
                assertFits(compose.onNodeWithTag("plan.browserFailure"), 2f)
                capture("failure-$locale")
                compose.runOnIdle { assertEquals(initialReads, reads) }
            }
            compose.runOnIdle { fail = false }
            compose.onNodeWithTag("plan.retry").performScrollTo().performClick()
            compose.onNodeWithTag("plan.failure").assertDoesNotExist()
            compose.onNodeWithTag("plan.current").performScrollTo().assertTextEquals("Pro")
        }
        scope.cancel()
    }

    @Test fun densityChangeRetainsTheSameWindowOpenPlanAndActualScroll() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        var scale by mutableStateOf(2f)
        var reads = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val plan = AccountPlan("go", "apple", "external", true)
        AccountPlanController(scope, { reads++; plan }, { true }).use { controller ->
            compose.setContent {
                val host = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(host.density, scale)) {
                    CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        AccountPlanSettings(controller) { error("No browser action expected") }
                    } } }
                }
            }
            compose.onNodeWithTag("settings.plan").performClick()
            compose.onNodeWithText("Privacy Policy").performScrollTo()
            fun position(): Pair<Float, Float> {
                val range = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                return range.value() to range.maxValue()
            }
            val before = position()
            assertTrue("Must exercise actual positive scroll, not a zero-offset fixture", before.first > 0 && before.second > 0)
            val initialReads = reads
            val beforeWindow = android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            capture("window-density-before")
            compose.runOnIdle { scale = 1.9f }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("plan.title").fetchSemanticsNodes().isNotEmpty() }
            assertFits(compose.onNodeWithTag("plan.title"), 1.9f)
            val after = position()
            compose.runOnIdle { assertSame(beforeWindow, android.view.inspector.WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }) }
            assertTrue("The new viewport must still require scrolling", after.second > 0)
            assertEquals("Keep the old pixel offset, clamping only to the new maximum", minOf(before.first, after.second), after.first, 1f)
            compose.runOnIdle { assertEquals(initialReads, reads); assertSame(plan, controller.state.value.plan) }
            capture("window-density-after")
            File(compose.activity.getExternalFilesDir(null), "account-plan-locales/window-density.json").writeText(
                JSONObject().put("beforeOffset", before.first).put("beforeMaximum", before.second)
                    .put("afterOffset", after.first).put("afterMaximum", after.second)
                    .put("accountReadsBefore", initialReads).put("accountReadsAfter", reads)
                    .put("samePlanInstance", true).put("sameWindow", true).put("scope", "Controlled local density 2 to 1.9, retained actual dialog and scrollbar; no OS font-setting, physical account or process restoration acceptance").toString(2))
            compose.onNodeWithTag("plan.done").performClick()
            compose.onNodeWithTag("plan.title").assertDoesNotExist()
        }
        scope.cancel()
    }
}
