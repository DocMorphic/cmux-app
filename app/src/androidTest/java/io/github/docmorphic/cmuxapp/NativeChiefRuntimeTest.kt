package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.getOrNull
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
    private var testFontScale by mutableFloatStateOf(1f)

    @Before fun start() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        compose.runOnUiThread {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            computer = NativeChiefComputerSession(owner, api) { current }
        }
        // Match NativeScreenLayout's outer inset consumption. The Chief subtree
        // owns its transcript, while its production parent owns system bars.
        compose.setContent { CmuxTheme {
            val hostDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(hostDensity.density, testFontScale)) {
            Surface(Modifier.fillMaxSize().background(Color(0xFF0B0D10)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            NativeChiefView(computer, true, Modifier.fillMaxSize())
        } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefPinnedConversation").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun stop() { if (::owner.isInitialized) compose.runOnUiThread { computer.close(); owner.cancel() } }

    private inner class Api : NativeChiefApi {
        val homeChanges = Channel<NativeChiefSnapshot>(Channel.UNLIMITED)
        val transcriptChanges = Channel<NativeChiefRead>(Channel.UNLIMITED)
        val sends = CopyOnWriteArrayList<Pair<String, String?>>()
        val answers = CopyOnWriteArrayList<Pair<List<String>, String?>>()
        val marks = CopyOnWriteArrayList<ULong>()
        var readMarker = 0uL
        var uncertainAnswerOnce = false
        var sendFailure: Exception? = null
        var sendGate: CompletableDeferred<Unit>? = null
        var rows = listOf(message("hello", 1uL, "Welcome from your Mac"))
        val home get() = listOf(NativeChiefConversation("chief-main", "Chief", "Ready on your Mac", 1, true, false, instant),
            NativeChiefConversation("build", "Release build", "Review the Android app", 0, false, false, instant))
        override suspend fun status() = NativeChiefStatus(true, null, null, "chief", "Chief", "", "", 1, 2, emptyList(), "chief-main")
        override suspend fun conversations(afterCursor: String?, waitForChange: Boolean) =
            if (waitForChange) homeChanges.receive() else NativeChiefSnapshot(home, "home-cursor")
        override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead {
            val page = if (waitForChange) transcriptChanges.receive() else snapshot()
            val eligible = page.messages.filter { beforeSeq == null || it.seq < beforeSeq }
            return page.copy(messages = eligible.takeLast(limit), hasMore = eligible.size > limit)
        }
        fun snapshot() = NativeChiefRead("chief-main", false, rows, false, readMarker,
            rows.count { it.seq > readMarker && it.authorKind != "human" }.toLong(), rows.maxOfOrNull { it.seq }, "transcript-cursor")
        override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent {
            sends += text to clientMessageId
            sendGate?.await()
            sendFailure?.let { throw it }
            val seq = (rows.maxOfOrNull { it.seq } ?: 0uL) + 1uL
            val id = if (rows.none { it.id == "echo" }) "echo" else "echo-$seq"
            rows = rows + message(id, seq, text, human = true, clientId = clientMessageId)
            transcriptChanges.send(snapshot())
            return NativeChiefSent("chief-main", id, clientMessageId, false)
        }
        override suspend fun markRead(conversation: String?, upToSeq: ULong): NativeChiefMarked {
            marks += upToSeq; readMarker = maxOf(readMarker, upToSeq)
            return NativeChiefMarked("chief-main", upToSeq, true)
        }
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
        compose.onNodeWithContentDescription("Stop Chief").assertHasClickAction()
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

    @Test fun failedSendStaysBeforeLaterRepliesAndItsLateEchoKeepsTheSameRowIdentity() {
        compose.runOnUiThread { api.sendFailure = MobileRpcOutcomeUnknown() }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Unconfirmed phone message")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithText("Delivery unconfirmed").assertExists()
        val id = checkNotNull(api.sends.single().second)
        compose.runOnUiThread {
            api.rows = api.rows + message("later", 2uL, "A later Chief reply")
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:later").fetchSemanticsNodes().isNotEmpty() }
        val failedTop = compose.onNodeWithTag("ChiefRow:c:$id").fetchSemanticsNode().boundsInRoot.top
        val replyTop = compose.onNodeWithTag("ChiefRow:s:later").fetchSemanticsNode().boundsInRoot.top
        assertTrue("A failed row must remain before later arrivals", failedTop < replyTop)
        compose.runOnUiThread {
            api.rows = api.rows + message("late-echo", 3uL, "Unconfirmed phone message", true, id)
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:late-echo").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("ChiefRow:c:$id").assertCountEquals(1)
        compose.onNodeWithText("Delivery unconfirmed").assertDoesNotExist()
        compose.onAllNodesWithText("Unconfirmed phone message").assertCountEquals(1)
    }

    @Test fun transcriptSeparatorsAndReplyQuotesUseTheSourceRunBoundaries() {
        compose.runOnUiThread {
            api.rows = listOf(message("first", 1uL, "Original Chief message"),
                message("second", 2uL, "Same run").copy(at = instant.plusSeconds(60)),
                message("quote", 3uL, "One hour later").copy(at = instant.plusSeconds(3660), threadRoot = "first"))
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefTimestamp:s:first").assertExists()
        compose.onNodeWithTag("ChiefTimestamp:s:second").assertDoesNotExist()
        compose.onNodeWithTag("ChiefTimestamp:s:quote").assertExists()
        compose.onNodeWithTag("ChiefQuote:quote").assertExists()
        compose.onAllNodesWithText("Original Chief message").assertCountEquals(2)
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "chief-timeline.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun enlargedTextKeepsChiefHeaderAndComposerUsable() {
        compose.runOnUiThread { testFontScale = 2f }
        compose.onNodeWithTag("ChiefPinnedConversation").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Stop Chief").assertIsDisplayed()
        compose.onNodeWithText("‹  Home").assertIsDisplayed()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Large text draft")
        compose.onNodeWithTag("ChiefComposer").assertTextContains("Large text draft")
        compose.onNodeWithTag("ChiefSend").assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "chief-enlarged-text.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun failedSendSheetRetriesWithTheOriginalClientIdAndClosesOnItsEcho() {
        compose.runOnUiThread { api.sendFailure = MobileRpcOutcomeUnknown() }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Retry this message")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        val id = api.sends.single().second!!
        compose.onNodeWithTag("ChiefFailed:$id").performScrollTo().performClick()
        compose.onNodeWithTag("ChiefFailedSendDialog").assertExists()
        compose.onNodeWithTag("ChiefRetrySend").assertIsEnabled()
        compose.runOnUiThread { api.sendFailure = null }
        compose.onNodeWithTag("ChiefRetrySend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 2 }
        assertEquals(api.sends[0], api.sends[1])
        compose.onNodeWithTag("ChiefMessage:echo").assertExists()
        compose.onNodeWithTag("ChiefFailedSendDialog").assertDoesNotExist()
        compose.onNodeWithTag("ChiefFailed:$id").assertDoesNotExist()
        compose.onAllNodesWithText("Retry this message").assertCountEquals(1)
    }

    @Test fun enlargedFailedSendSheetDeletesLocallyAndDoesNotResurrectAKnownLateEcho() {
        compose.runOnUiThread { api.sendFailure = MobileRpcOutcomeUnknown(); testFontScale = 2f }
        compose.onNodeWithTag("ChiefPinnedConversation").performScrollTo().performClick()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Delete this failed message")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        val id = api.sends.single().second!!
        compose.onNodeWithTag("ChiefFailed:$id").performScrollTo().performClick()
        compose.onNodeWithTag("ChiefRetrySend").assertIsDisplayed()
        compose.onNodeWithTag("ChiefDiscardSend").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        val cancelBounds = compose.onNodeWithText("Cancel").fetchSemanticsNode().boundsInWindow
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val bottomInset = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            assertTrue("The full Cancel button must fit above the navigation bar",
                cancelBounds.bottom <= decor.height - bottomInset)
        }
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "chief-failed-send-enlarged.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("ChiefFailedSendDialog").assertDoesNotExist()
        compose.onNodeWithTag("ChiefFailed:$id").performScrollTo().performClick()
        compose.onNodeWithTag("ChiefDiscardSend").performClick()
        compose.onNodeWithTag("ChiefFailedSendDialog").assertDoesNotExist()
        compose.onAllNodesWithText("Delete this failed message").assertCountEquals(0)
        compose.runOnUiThread {
            api.rows = api.rows + message("late-deleted", 2uL, "Delete this failed message", true, id)
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.headSeq == 2uL }
        compose.waitForIdle()
        compose.onNodeWithTag("ChiefMessage:late-deleted").assertDoesNotExist()
        assertEquals(1, api.sends.size)
    }

    @Test fun navigatingBackWhileSendingKeepsTheOwnerQueueAndTheNextMessageOrder() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread { api.sendGate = gate }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefComposer").performTextInput("First retained send")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithText("‹  Home").performClick()
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithText("First retained send").assertExists()
        compose.onNodeWithText("Delivery unconfirmed").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Second retained send")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.size == 2 }
        assertEquals(1, api.sends.size)
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.isEmpty() == true }
        assertEquals(listOf("First retained send", "Second retained send"), api.sends.map { it.first })
        assertNotEquals(api.sends[0].second, api.sends[1].second)
        compose.onNodeWithTag("ChiefMessage:echo").assertExists()
        compose.onNodeWithTag("ChiefMessage:echo-3").assertExists()
        compose.onAllNodesWithText("First retained send").assertCountEquals(1)
        compose.onAllNodesWithText("Second retained send").assertCountEquals(1)
    }

    @Test fun openingUnreadHistoryReadsTheVisitAndCatchUpLoadsTheOriginalFirstUnread() {
        compose.runOnUiThread {
            api.rows = (1..100).map { message("m$it", it.toULong(), "Chief history message $it") }
            api.readMarker = 20uL
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { api.marks.contains(100uL) }
        compose.onNodeWithTag("ChiefMessage:m100").assertIsDisplayed()
        compose.onNodeWithTag("ChiefMessage:m21").assertDoesNotExist()
        compose.onNodeWithTag("ChiefCatchUp").assertIsDisplayed()
        val headerBefore = compose.onNodeWithContentDescription("Stop Chief").fetchSemanticsNode().boundsInWindow
        saveScreenshot("chief-unread-newest")
        compose.onNodeWithTag("ChiefCatchUp").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.catchUpMarker == null }
        compose.onNodeWithTag("ChiefMessage:m21").assertIsDisplayed()
        compose.onNodeWithTag("ChiefCatchUp").assertDoesNotExist()
        val headerAfter = compose.onNodeWithContentDescription("Stop Chief").fetchSemanticsNode().boundsInWindow
        assertEquals(headerBefore.top, headerAfter.top, 0.5f)
        assertEquals(headerBefore.left, headerAfter.left, 0.5f)
        assertEquals(listOf(100uL), api.marks.toList())
        saveScreenshot("chief-unread-target")
    }

    @Test fun arrivalsReadWhileBrowsingHistoryPreserveTheViewportAndANewSendReturnsToNewest() {
        compose.runOnUiThread {
            api.rows = (1..100).map { message("m$it", it.toULong(), "Chief history message $it") }
            api.readMarker = 100uL
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefMessage:m100").assertIsDisplayed()
        compose.onNodeWithTag("ChiefMessages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().boundsInRoot
        val historyRows = SemanticsMatcher("Chief history message") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("ChiefMessage:m") == true
        }
        val anchor = compose.onAllNodes(historyRows).fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0 && it.boundsInRoot.top >= viewport.top && it.boundsInRoot.bottom <= viewport.bottom }
            .minBy { it.boundsInRoot.top }
        val anchorTag = anchor.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
        val anchorTop = anchor.boundsInRoot.top
        compose.runOnUiThread {
            api.rows = api.rows + message("m101", 101uL, "New arrival while reading history")
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { api.marks.contains(101uL) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag(anchorTag).assertIsDisplayed()
        assertEquals(anchorTop, compose.onNodeWithTag(anchorTag).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("ChiefMessage:m101").assertIsNotDisplayed()
        val gate = CompletableDeferred<Unit>(); compose.runOnUiThread { api.sendGate = gate }
        compose.onNodeWithTag("ChiefComposer").performTextInput("Send from history")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithText("Send from history").assertIsDisplayed()
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.isEmpty() == true }
        compose.waitUntil(10_000) { compose.onNodeWithTag("ChiefMessage:echo").isDisplayed() }
        compose.onNodeWithTag("ChiefMessage:echo").assertIsDisplayed()
    }

    @Test fun browsingKeepsTheLoadedHistoryUntilANewSendReturnsToNewestAndTrimsIt() {
        compose.runOnUiThread {
            api.rows = (1..2000).map { message("m$it", it.toULong(), "Loaded Chief history $it") }
            api.readMarker = 2000uL
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefMessage:m2000").assertIsDisplayed()
        compose.onNodeWithTag("ChiefMessages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val loaded = CompletableDeferred<Unit>()
        compose.runOnUiThread { owner.launch {
            repeat(30) { checkNotNull(computer.transcript()).loadOlder() }
            loaded.complete(Unit)
        } }
        compose.waitUntil(20_000) { loaded.isCompleted }
        compose.waitForIdle()
        assertEquals("Browsing must retain the loaded history", 1550, computer.transcript()!!.state.value.messages.size)
        compose.onNodeWithTag("ChiefMessage:m2000").assertIsNotDisplayed()
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread { api.sendGate = gate }
        compose.onNodeWithTag("ChiefComposer").performTextInput("Return to newest and bound the window")
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.waitUntil(10_000) { api.sends.size == 1 && computer.transcript()?.state?.value?.messages?.size == 1000 }
        compose.onNodeWithText("Return to newest and bound the window").assertIsDisplayed()
        assertTrue(computer.transcript()!!.state.value.hasMore)
        assertEquals(1001uL, computer.transcript()!!.state.value.messages.first().seq)
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.isEmpty() == true }
        compose.waitUntil(10_000) { compose.onNodeWithTag("ChiefMessage:echo").isDisplayed() }
        assertEquals(1001, computer.transcript()!!.state.value.messages.size)
    }

    private fun saveScreenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Compose's test clock may finish before SurfaceFlinger presents its
        // final window frame; this short real-frame wait affects capture only.
        android.os.SystemClock.sleep(150)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
