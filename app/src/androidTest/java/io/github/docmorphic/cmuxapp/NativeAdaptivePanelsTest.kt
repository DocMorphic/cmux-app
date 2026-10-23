package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.text.Selection
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real production panels with controlled data; never a real account or Cloud operation. */
@OptIn(ExperimentalTestApi::class)
class NativeAdaptivePanelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private var dark by mutableStateOf(false)
    private val files = mutableListOf<File>()
    private var peer: NativeFixturePeer? = null
    private var client: MobileRpcClient? = null
    private var cloud: CloudMachinesController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Before fun prepare() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Owned emulator only" }
    }
    @After fun cleanup() {
        compose.activity.finish(); client?.close(); peer?.close()
        compose.runOnUiThread { cloud?.close(); scope.cancel() }
        files.forEach(File::delete)
    }
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CmuxTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("adaptive.panel")) { content() }
        } } }
    }
    private fun capture(name: String, readable: String) {
        val node = compose.onNodeWithText(readable, useUnmergedTree = true)
        node.assertIsDisplayed()
        val text = node.captureToImage().asAndroidBitmap()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        node.assertIsDisplayed()
        // Material Text may reuse its TextLayoutResult while a draw-time color
        // producer changes the ink. Verify the actual raster, not stale layout color.
        val expected = if (dark) 0xFFF4F5F7.toInt() else 0xFF1C1C1E.toInt()
        var inkPixels = 0
        for (y in 0 until text.height) for (x in 0 until text.width) if (text.getPixel(x, y) == expected) inkPixels++
        File(folder, "$name.json").writeText(JSONObject().put("dark", dark).put("expectedInkArgb", expected).put("inkPixels", inkPixels)
            .put("width", image.width).put("height", image.height)
            .put("scope", "Production panel with controlled data; actual raster ink, not a matched native reference, real backend, all screens or physical-device/full parity acceptance.").toString(2))
        assertTrue("Actual foreground must adapt in $name: $inkPixels matching pixels", inkPixels >= 5)
    }
    private fun eachAppearance(action: (String) -> Unit) {
        for ((index, value) in listOf(false, true, false).withIndex()) {
            compose.runOnIdle { dark = value }; compose.waitForIdle()
            action(if (index == 2) "light-returned" else if (value) "dark" else "light")
        }
    }
    @Test fun accountSettingsAndNotificationExplanationsAdapt() {
        var selected by mutableStateOf("personal")
        show {
            NativeSettingsLayout({}, account = {
                NativeAccountTeamSection(NativeAccountTeamsState(userId = "fixture", displayName = "Appearance account",
                    email = "appearance@example.test", teams = listOf(NativeTeam("personal", "Personal"), NativeTeam("work", "Work")),
                    selectedTeamId = selected), {}, { selected = it }, onCreate = { false })
            }, computers = {}, connections = {}, preferences = {}, notifications = { NativeNotificationSettings {} })
        }
        eachAppearance { name ->
            capture("adaptive-settings-$name", "Appearance account")
            compose.onNodeWithTag("settings.team:work").performClick().assertIsSelected()
            compose.onNodeWithText("Control sounds and app icon badges in Android settings.").assertExists()
        }
    }
    @Test fun taskOptionsRetainEditsAndReadableRouteRowsAcrossAppearanceChanges() {
        var draft by mutableStateOf(TaskDraft("fixture", "mac", "My Mac", 0, directory = "/projects/cmux", workspaceName = "Appearance task"))
        show { TaskOptionsView(draft, emptyList(), TaskGroupSelection(null, emptyList(), true, true), true, null,
            { draft = draft.copy(workspaceName = it) }, {}, {}, {}, {}, {}) }
        eachAppearance { name ->
            compose.onNodeWithText("Workspace name (optional)").assertTextContains("Appearance task")
            capture("adaptive-task-options-$name", "/projects/cmux")
        }
    }
    @Test fun cloudMachineCardsAndPrimaryNavigationAdapt() {
        val machine = CloudMachine("vm", "fixture", "running", "Appearance machine", null, CloudMachineResources(4, 8192))
        val service = object : CloudMachinesService {
            override suspend fun catalog() = CloudMachineCatalog(listOf(machine), setOf(CloudMachineKind.DESKTOP),
                CloudMachineLimits(5, 1, "pro", listOf(8192), emptyList(), null, null, null))
            override suspend fun create(options: CloudMachineCreateOptions, idempotencyKey: String): CloudMachine = error("No writes")
            override suspend fun pause(id: String) = error("No writes")
            override suspend fun resume(id: String) = error("No writes")
            override suspend fun restart(id: String) = error("No writes")
            override suspend fun delete(id: String) = error("No writes")
            override fun close() {}
        }
        compose.runOnUiThread { cloud = CloudMachinesController(scope, service, object : CloudCreateJournal {
            override suspend fun resolve(options: CloudMachineCreateOptions) = error("No writes")
            override suspend fun complete(key: String) = error("No writes")
        }, { true }).also { it.refresh() } }
        show { Column(Modifier.fillMaxSize()) {
            NativeCloudScreen(cloud, {}, {}, Modifier.weight(1f))
            NativePrimaryNavigation(false, 0, NativeSearchState(), {}, {}, { _, _ -> }, {}, {}, cloudTab = true, onCloud = {})
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Appearance machine").fetchSemanticsNodes().isNotEmpty() }
        eachAppearance { name ->
            capture("adaptive-cloud-$name", "Appearance machine")
            val expected = if (dark) 0xFF69CE8D.toInt() else 0xFF237A3B.toInt()
            val background = if (dark) 0xFF191B1F.toInt() else android.graphics.Color.WHITE
            val status = compose.onNodeWithText("Running").captureToImage().asAndroidBitmap()
            var pixels = 0
            for (y in 0 until status.height) for (x in 0 until status.width) if (status.getPixel(x,y) == expected) pixels++
            assertTrue("Running must use readable semantic green in $name", pixels >= 5)
            val contrast = androidx.core.graphics.ColorUtils.calculateContrast(expected,background)
            assertTrue("Running contrast: $contrast", contrast >= 4.5)
            File(compose.activity.getExternalFilesDir(null), "screenshots/adaptive-cloud-status-$name.json")
                .writeText(JSONObject().put("foregroundArgb",expected).put("backgroundArgb",background).put("contrast",contrast).put("inkPixels",pixels).toString(2))
            status.recycle()
            compose.onNode(hasText("Cloud") and isSelected()).assertIsDisplayed()
        }
    }
    @Test fun realFileGalleryAdaptsAndRetainsItsSearchAndViewMode() {
        val fixture = NativeFixturePeer().also { peer = it }
        fixture.artifactResponse = { method, _ ->
            val item = JSONObject().put("path", "/Appearance.txt").put("kind", "text").put("size", 32)
            if (method.endsWith("scan")) JSONObject().put("session_id", "session").put("artifacts", JSONArray().put(item))
            else JSONObject().put("session_id", "session").put("generation", "g1").put("referenced", JSONArray().put(item)).put("referenced_total", 1)
        }
        val connection = MobileRpcClient(PairingCode.Route("127.0.0.1", fixture.port), { "fixture" }).also { client = it }
        runBlocking { connection.connect() }
        val rpc = ArtifactRpc(connection, setOf("terminal.artifact.v1", "chat.artifact.gallery.v1", "chat.artifact.folders.v1"))
        show { ArtifactFilesSheet(rpc, ArtifactAuthorization.Terminal("workspace", "surface")) {} }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Open file /Appearance.txt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("Appearance")
        compose.onNodeWithContentDescription("Icons").performClick()
        compose.runOnUiThread {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                it.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
            }
        }
        eachAppearance { name ->
            compose.onNode(hasSetTextAction()).assertTextContains("Appearance")
            compose.onNodeWithContentDescription("List").assertIsDisplayed()
            compose.waitUntil(10_000) {
                fixture.requests.any { it.optString("method").endsWith("gallery") && it.getJSONObject("params").optString("query") == "Appearance" } &&
                    compose.onAllNodesWithContentDescription("Open file /Appearance.txt").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Open file /Appearance.txt").performScrollTo()
            capture("adaptive-files-$name", "Appearance.txt")
        }
    }
    @Test fun actualChangedFileDiffHasReadableTextInBothAppearances() {
        val fixture = NativeFixturePeer().also { peer = it }
        fixture.changesResponse = { method, _ -> if (method.endsWith(".files"))
            JSONObject().put("workspace_id", "ws").put("repo_root", "/fixture").put("branch", "feature").put("base_ref", "main")
                .put("files_changed", 1).put("additions", 1).put("deletions", 1)
                .put("files", JSONArray().put(JSONObject().put("path", "Appearance.kt").put("status", "modified").put("additions", 1).put("deletions", 1)))
            else JSONObject().put("path", "Appearance.kt").put("unified_diff", "@@ -1 +1 @@\n-old appearance\n+new appearance\n").put("truncated", false)
        }
        val connection = MobileRpcClient(PairingCode.Route("127.0.0.1", fixture.port), { "fixture" }).also { client = it }
        runBlocking { connection.connect() }
        show { NativeChangesView(connection, "ws", "Appearance repo", {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Open diff Appearance.kt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open diff Appearance.kt").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("new appearance").fetchSemanticsNodes().isNotEmpty() }
        eachAppearance { name -> capture("adaptive-diff-$name", "new appearance") }
    }
    @Test fun nativeTerminalAndFeedReadersRecolorWithoutReplacingTheirCapturedText() {
        var route by mutableIntStateOf(0)
        val text = (1..90).joinToString("\n") { "Captured line $it remains readable" }
        val position = NativeMarkdownPosition()
        val quotePosition = ComposerScrollPosition()
        val quote = agentFeedSpanned(AgentFeedMarkdown.parse(text))
        show { when (route) {
            0 -> NativeMarkdownReader(text, position)
            1 -> TerminalTextSheet(TerminalTextSnapshot(text, false, 90)) {}
            else -> AgentFeedComposerScroll(Modifier.fillMaxSize(), quote, false, quotePosition,
                {}, {}, {}, {}, {}, {})
        } }
        for ((index, tag) in listOf("NativeMarkdownBody.0", "terminal-text-snapshot", "AgentFeedExpandedQuote").withIndex()) {
            compose.runOnIdle { route = index }
            var body: android.widget.TextView? = null
            compose.waitUntil(5_000) {
                compose.runOnUiThread { body = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .firstNotNullOfOrNull { it.findViewWithTag<android.widget.TextView>(tag) } }
                body?.text?.toString() == text
            }
            var buffer: CharSequence? = null
            compose.runOnUiThread { buffer = body!!.text; Selection.setSelection(buffer as Spannable, 5, 15) }
            eachAppearance { name ->
                val expected = if (index == 2) { if (dark) 0xFF9B9FA8.toInt() else 0xFF636366.toInt() }
                    else if (dark) 0xFFF4F5F7.toInt() else 0xFF1C1C1E.toInt()
                compose.waitUntil(5_000) {
                    var applied = false
                    compose.runOnUiThread { applied = body!!.currentTextColor == expected }
                    applied
                }
                compose.runOnUiThread {
                    assertSame(buffer, body!!.text)
                    assertEquals(5, body!!.selectionStart); assertEquals(15, body!!.selectionEnd)
                    assertEquals(expected, body!!.currentTextColor)
                    if (index != 1) assertEquals(if (dark) 0xFF76B9FF.toInt() else 0xFF007AFF.toInt(), body!!.linkTextColors.defaultColor)
                }
                val target = if (index == 1) compose.onNodeWithText("Terminal Text") else compose.onNodeWithTag("adaptive.panel")
                target.captureToImage() // Wait for this window's native drawing before compositor capture.
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val folder = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                File(folder, "adaptive-native-reader-$index-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    @Test fun rawTextRecolorsSyntaxWithoutReplacingItsBufferSelectionOrViewport() {
        val text = (1..90).joinToString("\n") { "val item$it = \"Hello\" // line $it" }
        val file = File(compose.activity.cacheDir, "appearance.kt").apply { writeText(text) }.also(files::add)
        val state = ArtifactViewerState(compose.activity, LocalFilePreview(file, file.length(), "text/plain", ChangesPreviewRoute.TEXT))
        state.document = ArtifactTextDocument(text)
        show { ArtifactRawTextPreview(state) }
        fun ready() = state.highlightedDocument === state.document && state.highlightedDark == dark && state.syntax != null
        compose.waitUntil(25_000) { ready() }
        var native: ArtifactTextScrollView? = null
        val start = text.indexOf("item45"); val end = start + 6
        compose.runOnUiThread { native = checkNotNull(findArtifactTextInWindows()); state.query = "item45" }
        compose.waitUntil(5_000) { state.matches.size == 1 && native!!.scrollY > 0 }
        compose.runOnUiThread { Selection.setSelection(native!!.textView.text as Spannable, start, end) }
        var buffer: CharSequence? = null; var scroll = 0
        compose.runOnUiThread { buffer = native!!.textView.text; scroll = native!!.scrollY }
        eachAppearance { name ->
            compose.waitUntil(25_000) { ready() }
            compose.runOnUiThread {
                val view = checkNotNull(findArtifactTextInWindows()); assertSame(native, view)
                assertSame(buffer, view.textView.text); assertEquals(scroll, view.scrollY)
                val spans = view.textView.text as Spannable
                assertEquals(start, Selection.getSelectionStart(spans)); assertEquals(end, Selection.getSelectionEnd(spans))
                val keyword = spans.getSpans(0, 3, ArtifactSyntaxSpan::class.java).single()
                assertEquals(if (dark) 0xFFFC5FA3.toInt() else 0xFFAA0D91.toInt(), keyword.color)
                val highlight = spans.getSpans(start, end, BackgroundColorSpan::class.java).single()
                assertEquals(if (dark) 0xFF8A6320.toInt() else 0xFFFFE082.toInt(), highlight.backgroundColor)
                assertEquals(if (dark) 0xFFF4F5F7.toInt() else 0xFF1C1C1E.toInt(), view.textView.currentTextColor)
            }
            compose.onNodeWithContentDescription("Raw text preview").captureToImage()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val folder = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(folder, "adaptive-code-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
