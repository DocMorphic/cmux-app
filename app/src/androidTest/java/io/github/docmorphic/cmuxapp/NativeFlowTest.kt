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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startPeer() {
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
        compose.activity.finish()
        peer.close()
        NativeCredentialStore(context).clear()
        NativeCredentialStore(context, "native_notification_state").clear()
        NativeCredentialStore(context, "native_terminal_drafts").clear()
        TerminalDraftRepository.get(context).drafts.clear()
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
        val initialRows = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" }
            .getJSONObject("params").getInt("viewport_rows")
        screenshot("terminal")
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("printf cmux")
        try { compose.waitUntil(10_000) {
            peer.requests.any {
                it.optString("method") == "mobile.terminal.viewport" &&
                    it.getJSONObject("params").optInt("viewport_rows", initialRows) < initialRows
            }
        } } finally {
            waitForTerminalText()
            screenshot("terminal-keyboard")
        }
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

    @Test fun directKeyboardCompositionKeysPauseAndTargetSwitch() {
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
        val initialRows = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" }
            .getJSONObject("params").getInt("viewport_rows")
        compose.onNode(hasSetTextAction()).performTextInput("Keep my composer draft")
        compose.onNodeWithText("Keyboard").performClick()
        try { compose.waitUntil(10_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true &&
                peer.requests.lastOrNull { it.optString("method") == "mobile.terminal.viewport" }
                    ?.getJSONObject("params")?.optInt("viewport_rows", initialRows)?.let { it < initialRows } == true
        } } finally { screenshot("terminal-direct-open") }
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
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 7 }
        assertEquals(listOf("你", "\u007f\u007f", "界", "\r", "\u0003", "\u001b[A", "\u001bOP"),
            peer.requests.filter { it.optString("method") == "terminal.input" }.map { it.getJSONObject("params").getString("text") })
        peer.rejectNextInput.set(true)
        val release = CountDownLatch(1)
        peer.releaseNextInput = release
        compose.runOnIdle { connection.commitText("maybe delivered", 1); connection.commitText("never replay this", 1) }
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 8 }
        release.countDown()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Resume typing").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Resume typing").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Resume typing").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            keyboard = findTerminalKeyboard(compose.activity.window.decorView)!!
            assertTrue(keyboard.isEnabled)
            connection = keyboard.onCreateInputConnection(EditorInfo())!!
            connection.commitText("after resume", 1)
        }
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 9 }
        assertTrue(peer.requests.none { it.optJSONObject("params")?.optString("text") == "never replay this" })
        compose.onNodeWithText("Compose").performClick()
        assertDraft("Keep my composer draft")
        val oldConnection = connection
        compose.onNodeWithText("‹  2").performClick()
        openReadProject()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Keyboard").performClick()
        compose.runOnIdle {
            assertTrue(!oldConnection.commitText("stale input", 1))
            val next = findTerminalKeyboard(compose.activity.window.decorView)!!
            next.onCreateInputConnection(EditorInfo())!!.commitText("second terminal", 1)
        }
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 10 }
        val last = peer.requests.last { it.optString("method") == "terminal.input" }.getJSONObject("params")
        assertEquals("terminal-2", last.getString("surface_id"))
        assertEquals("second terminal", last.getString("text"))
        assertTrue(peer.requests.none { it.optJSONObject("params")?.optString("text") == "stale input" })
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
        terminal.performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height / 2f)) }
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.mouse" } }
        val click = peer.requests.single { it.optString("method") == "mobile.terminal.mouse" }.getJSONObject("params")
        val viewport = peer.requests.first { it.optString("method") == "mobile.terminal.viewport" }.getJSONObject("params")
        assertEquals(viewport.getInt("viewport_columns") / 2, click.getInt("col"))
        assertEquals(viewport.getInt("viewport_rows") / 2, click.getInt("row"))
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
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Task prompt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Agent").performClick()
            compose.onNodeWithText("Codex", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Task prompt").performTextInput("First Mac saved task")
            compose.onNodeWithText("‹  Workspaces").performClick()
            compose.onNodeWithText("Save Draft").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Computer filter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Computer filter").performClick()
            compose.onNode(hasText("Second Mac") and hasAnyAncestor(isPopup())).performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("New Task") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("New Task").performClick()
            compose.onNodeWithText("Task prompt").performTextInput("Second Mac saved task")
            compose.onNodeWithText("Drafts").performClick()
            compose.onNodeWithText("First Mac saved task").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("First Mac saved task") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Create Task").performClick()
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

    @Test fun taskCreationOpensExactTerminalAndPreservesExistingWorkspaces() {
        peer.notificationFeed = searchNotifications()
        showSearchFixture()
        compose.onNodeWithContentDescription("New Task").performClick()
        compose.onNodeWithText("Task prompt").performTextInput("Create and open a task")
        compose.onNodeWithText("Create Task").performClick()
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
        compose.onNodeWithText("Task prompt").performTextInput("Recover and open a task")
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Refresh Workspaces") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Create Task").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Create Task").assertIsNotEnabled()
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
        compose.onNodeWithText("New Task").assertIsDisplayed()
        compose.onNodeWithText("‹  Workspaces").performClick()
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
    val rejectNextPaste = AtomicBoolean(false)
    val rejectNextInput = AtomicBoolean(false)
    @Volatile var releaseNextInput: CountDownLatch? = null
    @Volatile var releaseNextPaste: CountDownLatch? = null
    @Volatile var releaseNextFeed: CountDownLatch? = null
    private val sockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var closed = false
    private var revision = 0
    @Volatile var deviceId = "fixture-mac"
    @Volatile var displayName = "Fixture Mac"
    @Volatile var notificationFeed = JSONArray()
    @Volatile var renamedWorkspace: String? = null
    @Volatile var hiddenWorkspaceId: String? = null
    @Volatile var customWorkspaceListing: JSONObject? = null
    private val readNotifications = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var releaseTaskModels: CountDownLatch? = null
    @Volatile var taskModelsResponse: JSONObject? = null
    @Volatile var taskModelErrorCode: String? = null
    val nextTaskCreateError = java.util.concurrent.atomic.AtomicReference<String?>(null)
    @Volatile var rawTerminal = false
    @Volatile var screenAnchor = true
    @Volatile var alternateScreen = false
    @Volatile var gridFirstLine = "cmux Android terminal"
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
    fun pushBytes(bytes: ByteArray, sequence: Long) {
        val event = JSONObject().put("kind", "event").put("topic", "terminal.bytes").put("stream_id", terminalStreamId)
            .put("payload", JSONObject().put("surface_id", "terminal-1").put("seq", sequence)
                .put("data_b64", java.util.Base64.getEncoder().encodeToString(bytes)))
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
                        val result = if (taskError != null) JSONObject() else response(request.optString("method"), request.optJSONObject("params") ?: JSONObject())
                        val modelError = taskModelErrorCode.takeIf { request.optString("method") == "mobile.task.models.list" }
                        val rejected = taskError != null || modelError != null || (request.optString("method") == "terminal.paste" && rejectNextPaste.getAndSet(false)) ||
                            (request.optString("method") == "terminal.input" && rejectNextInput.getAndSet(false))
                        val envelope = JSONObject().put("id", request.getString("id")).put("ok", !rejected)
                        if (rejected) envelope.put("error", JSONObject().put("code", taskError ?: modelError ?: "surface_unavailable")
                            .put("message", "Fixture terminal temporarily unavailable"))
                        else envelope.put("result", result)
                        send(socket, envelope)
                    }
                }
            }
        } catch (failure: Exception) { if (!closed) failures += failure.toString() }
    }

    private fun response(method: String, params: JSONObject): JSONObject = when (method) {
        "mobile.task.models.list" -> taskModelsResponse?.let { JSONObject(it.toString()) } ?: run {
            val provider = params.getString("provider")
            val model = JSONObject().put("id", "$provider-live").put("display_name", "Local $provider")
                .put("default_effort_id", "high").put("efforts", JSONArray("""[
                  {"id":"low","display_name":"Low","description":"Faster responses"},
                  {"id":"high","display_name":"High","description":"More reasoning"}]
                """))
            JSONObject().put("source", "discovered").put("models", JSONArray().put(model)).put("default_model", model)
        }
        "workspace.create" -> JSONObject("""{"created_workspace_id":"task-created",
            "created_terminal_id":"task-terminal","workspaces":[{"id":"task-created","title":"Created task",
            "terminals":[{"id":"task-terminal","title":"Agent"}]}]}""").also { created ->
            val listing = response("mobile.workspace.list", JSONObject())
            listing.getJSONArray("workspaces").put(created.getJSONArray("workspaces").getJSONObject(0))
            customWorkspaceListing = listing
        }
        "mobile.host.status" -> JSONObject().put("mac_display_name", displayName)
            .put("mac_device_id", deviceId).put("capabilities", JSONArray().put("task.attachments.v1").put("workspace.move.v1").put("workspace.task_create.v1").also {
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
                .put("row_spans", spans).put("styles", JSONArray())
                .put("cursor", JSONObject().put("row", 4.coerceAtMost(rows - 1)).put("column", 0)
                    .put("visible", true).put("style", "block")))
        }
        else -> JSONObject()
    }

    override fun close() {
        closed = true
        server.close()
        sockets.forEach { runCatching { it.close() } }
        acceptThread.join(1000)
    }
}
