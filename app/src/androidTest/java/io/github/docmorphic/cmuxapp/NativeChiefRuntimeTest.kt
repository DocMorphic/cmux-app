package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Production Chief composition with a typed local owner; no account store or host mutations. */
@OptIn(ExperimentalTestApi::class)
class NativeChiefRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var computer: NativeChiefComputerSession
    private lateinit var owner: CoroutineScope
    private val instant = Instant.parse("2026-10-20T12:00:00Z")
    private val api = Api()
    private var current = true

    @Before fun start() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        compose.runOnUiThread {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            computer = NativeChiefComputerSession(owner, api) { current }
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { NativeChiefView(computer, true, Modifier.fillMaxSize()) } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefPinnedConversation").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun stop() { if (::owner.isInitialized) compose.runOnUiThread { computer.close(); owner.cancel() } }

    private inner class Api : NativeChiefApi {
        val homeChanges = Channel<NativeChiefSnapshot>(Channel.UNLIMITED)
        val transcriptChanges = Channel<NativeChiefRead>(Channel.UNLIMITED)
        val sends = CopyOnWriteArrayList<Pair<String, String?>>()
        val answers = CopyOnWriteArrayList<Pair<List<String>, String?>>()
        var uncertainAnswerOnce = false
        var rows = listOf(message("hello", 1uL, "Welcome from your Mac"))
        val home get() = listOf(NativeChiefConversation("chief-main", "Chief", "Ready on your Mac", 1, true, false, instant),
            NativeChiefConversation("build", "Release build", "Review the Android app", 0, false, false, instant))
        override suspend fun status() = NativeChiefStatus(true, null, null, "chief", "Chief", "", "", 1, 2, emptyList(), "chief-main")
        override suspend fun conversations(afterCursor: String?, waitForChange: Boolean) =
            if (waitForChange) homeChanges.receive() else NativeChiefSnapshot(home, "home-cursor")
        override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead =
            if (waitForChange) transcriptChanges.receive() else snapshot()
        fun snapshot() = NativeChiefRead("chief-main", false, rows, false, 0uL, 1, rows.maxOfOrNull { it.seq }, "transcript-cursor")
        override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent {
            sends += text to clientMessageId
            rows = rows + message("echo", 2uL, text, human = true, clientId = clientMessageId)
            transcriptChanges.send(snapshot())
            return NativeChiefSent("chief-main", "echo", clientMessageId, false)
        }
        override suspend fun markRead(conversation: String?, upToSeq: ULong) = NativeChiefMarked("chief-main", upToSeq, true)
        override suspend fun stop(conversation: String?) = NativeChiefStopped("chief-main", true)
        override suspend fun approve(conversation: String?, request: String?, choice: String) = NativeChiefApproved("chief-main", request.orEmpty(), choice, true)
        override suspend fun answer(conversation: String?, message: String?, choices: List<String>, text: String?): NativeChiefAnswered {
            answers += choices to text
            if (uncertainAnswerOnce) { uncertainAnswerOnce = false; throw MobileRpcOutcomeUnknown() }
            rows = rows.map { row -> row.copy(partPayloads = row.partPayloads.map { part ->
                part.question?.let { q -> part.copy(question = q.copy(state = NativeChiefQuestion.State.CANCELLED)) } ?: part
            }) }
            transcriptChanges.send(snapshot())
            return NativeChiefAnswered("chief-main", message.orEmpty(), choices)
        }
        override suspend fun open(windowId: String?, workspaceId: String?) = NativeChiefOpened(windowId, true)
    }
    private fun message(id: String, seq: ULong, text: String, human: Boolean = false, clientId: String? = null,
        parts: List<NativeChiefPart> = emptyList()) = NativeChiefMessage(id, seq, if (human) "user" else "chief",
        if (human) "You" else "Chief", if (human) "human" else "chief", text, instant, emptyList(), parts, null, clientId)

    @Test fun homeSearchSendEchoAndConversationDraftRestorationUseTheProductionComposition() {
        compose.onNodeWithTag("ChiefSearch").performTextInput("Release")
        compose.onNodeWithTag("ChiefPinnedConversation").assertDoesNotExist()
        compose.onNodeWithTag("ChiefConversation:build").assertIsDisplayed()
        compose.onNodeWithTag("ChiefSearch").performTextClearance()
        compose.onNodeWithTag("ChiefSearch").performTextInput("Chief")
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Welcome from your Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefComposer").performTextInput("A phone message")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithTag("ChiefMessage:echo").assertExists()
        compose.onAllNodesWithText("A phone message", substring = false).assertCountEquals(1)
        assertEquals("A phone message", api.sends.single().first); assertFalse(api.sends.single().second.isNullOrBlank())
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep my draft")
        compose.onNodeWithText("‹  Home").performClick()
        compose.onNodeWithTag("ChiefSearch").assertTextContains("Chief")
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefComposer").assertTextContains("Keep my draft")
    }

    @Test fun fullQuestionFormQualifiesDuplicateOptionIdsAndShowsTheMacReceipt() {
        val q = NativeChiefQuestion("question", NativeChiefQuestion.Source(NativeChiefQuestion.Harness.CHIEF, "chief-main", null, null, null, null, null),
            listOf(NativeChiefQuestion.Item("region", "Region", "Where should it run?", listOf(NativeChiefQuestion.Option("default", "US", null, null)), false, true),
                NativeChiefQuestion.Item("checks", "Checks", "Which checks?", listOf(NativeChiefQuestion.Option("default", "Unit tests", "Run focused checks", null)), true, false)),
            NativeChiefQuestion.State.PENDING, null, null)
        compose.runOnUiThread {
            api.rows = listOf(message("question-row", 1uL, "Choose the next step", parts = listOf(NativeChiefPart("question", null, null, q))))
            api.uncertainAnswerOnce = true
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefAnswer:question-row").performScrollTo().performClick()
        compose.onNodeWithTag("ChiefQuestionOption:checks:default").performScrollTo().performClick()
        compose.onNodeWithTag("ChiefQuestionOther:region").performScrollTo().performTextInput("Canada")
        compose.onNodeWithTag("ChiefQuestionSheet").assertExists()
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "chief-question-sheet.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithTag("ChiefQuestionSubmit").performClick()
        compose.waitUntil(10_000) { api.answers.size == 1 }
        compose.onNodeWithTag("ChiefQuestionError").assertExists()
        compose.onNodeWithTag("ChiefQuestionSubmit").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { api.answers.size == 2 }
        assertTrue(api.answers.all { it.first == listOf("checks\u001fdefault") && it.second == "Canada" })
        compose.onNodeWithTag("ChiefQuestionSheet").assertDoesNotExist()
        compose.onNodeWithText("Answered in chat").assertExists()
    }

    @Test fun retiringTheOwnerClearsTheVisibleTranscriptAndDraft() {
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Private draft")
        compose.runOnUiThread { current = false; computer.setActive(false) }
        compose.onNodeWithTag("ChiefComposer").assertDoesNotExist()
        compose.onNodeWithText("Private draft").assertDoesNotExist()
        compose.onNodeWithText("Welcome from your Mac").assertDoesNotExist()
    }
}
