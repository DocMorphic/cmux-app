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
import androidx.compose.ui.unit.sp
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
import org.json.JSONObject
import java.security.MessageDigest

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
        val historyRequests = CopyOnWriteArrayList<ULong>()
        var historyGate: CompletableDeferred<Unit>? = null
        var historyFailures = 0
        var readMarker = 0uL
        var uncertainAnswerOnce = false
        var sendFailure: Exception? = null
        var readFailure: Exception? = null
        var homeUnread = 1L
        var sendGate: CompletableDeferred<Unit>? = null
        var rows = listOf(message("hello", 1uL, "Welcome from your Mac"))
        val home get() = listOf(NativeChiefConversation("chief-main", "Chief", "Ready on your Mac", homeUnread, true, false, instant),
            NativeChiefConversation("build", "Release build", "Review the Android app", 0, false, false, instant))
        override suspend fun status() = NativeChiefStatus(true, null, null, "chief", "Chief", "", "", 1, 2, emptyList(), "chief-main")
        override suspend fun conversations(afterCursor: String?, waitForChange: Boolean) =
            if (waitForChange) homeChanges.receive() else NativeChiefSnapshot(home, "home-cursor")
        override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead {
            if (beforeSeq != null) {
                historyRequests += beforeSeq
                historyGate?.await()
                if (historyFailures > 0) { --historyFailures; throw java.io.IOException("History temporarily unavailable") }
            }
            val page = if (waitForChange) transcriptChanges.receive() else snapshot()
            readFailure?.let { readFailure = null; throw it }
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

    private fun openReferenceScene(name: String) {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("parity/chief-conversation-scenes.json").use { it.readBytes() }
        assertEquals("e2d477d1db72e0ca574f8a1c04db8ad9245fbcd3df6d6015a317e3846df9c2fb",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val scenes = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("scenes")
        val scene = (0 until scenes.length()).map(scenes::getJSONObject).single { it.getString("id") == name }
        val rows = scene.getJSONArray("rows")
        compose.runOnUiThread { api.homeUnread = 0; api.rows = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            message(row.getString("id"), (index + 1).toULong(), row.getString("text"), row.getBoolean("outgoing"))
                .copy(at = instant.plusSeconds((-300 + index * 30).toLong()), threadRoot = row.optString("replyTo").takeIf(String::isNotEmpty))
        }; computer.home.refresh() }
        compose.waitUntil(10_000) { computer.home.state.value.pinned?.unread == 0L }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        val last = rows.getJSONObject(rows.length() - 1).getString("id")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefBubbleBody:$last").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun tapStableAction(tag: String) {
        // IME/window motion is external to Compose's test clock. Use a real touch only
        // after the actual target stops moving, including when reopening a dismissed sheet.
        var previous: androidx.compose.ui.geometry.Rect? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            val bounds = node?.boundsInWindow
            val now = android.os.SystemClock.uptimeMillis()
            if (bounds != previous) { previous = bounds; stableSince = now }
            node != null && bounds != null && bounds.height > 0 && bounds.width > 0 &&
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null && now - stableSince >= 200
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }

    private fun captureChiefReference(name: String) {
        val bounds = compose.onAllNodes(SemanticsMatcher("Chief body bounds") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("ChiefBubbleBody:") == true
        }).fetchSemanticsNodes().associate { node ->
            val rect = node.boundsInRoot
            node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] to
                org.json.JSONArray(listOf(rect.left, rect.top, rect.width, rect.height))
        }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(directory, "chief-reference-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val density = Density(compose.activity.resources.displayMetrics.density, testFontScale)
        val emojiLabels = compose.onAllNodes(SemanticsMatcher("Chief emoji glyph metrics") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("ChiefEmojiGlyph:") == true
        }).fetchSemanticsNodes().map { node ->
            val tag = node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            JSONObject().put("tag", tag).put("text", layout.layoutInput.text.text)
                .put("fontPoints", with(density) { layout.layoutInput.style.fontSize.toDp().value })
                .put("frameInRoot", org.json.JSONArray(node.boundsInRoot.let { listOf(it.left, it.top, it.width, it.height) }))
        }
        File(directory, "chief-reference-$name.json").writeText(JSONObject()
            .put("scene", name).put("fixtureSha256", "e2d477d1db72e0ca574f8a1c04db8ad9245fbcd3df6d6015a317e3846df9c2fb")
            .put("imageSha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            .put("viewportPixels", org.json.JSONArray(listOf(bitmap.width, bitmap.height)))
            .put("density", density.density).put("fontScale", testFontScale)
            .put("effectiveBodyPoints", with(density) { 17.sp.toDp().value })
            .put("messageAnchorDate", instant.toString()).put("timeZone", java.time.ZoneId.systemDefault().id)
            .put("bodyBoundsInComposeRoot", JSONObject(bounds))
            .put("headerBoundsInComposeRoot", JSONObject(listOf("ChiefHeader", "ChiefHeaderPhoto", "ChiefHeaderAvatar",
                "ChiefHeaderName", "ChiefHeaderTitle", "ChiefHeaderChevron", "ChiefBack", "ChiefHeaderStop").mapNotNull { tag ->
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.let { node ->
                    tag to org.json.JSONArray(node.boundsInRoot.let { listOf(it.left, it.top, it.width, it.height) })
                }
            }.toMap()))
            .put("emojiLabels", org.json.JSONArray(emojiLabels))
            .put("scope", "Production composition with typed local owner; authored UIKit scene text/reply/run inputs. Android system bars included; timestamps differ; header/composer and full visual/physical acceptance remain open.")
            .toString(2))
        bitmap.recycle()
    }

    private fun assertFixedEmojiFont(tag: String, expected: Float) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val density = Density(compose.activity.resources.displayMetrics.density, testFontScale)
        assertEquals(expected, with(density) { layouts.single().layoutInput.style.fontSize.toDp().value }, .2f)
    }

    private fun usableMessagesBounds(): androidx.compose.ui.geometry.Rect {
        val viewport = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("ChiefHeader").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("ChiefComposerBar").fetchSemanticsNode().boundsInRoot
        return viewport.copy(top = maxOf(viewport.top, header.bottom), bottom = minOf(viewport.bottom, composer.top))
    }

    private fun swipeMessagesDown() {
        val list = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().boundsInRoot
        val usable = usableMessagesBounds()
        compose.onNodeWithTag("ChiefMessages").performTouchInput {
            // The real scroll viewport now extends under both bars. Begin on a
            // message rather than the header's clickable name/photo or editor.
            swipe(androidx.compose.ui.geometry.Offset(usable.center.x - list.left, usable.top + usable.height * .1f - list.top),
                androidx.compose.ui.geometry.Offset(usable.center.x - list.left, usable.bottom - usable.height * .1f - list.top))
        }
    }

    private fun settledComposerGeometry(): List<androidx.compose.ui.geometry.Rect> {
        val tags = listOf("ChiefComposerField", "ChiefSend", "ChiefHeaderStop")
        var previous = emptyList<androidx.compose.ui.geometry.Rect>()
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            var imeVisible = false
            compose.runOnUiThread { imeVisible = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            val current = tags.mapNotNull { tag -> compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.boundsInRoot }
            val now = android.os.SystemClock.uptimeMillis()
            if (!imeVisible || current != previous) stableSince = now
            previous = current
            imeVisible && current.size == tags.size && current.all { it.width > 0 && it.height > 0 } && now - stableSince >= 200
        }
        return previous
    }

    @Test fun emojiClassificationAgreesWithActualSwiftOnTheAndroidRuntime() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("parity/chief-emoji.json").use { it.readBytes() }
        assertEquals("9a6f61d51209117c39a3323381e5e0da190348db5892f5079e7cffcc46526c11",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val rows = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("cases")
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index); val text = row.getString("text")
            assertEquals("prefilter $index", row.getBoolean("prefilter"), NativeChiefEmojiPolicy.mayBeEmojiOnly(text))
            assertEquals("predicate $index", row.getBoolean("emojiOnly"), NativeChiefEmojiPolicy.isEmojiOnly(text))
            assertEquals("count $index", row.getInt("count"), NativeChiefEmojiPolicy.count(text))
            assertEquals("font $index", row.getInt("fontSize").takeIf { row.getBoolean("emojiOnly") }, NativeChiefEmojiPolicy.fontSize(text))
            assertEquals("composer $index", if (row.isNull("composerPointSize")) null else row.getInt("composerPointSize"), NativeChiefEmojiPolicy.composerFontSize(text))
        }
    }

    @Test fun sourceEmojiSceneUsesABareSingleGlyphAndANormalSpacedPair() {
        openReferenceScene("emoji-dark")
        compose.onNodeWithTag("ChiefEmoji:one").assertIsDisplayed()
        compose.onNodeWithTag("ChiefBubbleBody:one").assertDoesNotExist()
        compose.onNodeWithTag("ChiefBubbleBody:two").assertIsDisplayed()
        compose.onNodeWithTag("ChiefEmoji:two").assertDoesNotExist()
        assertFixedEmojiFont("ChiefEmojiGlyph:one", 72f)
        val density = compose.activity.resources.displayMetrics.density
        val glyph = compose.onNodeWithTag("ChiefEmojiGlyph:one").fetchSemanticsNode().boundsInRoot
        val balloon = compose.onNodeWithTag("ChiefEmoji:one").fetchSemanticsNode().boundsInRoot
        val following = compose.onNodeWithTag("ChiefBubbleBody:two").fetchSemanticsNode().boundsInRoot
        assertEquals(26.84f * density, balloon.height - glyph.height, 2f)
        assertEquals(10f * density, glyph.top - balloon.top, 1f)
        assertEquals(10f * density, following.top - balloon.bottom, 2f)
        captureChiefReference("emoji-dark")
    }

    @Test fun adjacentAndJoinedEmojiUseFixedSizesAtAccessibilityScale() {
        compose.runOnUiThread {
            testFontScale = 2f
            api.rows = listOf(message("joined", 1uL, "👨‍👩‍👧‍👦"), message("pair", 2uL, "🙂🎉", true),
                message("four", 3uL, "🙂🙂🙂🙂"))
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefBubbleBody:four").fetchSemanticsNodes().isNotEmpty() }
        assertFixedEmojiFont("ChiefEmojiGlyph:joined", 72f)
        assertFixedEmojiFont("ChiefEmojiGlyph:pair", 48f)
        compose.onNodeWithTag("ChiefEmoji:four").assertDoesNotExist()
        val density = compose.activity.resources.displayMetrics.density
        val glyph = compose.onNodeWithTag("ChiefEmojiGlyph:joined").fetchSemanticsNode().boundsInRoot
        val balloon = compose.onNodeWithTag("ChiefEmoji:joined").fetchSemanticsNode().boundsInRoot
        assertEquals((26.84f * 1.2f) * density, balloon.height - glyph.height, 2f)
        saveScreenshot("chief-emoji-accessibility")
    }

    @Test fun composerRestylesOnlyUntrimmedEmojiAndRetainsTheBarePendingAndFailedRow() {
        openReferenceScene("emoji-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("👍🏽")
        compose.waitForIdle()
        assertFixedEmojiFont("ChiefComposer", 72f)
        val bigHeight = compose.onNodeWithTag("ChiefComposerField").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("ChiefComposer").performTextReplacement(" 👍🏽 ")
        compose.waitForIdle()
        assertFixedEmojiFont("ChiefComposer", 17f)
        val smallHeight = compose.onNodeWithTag("ChiefComposerField").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Emoji field height $bigHeight; normal field height $smallHeight", smallHeight < bigHeight)
        compose.onNodeWithTag("ChiefComposer").performTextReplacement("🙂🎉")
        compose.waitForIdle()
        assertFixedEmojiFont("ChiefComposer", 48f)
        compose.runOnUiThread { api.sendGate = CompletableDeferred(); api.sendFailure = MobileRpcOutcomeUnknown() }
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        val id = api.sends.single().second!!
        compose.onNodeWithTag("ChiefEmoji:$id").assertIsDisplayed()
        compose.onNodeWithTag("ChiefPendingBody:$id").assertDoesNotExist()
        assertFixedEmojiFont("ChiefEmojiGlyph:$id", 48f)
        compose.runOnUiThread { api.sendGate!!.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()!!.state.value.outgoing.singleOrNull()?.delivery?.isFailed == true }
        // Owner state precedes the layout and pinned-tail follow after footer insertion.
        compose.waitUntil(10_000) { compose.onNodeWithTag("ChiefFailed:$id").isDisplayed() }
        compose.onNodeWithTag("ChiefEmoji:$id").assertIsDisplayed()
        compose.onNodeWithTag("ChiefFailed:$id").assertIsDisplayed()
        saveScreenshot("chief-emoji-failed")
    }

    @Test fun compactHeaderUsesIntrinsicSymbolCanvasAndSourceClusterSpacing() = compactHeaderReference(false)

    @Test fun compactLargeHeaderKeepsTheSameSymbolCanvasAndCappedName() = compactHeaderReference(true)

    private fun compactHeaderReference(large: Boolean) {
        val configuration = compose.activity.resources.configuration
        org.junit.Assume.assumeTrue("Run on the matched landscape emulator surface",
            configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && configuration.screenHeightDp < 480)
        if (large) useReferenceAccessibilityBodySize()
        openReferenceScene("runs-dark")
        val density = compose.activity.resources.displayMetrics.density
        fun rect(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val header = rect("ChiefHeader")
        val avatar = rect("ChiefHeaderAvatar")
        val title = rect("ChiefHeaderTitle")
        val name = rect("ChiefHeaderName")
        val symbol = rect("ChiefHeaderChevron")
        assertEquals(44 * density, avatar.width, 1f)
        assertEquals(44 * density, avatar.height, 1f)
        assertEquals(25f / 3 * density, symbol.width, 1f)
        assertEquals(44f / 3 * density, symbol.height, 1f)
        assertEquals(12 * density, title.left - avatar.right, 1.5f)
        assertEquals(4 * density, symbol.left - title.right, 1.5f)
        assertEquals(8 * density, name.right - symbol.right, 1.5f)
        assertEquals(header.center.x, (avatar.left + name.right) / 2, 1f)
        assertEquals(avatar.center.y, symbol.center.y, 1f)
        compose.onNodeWithTag("ChiefHeaderName").assertIsDisplayed().performClick()
        compose.onNodeWithTag("ChiefDetails").assertIsDisplayed()
        compose.onNodeWithTag("ChiefDetailsBack").performClick()
        compose.onNodeWithTag("ChiefHeaderName").assertIsDisplayed()
        captureChiefReference(if (large) "compact-large-dark" else "compact-dark")
    }

    @Test fun transcriptUsesTheWholeViewportUnderBothBarsAndScrollsBehindTheWash() {
        compose.runOnUiThread { api.rows = (1..100).map { message("m$it", it.toULong(), "Scrollable transcript message $it") } }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:m100").fetchSemanticsNodes().isNotEmpty() }
        val frame = compose.onNodeWithTag("ChiefTranscript").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().boundsInRoot
        assertEquals(frame, viewport)
        val header = compose.onNodeWithTag("ChiefHeader").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("ChiefComposerBar").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.top, header.top, 1f)
        assertEquals(viewport.bottom, composer.bottom, 1f)
        compose.onNodeWithTag("ChiefMessages").performScrollToIndex(30)
        compose.waitForIdle()
        val rows = compose.onAllNodes(SemanticsMatcher("Transcript bodies behind header") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("ChiefBubbleBody:") == true
        }).fetchSemanticsNodes()
        assertTrue(rows.any { it.boundsInRoot.top < header.bottom && it.boundsInRoot.bottom > viewport.top })
        compose.onNodeWithTag("ChiefTopEdgeFade").assertExists()
        saveScreenshot("chief-overlay-scrolled")
    }

    @Test fun restingNewestBodyUsesTheSourceGapAboveTheOverlayComposer() {
        compose.runOnUiThread { api.rows = (1..50).map { message("m$it", it.toULong(), "Message $it") } }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        val density = compose.activity.resources.displayMetrics.density
        compose.waitUntil(10_000) {
            val body = compose.onAllNodesWithTag("ChiefBubbleBody:m50").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val field = compose.onAllNodesWithTag("ChiefComposerField").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            body != null && field != null && kotlin.math.abs(field.top - body.bottom - 16.71f * density) < 2f
        }
        compose.onNodeWithTag("ChiefBubbleBody:m50").assertIsDisplayed()
    }

    @Test fun sourceRunSceneUsesBodyGapsAndReservesOnlyFinalTails() {
        openReferenceScene("runs-dark")
        fun body(id: String) = compose.onNodeWithTag("ChiefBubbleBody:$id").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val one = body("one"); val two = body("two"); val three = body("three")
        assertEquals(4 * density, two.top - one.bottom, 2f)
        assertEquals(10 * density, three.top - two.bottom, 2f)
        val untailed = compose.onNodeWithTag("ChiefMessage:one").fetchSemanticsNode().boundsInRoot
        val tailed = compose.onNodeWithTag("ChiefMessage:two").fetchSemanticsNode().boundsInRoot
        assertEquals(one.height, untailed.height, 1f)
        assertTrue(tailed.height - two.height > 6 * density)
        val text = compose.onNodeWithText("Welcome from your Mac.").fetchSemanticsNode().boundsInRoot
        assertEquals(20 * density, one.height - text.height, 2f)
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Messages in one run share an edge.")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layout) }
        val measured = layout.single()
        val widest = (0 until measured.lineCount).maxOf { kotlin.math.abs(measured.getLineRight(it) - measured.getLineLeft(it)) }
        assertEquals(kotlin.math.ceil(widest + 28 * density), two.width, 1f)
        captureChiefReference("runs-dark")
    }

    private fun waitForReplyThread() {
        try { compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Exception) {
            saveScreenshot("chief-reply-opening-diagnostic")
            throw AssertionError("Thread did not render: root=${computer.transcript()?.state?.value?.reply?.rootID}, retired=${computer.transcript()?.state?.value?.retired}, details=${compose.onAllNodesWithTag("ChiefDetails").fetchSemanticsNodes().size}; " + compose.onRoot().printToString(), error)
        }
    }

    @Test fun rightSwipeOnAcknowledgedReplyTargetsThatMessageWithinItsLoadedRoot() {
        openReferenceScene("quote-dark")
        val body = compose.onNodeWithTag("ChiefMessage:reply").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("ChiefReplySwipe:reply").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        val start = body.center - row.topLeft
        compose.onNodeWithTag("ChiefReplySwipe:reply").performTouchInput {
            swipe(start, start + androidx.compose.ui.geometry.Offset(80 * density, 0f), 350)
        }
        waitForReplyThread()
        val reply = computer.transcript()!!.state.value.reply!!
        assertEquals("parent", reply.rootID); assertEquals("reply", reply.targetID)
        assertEquals(NativeChiefReplySwipePolicy.bubbleOffset(80.0).toFloat(), reply.releaseOffset, .25f)
        compose.onNodeWithTag("ChiefThreadMessage:next").assertDoesNotExist()
        assertTrue(api.sends.isEmpty())
    }

    @Test fun replySwipeMovesOnlyTheBalloonAndReversingBelowThresholdUncommits() {
        openReferenceScene("quote-dark")
        val density = compose.activity.resources.displayMetrics.density
        val body = compose.onNodeWithTag("ChiefMessage:reply").fetchSemanticsNode().boundsInRoot
        val quote = compose.onNodeWithTag("ChiefQuote:reply").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("ChiefReplySwipe:reply").fetchSemanticsNode().boundsInRoot
        val start = body.center - row.topLeft
        compose.onNodeWithTag("ChiefReplySwipe:reply").performTouchInput {
            down(start)
            for (i in 1..10) moveTo(start + androidx.compose.ui.geometry.Offset(8f * i * density, 0f), 16)
        }
        val moved = compose.onNodeWithTag("ChiefMessage:reply").fetchSemanticsNode().boundsInRoot
        assertEquals((NativeChiefReplySwipePolicy.bubbleOffset(80.0) * density).toFloat(), moved.left - body.left, 2f)
        assertEquals(quote, compose.onNodeWithTag("ChiefQuote:reply").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
        saveScreenshot("chief-reply-swipe-held")
        compose.onNodeWithTag("ChiefReplySwipe:reply").performTouchInput {
            for (i in 1..10) moveTo(start + androidx.compose.ui.geometry.Offset((80f - 6f * i) * density, 0f), 16)
            up()
        }
        compose.waitUntil(5_000) {
            kotlin.math.abs(compose.onNodeWithTag("ChiefMessage:reply").fetchSemanticsNode().boundsInRoot.left - body.left) < 1
        }
        assertNull(computer.transcript()!!.state.value.reply)
        assertTrue(api.sends.isEmpty())
    }

    @Test fun leftDiagonalAndOutsideIncomingSwipeDoNotEnterReplyMode() {
        openReferenceScene("quote-dark")
        val density = compose.activity.resources.displayMetrics.density
        val row = compose.onNodeWithTag("ChiefReplySwipe:parent").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("ChiefMessage:parent").fetchSemanticsNode().boundsInRoot
        val start = body.center - row.topLeft
        compose.onNodeWithTag("ChiefReplySwipe:parent").performTouchInput {
            swipe(start, start + androidx.compose.ui.geometry.Offset(-70 * density, 0f), 350)
        }
        assertNull(computer.transcript()!!.state.value.reply)
        compose.onNodeWithTag("ChiefReplySwipe:parent").performTouchInput {
            swipe(start, start + androidx.compose.ui.geometry.Offset(50 * density, 50 * density), 350)
        }
        assertNull(computer.transcript()!!.state.value.reply)
        compose.onNodeWithTag("ChiefReplySwipe:parent").performTouchInput {
            val edge = androidx.compose.ui.geometry.Offset(2 * density, start.y)
            down(edge); moveTo(edge + androidx.compose.ui.geometry.Offset(10 * density, 0f), 20)
            moveTo(edge + androidx.compose.ui.geometry.Offset(80 * density, 0f), 120); up()
        }
        assertNull(computer.transcript()!!.state.value.reply)
        assertTrue(api.sends.isEmpty())
    }

    @Test fun cancelledCommittedSwipeRestoresTheBalloonWithoutOpeningAThread() {
        openReferenceScene("quote-dark")
        val density = compose.activity.resources.displayMetrics.density
        val row = compose.onNodeWithTag("ChiefReplySwipe:parent").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("ChiefMessage:parent").fetchSemanticsNode().boundsInRoot
        val start = body.center - row.topLeft
        compose.onNodeWithTag("ChiefReplySwipe:parent").performTouchInput {
            down(start)
            for (i in 1..10) moveTo(start + androidx.compose.ui.geometry.Offset(8f * i * density, 0f), 16)
            cancel()
        }
        compose.waitUntil(5_000) {
            kotlin.math.abs(compose.onNodeWithTag("ChiefMessage:parent").fetchSemanticsNode().boundsInRoot.left - body.left) < 1
        }
        assertNull(computer.transcript()!!.state.value.reply)
        assertTrue(api.sends.isEmpty())
    }

    @Test fun unacknowledgedOutgoingRowsCannotStartReplySwipes() {
        openReferenceScene("quote-dark")
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread { api.sendGate = gate }
        compose.onNodeWithTag("ChiefComposer").performTextInput("Still waiting for acknowledgment")
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        val id = checkNotNull(api.sends.single().second)
        val density = compose.activity.resources.displayMetrics.density
        val row = compose.onNodeWithTag("ChiefReplySwipe:$id").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("ChiefPending:$id").fetchSemanticsNode().boundsInRoot
        val start = body.center - row.topLeft
        compose.onNodeWithTag("ChiefReplySwipe:$id").performTouchInput {
            swipe(start, start + androidx.compose.ui.geometry.Offset(80 * density, 0f), 350)
        }
        assertNull(computer.transcript()!!.state.value.reply)
        assertEquals(NativeChiefDelivery.SENDING, computer.transcript()!!.state.value.outgoing.single().delivery)
        assertEquals(1, api.sends.size)
        compose.runOnUiThread { gate.complete(Unit) }
    }

    @Test fun quoteOpensOnlyTheLoadedThreadAndKeepsTheDraftAndEditorFocusOnClose() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep this draft through the thread")
        compose.onNodeWithTag("ChiefQuote:reply").performScrollTo().performClick()
        waitForReplyThread()
        compose.onNodeWithTag("ChiefReplyThread").assertExists()
        compose.onNodeWithTag("ChiefThreadMessage:parent").assertExists()
        compose.onNodeWithTag("ChiefThreadMessage:reply").assertExists()
        compose.onNodeWithTag("ChiefThreadMessage:next").assertDoesNotExist()
        compose.onNodeWithTag("ChiefBack").assertDoesNotExist()
        compose.onNodeWithTag("ChiefHeaderStop").assertDoesNotExist()
        compose.onNodeWithTag("ChiefReplyClose").assertIsDisplayed()
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keep this draft through the thread")
        val density = compose.activity.resources.displayMetrics.density
        compose.waitUntil(10_000) {
            val root = compose.onNodeWithTag("ChiefThreadRow:parent").fetchSemanticsNode().boundsInRoot
            val child = compose.onNodeWithTag("ChiefThreadRow:reply").fetchSemanticsNode().boundsInRoot
            val bubble = compose.onNodeWithTag("ChiefThreadMessage:reply").fetchSemanticsNode().boundsInRoot
            val field = compose.onNodeWithTag("ChiefComposerField").fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(child.top - root.bottom - 10 * density) < 2 && kotlin.math.abs(field.top - bubble.bottom - 10 * density) < 2
        }
        compose.runOnUiThread {
            api.rows = api.rows + message("new-reply", 4uL, "Arrived after the thread opened").copy(threadRoot = "parent")
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.any { it.id == "new-reply" } == true }
        compose.onNodeWithTag("ChiefThreadMessage:new-reply").assertDoesNotExist()
        saveScreenshot("chief-reply-loaded-snapshot")
        compose.onNodeWithTag("ChiefReplyClose").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keep this draft through the thread")
        compose.onNodeWithTag("ChiefHeaderStop").assertExists()
        assertTrue(api.sends.isEmpty())
    }

    @Test fun replySendClosesTheThreadAndKeepsTheOptimisticQuoteUntilOrdinaryChiefAcknowledges() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefQuote:reply").performClick()
        waitForReplyThread()
        compose.onNodeWithText("Reply").assertExists()
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread { api.sendGate = gate }
        compose.onNodeWithTag("ChiefComposer").performTextInput("A reply through the existing send boundary")
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        val id = checkNotNull(api.sends.single().second)
        assertEquals("A reply through the existing send boundary", api.sends.single().first)
        assertNull(computer.transcript()!!.state.value.reply)
        assertEquals("parent", computer.transcript()!!.state.value.outgoing.single().replyToID)
        compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
        compose.onNodeWithTag("ChiefQuote:$id").assertExists()
        saveScreenshot("chief-reply-optimistic-quote")
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.isEmpty() == true }
        compose.onNodeWithTag("ChiefQuote:echo").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun replyBackdropAndOwnerRetirementCloseTheCopiesWithoutSendingOrKeepingTheirText() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefQuote:reply").performClick()
        waitForReplyThread()
        compose.onNodeWithTag("ChiefReplyThread").performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, height / 2f)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
        compose.onNodeWithTag("ChiefQuote:reply").performScrollTo().performClick()
        waitForReplyThread()
        compose.onNodeWithTag("ChiefReplyThread").assertExists()
        compose.runOnUiThread { computer.transcript()!!.close() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("ChiefComposer").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        assertTrue(api.sends.isEmpty())
    }

    @Test fun replyAccessibilityActionRetargetsTheCopyAndEscapeKeepsTheDraft() {
        openReferenceScene("quote-dark")
        val action = compose.onNodeWithTag("ChiefReplyActions:reply").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Reply" }
        compose.runOnUiThread { assertTrue(action.action()) }
        waitForReplyThread()
        assertEquals("reply", computer.transcript()!!.state.value.reply!!.targetID)
        compose.onNodeWithTag("ChiefMessage:next").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep through Escape")
        val copyAction = compose.onNodeWithTag("ChiefThreadMessage:parent").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Reply" }
        compose.runOnUiThread { assertTrue(copyAction.action()) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.reply?.targetID == "parent" }
        compose.onNodeWithTag("ChiefComposer").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keep through Escape")
        assertTrue(api.sends.isEmpty())
    }

    @Test fun structuredThreadCopiesKeepTheirPromptAsReadOnlyContent() {
        compose.runOnUiThread {
            api.rows = listOf(message("approval", 1uL, "", parts = listOf(NativeChiefPart("approval", null,
                NativeChiefApproval("request", "Review before running", listOf("allow", "deny", "always:allow"), null), null))))
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyActions:approval").fetchSemanticsNodes().isNotEmpty() }
        val action = compose.onNodeWithTag("ChiefReplyActions:approval").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Reply" }
        compose.runOnUiThread { assertTrue(action.action()) }
        waitForReplyThread()
        compose.onNodeWithText("Review before running").assertExists()
        compose.onNodeWithText("allow").assertExists()
        compose.onNodeWithText("deny").assertExists()
        compose.onNodeWithTag("ChiefApprove:approval:allow").assertDoesNotExist()
        assertTrue(computer.transcript()!!.state.value.deciding.isEmpty())
        assertTrue(api.sends.isEmpty())
    }

    @Test fun longReplyThreadStartsAtItsTailAndSupportsAccessibilityScrolling() {
        compose.runOnUiThread {
            api.rows = (1..24).map { i -> message("m$i", i.toULong(), "Loaded thread message $i").copy(threadRoot = if (i > 1) "m1" else null) }
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefQuote:m24").fetchSemanticsNodes().isNotEmpty() }
        tapStableAction("ChiefQuote:m24")
        waitForReplyThread()
        compose.waitUntil(10_000) {
            var visible = false
            compose.runOnUiThread { visible = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            visible
        }
        fun axis() = compose.onNodeWithTag("ChiefReplyTranscript").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        compose.waitUntil(10_000) { axis().maxValue() > 0 && kotlin.math.abs(axis().value() - axis().maxValue()) < 1 }
        val before = axis().value()
        assertEquals("Reply transcript", compose.onNodeWithTag("ChiefReplyTranscript").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].label)
        compose.onNodeWithTag("ChiefReplyTranscript").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            assertTrue(it(0f, -100 * compose.activity.resources.displayMetrics.density))
        }
        try { compose.waitUntil(10_000) { axis().value() < before - 1 } }
        catch (error: AssertionError) { throw AssertionError("Thread scroll reset: before=$before, value=${axis().value()}, maximum=${axis().maxValue()}", error) }
        val nextAction = compose.onNodeWithTag("ChiefReplyTranscript").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.ScrollByOffset]
        val consumed = CompletableDeferred<androidx.compose.ui.geometry.Offset>()
        val prior = axis().value()
        compose.runOnUiThread { owner.launch {
            consumed.complete(nextAction(androidx.compose.ui.geometry.Offset(0f, -50 * compose.activity.resources.displayMetrics.density)))
        } }
        compose.waitUntil(10_000) { consumed.isCompleted }
        assertTrue(runBlocking { consumed.await() }.y < 0)
        compose.waitUntil(10_000) { axis().value() < prior - 1 }
        assertEquals(24, computer.transcript()!!.state.value.reply!!.rows.size)
        assertTrue(api.sends.isEmpty())
        saveScreenshot("chief-reply-long-thread")
    }

    @Test fun sourceQuoteSceneUsesTheOriginalSenderSideAndSeparateOutline() {
        openReferenceScene("quote-dark")
        fun body(id: String) = compose.onNodeWithTag("ChiefBubbleBody:$id").fetchSemanticsNode().boundsInRoot
        val parent = body("parent"); val reply = body("reply")
        val quotedParent = compose.onNodeWithTag("ChiefQuote:reply").fetchSemanticsNode().boundsInRoot
        val quotedReply = compose.onNodeWithTag("ChiefQuote:next").fetchSemanticsNode().boundsInRoot
        assertEquals(parent.left, quotedParent.left, 1f)
        assertEquals(reply.right, quotedReply.right, 1f)
        assertTrue(quotedParent.bottom < reply.top)
        assertTrue(quotedReply.bottom < body("next").top)
        captureChiefReference("quote-dark")
    }

    @Test fun sourceLongSceneUsesTheReservedTranscriptWidthAndPreservesUnicode() {
        openReferenceScene("long-dark")
        val density = compose.activity.resources.displayMetrics.density
        val viewport = usableMessagesBounds()
        val first = compose.onNodeWithTag("ChiefBubbleBody:one").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("ChiefBubbleBody:two").fetchSemanticsNode().boundsInRoot
        val maximum = viewport.width - (32 + 268f / 3) * density
        assertTrue(first.width <= maximum + 2)
        assertTrue(second.width <= maximum + 2)
        compose.onNodeWithText("Keep this line break.\n日本語のメッセージも同じ幅で表示します。").assertExists()
        captureChiefReference("long-dark")
    }

    private fun useReferenceAccessibilityBodySize() {
        compose.runOnUiThread {
            // The real Android conversion is nonlinear; a nominal 28/17 ratio renders below 28.
            var low = 1f; var high = 3f
            repeat(24) {
                val middle = (low + high) / 2
                if (with(Density(1f, middle)) { 17.sp.toDp().value } < 28.01f) low = middle else high = middle
            }
            val points = with(Density(1f, high)) { 17.sp.toDp().value }
            assertTrue("Actual body size was $points", points in 28f..28.2f)
            testFontScale = high
        }
    }

    @Test fun sourceLargeSceneUsesAccessibilityPaddingWithoutClippingTheComposer() {
        useReferenceAccessibilityBodySize()
        openReferenceScene("large-dark")
        val density = compose.activity.resources.displayMetrics.density
        val body = compose.onNodeWithTag("ChiefBubbleBody:one").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText("Large text.").fetchSemanticsNode().boundsInRoot
        assertEquals(24 * density, body.height - text.height, 2f)
        assertEquals((100f / 3) * density, body.width - text.width, 2f)
        compose.onNodeWithTag("ChiefComposer").assertIsDisplayed()
        captureChiefReference("large-dark")
    }

    @Test fun minimumWidthBubbleCentersItsShortText() {
        compose.runOnUiThread { api.rows = listOf(message("short", 1uL, "I")) }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefBubbleBody:short").fetchSemanticsNodes().isNotEmpty() }
        val body = compose.onNodeWithTag("ChiefBubbleBody:short").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText("I").fetchSemanticsNode().boundsInRoot
        assertEquals(48 * compose.activity.resources.displayMetrics.density, body.width, 1f)
        assertEquals(body.center.x, text.center.x, 1f)
    }

    @Test fun portraitHeaderAndBothDetailsEntrancesPreserveTheDraftAndSourceDefaults() {
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.hasLoadedInfo == true }
        val density=compose.activity.resources.displayMetrics.density
        val avatar=compose.onNodeWithTag("ChiefHeaderAvatar",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val stop=compose.onNodeWithTag("ChiefHeaderStop").fetchSemanticsNode().boundsInRoot
        assertEquals(60*density,avatar.width,1f);assertEquals(44*density,stop.width,1f)
        assertEquals(avatar.top,stop.top,1f)
        compose.onNodeWithTag("ChiefBack").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"1 unread"))
        compose.onNodeWithTag("ChiefServiceIntro").assertExists()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Retain the draft through details")
        compose.onNodeWithTag("ChiefHeaderName").performClick()
        compose.onNodeWithTag("ChiefDetailsTitle").assertTextEquals("Chief")
        for(tag in listOf("Call","Video","Mail"))compose.onNodeWithTag("ChiefDetails$tag").assertIsNotEnabled()
        compose.onNodeWithTag("ChiefHideAlerts").assertIsOff().performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.listState?.muted == false }
        compose.onNodeWithTag("ChiefHideAlerts").assertIsOff()
        compose.onNodeWithTag("ChiefReadReceipts").assertIsOn().performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.listState?.readReceipts == true }
        compose.onNodeWithTag("ChiefReadReceipts").assertIsOn()
        saveScreenshot("chief-details-normal")
        compose.onNodeWithTag("ChiefDetailsBack").performClick()
        compose.onNodeWithTag("ChiefDetails").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").assertTextContains("Retain the draft through details")
        compose.onNodeWithTag("ChiefComposer").assertIsNotFocused()
        compose.onNodeWithTag("ChiefHeaderPhoto").performClick()
        compose.onNodeWithTag("ChiefDetails").assertExists()
        compose.runOnUiThread { computer.transcript()!!.close() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefDetails").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("ChiefDetails").assertDoesNotExist()
        assertTrue(api.sends.isEmpty())
    }

    @Test fun enlargedDetailsKeepBackAndBothSettingsReachable() {
        compose.runOnUiThread { testFontScale=2f }
        compose.onNodeWithTag("ChiefPinnedConversation").performScrollTo().performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.hasLoadedInfo == true }
        compose.onNodeWithTag("ChiefHeaderName").assertIsDisplayed().performClick()
        compose.onNodeWithTag("ChiefDetailsBack").assertIsDisplayed()
        val rendered=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("ChiefDetailsTitle").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(rendered)}
        val layout=rendered.single()
        assertEquals("Details must retain the actual host font scale",2f,layout.layoutInput.density.fontScale,.01f)
        val points=with(layout.layoutInput.density){layout.layoutInput.style.fontSize.toDp().value}
        assertTrue("Details must actually render accessibility title type",points>=38)
        compose.onNodeWithTag("ChiefHideAlerts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ChiefReadReceipts").performScrollTo().assertIsDisplayed()
        saveScreenshot("chief-details-large")
        compose.onNodeWithTag("ChiefDetailsBack").performClick()
        compose.onNodeWithTag("ChiefBack").assertIsDisplayed()
    }

    @Test fun serviceIntroductionOnlyAppearsWhenTheOlderHistoryIsExhausted() {
        compose.runOnUiThread { api.rows=(1..150).map{message("m$it",it.toULong(),"History $it")} }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.hasMore == true }
        compose.onNodeWithTag("ChiefServiceIntro").assertDoesNotExist()
        val loaded=CompletableDeferred<Unit>()
        compose.runOnUiThread { owner.launch{repeat(2){computer.transcript()!!.loadOlder()};loaded.complete(Unit)} }
        compose.waitUntil(10_000) { loaded.isCompleted }
        assertFalse(computer.transcript()!!.state.value.hasMore)
        swipeMessagesDown()
        compose.onNodeWithTag("ChiefMessages").performScrollToIndex(0)
        compose.waitUntil(10_000) {
            val intro=compose.onAllNodesWithTag("ChiefServiceIntro").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val viewport=usableMessagesBounds()
            intro != null && intro.height > 0 && intro.top >= viewport.top && intro.bottom <= viewport.bottom
        }
        compose.onNodeWithTag("ChiefServiceIntro").assertIsDisplayed()
    }

    @Test fun composerHidesBlankSendAndKeepsSendingAvailableWhenAReadFails() {
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Welcome from your Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefSend").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").performTextInput("\u200b \n\u0085")
        compose.onNodeWithTag("ChiefSend").assertDoesNotExist()
        compose.runOnUiThread { api.readFailure = java.io.IOException("Read temporarily unavailable"); owner.launch { api.transcriptChanges.send(api.snapshot()) } }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.connected == false }
        compose.onNodeWithTag("ChiefComposer").performTextClearance()
        compose.onNodeWithTag("ChiefComposer").performTextInput("\u0085 **literal** \u200b")
        compose.onNodeWithTag("ChiefSend").assertIsDisplayed().assertIsEnabled()
        val (field, send, _) = settledComposerGeometry()
        assertTrue("Send $send must stay inside field $field", field.contains(send.topLeft) && field.contains(send.bottomRight))
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        assertEquals("**literal**", api.sends.single().first)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:echo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("**literal**").assertExists()
        compose.onNodeWithTag("ChiefSend").assertDoesNotExist()
    }

    @Test fun composerGrowsBeyondFiveLinesThenScrollsBelowTheHeaderWithSendInside() {
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Welcome from your Mac").fetchSemanticsNodes().isNotEmpty() }
        val initial = compose.onNodeWithTag("ChiefComposerField").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("ChiefComposer").performTextInput((1..30).joinToString("\n") { "Draft line $it" })
        compose.waitForIdle()
        val (field, send, header) = settledComposerGeometry()
        assertTrue(field.height > 160 * compose.activity.resources.displayMetrics.density)
        assertTrue(field.height > initial.height * 5)
        assertTrue(field.top > header.bottom)
        assertTrue("Field $field must contain Send $send", field.contains(send.topLeft) && field.contains(send.bottomRight))
        compose.onNodeWithTag("ChiefComposer").assertTextContains("Draft line 30", substring = true)
        val editor = compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode()
        val selection = editor.config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        val actualText = editor.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals(actualText.length, selection.end)
        compose.waitUntil(10_000) {
            val axis = compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
            axis != null && axis.maxValue() > 0 && axis.maxValue() - axis.value() <= 1
        }
        saveScreenshot("chief-composer-expanded")
        compose.onNodeWithTag("ChiefComposer").performTextClearance()
        compose.onNodeWithTag("ChiefSend").assertDoesNotExist()
        compose.waitUntil(10_000) { compose.onNodeWithTag("ChiefComposerField").fetchSemanticsNode().boundsInRoot.height <= initial.height + 1 }
    }

    @Test fun typingAndLeavingInTheSameEventRetainsTheDraftWithoutSending() {
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Welcome from your Mac").fetchSemanticsNodes().isNotEmpty() }
        val insert = compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsActions.InsertTextAtCursor].action!!
        compose.runOnUiThread {
            assertTrue(insert(androidx.compose.ui.text.AnnotatedString("Keep the final edit")))
            computer.back()
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == "Keep the final edit"
        }
        assertTrue(api.sends.isEmpty())
    }

    @Test fun retiringTheTranscriptClearsTheEditorAndRejectsFurtherEdits() {
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Welcome from your Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefComposer").performTextInput("Private unsent draft")
        compose.runOnUiThread { computer.transcript()!!.close() }
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text == ""
        }
        compose.onNodeWithTag("ChiefSend").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").assertIsNotEnabled()
        compose.onAllNodesWithText("Private unsent draft").assertCountEquals(0)
        assertTrue(api.sends.isEmpty())
    }

    @Test fun homeSearchSendEchoAndConversationDraftRestorationUseTheProductionComposition() {
        compose.onNodeWithTag("ChiefSearch").performTextInput("Release")
        compose.onNodeWithTag("ChiefPinnedConversation").assertDoesNotExist()
        compose.onNodeWithTag("ChiefConversation:build").assertIsDisplayed()
        compose.onNodeWithTag("ChiefSearch").performTextClearance()
        compose.onNodeWithTag("ChiefSearch").performTextInput("Chief")
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Welcome from your Mac").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefComposer").performTextInput("A phone message")
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithTag("ChiefMessage:echo").assertExists()
        compose.onAllNodesWithText("A phone message", substring = false).assertCountEquals(1)
        assertEquals("A phone message", api.sends.single().first); assertFalse(api.sends.single().second.isNullOrBlank())
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep my draft")
        compose.onNodeWithTag("ChiefBack").performClick()
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
        tapSendWhenReady()
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

    @Test fun chiefBodiesQuotesAndPendingEchoesPreserveLiteralTextAndDetectedLinks() {
        val literal = "**Literal stars** `code` [Label](https://example.com)"
        val draft = "__Keep this__ <tag> https://example.org/a"
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread {
            api.rows = listOf(message("literal", 1uL, literal),
                message("reply", 2uL, "A literal reply").copy(threadRoot = "literal"))
            api.sendGate = gate
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(literal).fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText(literal).assertCountEquals(2)
        compose.onNodeWithTag("ChiefComposer").performTextInput(draft)
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithText(draft).assertExists()
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:echo").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(draft).assertCountEquals(1)
        assertEquals(draft, api.sends.single().first)

        // Exercise Android's real detector and the exact UTF-16 ranges carried by production Text.
        val source = "🙂 **bold** [Label](https://example.com/path) mail a@example.org or +1 650-253-0000"
        val opened = mutableListOf<String>()
        val annotated = nativeChiefDetectedText(source, opened::add)
        assertEquals(source, annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        val web = links.single { (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url == "https://example.com/path" }
        assertEquals("https://example.com/path", source.substring(web.start, web.end))
        assertTrue(links.any { (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url == "mailto:a@example.org" })
        assertTrue(links.any { (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url?.startsWith("tel:") == true })
        web.item.linkInteractionListener?.onClick(web.item)
        assertEquals(listOf("https://example.com/path"), opened)
    }

    @Test fun enlargedTextKeepsChiefHeaderAndComposerUsable() {
        compose.runOnUiThread { testFontScale = 2f }
        compose.onNodeWithTag("ChiefPinnedConversation").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Stop Chief").assertIsDisplayed()
        compose.onNodeWithTag("ChiefBack").assertIsDisplayed()
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
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        val id = api.sends.single().second!!
        compose.waitUntil(10_000) {
            val retry = compose.onAllNodesWithTag("ChiefFailed:$id").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val viewport = usableMessagesBounds()
            retry != null && retry.height > 0 && retry.top >= viewport.top && retry.bottom <= viewport.bottom
        }
        compose.onNodeWithTag("ChiefFailed:$id").assertIsDisplayed().performClick()
        compose.onNodeWithTag("ChiefFailedSendDialog").assertExists()
        compose.onNodeWithTag("ChiefRetrySend").assertIsEnabled()
        compose.runOnUiThread { api.sendFailure = null }
        tapStableAction("ChiefRetrySend")
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
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        val id = api.sends.single().second!!
        compose.waitUntil(10_000) {
            val retry = compose.onAllNodesWithTag("ChiefFailed:$id").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val viewport = usableMessagesBounds()
            retry != null && retry.height > 0 && retry.top >= viewport.top && retry.bottom <= viewport.bottom
        }
        assertEquals("", computer.transcript()!!.state.value.draft)
        assertEquals("", compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("ChiefFailed:$id").assertIsDisplayed().performClick()
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
        compose.waitUntil(10_000) { compose.onNodeWithTag("ChiefFailed:$id").isDisplayed() }
        compose.onNodeWithTag("ChiefFailed:$id").assertIsDisplayed().performClick()
        tapStableAction("ChiefDiscardSend")
        compose.onNodeWithTag("ChiefFailedSendDialog").assertDoesNotExist()
        compose.waitUntil(10_000) { computer.transcript()!!.state.value.outgoing.none { it.clientMessageId == id } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Delete this failed message").fetchSemanticsNodes().isEmpty()
        }
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
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithTag("ChiefBack").performClick()
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithText("First retained send").assertExists()
        compose.onNodeWithText("Delivery unconfirmed").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Second retained send")
        tapSendWhenReady()
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
        swipeMessagesDown()
        compose.waitForIdle()
        val viewport = usableMessagesBounds()
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
        tapSendWhenReady()
        compose.waitUntil(10_000) { api.sends.size == 1 }
        compose.onNodeWithText("Send from history").assertIsDisplayed()
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.isEmpty() == true }
        compose.waitUntil(10_000) { compose.onNodeWithTag("ChiefMessage:echo").isDisplayed() }
        compose.onNodeWithTag("ChiefMessage:echo").assertIsDisplayed()
    }

    @Test fun browsingKeepsTheLoadedHistoryUntilANewSendReturnsToNewestAndTrimsIt() {
        compose.runOnUiThread {
            api.rows = (1..2000).map { message("m$it", it.toULong(), "Loaded Chief history $it\n".repeat(12)) }
            api.readMarker = 2000uL
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefMessage:m2000").assertIsDisplayed()
        swipeMessagesDown()
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
        tapSendWhenReady()
        try { compose.waitUntil(10_000) { api.sends.size == 1 } }
        catch(error: Exception) { throw AssertionError("Send touch admission: sends=${api.sends.size}, outgoing=${computer.transcript()?.state?.value?.outgoing?.size}, draftLength=${computer.transcript()?.state?.value?.draft?.length}",error) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.size == 1000 }
        compose.onNodeWithText("Return to newest and bound the window").assertIsDisplayed()
        assertTrue(computer.transcript()!!.state.value.hasMore)
        assertEquals(1001uL, computer.transcript()!!.state.value.messages.first().seq)
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.isEmpty() == true }
        compose.waitUntil(10_000) { compose.onNodeWithTag("ChiefMessage:echo").isDisplayed() }
        assertEquals(1001, computer.transcript()!!.state.value.messages.size)
    }

    @Test fun measuredHistoryPrefetchStopsFarRetriesAndResumesAfterAPausedLayoutWithoutMovingTheReader() {
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread {
            api.rows = (1..150).map { message("m$it", it.toULong(), "Variable height history message $it\n".repeat(5 + it % 7))
                .let { row -> if (it == 106) row.copy(threadRoot = "m101") else row } }
            api.readMarker = 150uL
            api.historyGate = gate
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefMessage:m150").assertIsDisplayed()
        assertTrue(api.historyRequests.isEmpty())
        // A reader drag owns unpinning; the subsequent exact-index movement only
        // stages a deterministic measured distance and must not pretend to be a drag.
        swipeMessagesDown()
        compose.waitForIdle()
        compose.onNodeWithTag("ChiefMessages").performScrollToIndex(5)
        compose.waitUntil(10_000) { api.historyRequests.size == 1 }
        compose.onNodeWithTag("ChiefMessage:m101").assertIsNotDisplayed()
        assertEquals(101uL, api.historyRequests.single())
        compose.onNodeWithTag("ChiefMessages").performScrollToIndex(50)
        compose.waitForIdle()
        compose.runOnUiThread { api.historyFailures = 1; api.historyGate = null; gate.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.historyRetryAttempt == 1 }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.loadingOlder == false }
        assertEquals("Moving beyond six measured screens must stop the failed retry", 1, api.historyRequests.size)
        val resumed = CompletableDeferred<Unit>()
        compose.runOnUiThread { computer.setActive(false); api.historyGate = resumed }
        compose.onNodeWithTag("ChiefMessages").performScrollToIndex(5)
        compose.waitForIdle()
        assertEquals("A paused layout must not restart reads", 1, api.historyRequests.size)
        assertFalse(computer.transcript()!!.state.value.readsActive)
        compose.runOnUiThread { computer.setActive(true) }
        compose.waitUntil(10_000) { api.historyRequests.size == 2 }
        val anchorTop = compose.onNodeWithTag("ChiefMessage:m105").fetchSemanticsNode().boundsInRoot.top
        compose.runOnUiThread { resumed.complete(Unit) }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.size == 100 }
        assertEquals(listOf(101uL, 101uL), api.historyRequests.toList())
        compose.onNodeWithTag("ChiefMessage:m105").assertIsDisplayed()
        assertEquals(anchorTop, compose.onNodeWithTag("ChiefMessage:m105").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertNull(computer.transcript()!!.state.value.historyError)
    }

    private fun tapSendWhenReady() {
        // The source hides Send until a meaningful draft has rendered.
        compose.waitUntil(10_000) {
            val node = compose.onAllNodesWithTag("ChiefSend").fetchSemanticsNodes().singleOrNull()
            node != null && node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == null
        }
        // The real software IME is external to Compose's test clock. Sending while it
        // is moving can place a recorded touch on the keyboard rather than the field.
        compose.waitUntil(10_000) {
            var visible=false
            compose.runOnUiThread { visible=compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            visible
        }
        tapStableAction("ChiefSend")
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
