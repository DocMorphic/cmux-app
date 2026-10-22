package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Production presentation and retained owner, real Activity recreation, controlled read-only API. */
class NativeChiefPollLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<NativeChiefLifecycleTestActivity>? = null
    private lateinit var owner: CoroutineScope
    private lateinit var computer: NativeChiefComputerSession
    private val api = Api()
    private var current = true
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrument)
    private val instant = Instant.parse("2026-10-20T12:00:00Z")

    @Before fun launch() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        instrument.runOnMainSync {
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            computer = NativeChiefComputerSession(owner, api) { current }
            NativeChiefLifecycleTestActivity.computer = computer
        }
        scenario = ActivityScenario.launch(NativeChiefLifecycleTestActivity::class.java)
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.hasLoadedInfo == true }
        compose.runOnIdle { computer.transcript()!!.openPollDetails("poll", "You", "Answer questions") }
        compose.onNodeWithTag("ChiefPollSheet").assertIsDisplayed()
    }
    @After fun cleanup() {
        scenario?.close()
        if (::owner.isInitialized) instrument.runOnMainSync { computer.close(); owner.cancel() }
        NativeChiefLifecycleTestActivity.computer = null
        device.unfreezeRotation()
    }
    private fun probe() = compose.onNodeWithTag("ChiefPollTable").fetchSemanticsNode().config[ChiefPollScrollProbe].invoke()
    private fun capture(name: String, data: JSONObject) {
        val folder = File(instrument.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot().let { image ->
            File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        File(folder, "$name.json").writeText(data.toString(2))
    }
    @Test fun recreatedActivityPreservesAnsweredSnapshotAndActualScrollThenDismissesNormally() {
        val session = computer.transcript()!!
        compose.onNodeWithTag("ChiefPollTable").performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 600f)) }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val offset = probe().offset; assertTrue(offset > 0)
        val before = session.pollDetails.value!!
        // The source reload guard retains its last complete table after a receipt.
        compose.runOnIdle { api.answered = true; api.changes.trySend(api.snapshot()) }
        compose.waitUntil(10_000) { session.state.value.messages.first().partPayloads.first().approval?.decided == "allow" }
        compose.waitForIdle()
        var oldActivity: NativeChiefLifecycleTestActivity? = null
        scenario!!.onActivity { oldActivity = it }
        scenario!!.recreate()
        scenario!!.onActivity { assertNotSame(oldActivity, it) }
        compose.onNodeWithTag("ChiefPollSheet").assertIsDisplayed()
        compose.waitForIdle()
        assertSame(session, computer.transcript())
        assertEquals(before, session.pollDetails.value)
        assertEquals("Actual retained scroll offset", offset, probe().offset)
        val reloaded = probe()
        capture("chief-poll-recreated-receipt", JSONObject().put("beforeOffset", offset).put("afterOffset", reloaded.offset)
            .put("sameTranscript", true).put("sameSnapshot", true).put("generation", before.generation))
        compose.onNodeWithContentDescription("Done").assertIsDisplayed().performClick()
        compose.onNodeWithTag("ChiefPollSheet").assertDoesNotExist()
        assertNull(session.pollDetails.value); assertEquals(0, api.writes)
    }
    @Test fun rotationKeepsSheetAndRevokedOwnerCannotRestoreIt() {
        val session = computer.transcript()!!; val before = session.pollDetails.value!!
        device.setOrientationLeft()
        compose.waitUntil(15_000) { device.displayWidth > device.displayHeight }
        compose.onNodeWithTag("ChiefPollSheet").assertIsDisplayed()
        assertSame(session, computer.transcript()); assertEquals(before, session.pollDetails.value)
        val sheet = compose.onNodeWithTag("ChiefPollSheet").fetchSemanticsNode().boundsInWindow
        assertTrue("Recreated sheet uses actual landscape viewport", sheet.width > sheet.height)
        capture("chief-poll-live-rotation", JSONObject().put("width", sheet.width).put("height", sheet.height)
            .put("displayWidth", device.displayWidth).put("displayHeight", device.displayHeight).put("sameSnapshot", true))
        compose.runOnIdle { current = false }
        scenario!!.recreate()
        compose.onNodeWithTag("ChiefPollSheet").assertDoesNotExist()
        assertTrue(session.state.value.retired); assertNull(session.pollDetails.value)
        assertEquals(0, api.writes)
    }
    @Test fun actualSystemFontScaleRecreatesActivityAndRetainsTheOpenPollAndScroll() {
        val session = computer.transcript()!!; val snapshot = session.pollDetails.value!!
        val table = compose.onNodeWithTag("ChiefPollTable")
        table.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 300f)) }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        val offset = probe().offset; assertTrue(offset > 0)
        val savedSetting = device.executeShellCommand("settings get system font_scale").trim()
        require(savedSetting == "null" || savedSetting.toFloatOrNull()?.let { it.isFinite() && it > 0 } == true)
        var before: NativeChiefLifecycleTestActivity? = null
        var scaled: NativeChiefLifecycleTestActivity? = null
        var original = 1f
        scenario!!.onActivity { before = it; original = it.resources.configuration.fontScale }
        val target = if (kotlin.math.abs(original - 1.3f) < .01f) 1.5f else 1.3f
        capture("chief-poll-system-font-before", JSONObject().put("fontScale", original).put("offset", offset))
        try {
            device.executeShellCommand("settings put system font_scale $target")
            compose.waitUntil(15_000) { runCatching {
                var ready = false
                scenario!!.onActivity {
                    if (it !== before && kotlin.math.abs(it.resources.configuration.fontScale - target) < .001f) { scaled = it; ready = true }
                }
                ready
            }.getOrDefault(false) }
            val text = compose.onNode(hasTestTag("ChiefPollLabel") and hasAnyAncestor(hasTestTag("ChiefPollParticipant:chief")), useUnmergedTree = true)
            var actual: androidx.compose.ui.text.TextLayoutResult? = null
            compose.waitUntil(10_000) { runCatching {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                actual = layouts.single()
                kotlin.math.abs(actual!!.layoutInput.density.fontScale - target) < .001f
            }.getOrDefault(false) }
            text.assertIsDisplayed()
            compose.waitForIdle()
            assertSame(session, computer.transcript()); assertEquals(snapshot, session.pollDetails.value)
            assertEquals("OS recreation retains actual scroll pixels", offset, probe().offset)
            val layout = checkNotNull(actual).layoutInput
            val points = with(layout.density) { layout.style.fontSize.toDp().value }
            assertEquals(with(layout.density) { 17.sp.toDp().value }.toDouble(), points.toDouble(), .02)
            capture("chief-poll-system-font-scaled", JSONObject().put("fontScale", layout.density.fontScale)
                .put("bodyPoints", points).put("offset", probe().offset).put("activityReplaced", true).put("sameSnapshot", true))
            assertEquals(0, api.writes)
        } finally {
            device.executeShellCommand(if (savedSetting == "null") "settings delete system font_scale" else "settings put system font_scale $savedSetting")
            compose.waitUntil(15_000) { runCatching {
                var ready = false
                scenario!!.onActivity { ready = it !== scaled && kotlin.math.abs(it.resources.configuration.fontScale - original) < .001f }
                ready
            }.getOrDefault(false) }
            compose.onNodeWithTag("ChiefPollSheet").assertIsDisplayed()
            assertSame(session, computer.transcript()); assertEquals(snapshot, session.pollDetails.value)
            capture("chief-poll-system-font-restored", JSONObject().put("fontScale", original).put("sameSnapshot", true))
        }
    }
    private inner class Api : NativeChiefApi {
        val changes = Channel<NativeChiefRead>(Channel.UNLIMITED)
        var answered = false
        var writes = 0
        private fun message(id: String, seq: ULong, author: String, name: String, parts: List<NativeChiefPart> = emptyList()) =
            NativeChiefMessage(id, seq, author, name, if (author == "chief") "chief" else "human", "", instant, emptyList(), parts, null, null)
        fun snapshot() = NativeChiefRead("chief-main", false,
            listOf(message("poll", 1uL, "chief", "Chief", listOf(NativeChiefPart("approval", null,
                NativeChiefApproval("request", "Keep this poll through recreation?", listOf("allow", "deny"), if (answered) "allow" else null), null)))) +
                (1..24).map { message("person-$it", (it + 1).toULong(), "person-$it", "Participant $it") },
            false, 25uL, 0, 25uL, "lifecycle-cursor")
        override suspend fun status() = NativeChiefStatus(true, null, null, "chief", "Chief", "", "", 1, 1, emptyList(), "chief-main")
        override suspend fun conversations(afterCursor: String?, waitForChange: Boolean): NativeChiefSnapshot {
            if (waitForChange) awaitCancellation()
            return NativeChiefSnapshot(listOf(NativeChiefConversation("chief-main", "Chief", "Lifecycle fixture", 0, true, false, instant)), "home-cursor")
        }
        override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean) =
            if (waitForChange) changes.receive() else snapshot()
        override suspend fun markRead(conversation: String?, upToSeq: ULong) = NativeChiefMarked("chief-main", upToSeq, true)
        private fun write(): Nothing { writes++; error("Read-only Poll Details attempted a mutation") }
        override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent = write()
        override suspend fun stop(conversation: String?): NativeChiefStopped = write()
        override suspend fun approve(conversation: String?, request: String?, choice: String): NativeChiefApproved = write()
        override suspend fun answer(conversation: String?, message: String?, choices: List<String>, text: String?): NativeChiefAnswered = write()
        override suspend fun open(windowId: String?, workspaceId: String?): NativeChiefOpened = write()
    }
}
