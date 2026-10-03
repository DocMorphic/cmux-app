package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.compose.ui.test.ExperimentalTestApi

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the real Compose flow and framed RPC client against a local test peer. */
@OptIn(ExperimentalTestApi::class)
class NativeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private var fixtureStarted = false
    private val observedClients = CopyOnWriteArrayList<MobileRpcClient>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startPeer() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Synthetic account tests require the disposable emulator"
        }
        fixtureStarted = true
        context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE).edit().remove("terminal-folder-tap").remove("show-missing-files").commit()
        compose.runOnUiThread {
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        peer = NativeFixturePeer()
        NativeCredentialStore(context, "native_notification_state").clear()
        NativeCredentialStore(context, "native_terminal_drafts").clear()
        TerminalDraftRepository.get(context).drafts.clear()
        NativeCredentialStore(context).update {
            it.put("refresh_token", "emulator-fixture-only")
            it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
        }
    }

    @After fun cleanUp() {
        if (!fixtureStarted) return
        context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE).edit().remove("terminal-folder-tap").remove("show-missing-files").commit()
        compose.activity.finish()
        peer.close()
        NativeCredentialStore(context).clear()
        NativeCredentialStore(context, "native_notification_state").clear()
        NativeCredentialStore(context, "native_terminal_drafts").clear()
        TerminalDraftRepository.get(context).drafts.clear()
    }

    @Test fun workspaceDeleteConfirmationCancelsThenClosesOnlySelectedWorkspace() {
        peer.notificationFeed = searchNotifications()
        showSearchFixture()
        fun openConfirmation() {
            compose.onNodeWithContentDescription("Actions for Claude Code task").performClick()
            compose.onNodeWithText("Close workspace").performClick()
            compose.onNodeWithText("Delete Workspace?").assertIsDisplayed()
            compose.onNodeWithText("This will close the workspace on your Mac.").assertIsDisplayed()
            compose.onNodeWithTag("workspace.close.confirm").assertTextEquals("Delete")
        }
        openConfirmation(); screenshot("mac-workspace-delete-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(peer.requests.none { it.optString("method") == "workspace.close" })
        compose.onNodeWithText("Claude Code task").assertExists()
        openConfirmation(); compose.onNodeWithTag("workspace.close.confirm").performClick()
        compose.waitUntil(10000) { peer.requests.any { it.optString("method") == "workspace.close" } }
        compose.onNodeWithText("Delete Workspace?").assertDoesNotExist()
        val request = peer.requests.single { it.optString("method") == "workspace.close" }.getJSONObject("params")
        assertEquals("workspace-1", request.getString("workspace_id"))
        assertEquals("fixture-window", request.getString("window_id"))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Open Completed group").assertIsDisplayed()
    }

    @Test fun accountDeletionConfirmationCancelsInNativeSettingsWithoutChangingSession() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        val login = NativeCredentialStore(context).taskSession()
        compose.onNodeWithContentDescription("cmux settings").performClick()
        compose.onNodeWithText("Delete Account").performScrollTo().performClick()
        compose.onNodeWithText("Delete Account?").assertIsDisplayed()
        compose.onNodeWithText("This permanently deletes your cmux account and cmux data. You will be signed out on this device.").assertIsDisplayed()
        screenshot("native-account-delete-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(login, NativeCredentialStore(context).taskSession())
        assertNull(NativeAccountDeletionRecord.read(NativeCredentialStore(context).load()))
        compose.onNodeWithText("Sign out").performScrollTo().performClick()
        compose.waitUntil(5_000) { NativeCredentialStore(context).taskSession() == null }
        assertFalse(NativeAccount(NativeCredentialStore(context)).isSignedIn())
    }

    @Test fun terminalFilesChipAndRelativePathTapUseNativeTerminalRoute() {
        context.getSharedPreferences("native_display", android.content.Context.MODE_PRIVATE).edit().putFloat("terminal_scale", 1f).commit()
        peer.artifactsSupported = true
        peer.gridFirstLine = "open notes.md"
        val body = "Native terminal path preview"
        peer.artifactResponse = { method, _ -> when {
            method.endsWith("scan") -> JSONObject().put("session_id", "session").put("gallery_row_total", 1)
            method.endsWith("gallery") -> JSONObject().put("session_id", "session").put("referenced", JSONArray().put(JSONObject().put("path", "/fixture/notes.md").put("kind", "text")))
            method.endsWith("stat") -> JSONObject().put("exists", true).put("is_directory", false).put("kind", "text").put("size", body.length)
            else -> JSONObject().put("offset", 0).put("total_size", body.length).put("eof", true)
                .put("data_b64", java.util.Base64.getEncoder().encodeToString(body.toByteArray()))
        } }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Open files in view").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open files in view").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Open file /fixture/notes.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Done").performClick()
        val terminal = compose.onNodeWithText("open notes.md", substring = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("open notes.md", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val viewport = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" && !it.getJSONObject("params").optBoolean("clear") }.getJSONObject("params")
        val metrics = context.resources.displayMetrics
        val cells = TerminalCellMetrics.fromFontSize(TerminalFontSize.DEFAULT * metrics.scaledDensity, 2f * metrics.density)
        val bounds = terminal.fetchSemanticsNode().boundsInRoot
        val geometry = TerminalSharedGridLayout.resolve(bounds.width, bounds.height, viewport.getInt("viewport_columns"), viewport.getInt("viewport_rows"), cells, metrics.density)!!.geometry
        terminal.performTouchInput { click(androidx.compose.ui.geometry.Offset(geometry.originX + geometry.cellWidth * 7.5f, geometry.originY + geometry.cellHeight * .5f)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Rendered Markdown").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Viewer actions").performClick()
        compose.onNodeWithText("Raw").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            var loaded = false
            compose.runOnUiThread { loaded = findArtifactTextInWindows()?.textView?.text?.toString() == body }
            loaded
        }
        assertTrue(peer.requests.any { it.optString("method") == "mobile.terminal.artifact.stat" && it.getJSONObject("params").optString("path") == "notes.md" })
        assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.mouse" })
    }

    @Test fun folderTapPreferencePersistsAndChangesActualTerminalTapBehavior() {
        context.getSharedPreferences("native_display", android.content.Context.MODE_PRIVATE).edit().putFloat("terminal_scale", 1f).commit()
        peer.artifactsSupported = true
        peer.gridFirstLine = "open ./folder"
        peer.artifactResponse = { method, _ -> when {
            method.endsWith("scan") -> JSONObject().put("gallery_row_total", 1)
            method.endsWith("stat") -> JSONObject().put("exists", true).put("is_directory", true).put("kind", "directory")
            method.endsWith("list") -> JSONObject().put("entries", JSONArray().put(JSONObject().put("name", "note.txt").put("kind", "text").put("is_directory", false)))
            else -> JSONObject()
        } }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("cmux settings").performClick()
        compose.onNodeWithContentDescription("Open Folders on Tap").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithContentDescription("Show Missing Files").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithText("‹  Back").performScrollTo().performClick()
        assertTrue(!context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE).getBoolean("terminal-folder-tap", true))
        compose.onNodeWithText("Claude Code task").performClick()
        tapArtifactCell("open ./folder", 8.5f)
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.mouse" } }
        compose.onNodeWithContentDescription("Open file ./folder/note.txt").assertDoesNotExist()
        assertTrue(peer.requests.none { it.optString("method").endsWith("artifact.list") })
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("cmux settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("cmux settings").performClick()
        compose.onNodeWithContentDescription("Open Folders on Tap").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithContentDescription("Show Missing Files").performScrollTo().assertIsOn()
        compose.onNodeWithText("‹  Back").performScrollTo().performClick()
        compose.onNodeWithText("Claude Code task").performClick()
        val clicks = peer.requests.count { it.optString("method") == "mobile.terminal.mouse" }
        tapArtifactCell("open ./folder", 8.5f)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Open file ./folder/note.txt").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(clicks, peer.requests.count { it.optString("method") == "mobile.terminal.mouse" })
    }

    private fun tapArtifactCell(text: String, column: Float) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        val terminal = compose.onNodeWithText(text, substring = true)
        val viewport = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" && !it.getJSONObject("params").optBoolean("clear") }.getJSONObject("params")
        val metrics = context.resources.displayMetrics
        val cells = TerminalCellMetrics.fromFontSize(TerminalFontSize.DEFAULT * metrics.scaledDensity, 2f * metrics.density)
        val bounds = terminal.fetchSemanticsNode().boundsInRoot
        val geometry = TerminalSharedGridLayout.resolve(bounds.width, bounds.height, viewport.getInt("viewport_columns"), viewport.getInt("viewport_rows"), cells, metrics.density)!!.geometry
        terminal.performTouchInput { click(androidx.compose.ui.geometry.Offset(geometry.originX + geometry.cellWidth * column, geometry.originY + geometry.cellHeight * .5f)) }
    }

    @Test fun longTerminalTitleKeepsKeyboardAndBackControlsVisible() {
        val title = "clear; for i in 1 2 3 4 5 6; do echo cmux_net_\$i; sleep 10; done"
        peer.customWorkspaceListing = JSONObject().put("workspaces", JSONArray().put(
            JSONObject().put("id", "workspace-1").put("title", "Reconnect test")
                .put("terminals", JSONArray().put(JSONObject().put("id", "terminal-1").put("title", title)))))
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Reconnect test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Reconnect test").performClick()
        waitForTerminalText()
        compose.onNodeWithContentDescription("Back to workspaces").assertIsDisplayed()
        compose.onNodeWithText("Keyboard").assertIsDisplayed().performClick()
        compose.onNodeWithText("Compose").assertIsDisplayed().performClick()
        compose.onNodeWithText("$title ▾").performClick()
        compose.onNodeWithText("View as Text").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.onNodeWithText("Reconnect test").assertIsDisplayed()
    }

    @Test fun workspaceFilterTerminalInputAndKeyboardResize() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Open Completed group").assertIsDisplayed()
        screenshot("workspaces")
        compose.onNodeWithText("☷").performClick()
        compose.onNodeWithText("Unread").performClick()
        compose.onNodeWithText("Read project").assertDoesNotExist()
        compose.onNodeWithText("Completed group").assertDoesNotExist()
        compose.onNodeWithText("Claude Code task").performClick()

        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        waitForTerminalText()
        val initialRows = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" && !it.getJSONObject("params").optBoolean("clear") }
            .getJSONObject("params").getInt("viewport_rows")
        val initialHeight = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height
        val beforeKeyboard = peer.requests.size
        screenshot("terminal")
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("printf cmux")
        try { compose.waitUntil(10_000) {
            compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height < initialHeight - 50
        } } finally {
            waitForTerminalText()
            screenshot("terminal-keyboard")
        }
        assertTrue("Opening the keyboard must not reflow the primary PTY", peer.requests.drop(beforeKeyboard)
            .filter { it.optString("method") == "mobile.terminal.viewport" && !it.getJSONObject("params").optBoolean("clear") }
            .all { it.getJSONObject("params").getInt("viewport_rows") == initialRows })
        compose.onNodeWithText("The coroutine scope left the composition").assertDoesNotExist()
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "terminal.paste" } }
        val input = peer.requests.filter { it.optString("method") == "terminal.paste" }
        assertEquals(1, input.size)
        assertEquals("printf cmux", input.single().getJSONObject("params").getString("text"))
        assertEquals("return", input.single().getJSONObject("params").getString("submit_key"))
        assertTrue(peer.requests.none { it.optString("method") == "terminal.input" })
        compose.onNodeWithText("‹  2").performClick()
        compose.waitUntil(10_000) {
            peer.requests.any {
                it.optString("method") == "mobile.terminal.viewport" &&
                    it.getJSONObject("params").optBoolean("clear")
            }
        }
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun multilineDraftSurvivesNavigationAndRejectedSend() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val message = "Review these changes\nKeep the existing API"
        compose.onNode(hasSetTextAction()).performTextInput(message)
        compose.onNodeWithText("‹  2").performClick()
        openReadProject()
        assertDraft("")
        compose.onNode(hasSetTextAction()).performTextInput("Separate draft")
        compose.onNodeWithText("‹  2").performClick()
        compose.onNodeWithText("Claude Code task").performClick()
        assertDraft(message)
        peer.rejectNextPaste.set(true)
        val releasePaste = CountDownLatch(1)
        peer.releaseNextPaste = releasePaste
        compose.onNodeWithText("Send").performClick()
        try {
            compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "terminal.paste" } }
            compose.onNodeWithText("Sending…").assertIsNotEnabled()
            val pending = TerminalDrafts(NativeCredentialStore(context, "native_terminal_drafts")
                .load()?.optJSONArray("drafts"))
            assertTrue(pending.state.value.values.any { it.text == message && it.error != null })
        } finally { releasePaste.countDown() }
        compose.waitUntil(10_000) {
            TerminalDraftRepository.get(context).drafts.state.value.values.any {
                it.text == message && it.error == TerminalDrafts.DELIVERY_UNCONFIRMED
            }
        }
        compose.onNodeWithText(TerminalDrafts.DELIVERY_UNCONFIRMED).assertIsDisplayed()
        assertDraft(message)
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.paste" })
        screenshot("composer-rejected-send")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) {
            peer.requests.count { it.optString("method") == "terminal.paste" } == 2 &&
                TerminalDraftRepository.get(context).drafts.state.value.values.none { it.text == message }
        }
        assertDraft("")
        val requests = peer.requests.filter { it.optString("method") == "terminal.paste" }
        requests.forEach { request ->
            assertEquals(message, request.getJSONObject("params").getString("text"))
            assertEquals("return", request.getJSONObject("params").getString("submit_key"))
            assertEquals("workspace-1", request.getJSONObject("params").getString("workspace_id"))
            assertEquals("terminal-1", request.getJSONObject("params").getString("surface_id"))
        }
        compose.onNodeWithText("‹  2").performClick()
        openReadProject()
        assertDraft("Separate draft")
        compose.waitUntil(10_000) {
            val saved = NativeCredentialStore(context, "native_terminal_drafts").load()?.optJSONArray("drafts")
            TerminalDrafts(saved).state.value.values.any { it.text == "Separate draft" }
        }
        assertEquals("emulator-fixture-only", NativeCredentialStore(context).load()?.optString("refresh_token"))
        compose.onNodeWithText("Insert").performClick()
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.paste" } == 3 }
        val inserted = peer.requests.last { it.optString("method") == "terminal.paste" }.getJSONObject("params")
        assertEquals("none", inserted.getString("submit_key"))
        assertEquals("Separate draft", inserted.getString("text"))
        assertEquals("terminal-2", inserted.getString("surface_id"))
    }

    @Test fun lostComposerAcknowledgementReconnectsWithoutResendingAndKeepsWarning() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                    .also { it.connect(); observedClients += it }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val command = "echo delivered_before_reply_was_lost"
        compose.onNode(hasSetTextAction()).performTextInput(command)
        peer.dropReplyAfterMethod = "terminal.paste"
        val oldClients = observedClients.toList()
        val oldReplays = peer.requests.count { it.optString("method") == "mobile.terminal.replay" }
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(15_000) {
            TerminalDraftRepository.get(context).drafts.state.value.values.any {
                it.text == command && it.error == TerminalDrafts.DELIVERY_UNCONFIRMED
            }
        }
        // Wait for the real reconnect/handshake/replay path, not a manual Retry.
        compose.waitUntil(20_000) {
            observedClients.any { it !in oldClients && !it.isClosed } &&
                peer.requests.count { it.optString("method") == "mobile.terminal.replay" } > oldReplays
        }
        waitForTerminalText()
        assertDraft(command)
        compose.onNodeWithText(TerminalDrafts.DELIVERY_UNCONFIRMED).assertIsDisplayed()
        compose.waitUntil(10_000) {
            TerminalDrafts(NativeCredentialStore(context, "native_terminal_drafts").load()?.optJSONArray("drafts"))
                .state.value.values.any { it.text == command && it.error == TerminalDrafts.DELIVERY_UNCONFIRMED }
        }
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.paste" })
        assertEquals(listOf("terminal.paste"), peer.lostReplies.toList())
        screenshot("composer-lost-reply-reconnected")
        // A new explicit action succeeds; the earlier uncertain command never returns.
        compose.onNode(hasSetTextAction()).performTextReplacement("echo new_explicit_command")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) {
            peer.requests.count { it.optString("method") == "terminal.paste" } == 2 &&
                TerminalDraftRepository.get(context).drafts.state.value.values.none { it.operation != null }
        }
        assertDraft("")
        assertEquals(listOf(command, "echo new_explicit_command"), peer.requests
            .filter { it.optString("method") == "terminal.paste" }.map { it.getJSONObject("params").getString("text") })
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun attachmentPickerStagesEncryptsAndSendsAfterExplicitRetry() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val file = File(context.cacheDir, "attachment-fixture.txt").apply { writeText("Private fixture content") }
        val photo = File(context.cacheDir, "attachment-fixture.png")
        Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val repo = TerminalDraftRepository.get(context)
        val target = TerminalDrafts.Target("cmux-ios://attach?v=2&r=100.64.0.1:58465", "workspace-1", "terminal-1")
        fun choose(file: File, menu: String) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
            },
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))), true)
            try {
                compose.onNodeWithContentDescription("Add attachment").performClick()
                compose.onNodeWithText(menu).performClick()
                compose.waitUntil(10_000) { monitor.hits > 0 }
            } finally { instrumentation.removeMonitor(monitor) }
        }
        choose(photo, "Photos")
        compose.waitUntil(15_000) { repo.drafts.state.value[target]?.attachments?.size == 1 }
        val image = repo.drafts.state.value[target]!!.attachments.single()
        val imageBytes = runBlocking { repo.read(image) }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        assertEquals(2048, bounds.outWidth)
        assertEquals(1024, bounds.outHeight)
        choose(file, "Files")
        compose.waitUntil(15_000) { repo.drafts.state.value[target]?.attachments?.size == 2 }
        val attachment = repo.drafts.state.value[target]!!.attachments.last()
        assertEquals("Private fixture content", runBlocking { String(repo.read(attachment)) })
        val onDisk = File(context.noBackupFilesDir, "terminal-attachments/${attachment.id}").readBytes()
        assertTrue(!String(onDisk).contains("Private fixture content"))
        runBlocking { repo.persistNow() }
        val restored = TerminalDrafts(NativeCredentialStore(context, "native_terminal_drafts").load()?.getJSONArray("drafts"))
        assertEquals(listOf(image, attachment), restored.state.value[target]!!.attachments)
        compose.onNode(hasSetTextAction()).performTextInput("Explain these attachments")
        screenshot("composer-attachments")
        peer.rejectNextPaste.set(true)
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(15_000) { repo.drafts.state.value[target]?.error != null }
        assertEquals(listOf(attachment), repo.drafts.state.value[target]!!.attachments)
        assertDraft("Explain these attachments")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(15_000) { repo.drafts.state.value[target] == null }
        assertDraft("")
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.paste_image" })
        val uploads = peer.requests.filter { it.optString("method") == "mobile.task.attachment.upload" }
        assertEquals(2, uploads.size)
        uploads.forEach { request ->
            val params = request.getJSONObject("params")
            assertEquals(attachment.id, params.getString("operation_id"))
            assertEquals(attachment.id, params.getString("upload_id"))
            assertEquals("Private fixture content", String(java.util.Base64.getDecoder().decode(params.getString("data_b64"))))
        }
        val paste = peer.requests.last { it.optString("method") == "terminal.paste" }.getJSONObject("params")
        assertEquals("'/tmp/cmux fixture.txt' Explain these attachments", paste.getString("text"))
        runBlocking { repo.persistNow() }
        assertTrue(!File(context.noBackupFilesDir, "terminal-attachments/${attachment.id}").exists())
        file.delete(); photo.delete()
    }

    private fun showTodoFixture() {
        peer.todoSupported = true
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"todo-workspace","title":"Checklist workspace","terminals":[],"surfaces":[
            {"surface_id":"todo-panel","kind":"todo","title":"Todo","todo":{"status":"working","status_hidden":false,"items":[
                {"id":"a","text":"First","state":"pending","origin":"user"},
                {"id":"b","text":"Second","state":"in_progress","origin":"agent"},
                {"id":"c","text":"Completed","state":"completed","origin":"agent"}
            ]}}
        ]}]}""")
        peer.todoResponse = { method, params ->
            check(params.getString("workspace_id") == "todo-workspace")
            check(!params.has("surface_id"))
            val listing = JSONObject(peer.customWorkspaceListing.toString())
            val todo = listing.getJSONArray("workspaces").getJSONObject(0).getJSONArray("surfaces").getJSONObject(0).getJSONObject("todo")
            val rows = todo.getJSONArray("items").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
            when (method) {
                "mobile.todo.add" -> rows.add(rows.indexOfFirst { it.getString("state") == "completed" }.let { if (it < 0) rows.size else it },
                    JSONObject().put("id", "mac-added").put("text", params.getString("text")).put("state", "pending").put("origin", "user"))
                "mobile.todo.set_state" -> {
                    val row = rows.single { it.getString("id") == params.getString("id") }
                    row.put("state", params.getString("state"))
                    if (row.getString("state") == "completed") { rows.remove(row); rows.add(row) }
                }
                "mobile.todo.edit" -> rows.single { it.getString("id") == params.getString("id") }.put("text", params.getString("text"))
                "mobile.todo.move" -> {
                    val row = rows.single { it.getString("id") == params.getString("id") }; rows.remove(row)
                    rows.add(params.getInt("to_index").coerceIn(0, rows.size), row)
                }
                "mobile.todo.remove" -> rows.removeAll { it.getString("id") == params.getString("id") }
                "mobile.status.set" -> todo.put("status", params.getString("status").let { if (it == "auto") "working" else it })
                else -> error("Unexpected Todo mutation $method")
            }
            todo.put("items", JSONArray(rows)); peer.customWorkspaceListing = listing
            JSONObject()
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                    .also { it.connect(); observedClients += it }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Checklist workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Checklist workspace").performClick()
        waitForTerminalFixture(10_000) { compose.onAllNodesWithText("1 of 3 done").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 of 3 done").assertIsDisplayed()
    }

    @Test fun todoLostAddReplyReconcilesHostSnapshotWithoutResending() {
        try {
            showTodoFixture()
            val connectionsBefore = observedClients.size
            peer.dropReplyAfterMethod = "mobile.todo.add"
            compose.onNodeWithTag("todo-new-item").performTextInput("Added exactly once")
            compose.onNodeWithContentDescription("Add checklist item").performClick()
            compose.waitUntil(15_000) { "mobile.todo.add" in peer.lostReplies }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Couldn’t Update Checklist").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("OK").performClick()
            compose.waitUntil(20_000) { observedClients.size > connectionsBefore &&
                compose.onAllNodesWithTag("todo-row-mac-added").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Added exactly once").assertIsDisplayed()
            assertEquals(1, peer.requests.count { it.optString("method") == "mobile.todo.add" })
            screenshot("todo-reconnected")
        } catch (failure: Throwable) { screenshot("todo-reconnect-failure"); throw failure }
    }

    @Test fun todoPanelEditsOrdersStatusesAndRollsBackRejectedDeleteThroughNativeRpc() {
        try {
            showTodoFixture()
        fun waitControl(label: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
        }
        compose.onNodeWithContentDescription("Mark First as in progress").performClick()
        waitControl("Mark First as completed")
        compose.onNodeWithContentDescription("Mark First as completed").performClick()
        waitControl("Mark First as pending")
        compose.onNodeWithText("2 of 3 done").assertIsDisplayed()
        compose.onNodeWithText("Second").performClick()
        compose.onNodeWithTag("todo-edit-b").performTextReplacement("Edited second")
        compose.onNodeWithTag("todo-edit-b").performImeAction()
        waitControl("Mark Edited second as completed")
        compose.onNodeWithTag("todo-new-item").performTextInput("Ship 你好")
        compose.onNodeWithContentDescription("Add checklist item").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("todo-row-mac-added").fetchSemanticsNodes().isNotEmpty() }
        waitControl("Mark Ship 你好 as in progress")
        // Dismiss the real IME before dragging the whole checklist.
        compose.runOnUiThread { (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val bounds = compose.onNodeWithTag("todo-list").fetchSemanticsNode().boundsInRoot
        val from = compose.onNodeWithTag("todo-row-mac-added").fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithTag("todo-row-b").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("todo-list").performTouchInput {
            val x = width - 12f
            down(androidx.compose.ui.geometry.Offset(x, from.center.y - bounds.top)); advanceEventTime(700)
            moveTo(androidx.compose.ui.geometry.Offset(x, to.center.y - bounds.top), delayMillis = 150); up()
        }
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.todo.move" } }
        waitControl("Mark Ship 你好 as in progress")
        val moved = peer.requests.last { it.optString("method") == "mobile.todo.move" }.getJSONObject("params")
        assertEquals("mac-added", moved.getString("id")); assertEquals(0, moved.getInt("to_index"))
        compose.onNodeWithContentDescription("Choose status").performClick()
        compose.onNodeWithText("Review").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Review")).fetchSemanticsNodes().isNotEmpty() }
        waitControl("Choose status")
        screenshot("todo-checklist")
        compose.onNodeWithContentDescription("Choose status").performClick()
        compose.onNodeWithText("Automatic").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working")).fetchSemanticsNodes().isNotEmpty() }
        waitControl("Choose status")
        peer.rejectNextTodo.set(true)
        compose.onNodeWithTag("todo-row-a").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn’t Update Checklist").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, peer.requests.count { it.optString("method") == "mobile.todo.remove" })
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("First").assertIsDisplayed()
        compose.onNodeWithTag("todo-row-mac-added").performTouchInput { swipeLeft() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Ship 你好").fetchSemanticsNodes().isEmpty() }
        assertEquals("mac-added", peer.requests.last { it.optString("method") == "mobile.todo.remove" }.getJSONObject("params").getString("id"))
        assertTrue(peer.requests.none { it.optString("method").startsWith("mobile.terminal.") || it.optString("method") == "mobile.surface.focus" })
        } catch (failure: Throwable) { screenshot("todo-flow-failure"); throw failure }
    }

    private fun showMixedPickerWorkspace() {
        peer.browserCreationSupported = true
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"mixed","title":"Mixed workspace",
            "terminals":[{"id":"shell","title":"Shell","is_focused":true}],"surfaces":[
                {"surface_id":"web","kind":"browser","title":"Preview"},
                {"surface_id":"canvas","kind":"future.canvas","title":"Canvas"}]}]}""")
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Mixed workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mixed workspace").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Shell ▾").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun pickMixedPane(tag: String) {
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithTag("terminal-picker-$tag").performScrollTo().performClick()
    }

    @Test fun feedbackFromTerminalAndSettingsCancelsWithoutLosingSessionOrPane() {
        showMixedPickerWorkspace()
        val login = NativeCredentialStore(context).taskSession()
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithText("Send Feedback").performScrollTo().performClick()
        compose.onNodeWithTag("feedback-message").performTextInput("Unsent fixture note")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("native-terminal").assertExists()
        compose.onNodeWithText("Shell ▾").assertExists()
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.onNodeWithContentDescription("cmux settings").performClick()
        compose.onNodeWithText("Send Feedback").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("feedback-send").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feedback-send").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(login, NativeCredentialStore(context).taskSession())
        assertTrue(peer.requests.none { it.optString("method") == "dogfood.feedback.submit" })
    }

    @Test fun sharedPickerNavigatesBrowserSurfaceAndTerminalWithExactSelection() {
        showMixedPickerWorkspace()
        pickMixedPane("browser-web")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Browser fixture ▾").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithTag("terminal-picker-browser-web").assertIsSelected()
        compose.onNodeWithTag("terminal-picker-terminal-shell").assertIsNotSelected()
        compose.onNodeWithTag("terminal-picker-surface-canvas").assertIsNotSelected().performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Canvas ▾").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { peer.requests.any { it.optString("method") == "mobile.browser.stream.stop" } }
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithTag("terminal-picker-surface-canvas").assertIsSelected()
        compose.onNodeWithTag("terminal-picker-browser-web").assertIsNotSelected()
        compose.onNodeWithText("New Workspace").assertIsEnabled()
        compose.onNodeWithText("New Terminal").assertIsEnabled()
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Mac Surfaces")), 5_000))
        device.waitForIdle(1_000); screenshot("shared-pane-picker-surface")
        compose.onNodeWithTag("terminal-picker-terminal-shell").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Shell ▾").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-terminal").assertExists()
        assertEquals(setOf("web"), peer.requests.filter { it.optString("method") == "mobile.browser.stream.start" }
            .map { it.getJSONObject("params").getString("panel_id") }.toSet())
    }

    @Test fun streamedBrowserPickerCreatesTerminalInItsWorkspace() {
        showMixedPickerWorkspace(); pickMixedPane("browser-web")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Browser fixture ▾").fetchSemanticsNodes().isNotEmpty() }
        peer.terminalCreationResponse = { params ->
            assertEquals("mixed", params.getString("workspace_id"))
            val listing = JSONObject(peer.customWorkspaceListing.toString())
            listing.getJSONArray("workspaces").getJSONObject(0).getJSONArray("terminals")
                .put(JSONObject().put("id", "created-shell").put("title", "Created shell"))
            peer.customWorkspaceListing = listing
            JSONObject(listing.toString()).put("created_terminal_id", "created-shell")
        }
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithText("New Terminal").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Created shell ▾").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.create" })
        compose.onNodeWithTag("native-terminal").assertExists()
    }

    @Test fun macSurfacePickerCreatesWorkspaceAndSelectsItsTerminal() {
        showMixedPickerWorkspace(); pickMixedPane("surface-canvas")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Canvas ▾").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithText("New Workspace").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Agent ▾").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, peer.requests.count { it.optString("method") == "workspace.create" })
        compose.onNodeWithTag("native-terminal").assertExists()
    }

    @Test fun unsupportedBrowserUsesSurfaceCardAndFocusesExactPanelWithoutStreamRequests() {
        peer.panelArtifactsSupported = true
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"old-mac","title":"Legacy browser workspace","terminals":[],"surfaces":[
            {"surface_id":"legacy-browser","kind":"browser","title":"Legacy preview","is_focused":true}
        ]}]}""")
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                    .also { it.connect(); observedClients += it }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Legacy browser workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Legacy browser workspace").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Open on Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Browser · In “Legacy browser workspace”").assertIsDisplayed()
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithTag("terminal-picker-browser-legacy-browser").assertIsSelected()
        compose.onNodeWithText("Mac Surfaces").assertExists()
        compose.onNodeWithTag("terminal-picker-browser-update").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-picker-browser-legacy-browser").performClick()
        compose.onNodeWithText("Open on Mac").performClick()
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.surface.focus" } }
        val params = peer.requests.single { it.optString("method") == "mobile.surface.focus" }.getJSONObject("params")
        assertEquals("old-mac", params.getString("workspace_id"))
        assertEquals("legacy-browser", params.getString("surface_id"))
        assertTrue(peer.requests.none { it.optString("method").startsWith("mobile.browser.") })
        screenshot("legacy-browser-fallback")
    }

    @Test fun panelOnlyWorkspacePreviewsExactFileAndMarkdownAndFocusesUnknownSurface() {
        peer.panelArtifactsSupported = true
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"panels","title":"Panel workspace","terminals":[],"surfaces":[
            {"surface_id":"file","kind":"filePreview","title":"Text panel","file_path":"/fixture/notes.txt"},
            {"surface_id":"markdown","kind":"markdown","title":"Markdown panel","file_path":"/fixture/extensionless"},
            {"surface_id":"canvas","kind":"future.canvas","title":"Canvas panel","is_focused":true}
        ]}]}""")
        val text = "Panel-scoped text preview"
        val markdown = "# Panel Markdown\n\n**Rendered on Android** through the panel RPC."
        peer.artifactResponse = { method, params ->
            check(method.startsWith("mobile.panel.artifact."))
            check(params.getString("workspace_id") == "panels")
            val surface = params.getString("surface_id")
            val path = if (surface == "file") "/fixture/notes.txt" else "/fixture/extensionless"
            check(params.getString("path") == path)
            val data = (if (surface == "file") text else markdown).toByteArray()
            if (method.endsWith("stat")) JSONObject().put("exists", true).put("is_directory", false).put("kind", "text").put("size", data.size)
            else JSONObject().put("offset", 0).put("total_size", data.size).put("eof", true)
                .put("data_b64", java.util.Base64.getEncoder().encodeToString(data))
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                    .also { it.connect(); observedClients += it }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Panel workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Panel workspace").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Canvas panel ▾").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Other Surface · In “Panel workspace”").assertIsDisplayed()
        assertTrue(peer.requests.none { it.optString("method") == "mobile.surface.focus" })
        compose.onNodeWithText("Canvas panel ▾").performClick()
        compose.onNodeWithText("Text panel").performClick()
        compose.waitUntil(10_000) {
            var loaded = false
            compose.runOnUiThread { loaded = findArtifactTextInWindows()?.textView?.text?.toString() == text }
            loaded
        }
        screenshot("panel-file-preview")
        assertTrue(peer.requests.none { it.optString("method") == "mobile.surface.focus" })
        compose.onNodeWithText("Text panel ▾").performClick()
        compose.onNodeWithText("Canvas panel").performClick()
        compose.onNodeWithText("Other Surface · In “Panel workspace”").assertIsDisplayed()
        compose.onNodeWithText("Open on Mac").performClick()
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.surface.focus" } }
        val focus = peer.requests.last { it.optString("method") == "mobile.surface.focus" }.getJSONObject("params")
        assertEquals("panels", focus.getString("workspace_id")); assertEquals("canvas", focus.getString("surface_id"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Open on Mac").fetchSemanticsNodes().isNotEmpty() }
        screenshot("panel-unknown-surface")
        compose.onNodeWithText("Canvas panel ▾").performClick()
        compose.onNodeWithText("Markdown panel").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Rendered Markdown").fetchSemanticsNodes().isNotEmpty() }
        fun findPanelWeb(view: View): android.webkit.WebView? = when (view) {
            is android.webkit.WebView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findPanelWeb(view.getChildAt(it)) }
            else -> null
        }
        compose.waitUntil(15_000) {
            val done = CountDownLatch(1)
            val rendered = AtomicBoolean(false)
            compose.runOnUiThread {
                val web = findPanelWeb(compose.activity.window.decorView)
                if (web == null) done.countDown() else web.evaluateJavascript(
                    "document.querySelector('h1')?.textContent === 'Panel Markdown'") { rendered.set(it == "true"); done.countDown() }
            }
            done.await(3, TimeUnit.SECONDS) && rendered.get()
        }
        compose.waitUntil(15_000) {
            val bitmap = compose.onNodeWithContentDescription("Rendered Markdown").captureToImage().asAndroidBitmap()
            var ink = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (minOf(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color)) > 140) ink++
            }
            ink > 500
        }
        screenshot("panel-markdown-preview")
        compose.onNodeWithContentDescription("Viewer actions").performClick()
        compose.onNodeWithText("Raw").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            var loaded = false
            compose.runOnUiThread { loaded = findArtifactTextInWindows()?.textView?.text?.toString() == markdown }
            loaded
        }
        assertTrue(peer.requests.none { it.optString("method").startsWith("mobile.terminal.") || it.optString("method").startsWith("mobile.chat.artifact.") })
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.onNodeWithContentDescription("Open Markdown panel").assertIsDisplayed()
    }

    @Test fun terminalZoomPinchesReflowsAndScopesHostFontEvents() {
        val preferences = context.getSharedPreferences("native_display", android.content.Context.MODE_PRIVATE)
        val previous = preferences.all[TerminalFontSize.SAVED_KEY] as? Float
        preferences.edit().remove(TerminalFontSize.SAVED_KEY).commit()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                    .also { it.connect(); observedClients += it }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.mainClock.autoAdvance = false
        fun pump() { compose.mainClock.advanceTimeBy(160); compose.waitForIdle() }
        fun sizeIs(size: Float): Boolean = compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Terminal font size $size")).fetchSemanticsNodes().isNotEmpty()
        fun awaitSize(size: Float) = compose.waitUntil(10_000) { pump(); sizeIs(size) }
        fun viewportColumns() = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" &&
            !it.getJSONObject("params").optBoolean("clear") }
            .getJSONObject("params").getInt("viewport_columns")
        fun push(size: Int, surface: String? = null, workspace: String? = null) {
            peer.pushTerminalEvent("terminal.set_font", JSONObject().put("font_size", size)
                .put("surface_id", surface).put("workspace_id", workspace))
            pump()
        }
        try {
            val initialColumns = viewportColumns()
            assertTrue(sizeIs(10f))
            push(18, surface = "other", workspace = "workspace-1")
            assertTrue(sizeIs(10f))
            push(18, workspace = "other")
            assertTrue(sizeIs(10f))
            push(18, surface = "terminal-1", workspace = "other")
            awaitSize(18f)
            compose.waitUntil(10_000) { pump(); viewportColumns() < initialColumns }
            compose.onNodeWithTag("terminal-zoom-size").assertDoesNotExist()
            // Pinch must continue over multiple resized/replayed frames, with no terminal click/scroll.
            val scrolls = peer.requests.count { it.optString("method") == "mobile.terminal.scroll" }
            val clicks = peer.requests.count { it.optString("method") == "mobile.terminal.mouse" }
            compose.onNodeWithTag("native-terminal").performTouchInput {
                down(0, androidx.compose.ui.geometry.Offset(centerX - width * .12f, centerY))
                down(1, androidx.compose.ui.geometry.Offset(centerX + width * .12f, centerY))
            }
            // Keep the same two fingers down while each resized viewport composes.
            for (distance in listOf(.15f, .18f, .21f, .24f, .27f, .3f)) {
                compose.onNodeWithTag("native-terminal").performTouchInput {
                    updatePointerTo(0, androidx.compose.ui.geometry.Offset(centerX - width * distance, centerY))
                    updatePointerTo(1, androidx.compose.ui.geometry.Offset(centerX + width * distance, centerY))
                    move(delayMillis = 50)
                }
                pump()
            }
            compose.onNodeWithTag("native-terminal").performTouchInput { up(0); up(1) }
            pump()
            compose.onNodeWithTag("terminal-zoom-size").assertIsDisplayed()
            compose.onNodeWithContentDescription("Set as default").performClick(); pump()
            val saved = preferences.getFloat(TerminalFontSize.SAVED_KEY, 0f)
            assertTrue("Pinch survives repeated viewport changes", saved > 19f)
            assertEquals(scrolls, peer.requests.count { it.optString("method") == "mobile.terminal.scroll" })
            assertEquals(clicks, peer.requests.count { it.optString("method") == "mobile.terminal.mouse" })
            screenshot("terminal-zoom-controls")
            push(12); awaitSize(12f)
            compose.onNodeWithContentDescription("Reset to default").performClick(); pump(); assertTrue(sizeIs(saved))
            compose.onNodeWithContentDescription("Restore built-in").performClick(); pump()
            assertTrue(sizeIs(10f)); assertFalse(preferences.contains(TerminalFontSize.SAVED_KEY))
            push(15, workspace = "workspace-1"); awaitSize(15f)
            compose.mainClock.advanceTimeBy(3000); compose.waitForIdle()
            compose.onNodeWithTag("terminal-zoom-size").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back to workspaces").performClick(); pump()
            compose.onNodeWithText("Claude Code task").performClick(); awaitSize(10f)
        } finally {
            preferences.edit().apply { if (previous == null) remove(TerminalFontSize.SAVED_KEY)
                else putFloat(TerminalFontSize.SAVED_KEY, previous) }.commit()
            compose.mainClock.autoAdvance = false
        }
    }

    @Test fun resizingRetainsPaintedFrameUntilReplayButAnotherTerminalStartsEmpty() {
        checkResizeRetention(raw = false)
    }

    @Test fun byteTerminalRetainsPaintedFrameDuringResizeAndClearsOnSwitch() {
        checkResizeRetention(raw = true)
    }

    private fun checkResizeRetention(raw: Boolean) {
        peer.rawTerminal = raw
        fun setFirstLine(text: String) {
            peer.gridFirstLine = text
            peer.rawReplayText = "$text\r\nColors and grid layout\r\n$ printf cmux\r\ncmux"
        }
        setFirstLine("cmux Android terminal")
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                    .also { it.connect(); observedClients += it }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val resize = CountDownLatch(1)
        val switch = CountDownLatch(1)
        fun foregroundPixels(node: SemanticsNodeInteraction): Int {
            val bitmap = node.captureToImage().asAndroidBitmap()
            var foreground = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) > 150 &&
                    android.graphics.Color.blue(pixel) > 150) foreground++
            }
            return foreground
        }
        fun painted(label: String) {
            val node = compose.onNodeWithText(label, substring = true).assertIsDisplayed()
            assertTrue("Terminal text must be painted, not just present in semantics", foregroundPixels(node) > 1000)
        }
        val mode = if (raw) "bytes" else "grid"
        try {
            peer.replayGateSurface = "terminal-1"
            peer.releaseReplays = resize
            setFirstLine("After resize")
            compose.onNodeWithText("Keyboard").performClick()
            waitForTerminalFixture(10_000) { "terminal-1" in peer.blockedReplaySurfaces }
            painted("cmux Android terminal")
            screenshot("terminal-resize-$mode-retained")
            resize.countDown()
            waitForTerminalFixture(10_000) { compose.onAllNodesWithText("After resize", substring = true).fetchSemanticsNodes().isNotEmpty() }
            painted("After resize")
            screenshot("terminal-resize-$mode-settled")
            compose.onNodeWithText("‹  2").performClick()
            peer.replayGateSurface = "terminal-2"
            peer.releaseReplays = switch
            setFirstLine("Second terminal")
            openReadProject()
            waitForTerminalFixture(10_000) { "terminal-2" in peer.blockedReplaySurfaces }
            compose.onNodeWithText("After resize", substring = true).assertDoesNotExist()
            compose.onNodeWithText("cmux Android terminal", substring = true).assertDoesNotExist()
            val emptyTerminal = compose.onNode(SemanticsMatcher("Terminal keyboard target") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open keyboard"
            }).assertIsDisplayed()
            assertEquals("A different terminal must not paint the previous terminal's frame", 0, foregroundPixels(emptyTerminal))
            switch.countDown()
            waitForTerminalFixture(10_000) { compose.onAllNodesWithText("Second terminal", substring = true).fetchSemanticsNodes().isNotEmpty() }
            painted("Second terminal")
        } finally { resize.countDown(); switch.countDown() }
    }

    @Test fun composerKeyboardImageStagesUntilSendAndPreservesItsText() {
        val keyboard = java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.platform.PlatformTextInputMethodRequest?>()
        compose.setContent { CaptureComposerInput({ keyboard.set(it) }) { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect(); observedClients += it }
            })
        } } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.onNode(hasSetTextAction()).performTextInput("Explain this picture")
        waitForTerminalFixture(10_000) { keyboard.get() != null }
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val photo = File(directory, "composer-keyboard-fixture.png")
        Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.CYAN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.task-previews", photo)
            compose.runOnIdle {
                val attributes = EditorInfo()
                val connection = keyboard.get()!!.createInputConnection(attributes)
                assertTrue(attributes.contentMimeTypes.contentEquals(arrayOf("image/*")))
                assertTrue(connection.commitContent(android.view.inputmethod.InputContentInfo(uri,
                    android.content.ClipDescription("Photo", arrayOf("image/png")), null), 0, null))
            }
            val repository = TerminalDraftRepository.get(context)
            val target = TerminalDrafts.Target("cmux-ios://attach?v=2&r=100.64.0.1:58465", "workspace-1", "terminal-1")
            waitForTerminalFixture(15_000) { repository.drafts.state.value[target]?.attachments?.size == 1 }
            assertDraft("Explain this picture")
            assertTrue(peer.requests.none { it.optString("method") == "terminal.paste_image" })
            waitForTerminalFixture(10_000) { compose.onAllNodesWithText("Send").fetchSemanticsNodes().any {
                !it.config.contains(SemanticsProperties.Disabled)
            } }
            compose.onNodeWithText("Send").performClick()
            waitForTerminalFixture(15_000) { peer.requests.any { it.optString("method") == "terminal.paste" } }
            val sent = peer.requests.filter { it.optString("method") in setOf("terminal.paste_image", "terminal.paste") }
            assertEquals(listOf("terminal.paste_image", "terminal.paste"), sent.map { it.getString("method") })
            assertEquals("Explain this picture", sent.last().getJSONObject("params").getString("text"))
            val bytes = java.util.Base64.getDecoder().decode(sent.first().getJSONObject("params").getString("image_base64"))
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { bitmap ->
                assertEquals(android.graphics.Color.CYAN, bitmap.getPixel(3, 3)); bitmap.recycle()
            }
        } finally { photo.delete() }
    }

    @Test fun keyboardImageReachesExactTerminalBeforeFollowingKeysWithoutChangingComposerDraft() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect(); observedClients += it }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val photo = File(directory, "terminal-keyboard-fixture.png")
        Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.GREEN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.task-previews", photo)
            compose.onNodeWithText("Keyboard").performClick()
            compose.runOnIdle {
                val keyboard = findTerminalKeyboard(compose.activity.window.decorView)!!
                val connection = keyboard.onCreateInputConnection(EditorInfo())!!
                assertTrue(connection.commitContent(android.view.inputmethod.InputContentInfo(uri,
                    android.content.ClipDescription("Photo", arrayOf("image/png")), null), 0, null))
                connection.commitText("after image", 1)
            }
            waitForTerminalFixture(10_000) { peer.requests.any { it.optString("method") == "terminal.input" } }
            val sent = peer.requests.filter { it.optString("method") in setOf("terminal.paste_image", "terminal.input", "terminal.paste") }
            assertEquals(listOf("terminal.paste_image", "terminal.input"), sent.map { it.getString("method") })
            val params = sent.first().getJSONObject("params")
            assertEquals("workspace-1", params.getString("workspace_id"))
            assertEquals("terminal-1", params.getString("surface_id"))
            val bytes = java.util.Base64.getDecoder().decode(params.getString("image_base64"))
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { bitmap ->
                assertEquals(android.graphics.Color.GREEN, bitmap.getPixel(3, 3)); bitmap.recycle()
            }
            assertEquals("after image", sent.last().getJSONObject("params").getString("text"))
            compose.onNodeWithText("Compose").performClick()
            assertDraft("Keep this draft")
        } finally { photo.delete() }
    }

    @Test fun modifierToolbarSupportsCommandStickyKeysAndResetsOnTerminalSwitch() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                    .also { it.connect(); observedClients += it }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.onNodeWithText("Keyboard").performClick()
        lateinit var connection: InputConnection
        compose.runOnIdle { connection = findTerminalKeyboard(compose.activity.window.decorView)!!.onCreateInputConnection(EditorInfo())!! }
        fun state(label: String, expected: String) = compose.onNodeWithText(label).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
        compose.onNodeWithText("Cmd").performClick()
        state("Cmd", "Armed")
        compose.runOnIdle { connection.commitText("a", 1) }
        state("Cmd", "Off")
        compose.onNodeWithText("Cmd").performClick()
        compose.runOnIdle { connection.deleteSurroundingText(2, 0) }
        state("Cmd", "Off")
        compose.onNodeWithText("Ctrl").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        state("Ctrl", "Locked")
        compose.runOnIdle { connection.commitText("c", 1) }
        compose.runOnIdle { connection.commitText("d", 1) }
        state("Ctrl", "Locked")
        screenshot("terminal-sticky-control")
        compose.onNodeWithText("Alt").performClick()
        state("Ctrl", "Off")
        compose.runOnIdle { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
        state("Alt", "Off")
        compose.onNodeWithText("Cmd").performClick()
        compose.runOnIdle { connection.setComposingText("n", 1); connection.deleteSurroundingText(1, 0) }
        state("Cmd", "Armed") // Editing an uncommitted candidate must not consume the modifier.
        compose.runOnIdle { connection.commitText("e", 1) }
        waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 6 }
        assertEquals(listOf("\u0001", "\u0015\u0015", "\u0003", "\u0004", "\u001bb", "\u0005"),
            peer.requests.filter { it.optString("method") == "terminal.input" }.map { it.getJSONObject("params").getString("text") })
        compose.onNodeWithText("Ctrl").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        state("Ctrl", "Locked")
        compose.onNodeWithText("Compose").performClick()
        state("Ctrl", "Off")
        compose.onNodeWithText("Keyboard").performClick()
        compose.runOnIdle {
            connection = findTerminalKeyboard(compose.activity.window.decorView)!!.onCreateInputConnection(EditorInfo())!!
        }
        compose.onNodeWithText("Ctrl").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        state("Ctrl", "Locked")
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        openReadProject()
        waitForTerminalText()
        state("Ctrl", "Off")
        state("Cmd", "Off")
        compose.onNodeWithText("Keyboard").performClick()
        compose.runOnIdle {
            assertTrue(!connection.commitText("stale", 1))
            findTerminalKeyboard(compose.activity.window.decorView)!!.onCreateInputConnection(EditorInfo())!!.commitText("c", 1)
        }
        waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 7 }
        val last = peer.requests.last { it.optString("method") == "terminal.input" }.getJSONObject("params")
        assertEquals("c", last.getString("text"))
        assertEquals("terminal-2", last.getString("surface_id"))
    }

    @Test fun customToolbarActionPersistsEditsAndSendsExactBytesWithoutArmedModifiers() {
        val preferences = context.getSharedPreferences("native_display", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString(TerminalToolbarLayout.PREFERENCE, null)
        preferences.edit().remove(TerminalToolbarLayout.PREFERENCE).commit()
        try {
            compose.setContent {
                CmuxTheme { Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect(); observedClients += it }
                    })
                } }
            }
            waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Claude Code task").performClick()
            waitForTerminalText()
            compose.onNodeWithContentDescription("Customize terminal shortcuts").performClick()
            val listBounds = compose.onNodeWithTag("terminal-shortcut-list").fetchSemanticsNode().boundsInRoot
            val ctrlBounds = compose.onNodeWithTag("shortcut-toggle-${TerminalToolbarButton.CONTROL.id}").fetchSemanticsNode().boundsInRoot
            val altBounds = compose.onNodeWithTag("shortcut-toggle-${TerminalToolbarButton.ALT.id}").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("terminal-shortcut-list").performTouchInput {
                val x = width - 18f
                down(androidx.compose.ui.geometry.Offset(x, ctrlBounds.center.y - listBounds.top))
                advanceEventTime(700)
                moveTo(androidx.compose.ui.geometry.Offset(x, altBounds.bottom - listBounds.top - 4f), delayMillis = 120)
                up()
            }
            compose.runOnIdle {
                assertEquals(listOf(TerminalToolbarButton.ALT.id, TerminalToolbarButton.CONTROL.id),
                    TerminalToolbarStore(preferences).layout.order.take(2))
            }
            compose.onNodeWithTag("shortcut-toggle-${TerminalToolbarButton.COMMAND.id}").performClick()
            compose.runOnIdle { assertFalse(TerminalToolbarButton.COMMAND.id in TerminalToolbarStore(preferences).layout.enabled) }
            compose.onNodeWithText("Add Custom Action").performClick()
            compose.onNodeWithTag("shortcut-action-title").performTextInput("Status")
            compose.onNodeWithTag("shortcut-action-text").performTextInput("pwd")
            compose.onNodeWithText("Save").performClick()
            compose.onNodeWithText("Done").performClick()
            val saved = TerminalToolbarLayout.decode(preferences.getString(TerminalToolbarLayout.PREFERENCE, null))
            val custom = saved.actions.single()
            assertEquals("pwd\n", custom.text)
            assertTrue(peer.requests.none { it.optString("method") == "terminal.input" })
            compose.onNodeWithText("Ctrl", useUnmergedTree = true).performClick()
            revealToolbarShortcut(custom.itemId)
            compose.onNodeWithTag("terminal-shortcut-${custom.itemId}").performClick()
            waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 1 }
            assertEquals("pwd\r", peer.requests.single { it.optString("method") == "terminal.input" }.getJSONObject("params").getString("text"))
            revealToolbarShortcut(TerminalToolbarButton.CONTROL.id, left = false)
            compose.onNodeWithTag("terminal-shortcut-${TerminalToolbarButton.CONTROL.id}")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
            compose.onNodeWithContentDescription("Customize terminal shortcuts").performClick()
            revealShortcutEditorAction("Edit Status")
            compose.onNodeWithContentDescription("Edit Status").performClick()
            compose.onNodeWithTag("shortcut-action-text").performTextReplacement("echo 你好")
            compose.onNodeWithContentDescription("Run after typing").performClick()
            compose.onNodeWithText("Save").performClick()
            compose.onNodeWithText("Done").performClick()
            revealToolbarShortcut(custom.itemId)
            compose.onNodeWithTag("terminal-shortcut-${custom.itemId}").performClick()
            waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 2 }
            assertEquals(listOf("pwd\r", "echo 你好"), peer.requests.filter { it.optString("method") == "terminal.input" }
                .map { it.getJSONObject("params").getString("text") })
            val reloaded = TerminalToolbarStore(preferences).layout
            assertEquals(custom.id, reloaded.actions.single().id)
            assertEquals("echo 你好", reloaded.actions.single().text)
            screenshot("terminal-custom-shortcut")
            compose.onNodeWithContentDescription("Customize terminal shortcuts").performClick()
            compose.onNodeWithText("Reset to Defaults").performClick()
            compose.runOnIdle {
                val reset = TerminalToolbarStore(preferences).layout
                assertEquals(custom.itemId, reset.order.last())
                assertTrue(custom.itemId in reset.enabled)
                assertTrue(TerminalToolbarButton.COMMAND.id in reset.enabled)
            }
            revealShortcutEditorAction("Edit Status")
            screenshot("terminal-shortcut-settings")
            compose.onNodeWithContentDescription("Delete Status").performClick()
            compose.runOnIdle { assertTrue(TerminalToolbarStore(preferences).layout.actions.isEmpty()) }
        } catch (failure: Throwable) {
            File(context.filesDir, "toolbar-test-failure.txt").writeText(failure.stackTraceToString())
            compose.mainClock.autoAdvance = false
            File(context.filesDir, "toolbar-test-tree.txt").writeText(compose.onAllNodes(isRoot()).printToString())
            screenshot("terminal-shortcut-failure")
            throw failure
        } finally {
            compose.mainClock.autoAdvance = false
            preferences.edit().putString(TerminalToolbarLayout.PREFERENCE, previous).commit()
        }
    }

    private fun revealShortcutEditorAction(description: String) {
        val previousAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            // The dialog is a separate Android window. Pump its first composition
            // after freezing the clock, before resolving lazy-list semantics.
            compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithTag("terminal-shortcut-list").fetchSemanticsNodes().isNotEmpty()
            }
            repeat(12) {
                if (compose.onNodeWithContentDescription(description).isDisplayed()) return
                compose.onNodeWithTag("terminal-shortcut-list").performTouchInput { swipeUp(durationMillis = 300) }
                compose.mainClock.advanceTimeBy(1_000)
                compose.waitForIdle()
            }
            compose.onNodeWithContentDescription(description).assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = previousAutoAdvance }
    }

    private fun revealToolbarShortcut(id: String, left: Boolean = true) {
        val previousAutoAdvance = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            repeat(16) {
                if (compose.onNodeWithTag("terminal-shortcut-$id").isDisplayed()) return
                compose.onNodeWithTag("terminal-toolbar-scroll").performTouchInput {
                    if (left) swipeLeft(durationMillis = 300) else swipeRight(durationMillis = 300)
                }
                compose.mainClock.advanceTimeBy(600)
                compose.waitForIdle()
            }
            compose.onNodeWithTag("terminal-shortcut-$id").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = previousAutoAdvance }
    }

    @Test fun directModeTakesHardwareFocusBeforeTheNativeEditorIsMounted() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        val click = compose.onNodeWithText("Keyboard").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val keys = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents("echo early".toCharArray())!!
        compose.runOnUiThread {
            assertTrue(click())
            // Same UI turn: Compose has not mounted the AndroidView or run its next-frame IME effect.
            assertNull(findTerminalKeyboard(compose.activity.window.decorView))
            keys.forEach { compose.activity.dispatchKeyEvent(it) }
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        waitForTerminalFixture(10_000) {
            peer.requests.filter { it.optString("method") == "terminal.input" }
                .joinToString("") { it.getJSONObject("params").getString("text") } == "echo early\r"
        }
        compose.onNodeWithText("Compose").performClick()
        assertDraft("Keep this draft")
    }

    @Test fun directKeyboardCompositionKeysPauseAndTargetSwitch() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect(); observedClients += it }
                    })
                }
            }
        }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val initialRows = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" && !it.getJSONObject("params").optBoolean("clear") }
            .getJSONObject("params").getInt("viewport_rows")
        val initialHeight = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height
        compose.onNode(hasSetTextAction()).performTextInput("Keep my composer draft")
        compose.onNodeWithText("Keyboard").performClick()
        try { waitForTerminalFixture(10_000) {
            compose.mainClock.advanceTimeBy(160)
            compose.waitForIdle()
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true &&
                compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height < initialHeight &&
                // The direct editor is shorter than the composer, allowing extra rows;
                // primary keyboard absorption must not shrink the Mac's grid.
                peer.requests.lastOrNull { it.optString("method") == "mobile.terminal.viewport" }
                    ?.getJSONObject("params")?.optInt("viewport_rows", 0)?.let { it >= initialRows } == true
        } } catch (failure: Throwable) {
            throw AssertionError("Direct keyboard geometry: initialRows=$initialRows initialHeight=$initialHeight " +
                "height=${compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height} " +
                "ime=${androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())} " +
                "viewports=${peer.requests.filter { it.optString("method") == "mobile.terminal.viewport" }.map { it.optJSONObject("params") }}", failure)
        } finally { screenshot("terminal-direct-open") }
        lateinit var keyboard: TerminalKeyboardView
        lateinit var connection: InputConnection
        compose.runOnIdle {
            keyboard = findTerminalKeyboard(compose.activity.window.decorView)!!
            connection = keyboard.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.setComposingText("n", 1))
            assertTrue(connection.setComposingText("ni", 1))
            assertEquals("ni", keyboard.text.toString())
            assertTrue(peer.requests.none { it.optString("method") == "terminal.input" })
            connection.commitText("你", 1)
            connection.deleteSurroundingText(2, 0)
            connection.setComposingText("🙂", 1)
            connection.deleteSurroundingText(1, 0)
            assertEquals("Direct typing · tap here for keyboard", keyboard.text.toString())
            connection.setComposingText("🙂", 1)
            connection.deleteSurroundingTextInCodePoints(1, 0)
            connection.setComposingText("界", 1)
        }
        screenshot("terminal-direct-keyboard")
        compose.runOnIdle {
            connection.finishComposingText()
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1))
        }
        waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 7 }
        assertEquals(listOf("你", "\u007f\u007f", "界", "\r", "\u0003", "\u001b[A", "\u001bOP"),
            peer.requests.filter { it.optString("method") == "terminal.input" }.map { it.getJSONObject("params").getString("text") })
        peer.rejectNextInput.set(true)
        val release = CountDownLatch(1)
        peer.releaseNextInput = release
        compose.runOnIdle { connection.commitText("maybe delivered", 1); connection.commitText("never replay this", 1) }
        waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 8 }
        release.countDown()
        waitForTerminalFixture(10_000) { compose.onAllNodesWithText("Resume typing").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Resume typing").performClick()
        waitForTerminalFixture(10_000) { compose.onAllNodesWithText("Resume typing").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            keyboard = findTerminalKeyboard(compose.activity.window.decorView)!!
            assertTrue(keyboard.isEnabled)
            connection = keyboard.onCreateInputConnection(EditorInfo())!!
            connection.commitText("after resume", 1)
        }
        waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 9 }
        assertTrue(peer.requests.none { it.optJSONObject("params")?.optString("text") == "never replay this" })
        compose.onNodeWithText("Compose").performClick()
        assertDraft("Keep my composer draft")
        val oldConnection = connection
        compose.onNodeWithText("‹  2").performClick()
        openReadProject()
        waitForTerminalFixture(10_000) { compose.onAllNodesWithText("Keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Keyboard").performClick()
        compose.runOnIdle {
            assertTrue(!oldConnection.commitText("stale input", 1))
            val next = findTerminalKeyboard(compose.activity.window.decorView)!!
            next.onCreateInputConnection(EditorInfo())!!.commitText("second terminal", 1)
        }
        waitForTerminalFixture(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 10 }
        val last = peer.requests.last { it.optString("method") == "terminal.input" }.getJSONObject("params")
        assertEquals("terminal-2", last.getString("surface_id"))
        assertEquals("second terminal", last.getString("text"))
        assertTrue(peer.requests.none { it.optJSONObject("params")?.optString("text") == "stale input" })
    }

    @Test fun hardwareLayoutCharactersAndCursorModesReachTheOriginalTerminal() {
        peer.rawTerminal = true
        peer.rawReplayText = "\u001b[?1hHardware layout fixture"
        peer.rawReplaySequence = 100
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        waitForTerminalFixture(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalFixture(10_000) { compose.onAllNodesWithText("Hardware layout fixture", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Keyboard").performClick()
        lateinit var connection: InputConnection
        val right = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        val left = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        fun key(code: Int, meta: Int = 0, action: Int = KeyEvent.ACTION_DOWN) =
            KeyEvent(0, 0, action, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
        compose.runOnIdle {
            connection = findTerminalKeyboard(compose.activity.window.decorView)!!.onCreateInputConnection(EditorInfo())!!
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_C, right))
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_C, right, KeyEvent.ACTION_UP))
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_E, right))
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_E))
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_DPAD_LEFT, left))
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_DPAD_UP))
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_E, right))
            connection.sendKeyEvent(key(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
        }
        fun sent() = peer.requests.filter { it.optString("method") == "terminal.input" }
        waitForTerminalFixture(10_000) { sent().size == 5 }
        assertEquals(listOf("ç", "é", "\u001bb", "\u001bOA", "\u0003"), sent().map { it.getJSONObject("params").getString("text") })
        val modeChange = "\u001b[?1l\r\nNormal cursor mode"
        // The IME can trigger a viewport replay while this event is in flight.
        // A real host's fresh snapshot already includes its latest mode/output.
        peer.rawReplayText += modeChange
        peer.rawReplaySequence = 100 + modeChange.toByteArray().size.toLong()
        peer.pushBytes(modeChange.toByteArray(), 100)
        waitForTerminalFixture(10_000) { compose.onAllNodesWithText("Normal cursor mode", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            connection = findTerminalKeyboard(compose.activity.window.decorView)!!.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.sendKeyEvent(key(KeyEvent.KEYCODE_DPAD_UP)))
        }
        waitForTerminalFixture(10_000) { sent().size == 6 }
        assertEquals("\u001b[A", sent().last().getJSONObject("params").getString("text"))
        assertEquals(setOf("terminal-1"), sent().map { it.getJSONObject("params").getString("surface_id") }.toSet())
        screenshot("terminal-hardware-layout")
    }

    @Test fun rawTerminalStreamsSplitBytesSwitchesScreensAndRecoversMissingOutput() {
        peer.rawTerminal = true
        peer.rawReplayText = "\u001b[2J\u001b[HRaw VT stream\r\n\u001b[6n"
        peer.rawReplaySequence = 100
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        fun waitText(text: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitText("Raw VT stream")
        val subscribe = peer.requests.last { it.optString("method") == "mobile.events.subscribe" &&
            it.optJSONObject("params")?.optJSONArray("topics")?.toString()?.contains("terminal.") == true }.getJSONObject("params")
        assertTrue(subscribe.getJSONArray("topics").toString().contains("terminal.bytes"))
        assertTrue(!subscribe.has("render_grid_anchor"))
        assertTrue(subscribe.getString("client_id").isNotBlank())
        val output = "\u001b[38;2;20;200;100m中 café\u001b[0m".toByteArray()
        val split = output.indexOf(0xe4.toByte()) + 1
        peer.pushBytes(output.copyOfRange(0, split), 100)
        peer.pushBytes(output.copyOfRange(split, output.size), 100L + split)
        waitText("中 café")
        peer.pushBytes(output, 100) // A duplicated network chunk must not duplicate text.
        var sequence = 100L + output.size
        fun push(text: String) { val bytes = text.toByteArray(); peer.pushBytes(bytes, sequence); sequence += bytes.size }
        push("\u001b[?1049h\u001b[2J\u001b[HVT full-screen editor\u001b[3;4HINSERT mode\u001b[?1h")
        waitText("VT full-screen editor")
        compose.onAllNodesWithText("Raw VT stream", substring = true).assertCountEquals(0)
        screenshot("terminal-vt-alternate")
        push("\u001b[?1049l\u001b[?1l")
        waitText("Raw VT stream")
        val before = peer.requests.count { it.optString("method") == "mobile.terminal.replay" }
        peer.rawReplayText = "\u001b[2J\u001b[HRecovered VT output"
        peer.rawReplaySequence = sequence + 21
        peer.pushBytes("Z".toByteArray(), sequence + 20) // Force a genuine gap.
        waitText("Recovered VT output")
        assertEquals(before + 1, peer.requests.count { it.optString("method") == "mobile.terminal.replay" })
        assertTrue(peer.requests.none { it.optString("method") == "terminal.input" }) // No parser device-query replies.
        screenshot("terminal-vt-recovered")
        sequence = peer.rawReplaySequence
        push("\u001b[2J\u001b[H" + (0..79).joinToString("\r\n") { "Line $it" })
        waitText("Line 79")
        compose.onNodeWithText("Line 79", substring = true).performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Scrollback ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("terminal-vt-scrollback")
        compose.onNodeWithText("Latest").performClick()
        waitText("Line 79")
        compose.waitForIdle()
        assertEquals(before + 1, peer.requests.count { it.optString("method") == "mobile.terminal.replay" })
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun terminalTextSheetCopiesImmutableSnapshotAndSupportsNativeSelection() {
        compose.setContent {
            CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val before = peer.requests.count { it.optString("method") == "mobile.terminal.replay" }
        compose.onNodeWithText("Shell ▾").performClick()
        compose.onNodeWithText("View as Text").performClick()
        compose.onNodeWithText("Terminal Text").assertIsDisplayed()
        compose.onNodeWithText("Copy All").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.runOnUiThread {
            val text = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!
                .getItemAt(0).text.toString()
            assertEquals("cmux Android terminal\nColors and grid layout\n$ printf cmux\ncmux", text)
        }
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
            org.hamcrest.CoreMatchers.`is`("terminal-text-snapshot" as Any)))
            .perform(androidx.test.espresso.action.ViewActions.longClick())
            .check { view, failure ->
                if (failure != null) throw failure
                assertTrue((view as android.widget.TextView).isTextSelectable)
                assertTrue(view.hasSelection())
            }
        screenshot("terminal-text-selection")
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
            org.hamcrest.CoreMatchers.`is`("terminal-text-snapshot" as Any)))
            .check { view, failure ->
                if (failure != null) throw failure
                val textView = view as android.widget.TextView
                val selected = textView.text.substring(textView.selectionStart, textView.selectionEnd)
                assertTrue(textView.onTextContextMenuItem(android.R.id.copy))
                assertEquals(selected, context.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
            }
        compose.onNodeWithText("Done").performClick()
        waitForTerminalText()
        assertEquals(before, peer.requests.count { it.optString("method") == "mobile.terminal.replay" })
        assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.mouse" || it.optString("method") == "terminal.input" })
        compose.onNodeWithText("cmux Android terminal", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Terminal Text").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.mouse" })
        // Screen-anchored primary scrolling belongs to the phone, never the Mac.
        compose.onNodeWithText("cmux Android terminal", substring = true).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.scroll" })
    }

    @Test fun alternateKeyboardHoldsFrameUntilConfirmedResizeReplayArrives() {
        peer.alternateScreen = true
        peer.gridFirstLine = "Old alternate frame"
        peer.gridBackground = "#004400"
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Old alternate frame", substring = true).fetchSemanticsNodes().isNotEmpty() }
        fun pixel() = compose.onNodeWithTag("native-terminal").captureToImage().asAndroidBitmap().let {
            it.getPixel(it.width / 2, it.height / 2)
        }
        assertEquals(android.graphics.Color.rgb(0, 68, 0), pixel())
        val replay = CountDownLatch(1)
        try {
            peer.replayGateSurface = "terminal-1"; peer.releaseReplays = replay
            peer.gridFirstLine = "New alternate frame"; peer.gridBackground = "#440000"
            compose.onNode(hasSetTextAction()).performClick()
            compose.waitUntil(10_000) { "terminal-1" in peer.blockedReplaySurfaces }
            assertEquals("Old pixels remain after resize acknowledgement, before replay", android.graphics.Color.rgb(0, 68, 0), pixel())
            compose.onNodeWithText("New alternate frame", substring = true).assertDoesNotExist()
            val heldActions = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().config
                .getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
            assertEquals(listOf("View as Text"), heldActions)
            val clicks = peer.requests.count { it.optString("method") == "mobile.terminal.mouse" }
            compose.onNodeWithTag("native-terminal").performTouchInput { click(center) }
            compose.waitForIdle()
            assertEquals("Held pixels cannot send clicks through new geometry", clicks,
                peer.requests.count { it.optString("method") == "mobile.terminal.mouse" })
            screenshot("alternate-frame-held")
            replay.countDown()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("New alternate frame", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(android.graphics.Color.rgb(68, 0, 0), pixel())
            screenshot("alternate-frame-released")
        } finally { replay.countDown() }
    }

    @Test fun alternateKeyboardReportsOnlyFinalCapacityAndRestoresOnDismissal() {
        peer.alternateScreen = true
        peer.gridFirstLine = "Keyboard target editor"
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Keyboard target editor", substring = true).fetchSemanticsNodes().isNotEmpty() }
        fun reports() = peer.requests.filter { it.optString("method") == "mobile.terminal.viewport" &&
            !it.getJSONObject("params").optBoolean("clear") }.map { it.getJSONObject("params").getInt("viewport_rows") }
        val initial = reports().last()
        val before = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height
        val start = reports().size
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(10_000) { reports().last() < initial }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot
        val metrics = context.resources.displayMetrics
        val cells = TerminalCellMetrics.fromFontSize(TerminalFontSize.DEFAULT * metrics.scaledDensity, 2f * metrics.density)
        val expected = TerminalViewport.fit(bounds.width.toInt(), bounds.height.toInt(), cells)!!.rows
        assertTrue(bounds.height < before - 50)
        compose.waitUntil(10_000) { reports().last() == expected }
        assertEquals("Only the announced target may reach the Mac", setOf(expected), reports().drop(start).toSet())
        screenshot("alternate-keyboard-target")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(10_000) { reports().last() == initial }
        compose.waitForIdle()
        assertEquals(setOf(expected, initial), reports().drop(start).toSet())
        assertEquals(before, compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height, 1f)
        screenshot("alternate-keyboard-dismissed")
    }

    @Test fun terminalTouchForwardsAlternateScrollAndClickWithOfficialScope() {
        peer.alternateScreen = true
        peer.gridFirstLine = "Mouse enabled editor"
        compose.setContent {
            CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Mouse enabled editor", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val terminal = compose.onNodeWithText("Mouse enabled editor", substring = true)
        terminal.performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.scroll" } }
        compose.waitForIdle()
        val scroll = peer.requests.filter { it.optString("method") == "mobile.terminal.scroll" }
        assertTrue(scroll.sumOf { it.getJSONObject("params").getDouble("delta_lines") } > 0)
        assertEquals(600, scroll.first().getJSONObject("params").getInt("max_scrollback_rows"))
        assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.mouse" })
        val viewport = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" && !it.getJSONObject("params").optBoolean("clear") }.getJSONObject("params")
        val bounds = terminal.fetchSemanticsNode().boundsInRoot
        val metrics = context.resources.displayMetrics
        val cells = TerminalCellMetrics.fromFontSize(TerminalFontSize.DEFAULT * metrics.scaledDensity, 2f * metrics.density)
        val geometry = TerminalSharedGridLayout.resolve(bounds.width, bounds.height, viewport.getInt("viewport_columns"),
            viewport.getInt("viewport_rows"), cells, metrics.density)!!.geometry
        terminal.performTouchInput { click(androidx.compose.ui.geometry.Offset(geometry.originX + geometry.cellWidth * 4.5f,
            geometry.originY + geometry.cellHeight * 5.5f)) }
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.mouse" } }
        val click = peer.requests.single { it.optString("method") == "mobile.terminal.mouse" }.getJSONObject("params")
        assertEquals(4, click.getInt("col"))
        assertEquals(5, click.getInt("row"))
        val clientId = peer.requests.first { it.optString("method") == "mobile.events.subscribe" &&
            it.optJSONObject("params")?.optJSONArray("topics")?.toString()?.contains("terminal.") == true }
            .getJSONObject("params").getString("client_id")
        (scroll.map { it.getJSONObject("params") } + click).forEach {
            assertEquals("workspace-1", it.getString("workspace_id"))
            assertEquals("terminal-1", it.getString("surface_id"))
            assertEquals(clientId, it.getString("client_id"))
        }
        assertTrue(peer.requests.none { it.optString("method") == "terminal.input" })
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun accessibilityScrollReadsHistoryLocallyAndReturnsToLatest() {
        peer.gridHistoryRows = 20
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.waitUntil(10_000) { compose.onNodeWithTag("native-terminal").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.ScrollBy) != null }
        // Exercise Android's exported accessibility action, not a touch gesture.
        fun containsTerminalText(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.text?.contains("cmux Android terminal") == true) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let { if (containsTerminalText(it)) return true }
            return false
        }
        fun findScrollable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && containsTerminalText(node)) return node
            for (index in 0 until node.childCount) findScrollable(node.getChild(index))?.let { return it }
            return null
        }
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        try {
            // Compose semantics can be ready before Android refreshes its exported tree.
            compose.waitUntil(5_000) {
                node = findScrollable(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                node != null
            }
        } finally {
            if (node == null) {
                val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    .dumpWindowHierarchy(File(directory, "terminal-accessibility-tree.xml"))
            }
        }
        assertTrue(node!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("History", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val latestAction = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Latest output" }
        compose.runOnIdle { assertTrue(latestAction.action()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Scrollback ·", substring = true).fetchSemanticsNodes().isEmpty() }
        assertTrue(peer.requests.none { it.optString("method") in setOf("mobile.terminal.scroll", "mobile.terminal.mouse", "terminal.input", "terminal.paste") })
        screenshot("terminal-accessibility-latest")
    }

    @Test fun alternateAccessibilityScrollUsesWheelRpcWithoutSendingClicksOrKeys() {
        peer.alternateScreen = true
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.waitUntil(10_000) { compose.onNodeWithTag("native-terminal").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Scroll up" } == true }
        val scrollUp = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Scroll up" }
        compose.runOnIdle { assertTrue(scrollUp.action()) }
        compose.waitUntil(5_000) { peer.requests.any { it.optString("method") == "mobile.terminal.scroll" } }
        val scroll = peer.requests.first { it.optString("method") == "mobile.terminal.scroll" }.getJSONObject("params")
        assertTrue(scroll.getDouble("delta_lines") > 0)
        assertEquals("terminal-1", scroll.getString("surface_id"))
        assertTrue(peer.requests.none { it.optString("method") in setOf("mobile.terminal.mouse", "terminal.input", "terminal.paste") })
    }

    @Test fun localPrimaryScrollKeepsMacViewportStillAndHistoryTapsDoNotClickLiveTerminal() {
        peer.gridHistoryRows = 20
        compose.setContent {
            CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        fun scrollIntoHistory() {
            compose.onNodeWithText("cmux Android terminal", substring = true).performTouchInput {
                down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 180.5f), 100)
                advanceEventTime(400); up()
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Scrollback ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.scroll" })
        }
        scrollIntoHistory()
        // Semantics can settle before the Android compositor presents its frame.
        // Distinct history backgrounds prove that scrollback was actually painted.
        compose.waitUntil(5_000) {
            val bitmap = compose.onNodeWithText("History", substring = true).captureToImage().asAndroidBitmap()
            var historyPixels = 0
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4)
                if (bitmap.getPixel(x, y) == android.graphics.Color.rgb(18, 52, 86)) historyPixels++
            historyPixels > 50
        }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "terminal-local-pixel-scrollback.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Latest").performClick()
        compose.onAllNodesWithText("Scrollback ·", substring = true).assertCountEquals(0)
        scrollIntoHistory()
        compose.onNodeWithText("History", substring = true).performTouchInput { click(center) }
        compose.waitForIdle()
        assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.mouse" })
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun viewportAnchoredScrollAppliesReturnedHostGrid() {
        peer.screenAnchor = false
        compose.setContent {
            CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        compose.onNodeWithText("cmux Android terminal", substring = true).performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scrolled host viewport", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Scrollback ·", substring = true).assertCountEquals(0)
        assertTrue(peer.requests.any { it.optString("method") == "mobile.terminal.scroll" })
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun scopedSearchMatchesGroupsNotificationMetadataAndPreservesCommittedFilters() {
        peer.notificationFeed = searchNotifications()
        showSearchFixture()
        openSearch()
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput(" Completed ")
        compose.onNodeWithText("Read project").assertIsDisplayed()
        compose.onAllNodesWithText("Claude Code task").assertCountEquals(0)
        compose.onAllNodesWithText("Completed group").assertCountEquals(0)
        field.performImeAction()
        assertSearchFilter("Completed")
        compose.onNodeWithText("Notifications (2)").performClick()
        openSearch()
        assertDraft("")
        field.performTextInput("cafe")
        compose.onNodeWithText("Build pipeline").assertIsDisplayed()
        compose.onAllNodesWithText("Read project").assertCountEquals(0)
        field.performTextReplacement("resume")
        compose.onNodeWithText("Build pipeline").assertIsDisplayed()
        field.performTextReplacement("ＡＧＥＮＴ ＰＡＮＥ")
        compose.onNodeWithText("Build pipeline").assertIsDisplayed()
        field.performImeAction()
        assertSearchFilter("ＡＧＥＮＴ ＰＡＮＥ", notifications = true)
        screenshot("notification-search")
        compose.onNode(hasText("Workspaces") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        assertSearchFilter("Completed")
        openReadProject()
        waitForTerminalText()
        compose.onNodeWithText("‹  2").performClick()
        assertSearchFilter("Completed")
        openSearch()
        compose.onNodeWithContentDescription("Cancel search").performClick()
        openSearch()
        field.performTextInput("Fixture Mac")
        compose.onNodeWithText("Read project").assertIsDisplayed()
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel search").performClick()
        openSearch()
        field.performTextInput("release gate")
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        compose.onAllNodesWithText("Read project").assertCountEquals(0)
        field.performImeAction()
        compose.onNodeWithText("Notifications (2)").performClick()
        assertSearchFilter("ＡＧＥＮＴ ＰＡＮＥ", notifications = true)
        compose.onNodeWithText("Build pipeline").assertIsDisplayed()
        openSearch()
        compose.onNodeWithContentDescription("Cancel search").performClick()
        compose.onNodeWithText("Read project").assertIsDisplayed()
        assertTrue(peer.requests.none { it.optString("method") == "notification.feed.mark_read" })
    }

    @Test fun savedTaskDraftReconnectsToItsOwnMacBeforeSubmission() {
        val other = NativeFixturePeer().apply { deviceId = "second-mac"; displayName = "Second Mac" }
        fun listing(title: String) = JSONObject().put("workspaces", JSONArray().put(JSONObject()
            .put("id", "workspace-1").put("title", title).put("terminals", JSONArray()
                .put(JSONObject().put("id", "terminal-1").put("title", "Shell")))))
        peer.customWorkspaceListing = listing("First workspace")
        other.customWorkspaceListing = listing("Second workspace")
        val store = NativeCredentialStore(context)
        store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.2:58465", "second-mac", "Second Mac")
        store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { pairing, _ ->
                    val target = if (pairing.routes.first().host == "100.64.0.2") other else peer
                    MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("First workspace").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("New Task").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Task prompt").fetchSemanticsNodes().isNotEmpty() }
            compose.openTaskPicker("Agent")
            compose.onNodeWithText("Codex", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("Task prompt").performTextInput("First Mac saved task")
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.onNodeWithText("Save Draft").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Computer filter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Computer filter").performClick()
            compose.onNode(hasText("Second Mac") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("New Task") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("New Task").performClick()
            compose.onNodeWithContentDescription("Task prompt").performTextInput("Second Mac saved task")
            compose.onNodeWithContentDescription("Drafts").performClick()
            compose.onNodeWithText("First Mac saved task").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("First Mac saved task") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Create Task").performClick()
            compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" &&
                it.optJSONObject("params")?.optString("surface_id") == "task-terminal" } }
            assertEquals(1, peer.requests.count { it.optString("method") == "workspace.create" })
            assertTrue(other.requests.none { it.optString("method") == "workspace.create" })
            val repository = TaskDraftRepository.get(context, store.taskSession()!!)
            runBlocking { repository.persistNow() }
            assertEquals(listOf("Second Mac saved task"), TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.values.map { it.prompt })
            screenshot("task-draft-restored-mac")
            assertEquals(TaskTemplate.builtInId(TaskCommand.Agent.CODEX), repository.templates.state.value.lastTemplateId)
            compose.onNodeWithText("‹  2").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Computer filter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Computer filter").performClick()
            compose.onNode(hasText("Second Mac") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Second workspace").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("New Task") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("New Task").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Agent") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Codex")).fetchSemanticsNodes().isNotEmpty() }
            val freshDraft = repository.drafts.state.value.values.single { it.prompt.isEmpty() }
            assertEquals(store.pairedMacs().first { it.deviceId == "fixture-mac" }.origin, freshDraft.origin)
            assertEquals(TaskTemplate.builtInId(TaskCommand.Agent.CODEX), freshDraft.templateId)
            assertTrue(other.requests.none { it.optString("method") == "workspace.create" })
            screenshot("task-remembered-agent-mac")
        } finally { other.close() }
    }

    @Test fun taskOptionsSwitchMacWithoutLosingPromptOrSendingOldGroup() {
        val other = NativeFixturePeer().apply { deviceId = "second-mac"; displayName = "Second Mac"; taskGroupsSupported = true }
        peer.taskGroupsSupported = true
        fun listing(name: String) = JSONObject().put("groups", JSONArray().put(JSONObject().put("id", "shared-group").put("name", name)))
            .put("workspaces", JSONArray().put(JSONObject().put("id", "workspace-1").put("title", "$name workspace")
                .put("current_directory", "/$name").put("terminals", JSONArray().put(JSONObject().put("id", "terminal-1").put("title", "Shell")))))
        peer.customWorkspaceListing = listing("First")
        other.customWorkspaceListing = listing("Second")
        val store = NativeCredentialStore(context)
        store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.2:58465", "second-mac", "Second Mac")
        store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { pairing, _ ->
                    val target = if (pairing.routes.first().host == "100.64.0.2") other else peer
                    MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("New Task") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("New Task").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Task prompt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Task prompt").performTextInput("Move my unsent task 中")
            compose.chooseTaskDirectory(peer, "/typed/path")
            compose.openTaskOptions()
            compose.onNodeWithText("Workspace name (optional)").performTextInput("Preserved task name")
            compose.onNodeWithContentDescription("Workspace group").performClick()
            compose.onNode(hasText("First") and hasAnyAncestor(isPopup())).performClick()
            compose.onNodeWithContentDescription("Task Mac").performClick()
            compose.onNodeWithContentDescription("Task Mac: Second Mac").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Task Mac") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Second Mac")).fetchSemanticsNodes().isNotEmpty() }
            // The editor now stays mounted through the connection change, including its options sheet.
            compose.onNodeWithText("Done").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Move my unsent task 中") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.assertTaskDirectory("/typed/path")
            compose.openTaskOptions()
            compose.onNodeWithContentDescription("Task Mac").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Second Mac"))
            compose.onNodeWithText("Workspace name (optional)").assertTextContains("Preserved task name")
            compose.onNodeWithContentDescription("Workspace group").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "None"))
            val restored = TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.values.single()
            assertEquals(store.pairedMacs().first { it.deviceId == "second-mac" }.origin, restored.origin)
            assertEquals("Preserved task name", restored.workspaceName); assertNull(restored.groupId)
            screenshot("task-options-second-mac")
            compose.onNodeWithContentDescription("Workspace group").performClick()
            compose.onNode(hasText("Second") and hasAnyAncestor(isPopup())).performClick()
            compose.onNodeWithText("Done").performClick(); compose.onNodeWithContentDescription("Create Task").performClick()
            compose.waitUntil(15_000) { other.requests.any { it.optString("method") == "mobile.terminal.replay" && it.getJSONObject("params").optString("surface_id") == "task-terminal" } }
            assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
            val sent = other.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params")
            assertEquals("shared-group", sent.getString("group_id")); assertEquals("Preserved task name", sent.getString("title"))
            assertEquals("/typed/path", sent.getString("working_directory"))
            assertEquals("Move my unsent task 中", sent.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
        } finally { other.close() }
    }

    @Test fun taskCreationOpensExactTerminalAndPreservesExistingWorkspaces() {
        peer.notificationFeed = searchNotifications()
        showSearchFixture()
        compose.onNodeWithContentDescription("New Task").performClick()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Create and open a task")
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" &&
            it.getJSONObject("params").optString("surface_id") == "task-terminal" } }
        compose.onNodeWithText("New Task").assertDoesNotExist()
        compose.onNodeWithText("Agent ▾").assertIsDisplayed()
        waitForTerminalText()
        screenshot("task-created-terminal")
        compose.onNodeWithText("‹  3").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        // The existing group's anchor is represented by its group header, as on iOS.
        compose.onNodeWithContentDescription("Open Completed group").assertIsDisplayed()
        compose.onNodeWithText("Created task").assertIsDisplayed()
        screenshot("task-created-workspaces")
        assertEquals(1, peer.requests.count { it.optString("method") == "workspace.create" })
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun completedTaskRefreshesFullListingAndOpensRecoveredTerminal() {
        peer.notificationFeed = searchNotifications()
        peer.nextTaskCreateError.set("already_completed")
        showSearchFixture()
        compose.onNodeWithContentDescription("New Task").performClick()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Recover and open a task")
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Refresh Workspaces") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription("Create Task").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        val original = peer.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params")
        peer.customWorkspaceListing = JSONObject("""{"groups":[{"id":"complete","name":"Refreshed group",
            "anchor_workspace_id":"workspace-2"}],"workspaces":[
            {"id":"workspace-1","title":"Claude Code task","terminals":[{"id":"terminal-1"}]},
            {"id":"workspace-2","title":"Read project","group_id":"complete","terminals":[{"id":"terminal-2"}]},
            {"id":"fresh-remote","title":"Fresh remote workspace"}]}""")
        val requestsBefore = peer.requests.size
        compose.onNodeWithText("Refresh Workspaces").performClick()
        compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" &&
            it.getJSONObject("params").optString("surface_id") == "task-terminal" } }
        waitForTerminalText()
        val recoveryRequests = peer.requests.drop(requestsBefore).filter { it.optString("method") in setOf("mobile.workspace.list", "workspace.create") }
        assertEquals("mobile.workspace.list", recoveryRequests.first().getString("method"))
        val recovered = peer.requests.last { it.optString("method") == "workspace.create" }.getJSONObject("params")
        assertEquals(original.getString("operation_id"), recovered.getString("operation_id"))
        assertTrue(TaskSubmissionIdentity.sameRequest(original, recovered))
        compose.onNodeWithText("Agent ▾").assertIsDisplayed()
        screenshot("task-recovered-terminal")
        compose.onNodeWithText("‹  4").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Fresh remote workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open Refreshed group").assertIsDisplayed()
        compose.onNodeWithText("Created task").assertIsDisplayed()
        assertEquals(2, peer.requests.count { it.optString("method") == "workspace.create" })
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun primaryNavigationSearchCancelSubmitAndComposerEntry() {
        peer.notificationFeed = searchNotifications()
        showSearchFixture()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNode(hasText("Workspaces") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).assertIsSelected()
        compose.onNodeWithContentDescription("New Task").assertIsDisplayed()
        screenshot("primary-navigation-workspaces")
        compose.onNodeWithContentDescription("New Task").performClick()
        compose.onNodeWithContentDescription("Task title").assertIsDisplayed()
        screenshot("task-composer-canvas")
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        openSearch()
        compose.waitUntil(10_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNode(hasText("Workspaces") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).assertDoesNotExist()
        compose.onNodeWithContentDescription("New Task").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Read project")
        screenshot("primary-navigation-search")
        compose.onNodeWithContentDescription("Cancel search").performClick()
        assertSearchFilter("")
        compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("Read project")
        compose.onNode(hasSetTextAction()).performImeAction()
        assertSearchFilter("Read project")
        compose.onNodeWithText("Claude Code task").assertDoesNotExist()
        openSearch()
        assertDraft("Read project")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertSearchFilter("")
        compose.onNodeWithText("Notifications (2)").performClick()
        compose.onNodeWithText("Notifications (2)").assertIsSelected()
        compose.onNodeWithContentDescription("New Task").assertDoesNotExist()
        screenshot("primary-navigation-notifications")
        compose.onNodeWithContentDescription("cmux settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun workspaceHierarchyOpensAnchorPersistsCollapseAndDragsThroughRpc() {
        peer.notificationFeed = searchNotifications()
        peer.customWorkspaceListing = JSONObject("""{
          "groups":[{"id":"complete","name":"Completed group","anchor_workspace_id":"workspace-2"}],
          "workspaces":[
            {"id":"workspace-1","window_id":"fixture-window","title":"Claude Code task","terminals":[{"id":"terminal-1"}]},
            {"id":"workspace-2","window_id":"fixture-window","title":"Read project","group_id":"complete","terminals":[{"id":"terminal-2"}]},
            {"id":"child","window_id":"fixture-window","title":"Group child","group_id":"complete","has_unread":true},
            {"id":"tail","window_id":"fixture-window","title":"Tail workspace"}
          ]} """)
        showSearchFixture()
        compose.onNodeWithText("Read project").assertDoesNotExist()
        compose.onNodeWithText("Group child").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse Completed group").performClick()
        compose.onNodeWithText("Group child").assertDoesNotExist()
        assertTrue(NativeCredentialStore(context).load()!!.getJSONObject("collapsed_groups")
            .keys().asSequence().any { it.endsWith(":group:complete") })
        compose.onNodeWithContentDescription("Open Completed group").performClick()
        waitForTerminalText()
        assertEquals("workspace-2", peer.requests.last { it.optString("method") == "mobile.terminal.replay" }
            .getJSONObject("params").getString("workspace_id"))
        compose.onNodeWithText("‹  4").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Expand Completed group").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Group child").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand Completed group").performClick()
        compose.onNodeWithText("Group child").assertIsDisplayed()
        val from = compose.onNodeWithText("Tail workspace").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithText("Claude Code task").fetchSemanticsNode().boundsInRoot.center.copy(y =
            compose.onNodeWithText("Claude Code task").fetchSemanticsNode().boundsInRoot.top - 6f)
        compose.onRoot().performTouchInput {
            down(from); advanceEventTime(650); moveTo(to, delayMillis = 300); up()
        }
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "workspace.move" } }
        val move = peer.requests.single { it.optString("method") == "workspace.move" }.getJSONObject("params")
        assertEquals("tail", move.getString("workspace_id"))
        assertEquals("workspace-1", move.getString("before_workspace_id"))
        assertEquals("fixture-window", move.getString("window_id"))
        compose.waitUntil(10_000) {
            compose.onNodeWithText("Tail workspace").fetchSemanticsNode().boundsInRoot.top <
                compose.onNodeWithText("Claude Code task").fetchSemanticsNode().boundsInRoot.top
        }
        screenshot("workspace-hierarchy-reordered")
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("Group")
        compose.onNodeWithText("Read project").assertIsDisplayed()
        compose.onNodeWithText("Group child").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Completed group").assertDoesNotExist()
        val movable = SemanticsMatcher("has move accessibility actions") { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsActions.CustomActions) { emptyList() }
                .any { it.label == "Move up" || it.label == "Move down" }
        }
        compose.onAllNodes(movable).assertCountEquals(0)
    }

    @Test fun unreadCountsAggregateOnCollapseAndRefreshAfterReadActions() {
        peer.notificationFeed = searchNotifications()
        peer.customWorkspaceListing = JSONObject("""{
          "groups":[
            {"id":"counted","name":"Counted group","is_pinned":true,"icon_symbol":"terminal.fill","anchor_workspace_id":"workspace-2"},
            {"id":"legacy","name":"Legacy group","is_collapsed":true,"anchor_workspace_id":"old-anchor"}
          ],
          "workspaces":[
            {"id":"workspace-1","window_id":"fixture-window","title":"Claude Code task","has_unread":true,"unread_count":12,"terminals":[{"id":"terminal-1"}]},
            {"id":"workspace-2","window_id":"fixture-window","title":"Read project","group_id":"counted","has_unread":true,"unread_count":2,"terminals":[{"id":"terminal-2"}]},
            {"id":"child","window_id":"fixture-window","title":"Group child","group_id":"counted","has_unread":true,"unread_count":3,"terminals":[{"id":"terminal-child"}]},
            {"id":"old-anchor","window_id":"fixture-window","title":"Old anchor","group_id":"legacy","terminals":[{"id":"terminal-old"}]},
            {"id":"old-child","window_id":"fixture-window","title":"Old child","group_id":"legacy","has_unread":true}
          ]} """)
        showSearchFixture()
        fun headerState(expected: String) {
            val matcher = hasContentDescription("Open Counted group") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)
            compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        }
        fun childState(expected: String) {
            val matcher = hasText("Group child") and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)
            compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        }
        headerState("Pinned, 2 unread")
        childState("3 unread")
        compose.onNodeWithContentDescription("Open Legacy group").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread"))
        compose.onNodeWithText("Claude Code task").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "12 unread"))
        screenshot("workspace-counts-expanded")
        compose.onNodeWithContentDescription("Collapse Counted group").performClick()
        headerState("Pinned, 5 unread")
        compose.onNodeWithText("Group child").assertDoesNotExist()
        screenshot("workspace-counts-collapsed")
        compose.onNodeWithContentDescription("Expand Counted group").performClick()
        compose.onNodeWithContentDescription("Actions for Group child").performClick()
        compose.onNodeWithText("Mark read").performClick()
        childState("")
        compose.onNodeWithContentDescription("Collapse Counted group").performClick()
        headerState("Pinned, 2 unread")
        compose.onNodeWithContentDescription("Expand Counted group").performClick()
        compose.onNodeWithContentDescription("Actions for Group child").performClick()
        compose.onNodeWithText("Mark unread").performClick()
        childState("1 unread")
        compose.onNodeWithContentDescription("Collapse Counted group").performClick()
        headerState("Pinned, 3 unread")
        val actions = peer.requests.filter { it.optString("method") == "workspace.action" }
        assertEquals(listOf("mark_read", "mark_unread"), actions.map { it.getJSONObject("params").getString("action") })
        assertTrue(actions.all { it.getJSONObject("params").getString("workspace_id") == "child" })
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("Counted")
        compose.onNodeWithText("Read project").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 unread"))
        childState("1 unread")
        compose.onNodeWithContentDescription("Open Counted group").assertDoesNotExist()
    }

    private fun openReadProject() {
        val flat = compose.onAllNodesWithText("Read project").fetchSemanticsNodes().isNotEmpty()
        if (flat) compose.onNodeWithText("Read project").performClick()
        else compose.onNodeWithContentDescription("Open Completed group").performClick()
    }

    private fun openSearch() {
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertSearchFilter(query: String, notifications: Boolean = false) {
        val label = if (notifications) "Search notifications" else "Search workspaces"
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, if (query.isEmpty()) label else "$label: $query"))
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun notificationSearchNavigatesMovedSurfaceAndDoesNotReadMissingDestination() {
        peer.notificationFeed = searchNotifications()
        showSearchFixture()
        compose.onNodeWithText("Notifications (2)").performClick()
        openReadProject()
        waitForTerminalText()
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "notification.feed.mark_read" } }
        val replay = peer.requests.last { it.optString("method") == "mobile.terminal.replay" }.getJSONObject("params")
        assertEquals("workspace-2", replay.getString("workspace_id"))
        assertEquals("terminal-2", replay.getString("surface_id"))
        val read = peer.requests.single { it.optString("method") == "notification.feed.mark_read" }.getJSONObject("params")
        assertEquals("moved", read.getJSONArray("notification_ids").getString(0))
        compose.onNodeWithText("‹  2").performClick()
        compose.onNodeWithText("Closed workspace").assertDoesNotExist()
        openSearch()
        compose.onNode(hasSetTextAction()).performTextInput("Build pipeline")
        // The row was valid when displayed; remove its target atomically with the tap.
        val open = compose.onNode(hasText("Build pipeline") and !hasSetTextAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { peer.hiddenWorkspaceId = "workspace-1"; open() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("This notification's workspace is no longer available.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This notification's workspace is no longer available.").assertIsDisplayed()
        assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_read" })
        screenshot("notification-unavailable")
    }

    private fun showSearchFixture() {
        compose.setContent {
            CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Notifications (2)").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun searchNotifications() = JSONArray("""[
        {"id":"first","workspace_id":"workspace-1","surface_id":"terminal-1","title":"Claude Code",
         "body":"Waiting for café report","subtitle":"Résumé review","workspace_title":"Build pipeline",
         "surface_title":"Agent Pane","is_read":false},
        {"id":"moved","workspace_id":"workspace-1","surface_id":"terminal-2","title":"Codex",
         "body":"Moved terminal alert","workspace_title":"Read project","is_read":false,"retargets_to_live_surface_owner":true},
        {"id":"missing","workspace_id":"removed","surface_id":"gone","title":"Orphan alert",
         "body":"Missing destination","workspace_title":"Closed workspace","is_read":false,"retargets_to_live_surface_owner":true}
    ]""").also { items ->
        for (index in 0 until items.length()) items.getJSONObject(index)
            .put("created_at", System.currentTimeMillis() / 1000.0 - 60 * (index + 1))
    }

    private fun findTerminalKeyboard(view: View): TerminalKeyboardView? {
        if (view is TerminalKeyboardView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTerminalKeyboard(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertDraft(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    }

    private fun waitForTerminalFixture(timeout: Long, condition: () -> Boolean) {
        try { compose.waitUntil(timeout, condition) }
        catch (failure: Throwable) {
            screenshot("terminal-input-failure")
            val failures = observedClients.flatMap { it.disconnected.replayCache }.joinToString("\n") { it.stackTraceToString() }
            throw AssertionError("Terminal transport failures: $failures\nPeer failures: ${peer.failures}\n" +
                "Methods: ${peer.requests.map { it.optString("method") }}\n" + compose.onRoot().printToString(), failure)
        }
    }

    private fun waitForTerminalText() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("cmux Android terminal", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun alertSwitchesSavedMacAndExactTerminalThenCanBeOpenedAgain() {
        val other = NativeFixturePeer().apply { deviceId = "second-mac"; gridFirstLine = "Second Mac terminal" }
        val releaseFirstFeed = CountDownLatch(1)
        peer.releaseNextFeed = releaseFirstFeed
        try {
            val firstCode = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
            val secondCode = "cmux-ios://attach?v=2&r=100.64.0.2:58465"
            val credentials = NativeCredentialStore(context)
            credentials.rememberMac(secondCode, "second-mac", "Second Mac")
            credentials.rememberMac(firstCode, "fixture-mac", "Fixture Mac")
            var route: NotificationDestination? = null
            NativeCredentialStore(context, "native_notification_state").update {
                route = NativeNotificationLedger(it).stage(pairingOrigin(secondCode, "second-mac"),
                    NativeNotification("shared-id", "workspace-2", "terminal-2", "Ready", "", false))
            }
            val incoming = mutableStateOf<String?>(null)
            val handled = java.util.concurrent.atomic.AtomicInteger()
            compose.setContent {
                CmuxTheme { Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, incomingNotificationRoute = incoming.value,
                        onNotificationHandled = { incoming.value = null; handled.incrementAndGet() },
                        connector = NativeConnector { pairing, _ ->
                            val target = if (pairing.routes.first().host == "100.64.0.2") other else peer
                            MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                        })
                } }
            }
            compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "notification.feed.list" } }
            compose.waitUntil(10_000) { other.requests.any { it.optString("method") == "mobile.events.subscribe" } }
            val backgroundHandshakes = other.requests.count { it.optString("method") == "mobile.host.status" }
            compose.runOnIdle { incoming.value = route!!.routeId }
            compose.waitUntil(10_000) { other.requests.count { it.optString("method") == "mobile.host.status" } > backgroundHandshakes }
            releaseFirstFeed.countDown()
            compose.waitUntil(15_000) { handled.get() == 1 && other.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            val replay = other.requests.last { it.optString("method") == "mobile.terminal.replay" }.getJSONObject("params")
            assertEquals("workspace-2", replay.getString("workspace_id"))
            assertEquals("terminal-2", replay.getString("surface_id"))
            assertEquals(0, peer.requests.count { it.optString("method") == "notification.feed.mark_read" })
            assertEquals(1, other.requests.count { it.optString("method") == "notification.feed.mark_read" })
            compose.runOnIdle { incoming.value = route!!.routeId }
            compose.waitUntil(10_000) { handled.get() == 2 }
            assertEquals(2, other.requests.count { it.optString("method") == "notification.feed.mark_read" })
            assertEquals(secondCode, credentials.load()!!.getString("pairing_code"))
            screenshot("notification-mac-route")
        } finally { releaseFirstFeed.countDown(); other.close() }
    }

    @Test fun notificationRetryWaitsForFreshHandshakeBeforeOpeningTheRetainedTarget() = checkNotificationRetry(false)
    @Test fun notificationReplacingARetryCannotBeOverriddenByTheOldTarget() = checkNotificationRetry(true)

    private fun checkNotificationRetry(replace: Boolean) {
        val incoming = mutableStateOf<String?>(null)
        val handled = java.util.concurrent.atomic.AtomicInteger()
        val holdNext = java.util.concurrent.atomic.AtomicBoolean()
        val entered = java.util.concurrent.atomic.AtomicBoolean()
        val release = CountDownLatch(1)
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, incomingNotificationRoute = incoming.value,
                    onNotificationHandled = { if (incoming.value == it) { incoming.value = null; handled.incrementAndGet() } },
                    connector = NativeConnector { _, _ ->
                        if (holdNext.getAndSet(false)) {
                            entered.set(true)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                        }
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                    })
            } } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
            val mac = NativeCredentialStore(context).pairedMacs().single { it.deviceId == "fixture-mac" }
            fun route(id: String, workspace: String, surface: String): String {
                var created: NotificationDestination? = null
                NativeCredentialStore(context, "native_notification_state").update {
                    created = NativeNotificationLedger(it).stage(mac.origin, NativeNotification(id, workspace, surface, "Ready", "", false))
                }
                return checkNotNull(created).routeId
            }
            val first = route("retry-first", "workspace-1", "terminal-1")
            peer.rejectNextForegroundFeed.set(true)
            compose.runOnIdle { incoming.value = first }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not open notification").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, handled.get()); assertEquals(first, incoming.value)
            holdNext.set(true)
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(5_000) { entered.get() }
            compose.onNodeWithText("Opening notification").assertIsDisplayed()
            assertEquals(0, handled.get())
            assertTrue(peer.requests.none { it.optString("method") in setOf("notification.feed.mark_read", "mobile.terminal.replay") })
            if (replace) compose.runOnIdle { incoming.value = route("retry-new", "workspace-2", "terminal-2") }
            release.countDown()
            compose.waitUntil(15_000) { handled.get() == 1 && peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            val replay = peer.requests.last { it.optString("method") == "mobile.terminal.replay" }.getJSONObject("params")
            assertEquals(if (replace) "terminal-2" else "terminal-1", replay.getString("surface_id"))
            val marks = peer.requests.filter { it.optString("method") == "notification.feed.mark_read" }
            assertEquals(1, marks.size)
            assertEquals(if (replace) "retry-new" else "retry-first", marks.single().getJSONObject("params").getJSONArray("notification_ids").getString(0))
        } finally { release.countDown() }
    }

    @Test fun missingNotificationTerminalDoesNotOpenASiblingOrMarkRead() = checkUnavailableNotification("missing-terminal", null)
    @Test fun notificationForAnotherLoginDoesNotNavigateOrMarkRead() = checkUnavailableNotification("terminal-1", "different-login")
    private fun checkUnavailableNotification(surface: String, login: String?) {
        val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
        NativeCredentialStore(context).rememberMac(code, "fixture-mac", "Fixture Mac")
        var route: NotificationDestination? = null
        NativeCredentialStore(context, "native_notification_state").update {
            route = NativeNotificationLedger(it).stage(pairingOrigin(code, "fixture-mac"),
                NativeNotification("unavailable", "workspace-1", surface, "Ready", "", false), login)
        }
        val incoming = mutableStateOf<String?>(checkNotNull(route).routeId)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, incomingNotificationRoute = incoming.value,
                onNotificationHandled = { incoming.value = null }, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
        } } }
        compose.waitUntil(15_000) { incoming.value == null }
        assertTrue(peer.requests.none { it.optString("method") in setOf("notification.feed.mark_read", "mobile.terminal.replay") })
    }

    @Test fun forgottenMacNotificationDoesNotUseCurrentMacOrMarkRead() {
        var route: NotificationDestination? = null
        NativeCredentialStore(context, "native_notification_state").update {
            route = NativeNotificationLedger(it).stage("forgotten-mac",
                NativeNotification("n", "workspace-1", "terminal-1", "Ready", "", false))
        }
        val consumed = java.util.concurrent.atomic.AtomicBoolean()
        compose.setContent {
            CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, incomingNotificationRoute = route!!.routeId,
                    onNotificationHandled = { consumed.set(true) }, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                    })
            } }
        }
        compose.waitUntil(10_000) { consumed.get() }
        assertEquals(0, peer.requests.count { it.optString("method") == "notification.feed.mark_read" })
        assertEquals(0, peer.requests.count { it.optString("method") == "mobile.terminal.replay" })
    }

    @Test fun notificationHistoryFilterSwipeAndReadActionsMatchFeedScope() {
        val now = java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()
        peer.notificationFeed = JSONArray().also { feed ->
            listOf("Latest update", "Second update", "First update", "Yesterday update").forEachIndexed { index, body ->
                feed.put(JSONObject().put("id", "history-$index").put("workspace_id", "workspace-1")
                    .put("surface_id", "terminal-1").put("title", "Claude Code").put("body", body)
                    .put("workspace_title", if (index == 3) "Older task" else "Task group")
                    .put("created_at", now - if (index == 3) 86400 else index * 60).put("is_read", false))
            }
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Notifications (4)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Notifications (4)").performClick()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNode(hasText("Yesterday") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        compose.onNodeWithText("First update").assertDoesNotExist()
        screenshot("notification-history-collapsed")
        compose.onNodeWithContentDescription("Show earlier notifications").performClick()
        compose.onNodeWithText("First update").assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithText("Mark as Read").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Notifications (3)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("First update").performTouchInput { longClick() }
        compose.onNodeWithText("Mark as Unread").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Notifications (4)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Notification filter").performClick()
        compose.onNodeWithText("Unread").performClick()
        compose.onNodeWithText("Task group").performTouchInput { swipeRight() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Latest update").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, peer.requests.count { it.optString("method") == "notification.feed.mark_read" })
        screenshot("notification-history-unread")
        compose.onNodeWithContentDescription("Mark All Read").performClick()
        compose.onNodeWithText("Mark all notifications as read?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, peer.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
        compose.onNodeWithContentDescription("Mark All Read").performClick()
        compose.onNodeWithText("Mark All Read").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No unread notifications.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
    }

    @Test fun allComputerWorkspacesSearchMutateAndOpenTheirOwnerWithCollidingIds() {
        val other = NativeFixturePeer().apply {
            deviceId = "second-mac"; displayName = "Second Mac"; gridFirstLine = "Second Mac terminal"
        }
        try {
            val store = NativeCredentialStore(context)
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.2:58465", "second-mac", "Second Mac")
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { pairing, _ ->
                    val target = if (pairing.routes.first().host == "100.64.0.2") other else peer
                    MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().size == 2 }
            screenshot("workspaces-all-computers")
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput("Second Mac")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().size == 1 }
            // A mutation on a background Mac must not promote the foreground or touch its colliding ID.
            compose.onNode(hasText("⋯") and hasAnyAncestor(hasContentDescription("Claude Code task on Second Mac")))
                .performClick()
            compose.onNodeWithText("Rename").performClick()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Second renamed task")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Second renamed task").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, peer.requests.count { it.optString("method") == "workspace.action" })
            assertEquals(1, other.requests.count { it.optString("method") == "workspace.action" })
            assertEquals(0, other.requests.count { it.optString("method") == "mobile.terminal.replay" })
            compose.onNodeWithText("Second renamed task").performClick()
            compose.waitUntil(15_000) { other.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            assertEquals("workspace-1", other.requests.last { it.optString("method") == "mobile.terminal.replay" }
                .getJSONObject("params").getString("workspace_id"))
            assertEquals(0, peer.requests.count { it.optString("method") == "mobile.terminal.replay" })
            assertEquals(0, other.requests.count { it.optString("method") == "notification.feed.mark_read" })
            compose.onNodeWithText("‹  2").performClick()
            assertSearchFilter("Second Mac")
            compose.onNodeWithText("Second renamed task").assertIsDisplayed()
            compose.onNodeWithText("Claude Code task").assertDoesNotExist()
            openSearch(); compose.onNodeWithContentDescription("Cancel search").performClick()
            compose.onNodeWithContentDescription("Computer filter").performClick()
            compose.onNode(hasText("Fixture Mac") and hasAnyAncestor(isPopup())).performClick()
            // The old filter remains visible until the selected Mac finishes its handshake.
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Second renamed task").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Second renamed task").assertDoesNotExist()
            compose.onNodeWithText("Claude Code task").assertIsDisplayed()
            compose.onNodeWithContentDescription("Computer filter").performClick()
            compose.onNode(hasText("All Computers") and hasAnyAncestor(isPopup())).performClick()
            compose.onNodeWithText("Second renamed task").assertIsDisplayed()
            compose.onNodeWithText("Claude Code task").assertIsDisplayed()
        } finally { other.close() }
    }

    @Test fun combinedFeedKeepsDuplicateIdsAndRoutesSearchResultsToTheirMac() {
        val other = NativeFixturePeer().apply {
            deviceId = "second-mac"; displayName = "Second Mac"; gridFirstLine = "Second Mac terminal"
        }
        try {
            val now = System.currentTimeMillis() / 1000.0
            fun notification(title: String, workspace: String, surface: String) = JSONArray().put(JSONObject()
                .put("id", "same-id").put("workspace_id", workspace).put("surface_id", surface)
                .put("title", "Agent").put("workspace_title", title).put("body", "Ready for review")
                .put("created_at", now).put("is_read", false))
            peer.notificationFeed = notification("First machine task", "workspace-1", "terminal-1")
            other.notificationFeed = notification("Second machine task", "workspace-2", "terminal-2")
            val firstCode = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
            val secondCode = "cmux-ios://attach?v=2&r=100.64.0.2:58465"
            NativeCredentialStore(context).apply {
                rememberMac(secondCode, "second-mac", "Second Mac")
                rememberMac(firstCode, "fixture-mac", "Fixture Mac")
            }
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { pairing, _ ->
                    val target = if (pairing.routes.first().host == "100.64.0.2") other else peer
                    MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Notifications (2)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Notifications (2)").performClick()
            compose.onNodeWithText("First machine task").assertIsDisplayed()
            compose.onNodeWithText("Second machine task").assertIsDisplayed()
            screenshot("notification-multiple-macs")
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput("Second Mac")
            compose.onNodeWithText("First machine task").assertDoesNotExist()
            compose.onNodeWithText("Second machine task").performClick()
            try {
                compose.waitUntil(15_000) { other.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            } catch (failure: Throwable) {
                screenshot("notification-mac-switch-failure")
                throw AssertionError(compose.onRoot().printToString() + "\nSecond Mac methods: " +
                    other.requests.map { it.optString("method") }.joinToString(), failure)
            }
            compose.waitUntil(10_000) { other.requests.any { it.optString("method") == "notification.feed.mark_read" } }
            val replay = other.requests.last { it.optString("method") == "mobile.terminal.replay" }.getJSONObject("params")
            assertEquals("workspace-2", replay.getString("workspace_id"))
            assertEquals("terminal-2", replay.getString("surface_id"))
            assertEquals(0, peer.requests.count { it.optString("method") == "notification.feed.mark_read" })
            compose.onNodeWithText("‹  2").performClick()
            assertSearchFilter("Second Mac", notifications = true)
            compose.onNodeWithText("First machine task").assertDoesNotExist()
            // Search narrows the presentation; the confirmed bulk action still reaches all connected Macs.
            compose.onNodeWithContentDescription("Mark All Read").performClick()
            compose.onNodeWithText("Mark All Read").performClick()
            compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "notification.feed.mark_all_read" } }
            assertEquals(0, other.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
            assertEquals(0, peer.requests.count { it.optString("method") == "mobile.terminal.replay" })
        } finally { other.close() }
    }

    @Test fun notificationComputerPickerScopesBadgeSearchAndBulkRead() {
        val other = NativeFixturePeer().apply { deviceId = "second-mac"; displayName = "Second Mac" }
        try {
            fun notification(title: String) = JSONArray().put(JSONObject().put("id", "same-id")
                .put("workspace_id", "workspace-1").put("surface_id", "terminal-1")
                .put("workspace_title", title).put("title", "Agent").put("created_at", System.currentTimeMillis() / 1000.0))
            peer.notificationFeed = notification("First machine task")
            other.notificationFeed = notification("Second machine task")
            val store = NativeCredentialStore(context)
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.2:58465", "second-mac", "Second Mac")
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { pairing, _ ->
                    val target = if (pairing.routes.first().host == "100.64.0.2") other else peer
                    MobileRpcClient(PairingCode.Route("127.0.0.1", target.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Notifications (2)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Notifications (2)").performClick()
            compose.onNodeWithContentDescription("Computer filter").performClick()
            compose.onNode(hasText("Second Mac") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Notifications (1)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("First machine task").assertDoesNotExist()
            compose.onNodeWithText("Second machine task").assertIsDisplayed()
            assertEquals(store.pairedMacs().single { it.deviceId == "second-mac" }.origin,
                store.load()!!.getString("computer_selection"))
            openSearch()
            compose.onNode(hasSetTextAction()).performTextInput("First machine")
            compose.onNodeWithText("No matching notifications.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Mark All Read").performClick()
            compose.onNodeWithText("Mark All Read").performClick()
            compose.waitUntil(10_000) { other.requests.any { it.optString("method") == "notification.feed.mark_all_read" } }
            assertTrue(peer.requests.none { it.optString("method") == "notification.feed.mark_all_read" })
            // The confirmation dialog can still be restoring the system IME. Invoke the
            // accessible action so moving screen coordinates do not tap the keyboard.
            compose.onNodeWithContentDescription("Cancel search")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            assertSearchFilter("", notifications = true)
            screenshot("notification-computer-scope")
            compose.onNodeWithContentDescription("Computer filter").performClick()
            compose.onNode(hasText("All Computers") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("First machine task").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("First machine task").assertIsDisplayed()
            compose.onNodeWithText("Second machine task").assertIsDisplayed()
            assertEquals("", store.load()!!.getString("computer_selection"))
        } finally { other.close() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

internal class NativeFixturePeer : AutoCloseable {
    private val server = ServerSocket(0)
    val port get() = server.localPort
    val requests = CopyOnWriteArrayList<JSONObject>()
    val failures = CopyOnWriteArrayList<String>()
    val ignoreNextHostStatus = AtomicBoolean(false)
    @Volatile var reconciledNotificationIds = emptyList<String>()
    @Volatile var releaseReconcile: CountDownLatch? = null
    @Volatile var identifiedInput = false
    @Volatile var identifiedInputBusy = false
    val appliedInputIdentities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var dropReplyAfterMethod: String? = null
    val lostReplies = CopyOnWriteArrayList<String>()
    val rejectNextPaste = AtomicBoolean(false)
    val rejectNextInput = AtomicBoolean(false)
    @Volatile var releaseNextInput: CountDownLatch? = null
    @Volatile var releaseNextPaste: CountDownLatch? = null
    @Volatile var releaseNextFeed: CountDownLatch? = null
    val rejectNextForegroundFeed = java.util.concurrent.atomic.AtomicBoolean()
    private val sockets = CopyOnWriteArrayList<Socket>()
    fun disconnectClients() { sockets.toList().forEach { runCatching { it.close() } } }
    @Volatile private var closed = false
    private var revision = 0
    @Volatile var deviceId = "fixture-mac"
    @Volatile var instanceTag: String? = null
    @Volatile var displayName = "Fixture Mac"
    @Volatile var notificationFeed = JSONArray()
    @Volatile var renamedWorkspace: String? = null
    @Volatile var hiddenWorkspaceId: String? = null
    @Volatile var customWorkspaceListing: JSONObject? = null
    @Volatile var workspaceListingResponse: ((Boolean) -> JSONObject)? = null
    @Volatile var terminalCreationResponse: ((JSONObject) -> JSONObject)? = null
    @Volatile var workspaceCreationResponse: (() -> JSONObject)? = null
    private val readNotifications = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var releaseTaskModels: CountDownLatch? = null
    @Volatile var taskModelsResponse: JSONObject? = null
    @Volatile var taskModelErrorCode: String? = null
    @Volatile var directoryResponse: ((String, JSONObject) -> JSONObject)? = null
    @Volatile var directoryErrorCode: String? = null
    @Volatile var taskGroupsSupported = false
    @Volatile var browserResponse: ((String, JSONObject) -> JSONObject)? = null
    @Volatile var browserCreationSupported = false
    @Volatile var changesResponse: ((String, JSONObject) -> JSONObject)? = null
    @Volatile var todoSupported = false
    val rejectNextTodo = AtomicBoolean(false)
    @Volatile var todoResponse: ((String, JSONObject) -> JSONObject)? = null
    @Volatile var panelArtifactsSupported = false
    @Volatile var artifactResponse: ((String, JSONObject) -> JSONObject)? = null
    @Volatile var artifactsSupported = false
    @Volatile var changesErrorCode: String? = null
    val nextTaskCreateError = java.util.concurrent.atomic.AtomicReference<String?>(null)
    @Volatile var rawTerminal = false
    @Volatile var replayGateSurface: String? = null
    @Volatile var releaseReplays: CountDownLatch? = null
    val blockedReplaySurfaces = CopyOnWriteArrayList<String>()
    @Volatile var screenAnchor = true
    @Volatile var gridHistoryRows = 0
    @Volatile var alternateScreen = false
    @Volatile var gridFirstLine = "cmux Android terminal"
    @Volatile var gridBackground = "#111316"
    private var viewportColumns = 40
    private var viewportRows = 20
    @Volatile var rawReplayText = ""
    @Volatile var rawReplaySequence = 0L
    @Volatile private var terminalStreamId: String? = null
    private val outputLock = Any()
    private fun send(socket: Socket, envelope: JSONObject) = synchronized(outputLock) {
        socket.getOutputStream().write(MobileFrameCodec.encode(envelope.toString().toByteArray()))
        socket.getOutputStream().flush()
    }
    fun pushTerminalEvent(topic: String, payload: JSONObject) {
        val event = JSONObject().put("kind", "event").put("topic", topic).put("stream_id", terminalStreamId).put("payload", payload)
        sockets.filter { !it.isClosed }.forEach { send(it, event) }
    }
    fun pushBytes(bytes: ByteArray, sequence: Long) {
        val event = JSONObject().put("kind", "event").put("topic", "terminal.bytes").put("stream_id", terminalStreamId)
            .put("payload", JSONObject().put("surface_id", "terminal-1").put("seq", sequence)
                .put("data_b64", java.util.Base64.getEncoder().encodeToString(bytes)))
        sockets.filter { !it.isClosed }.forEach { send(it, event) }
    }
    fun pushBrowserEvent(topic: String, payload: JSONObject, streamId: String? = null) {
        val subscription = streamId ?: requests.last { it.optString("method") == "mobile.events.subscribe" &&
            it.getJSONObject("params").getJSONArray("topics").toString().contains("browser.") }.getJSONObject("params").getString("stream_id")
        val event = JSONObject().put("kind", "event").put("topic", topic).put("stream_id", subscription).put("payload", payload)
        sockets.filter { !it.isClosed }.forEach { send(it, event) }
    }
    private val acceptThread = Thread {
        try {
            while (!closed) {
                val socket = server.accept()
                sockets += socket
                Thread { serve(socket) }.apply { isDaemon = true; start() }
            }
        } catch (failure: Exception) { if (!closed) failures += failure.toString() }
    }.apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        var feedConnection = false
        try {
            socket.use {
                val input = socket.getInputStream()
                val decoder = MobileFrameDecoder()
                val buffer = ByteArray(16384)
                while (!closed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (frame in decoder.feed(buffer.copyOf(count))) {
                        val request = JSONObject(String(frame, Charsets.UTF_8))
                        requests += request
                        if (request.optString("method") == "mobile.host.status" && ignoreNextHostStatus.getAndSet(false)) continue
                        if (request.optString("method") == "notification.reconcile")
                            releaseReconcile?.let { check(it.await(30, TimeUnit.SECONDS)) { "Reconcile response not released" } }
                        if (request.optString("method") == "mobile.events.subscribe" &&
                            request.optJSONObject("params")?.optJSONArray("topics")?.toString()?.contains("notification.feed.changed") == true)
                            feedConnection = true
                        if (request.optString("method") == "mobile.terminal.replay") {
                            releaseReplays?.takeIf { replayGateSurface == request.getJSONObject("params").getString("surface_id") }?.let { latch ->
                                blockedReplaySurfaces += request.getJSONObject("params").getString("surface_id")
                                check(latch.await(20, TimeUnit.SECONDS)) { "Replay acknowledgement was not released" }
                            }
                        }
                        if (request.optString("method") == "terminal.paste") {
                            releaseNextPaste?.let { latch ->
                                check(latch.await(10, TimeUnit.SECONDS)) { "Paste test acknowledgement was not released" }
                                releaseNextPaste = null
                            }
                        }
                        if (request.optString("method") == "terminal.input") {
                            releaseNextInput?.let { latch ->
                                check(latch.await(10, TimeUnit.SECONDS)) { "Input acknowledgement was not released" }
                                releaseNextInput = null
                            }
                        }
                        if (request.optString("method") == "notification.feed.list") {
                            releaseNextFeed?.let { latch ->
                                check(latch.await(15, TimeUnit.SECONDS)) { "Feed acknowledgement was not released" }
                                releaseNextFeed = null
                            }
                        }
                        if (request.optString("method") == "mobile.task.models.list") {
                            releaseTaskModels?.let { latch ->
                                check(latch.await(30, TimeUnit.SECONDS)) { "Task model response not released" }
                                releaseTaskModels = null
                            }
                        }
                        val taskError = if (request.optString("method") == "workspace.create") nextTaskCreateError.getAndSet(null) else null
                        val todoRejected = (request.optString("method").startsWith("mobile.todo.") || request.optString("method").startsWith("mobile.status.")) && rejectNextTodo.getAndSet(false)
                        val result = if (taskError != null || todoRejected) JSONObject()
                            else if (request.optString("method") == "mobile.workspace.list" && workspaceListingResponse != null)
                                workspaceListingResponse!!.invoke(feedConnection)
                            else response(request.optString("method"), request.optJSONObject("params") ?: JSONObject())
                        val inputParams = request.optJSONObject("params")
                        if (identifiedInput && inputParams?.has("input_stream_id") == true) {
                            val identity = inputParams.getString("input_stream_id") + ":" + inputParams.getString("input_stream_seq")
                            val status = if (identifiedInputBusy) "busy" else if (appliedInputIdentities.add(identity)) "applied" else "duplicate"
                            result.put("input_ack", JSONObject().put("status", status).put("stream_id", inputParams.getString("input_stream_id"))
                                .put("sequence", inputParams.getString("input_stream_seq")).put("expected", "0"))
                        }
                        // Execute/record the request, then lose only its reply. socket.use
                        // closes this connection; the listener accepts the app's reconnect.
                        if (request.optString("method") == dropReplyAfterMethod) {
                            dropReplyAfterMethod = null
                            lostReplies += request.getString("method")
                            return
                        }
                        val modelError = taskModelErrorCode.takeIf { request.optString("method") == "mobile.task.models.list" }
                        val directoryError = directoryErrorCode.takeIf { request.optString("method").startsWith("mobile.directory.") }
                        val changesError = changesErrorCode.takeIf { request.optString("method").startsWith("mobile.workspace.changes.") }
                        val feedRejected = !feedConnection && request.optString("method") == "notification.feed.list" && rejectNextForegroundFeed.getAndSet(false)
                        val rejected = feedRejected || todoRejected || taskError != null || modelError != null || directoryError != null || changesError != null || (request.optString("method") == "terminal.paste" && rejectNextPaste.getAndSet(false)) ||
                            (request.optString("method") == "terminal.input" && rejectNextInput.getAndSet(false))
                        val envelope = JSONObject().put("id", request.getString("id")).put("ok", !rejected)
                        if (rejected) envelope.put("error", JSONObject().put("code", taskError ?: modelError ?: directoryError ?: changesError ?: "surface_unavailable")
                            .put("message", "Fixture terminal temporarily unavailable"))
                        else envelope.put("result", result)
                        send(socket, envelope)
                    }
                }
            }
        } catch (failure: Exception) { if (!closed) failures += failure.toString() }
    }

    private fun response(method: String, params: JSONObject): JSONObject = when (method) {
        "mobile.browser.create" -> browserResponse?.invoke(method, params) ?: JSONObject()
        "mobile.browser.stream.start" -> browserResponse?.invoke(method, params) ?: JSONObject().put("panel_id", params.getString("panel_id"))
            .put("url", "https://cmux.com").put("title", "Browser fixture").put("can_go_back", false).put("can_go_forward", false).put("is_loading", false)
        "mobile.directory.list", "mobile.directory.search" -> directoryResponse?.invoke(method, params) ?: JSONObject()
        "mobile.task.models.list" -> taskModelsResponse?.let { JSONObject(it.toString()) } ?: run {
            val provider = params.getString("provider")
            val model = JSONObject().put("id", "$provider-live").put("display_name", "Local $provider")
                .put("default_effort_id", "high").put("efforts", JSONArray("""[
                  {"id":"low","display_name":"Low","description":"Faster responses"},
                  {"id":"high","display_name":"High","description":"More reasoning"}]
                """))
            JSONObject().put("source", "discovered").put("models", JSONArray().put(model)).put("default_model", model)
        }
        "terminal.create" -> terminalCreationResponse?.invoke(params) ?: JSONObject()
        "workspace.close" -> JSONObject().also { hiddenWorkspaceId = params.getString("workspace_id") }
        "workspace.create" -> workspaceCreationResponse?.invoke() ?: JSONObject("""{"created_workspace_id":"task-created",
            "created_terminal_id":"task-terminal","workspaces":[{"id":"task-created","title":"Created task",
            "terminals":[{"id":"task-terminal","title":"Agent"}]}]}""").also { created ->
            val listing = response("mobile.workspace.list", JSONObject())
            listing.getJSONArray("workspaces").put(created.getJSONArray("workspaces").getJSONObject(0))
            customWorkspaceListing = listing
        }
        "mobile.host.status" -> JSONObject().put("mac_display_name", displayName)
            .put("mac_device_id", deviceId).put("mac_instance_tag", instanceTag).put("capabilities", JSONArray().put("task.attachments.v1").put("workspace.move.v1").put("workspace.task_create.v1").also {
                if (identifiedInput) it.put(TerminalInputDelivery.CAPABILITY)
                if (taskGroupsSupported) it.put("workspace.create_in_group.v1")
                if (browserCreationSupported) it.put("browser.stream.v1").put("browser.stream.create.v1")
                if (todoSupported) it.put("todo.v1")
                if (panelArtifactsSupported) it.put("panel.artifact.v1").put("surface.focus.v1")
                if (artifactsSupported) it.put("terminal.artifact.v1").put("chat.artifact.gallery.v1").put("terminal.artifact.list.v1")
                if (rawTerminal) it.put("terminal.bytes.v1")
                else { it.put("terminal.render_grid.v1"); if (screenAnchor) it.put("terminal.render_grid.screen_anchor.v1") }
            })
        "mobile.workspace.list" -> (customWorkspaceListing?.let { JSONObject(it.toString()) } ?: JSONObject("""{
            "groups":[{"id":"complete","name":"Completed group","is_collapsed":false,"anchor_workspace_id":"workspace-2"}],
            "workspaces":[
              {"id":"workspace-1","window_id":"fixture-window","title":"Claude Code task","current_directory":"~/projects/cmux-app","description":"Release gate",
               "has_unread":true,"terminals":[{"id":"terminal-1","title":"Shell"}]},
              {"id":"workspace-2","window_id":"fixture-window","title":"Read project","group_id":"complete",
               "has_unread":false,"terminals":[{"id":"terminal-2","title":"Shell"}]}
            ]} """)).also { listing ->
            renamedWorkspace?.let { listing.getJSONArray("workspaces").getJSONObject(0).put("title", it) }
            hiddenWorkspaceId?.let { hidden ->
                val items = listing.getJSONArray("workspaces")
                for (index in items.length() - 1 downTo 0) if (items.getJSONObject(index).getString("id") == hidden) items.remove(index)
            }
        }
        "workspace.move" -> JSONObject().also {
            customWorkspaceListing?.let { listing ->
                val array = listing.getJSONArray("workspaces")
                val rows = (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
                val moved = rows.single { it.getString("id") == params.getString("workspace_id") }
                rows.remove(moved)
                moved.put("group_id", params.optString("group_id").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                val before = rows.indexOfFirst { it.getString("id") == params.optString("before_workspace_id") }
                rows.add(if (before < 0) rows.size else before, moved)
                customWorkspaceListing = JSONObject(listing.toString()).put("workspaces", JSONArray(rows))
            }
        }
        "workspace.action" -> JSONObject().also {
            if (params.optString("action") == "rename" && params.optString("workspace_id") == "workspace-1")
                renamedWorkspace = params.getString("title")
            if (params.optString("action") in setOf("mark_read", "mark_unread")) {
                customWorkspaceListing?.let { original ->
                    val listing = JSONObject(original.toString())
                    val rows = listing.getJSONArray("workspaces")
                    for (index in 0 until rows.length()) {
                        val row = rows.getJSONObject(index)
                        if (row.getString("id") == params.getString("workspace_id")) {
                            val unread = params.getString("action") == "mark_unread"
                            row.put("has_unread", unread).put("unread_count", if (unread) 1 else 0)
                        }
                    }
                    customWorkspaceListing = listing
                }
            }
        }
        "mobile.task.attachment.upload" -> JSONObject().put("path", "/tmp/cmux fixture.txt")
        "notification.feed.mark_read", "notification.feed.mark_unread" -> JSONObject().also {
            val ids = params.getJSONArray("notification_ids")
            for (index in 0 until ids.length()) {
                val id = ids.getString(index)
                if (method == "notification.feed.mark_read") readNotifications.add(id) else readNotifications.remove(id)
            }
        }
        "notification.feed.mark_all_read" -> JSONObject().also {
            for (index in 0 until notificationFeed.length()) readNotifications.add(notificationFeed.getJSONObject(index).getString("id"))
        }
        "notification.reconcile" -> JSONObject().put("handled_ids", JSONArray(reconciledNotificationIds))
        "notification.feed.list" -> JSONObject().put("notifications", JSONArray().also { output ->
            for (index in 0 until notificationFeed.length()) {
                val item = JSONObject(notificationFeed.getJSONObject(index).toString())
                if (item.getString("id") in readNotifications) item.put("is_read", true)
                output.put(item)
            }
        })
        "mobile.events.subscribe" -> JSONObject().put("stream_id", params.optString("stream_id").also {
            if (params.optJSONArray("topics")?.toString()?.contains("terminal.") == true) terminalStreamId = it
        })
        "mobile.terminal.viewport" -> JSONObject().put("columns", params.optInt("viewport_columns", 40).also { viewportColumns = it })
            .put("rows", params.optInt("viewport_rows", 20).also { viewportRows = it })
        "mobile.terminal.scroll" -> if (!rawTerminal && !screenAnchor) {
            gridFirstLine = "Scrolled host viewport"
            response("mobile.terminal.replay", JSONObject().put("surface_id", params.getString("surface_id"))
                .put("viewport_columns", viewportColumns).put("viewport_rows", viewportRows))
        } else JSONObject()
        "mobile.terminal.replay" -> if (rawTerminal) {
            JSONObject().put("snapshot_data_b64", java.util.Base64.getEncoder().encodeToString(rawReplayText.toByteArray()))
                .put("seq", rawReplaySequence).put("columns", params.optInt("viewport_columns", 40))
                .put("rows", params.optInt("viewport_rows", 20))
        } else {
            val columns = params.optInt("viewport_columns", 40)
            val rows = params.optInt("viewport_rows", 20)
            val spans = JSONArray()
            listOf(gridFirstLine, "Colors and grid layout", "$ printf cmux", "cmux").forEachIndexed { row, line ->
                val value = line.take(columns)
                if (row < rows) spans.put(JSONObject().put("row", row).put("column", 0)
                    .put("text", value).put("cell_width", value.length).put("style_id", 0))
            }
            JSONObject().put("render_grid", JSONObject().put("format", "cmux.render-grid.v1")
                .put("surface_id", params.getString("surface_id")).put("columns", columns).put("rows", rows)
                .put("render_epoch", "fixture").put("render_revision", ++revision).put("full", true)
                .put("active_screen", if (alternateScreen) "alternate" else "primary")
                .put("terminal_background", gridBackground)
                .put("row_spans", spans).put("styles", JSONArray()).apply {
                    if (gridHistoryRows > 0) {
                        put("anchor", "screen"); put("history_rows", gridHistoryRows); put("row_space_revision", 1)
                        put("scrollback_rows", gridHistoryRows)
                        put("styles", JSONArray().put(JSONObject().put("id", 1).put("background", "#123456")))
                        put("scrollback_spans", JSONArray((0 until gridHistoryRows).map { row ->
                            val text = "History $row".take(columns)
                            JSONObject().put("row", row).put("column", 0).put("text", text).put("cell_width", text.length).put("style_id", 1)
                        }))
                    }
                }
                .put("cursor", JSONObject().put("row", 4.coerceAtMost(rows - 1)).put("column", 0)
                    .put("visible", true).put("style", "block")))
        }
        else -> when {
            method.startsWith("mobile.browser.") -> browserResponse?.invoke(method, params) ?: JSONObject()
            method.startsWith("mobile.workspace.changes.") -> changesResponse?.invoke(method, params) ?: JSONObject()
            method.startsWith("mobile.todo.") || method.startsWith("mobile.status.") -> todoResponse?.invoke(method, params) ?: JSONObject()
            method.startsWith("mobile.terminal.artifact.") || method.startsWith("mobile.chat.artifact.") || method.startsWith("mobile.panel.artifact.") -> artifactResponse?.invoke(method, params) ?: JSONObject()
            else -> JSONObject()
        }
    }

    override fun close() {
        closed = true
        server.close()
        sockets.forEach { runCatching { it.close() } }
        acceptThread.join(1000)
    }
}
