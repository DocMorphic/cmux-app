package io.github.docmorphic.cmuxapp

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.View
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual shared windows/input/scroll. Controlled local configuration, not OS or phone acceptance. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTestApi::class)
class NativeDialogEnvironmentRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class ProbeState(val root: View, val token: String, val scale: Float, val direction: LayoutDirection)
    private var observed: ProbeState? = null
    private var parentRoot: View? = null
    private var parentToken: String? = null
    private var actualSheet: SheetState? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun emulatorOnly() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
    }
    @After fun close() { compose.activity.finish() }

    @Composable private fun Probe(kind: String, scrolling: Boolean, modifier: Modifier) {
        val token = remember { UUID.randomUUID().toString() }
        var value by remember { mutableStateOf(TextFieldValue()) }
        val view = LocalView.current
        val scale = LocalDensity.current.fontScale
        val direction = LocalLayoutDirection.current
        val keyboard = LocalSoftwareKeyboardController.current
        SideEffect { observed = ProbeState(view.rootView, token, scale, direction) }
        Column(modifier.padding(12.dp)) {
            if (kind != "alert") Text(stringResource(R.string.cmux_billing_title), Modifier.fillMaxWidth().testTag("environment.title"))
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().testTag("environment.draft"),
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
            if (scrolling) {
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
                    repeat(24) { Text("Row $it", Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("environment.row.$it")) }
                }
            }
        }
    }

    private fun capture(name: String, root: View) {
        val painted = CountDownLatch(1)
        compose.runOnUiThread {
            assertSame(root, WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() })
            root.postOnAnimation { root.postOnAnimation { painted.countDown() } }
        }
        assertTrue(painted.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(compose.activity.getExternalFilesDir(null), "dialog-environment").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun dialogKeepsItsWindowRememberedDraftSelectionFocusAndScroll() = exercise("dialog")
    @Test fun nestedAlertKeepsBothWindowsAndItsRememberedEditor() = exercise("alert")
    @Test fun bottomSheetKeepsItsWindowSheetOwnerEditorAndScroll() = exercise("sheet")

    private fun exercise(kind: String) {
        var scale by mutableStateOf(1f)
        var language by mutableStateOf("en")
        var dark by mutableStateOf(false)
        compose.setContent {
            val configuration = remember(language, scale) { Configuration(compose.activity.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language)); fontScale = scale
            } }
            val context = remember(configuration) { android.view.ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(configuration) } }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(context.resources.displayMetrics.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CmuxTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) { Text("Shared window environment host") }
                    when (kind) {
                        "dialog" -> NativeDialog({ error("Unexpected dialog dismissal") }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                            Surface { Probe(kind, true, Modifier.fillMaxSize().safeDrawingPadding()) }
                        }
                        "alert" -> NativeDialog({ error("Unexpected parent dismissal") }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                            val token = remember { UUID.randomUUID().toString() }
                            val root = LocalView.current.rootView
                            SideEffect { parentRoot = root; parentToken = token }
                            Surface(Modifier.fillMaxSize()) { Text("Retained parent window", Modifier.safeDrawingPadding()) }
                            NativeAlertDialog({ error("Unexpected alert dismissal") },
                                title = { Text(stringResource(R.string.cmux_billing_title), Modifier.fillMaxWidth().testTag("environment.title")) },
                                text = { Probe(kind, false, Modifier.fillMaxWidth()) },
                                confirmButton = { TextButton(onClick = {}) { Text(stringResource(R.string.cmux_billing_done)) } })
                        }
                        "sheet" -> {
                            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                            SideEffect { actualSheet = sheet }
                            NativeModalBottomSheet({ error("Unexpected sheet dismissal") }, sheetState = sheet) {
                                Probe(kind, true, Modifier.fillMaxWidth().height(480.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("environment.title").assertTextEquals("Plans")
        compose.onNodeWithTag("environment.draft").performClick().performTextInput("safe draft")
        compose.onNodeWithTag("environment.draft").performTextInputSelection(TextRange(2, 5))
        compose.onNodeWithTag("environment.draft").performImeAction()
        val before = compose.runOnIdle { checkNotNull(observed) }
        compose.waitUntil(5_000) {
            before.root.rootWindowInsets?.let { !WindowInsetsCompat.toWindowInsetsCompat(it).isVisible(WindowInsetsCompat.Type.ime()) } == true
        }
        fun checkDraft() {
            compose.onNodeWithTag("environment.draft").assertTextEquals("safe draft").assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        }
        checkDraft()
        fun position(): Pair<Float, Float> {
            if (kind == "alert") return 0f to 0f
            val range = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            return range.value() to range.maxValue()
        }
        if (kind != "alert") compose.onNodeWithTag("environment.row.23").performScrollTo()
        val beforeScroll = position()
        if (kind != "alert") assertTrue(beforeScroll.first > 0)
        val oldParent = parentRoot
        val oldParentToken = parentToken
        val oldSheet = actualSheet
        if (kind == "alert") {
            assertNotNull(oldParent); assertNotNull(oldParentToken); assertNotSame(before.root, oldParent)
        }
        if (kind == "sheet") {
            assertNotNull(oldSheet); assertEquals(SheetValue.Expanded, oldSheet!!.currentValue)
        }
        capture("$kind-before", before.root)
        compose.runOnIdle { scale = 2f; language = if (kind == "alert") "ar" else "de"; dark = true }
        compose.onNodeWithTag("environment.title").assertTextEquals(if (kind == "alert") "الخطط" else "Tarife")
        val titleLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("environment.title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(2f, titleLayouts.single().layoutInput.density.fontScale, .001f)
        checkDraft()
        val after = compose.runOnIdle { checkNotNull(observed) }
        assertSame("Keep the actual window, not a replacement with restored fixture state", before.root, after.root)
        assertEquals("Keep plain remember state inside the shared content", before.token, after.token)
        assertEquals(2f, after.scale, .001f)
        assertEquals(if (kind == "alert") LayoutDirection.Rtl else LayoutDirection.Ltr, after.direction)
        assertSame(oldParent, parentRoot); assertEquals(oldParentToken, parentToken)
        assertSame(oldSheet, actualSheet)
        if (kind == "sheet") assertEquals(SheetValue.Expanded, actualSheet!!.currentValue)
        val afterScroll = position()
        assertEquals(minOf(beforeScroll.first, afterScroll.second), afterScroll.first, 1f)
        capture("$kind-after", after.root)
        val folder = File(compose.activity.getExternalFilesDir(null), "dialog-environment")
        File(folder, "$kind.json").writeText(JSONObject().put("kind", kind).put("sameWindow", true)
            .put("sameRememberedContentToken", true).put("draft", "safe draft").put("selectionStart", 2).put("selectionEnd", 5)
            .put("focusedEditorRetained", true).put("actualTitleLayoutScale", titleLayouts.single().layoutInput.density.fontScale)
            .put("beforeScroll", beforeScroll.first).put("afterScroll", afterScroll.first).put("afterMaximum", afterScroll.second)
            .put("nestedParentRetained", kind == "alert").put("sheetOwnerRetained", kind == "sheet")
            .put("scope", "Actual shared wrapper/window/editor with controlled local font/locale/theme; no OS settings, real account, physical keyboard or device acceptance").toString(2))
    }
}
