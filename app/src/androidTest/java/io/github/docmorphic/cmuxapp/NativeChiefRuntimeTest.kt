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
    private var testLayoutDirection by mutableStateOf(androidx.compose.ui.unit.LayoutDirection.Ltr)

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
            CompositionLocalProvider(LocalDensity provides Density(hostDensity.density, testFontScale),
                androidx.compose.ui.platform.LocalLayoutDirection provides testLayoutDirection) {
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
        val approvals = CopyOnWriteArrayList<Pair<String?, String>>()
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
        override suspend fun approve(conversation: String?, request: String?, choice: String): NativeChiefApproved {
            approvals += request to choice; return NativeChiefApproved("chief-main", request.orEmpty(), choice, true)
        }
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

    @Test fun captureSharedNormalThreadState() = captureSharedThreadState("thread-dark", 17f)
    @Test fun captureSharedAccessibilityThreadState() = captureSharedThreadState("thread-large-dark", 28f)

    private fun captureSharedThreadState(name: String, targetBodyPoints: Float) {
        org.junit.Assume.assumeTrue(compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
        // Android uses nonlinear font scaling. Match the native fixture's measured
        // body size, rather than assuming a nominal 1.65 setting renders 28 points.
        // The framework converter caches scale buckets; exact inversion is not
        // available. Use the existing bounded source-body fixture and record the
        // actual result, rather than claim exact native category equivalence.
        if (targetBodyPoints == 28f) useReferenceAccessibilityBodySize()
        else compose.runOnUiThread { testFontScale = 1f }
        openReferenceScene("quote-dark")
        val physicalDensity = compose.activity.resources.displayMetrics.density
        val mainTimestamp = compose.onNodeWithTag("ChiefTimestamp:s:parent").fetchSemanticsNode().boundsInRoot
        val mainBody = compose.onNodeWithTag("ChiefBubbleBody:parent").fetchSemanticsNode().boundsInRoot
        assertEquals(4f * physicalDensity, mainBody.top - mainTimestamp.bottom, 1f)
        val draft = "Retain the draft through replies"
        compose.onNodeWithTag("ChiefComposer").performTextInput(draft)
        // The native reference opens through the source quote's public AX
        // activation. Exercise the real Android quote semantic callback too.
        compose.onNodeWithTag("ChiefQuote:reply").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        waitForReplyThread()
        compose.onNodeWithTag("ChiefThreadMessage:parent").assertExists()
        compose.onNodeWithTag("ChiefThreadMessage:reply").assertExists()
        compose.onNodeWithTag("ChiefThreadMessage:next").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains(draft)
        val density = Density(compose.activity.resources.displayMetrics.density, testFontScale)
        compose.waitUntil(10_000) {
            val bubble = compose.onNodeWithTag("ChiefThreadMessage:reply").fetchSemanticsNode().boundsInRoot
            val field = compose.onNodeWithTag("ChiefComposerField").fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(field.top - bubble.bottom - 10 * density.density) < 2
        }
        var lastFrames: List<androidx.compose.ui.geometry.Rect>? = null
        var lastMove = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val now = android.os.SystemClock.uptimeMillis()
            val currentFrames = listOf("ChiefThreadTimestamp", "ChiefThreadRow:parent", "ChiefComposerField").map {
                compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
            }
            if (currentFrames != lastFrames) { lastFrames = currentFrames; lastMove = now }
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true && now - lastMove >= 350
        }
        fun font(node: SemanticsNodeInteraction): JSONObject {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val result = layouts.single()
            return JSONObject().put("text", result.layoutInput.text.text)
                .put("fontPoints", with(density) { result.layoutInput.style.fontSize.toDp().value })
                .put("fontWeight", result.layoutInput.style.fontWeight?.weight)
                .put("firstBaselinePixels", result.firstBaseline)
                .put("lineHeightPixels", result.getLineBottom(0) - result.getLineTop(0))
                .put("spanWeights", org.json.JSONArray(result.layoutInput.text.spanStyles.mapNotNull { it.item.fontWeight?.weight }))
        }
        val timestamp = font(compose.onNodeWithTag("ChiefThreadTimestamp:Text", useUnmergedTree = true))
        val sender = font(compose.onNode(hasText("Chief") and hasAnyAncestor(hasTestTag("ChiefThreadRow:parent")), useUnmergedTree = true))
        // Independent measurements from the actual UIKit thread captures. Allow
        // at most the Android converter's recorded category approximation and
        // physical-pixel rounding; old Material caption/padding does not fit.
        val large = targetBodyPoints == 28f
        val sourceFont = if (large) 20.0 else 11.0
        assertEquals(sourceFont, timestamp.getDouble("fontPoints"), .15)
        assertEquals(sourceFont, sender.getDouble("fontPoints"), .15)
        assertEquals(400, timestamp.getInt("fontWeight"))
        assertEquals(listOf(500), (0 until timestamp.getJSONArray("spanWeights").length()).map {
            timestamp.getJSONArray("spanWeights").getInt(it)
        })
        val timestampRow = compose.onNodeWithTag("ChiefThreadTimestamp").fetchSemanticsNode().boundsInRoot
        val timestampLabel = compose.onNodeWithTag("ChiefThreadTimestamp:Label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timestampText = compose.onNodeWithTag("ChiefThreadTimestamp:Text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals((if (large) 44.333333f else 28.666667f) * density.density, timestampRow.height, 4f)
        assertEquals((if (large) 24f else 18f) * density.density, timestampLabel.height, 3f)
        assertEquals(10f * density.density, timestampLabel.top - timestampRow.top, 1f)
        assertEquals((if (large) 29.109375f else 22.910156f) * density.density,
            timestampText.top + timestamp.getDouble("firstBaselinePixels").toFloat() - timestampRow.top, 2f)
        val parent = compose.onNodeWithTag("ChiefThreadRow:parent").fetchSemanticsNode().boundsInRoot
        assertEquals(timestampRow.bottom, parent.top, 1f)
        val senderFrame = compose.onNodeWithTag("ChiefThreadSender:parent:Label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals((if (large) 24f else 40f / 3) * density.density, senderFrame.height, 2f)
        val body = compose.onNodeWithTag("ChiefThreadBody:parent").fetchSemanticsNode().boundsInRoot
        assertEquals((if (large) 23f / 3 else 4f) * density.density, body.top - senderFrame.bottom, 1f)
        val captureFrames = listOf("ChiefThreadTimestamp", "ChiefThreadTimestamp:Label", "ChiefThreadTimestamp:Text",
            "ChiefThreadSender:parent:Label", "ChiefThreadSender:parent:Text", "ChiefThreadRow:parent", "ChiefThreadRow:reply", "ChiefThreadMessage:parent",
            "ChiefThreadMessage:reply", "ChiefThreadBody:parent", "ChiefThreadBody:reply", "ChiefComposerField", "ChiefReplyTranscript").associateWith { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        val frames = captureFrames.mapValues { (_, rect) -> org.json.JSONArray(listOf(rect.left, rect.top, rect.width, rect.height)) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        captureFrames.forEach { (tag, rect) ->
            assertEquals("Layout moved during capture: $tag", rect,
                compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        }
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(directory, "chief-reference-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "chief-reference-$name.json").writeText(JSONObject().put("scene", name)
            .put("fixtureSha256", "e2d477d1db72e0ca574f8a1c04db8ad9245fbcd3df6d6015a317e3846df9c2fb")
            .put("interactionFixtureSha256", "77f80ccb3b17a79edbc6792ab24d92bf18274f720f88a0e1f26af8a265203717")
            .put("imageSha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            .put("viewportPixels", org.json.JSONArray(listOf(bitmap.width, bitmap.height)))
            .put("density", density.density).put("fontScale", testFontScale)
            .put("targetBodyPoints", targetBodyPoints)
            .put("effectiveBodyPoints", with(density) { 17.sp.toDp().value }).put("draft", draft)
            .put("timestamp", timestamp).put("sender", sender).put("framesInRoot", JSONObject(frames))
            .put("scope", "Production local-owner thread using shared source rows/draft; system bars/keyboard included and timestamps differ. Capture of measured Android fonts/frames, not full visual/physical parity.")
            .toString(2))
        bitmap.recycle()
        compose.onNodeWithTag("ChiefReplyClose").performClick()
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains(draft)
        assertTrue(api.sends.isEmpty())
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

    @Test fun replyEntryCopiesTravelFromVisibleTwinsAndUnwindOnlyTheSwipedBalloon() {
        org.junit.Assume.assumeTrue("Enable emulator animator_duration_scale for timed motion", android.animation.ValueAnimator.areAnimatorsEnabled())
        checkReplyEntryMotion(reduced = false)
    }

    @Test fun reducedMotionOpensAtRestWithoutReleasingTheSwipeIntoTheCopy() {
        org.junit.Assume.assumeFalse("Disable emulator animator_duration_scale for reduced motion", android.animation.ValueAnimator.areAnimatorsEnabled())
        checkReplyEntryMotion(reduced = true)
    }

    @Test fun closingDuringEntryReturnsFromTheCurrentPoseAndDropsTheCarriedSwipe() {
        org.junit.Assume.assumeTrue("Enable animator duration scale for interrupted travel", android.animation.ValueAnimator.areAnimatorsEnabled())
        checkReplyEntryMotion(reduced = false, closeDuringEntry = true)
    }

    private fun checkReplyEntryMotion(reduced: Boolean, closeDuringEntry: Boolean = false) {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performClick()
        compose.waitUntil(10_000) {
            var visible = false
            compose.runOnUiThread { visible = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            visible
        }
        compose.onNodeWithTag("ChiefReplySwipe:reply").performScrollTo()
        var previous = androidx.compose.ui.geometry.Rect.Zero
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val current = compose.onNodeWithTag("ChiefMessage:reply").fetchSemanticsNode().boundsInRoot
            val now = android.os.SystemClock.uptimeMillis()
            if (current != previous) stableSince = now
            previous = current
            now - stableSince >= 350
        }
        val density = compose.activity.resources.displayMetrics.density
        val original = compose.onNodeWithTag("ChiefMessage:reply").fetchSemanticsNode().boundsInRoot
        val other = compose.onNodeWithTag("ChiefMessage:parent").fetchSemanticsNode().boundsInRoot
        val area = compose.onNodeWithTag("ChiefReplySwipe:reply").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefReplySwipe:reply").performTouchInput {
                val x = original.center.x - area.left
                val y = original.center.y - area.top
                down(androidx.compose.ui.geometry.Offset(x, y))
                moveTo(androidx.compose.ui.geometry.Offset(x + 80 * density, y), delayMillis = 100)
                up()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            fun copy(id: String) = compose.onNodeWithTag("ChiefThreadMessage:$id").fetchSemanticsNode().boundsInRoot
            val start = copy("reply")
            val parentStart = copy("parent")
            saveScreenshot(if (reduced) "chief-reply-reduced-entry" else "chief-reply-entry-start")
            if (!reduced) {
                assertEquals("copy starts over its visible twin", original.top, start.top, 2f)
                assertTrue("swiped balloon keeps release offset", start.left - original.left > 20 * density)
            } else assertEquals("reduced motion discards swipe offset", original.left, start.left, 2f)
            assertEquals("other balloon is not dragged", other.left, parentStart.left, 2f)
            compose.mainClock.advanceTimeBy(128)
            compose.waitForIdle()
            val middle = copy("reply")
            val measuredAt = compose.mainClock.currentTime
            saveScreenshot(if (reduced) "chief-reply-reduced-middle" else "chief-reply-entry-middle")
            if (closeDuringEntry) {
                // A pointer click advances the test clock before touch-up. This
                // check isolates admission itself; real pointer close/reopen is
                // covered by the interrupted header checks.
                val close = checkNotNull(compose.onNodeWithTag("ChiefReplyClose").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
                val atAdmission = copy("reply")
                val targetProbe = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().config[ChiefReplyOriginsProbe]
                var targetAtAdmission: Float? = null
                compose.runOnUiThread { targetAtAdmission = targetProbe().bubbles["reply"] }
                val capturedAt = compose.mainClock.currentTime
                compose.runOnUiThread { assertTrue(close()) }
                val clickedAt = compose.mainClock.currentTime
                assertEquals("Synchronous admission must not advance the display clock", capturedAt, clickedAt)
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val closing = closingCopy("reply")
                val diagnostic = "closing must not advance another entry frame: original=$original middle=$middle atAdmission=$atAdmission targetAtAdmission=$targetAtAdmission closing=$closing measuredAt=$measuredAt capturedAt=$capturedAt clickedAt=$clickedAt closingAt=${compose.mainClock.currentTime}"
                android.util.Log.i("ChiefHeaderFrame", diagnostic)
                assertTrue(diagnostic, closing.top <= middle.top)
                assertTrue("first return frame stays between current pose and its twin", closing.top >= original.top)
                assertEquals("source return clears the carried swipe", original.left, closing.left, 1f)
                compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
                compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
                assertNull(computer.transcript()!!.state.value.reply)
                assertTrue(api.sends.isEmpty())
                return
            }
            compose.mainClock.advanceTimeBy(1600)
            compose.waitForIdle()
            val landed = copy("reply")
            if (!reduced) {
                assertTrue("thread actually travels", kotlin.math.abs(landed.top - start.top) > 4 * density)
                assertTrue("sample is between origin and destination", middle.top in minOf(start.top, landed.top)..maxOf(start.top, landed.top))
                assertTrue("travel continues after the intermediate frame", kotlin.math.abs(middle.top - landed.top) > 1f)
            } else {
                assertEquals(start.top, middle.top, 1f)
                assertEquals(start.top, landed.top, 1f)
            }
            assertEquals("swipe fully unwinds", original.left, landed.left, 2f)
            assertEquals("other balloon stays in its horizontal lane", other.left, copy("parent").left, 2f)
            val name = if (reduced) "chief-reply-reduced" else "chief-reply-entry"
            saveScreenshot("$name-landed")
            fun frame(rect: androidx.compose.ui.geometry.Rect) = org.json.JSONArray(listOf(rect.left, rect.top, rect.width, rect.height))
            File(File(compose.activity.getExternalFilesDir(null), "screenshots"), "$name-motion.json").writeText(JSONObject()
                .put("reducedMotion", reduced).put("density", density).put("original", frame(original))
                .put("start", frame(start)).put("after128ms", frame(middle)).put("after1728ms", frame(landed))
                .put("scope", "Production Compose adapter, real swipe and manual frame clock on disposable emulator; physical animation appearance and closing choreography remain open.")
                .toString(2))
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun closingCopy(id: String): androidx.compose.ui.geometry.Rect {
        val probe = compose.onNodeWithTag("ChiefReplyClosing").fetchSemanticsNode().config[ChiefReplyCopyBounds]
        var result: androidx.compose.ui.geometry.Rect? = null
        compose.runOnUiThread { result = probe().getValue(id) }
        return checkNotNull(result)
    }

    private fun headerPose(): NativeChiefHeaderMotion.Pose {
        val probe = compose.onNodeWithTag("ChiefHeader").fetchSemanticsNode().config[ChiefHeaderMotionProbe]
        var value: NativeChiefHeaderMotion.Pose? = null
        compose.runOnUiThread { value = probe() }
        return checkNotNull(value)
    }

    @Test fun replyHeaderDelaysItsSymbolButAdmitsCloseImmediatelyAndPreservesTheEditor() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep header draft")
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        compose.onNodeWithTag("ChiefQuote:reply").performScrollTo()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefQuote:reply").performClick()
            repeat(2) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
            compose.mainClock.advanceTimeBy(128); compose.waitForIdle()
            compose.onNodeWithTag("ChiefBack").assertDoesNotExist()
            compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Stop Chief")
            assertEquals(0f, headerPose().backAlpha, .001f)
            assertEquals(1f, headerPose().trailingAlpha, .001f)
            saveScreenshot("chief-header-before-swap")
            compose.mainClock.advanceTimeBy(304); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Close")
            assertTrue(headerPose().trailingAlpha in .01f.. .95f)
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertEquals(1f, headerPose().trailingAlpha, 0f)
            saveScreenshot("chief-header-close-settled")
            val rect = compose.onNodeWithTag("ChiefReplyClose").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            File(compose.activity.getExternalFilesDir(null), "screenshots/chief-header-close.json").writeText(JSONObject()
                .put("density", density).put("trailingFrame", org.json.JSONArray(listOf(rect.left, rect.top, rect.width, rect.height)))
                .put("scope", "Production Compose header, settled generic close vector; not physical or full pixel parity").toString(2))
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            repeat(2) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
            compose.onNodeWithTag("ChiefHeaderStop").assertContentDescriptionEquals("Stop Chief")
            compose.onNodeWithTag("ChiefBack").assertHasClickAction()
            assertFalse(headerPose().closeLabel)
            assertEquals(0f, headerPose().backAlpha, .001f)
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertTrue(headerPose().backAlpha > .1f && headerPose().backAlpha < 1f)
            saveScreenshot("chief-header-back-return")
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            assertEquals(1f, headerPose().backAlpha, 0f)
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keep header draft")
            assertNull(computer.transcript()!!.state.value.reply); assertTrue(api.sends.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun interruptedHeaderTransitionsCannotHideStopOrRestoreBackIntoANewReply() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Retain interrupted header draft")
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        compose.onNodeWithTag("ChiefQuote:reply").performScrollTo()
        compose.mainClock.autoAdvance = false
        try {
            for ((enterMillis, reopenMillis) in listOf(100L to null, 340L to null, 850L to 100L, 340L to 16L)) {
                compose.onNodeWithTag("ChiefQuote:reply").performClick()
                assertNotNull("Quote must admit the reply", computer.transcript()!!.state.value.reply)
                repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                compose.mainClock.advanceTimeBy(enterMillis); compose.waitForIdle()
                if (enterMillis == 100L && reopenMillis == null) {
                    compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Stop Chief")
                        .performTouchInput { click(center) }
                } else compose.onNodeWithTag("ChiefReplyClose").performClick()
                repeat(2) { compose.mainClock.advanceTimeByFrame() }; compose.waitForIdle()
                assertNull(computer.transcript()!!.state.value.reply)
                compose.onNodeWithTag("ChiefHeaderStop").assertContentDescriptionEquals("Stop Chief")
                if (reopenMillis != null) {
                    compose.mainClock.advanceTimeBy(reopenMillis); compose.waitForIdle()
                    compose.onNodeWithTag("ChiefQuote:reply").performClick()
                    assertNotNull("Quote must admit the replacement reply", computer.transcript()!!.state.value.reply)
                    repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                    compose.mainClock.advanceTimeBy(128); compose.waitForIdle()
                    assertFalse("Old completion cannot skip the new delay", headerPose().closeLabel)
                    compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
                    assertEquals("Reopened header $enterMillis/$reopenMillis: ${headerPose()}", 0f, headerPose().backAlpha, 0f)
                    assertEquals(1f, headerPose().trailingAlpha, 0f)
                    compose.onNodeWithTag("ChiefBack").assertDoesNotExist()
                    compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Close")
                    compose.onNodeWithTag("ChiefReplyClose").performClick()
                    repeat(2) { compose.mainClock.advanceTimeByFrame() }
                }
                compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
                val normal = headerPose()
                assertEquals(1f, normal.backAlpha, 0f); assertEquals(1f, normal.trailingAlpha, 0f)
                assertEquals(0f, normal.closeFraction, 0f); assertFalse(normal.closeLabel)
                compose.onNodeWithTag("ChiefHeaderStop").assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Retain interrupted header draft")
                assertTrue(api.sends.isEmpty())
            }
            saveScreenshot("chief-header-interrupted-recovered")
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun disabledHeaderAnimationsSettleWithoutLeavingPendingGlyphChanges() {
        org.junit.Assume.assumeFalse(android.animation.ValueAnimator.areAnimatorsEnabled())
        openSettledReplyWithDraft("Reduced header draft")
        assertEquals(NativeChiefHeaderMotion.Pose(0f, 1f, 1f, true, false, false), headerPose())
        compose.onNodeWithTag("ChiefReplyClose").performClick()
        compose.waitForIdle()
        assertEquals(NativeChiefHeaderMotion.Pose(1f, 1f, 0f, false, false), headerPose())
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Reduced header draft")
    }

    @Test fun headerKeepsTheClosedPhaseWhenCloseAndReopenAreAdmittedInOneFrame() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("One frame header draft")
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        val quote = checkNotNull(compose.onNodeWithTag("ChiefQuote:reply").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(quote()) }
        waitForReplyThread(); compose.waitForIdle()
        compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Close")
        val close = checkNotNull(compose.onNodeWithTag("ChiefReplyClose").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val generation = computer.transcript()!!.state.value.reply!!.generation
        compose.mainClock.autoAdvance = false
        try {
            // Invoke the real UI actions in one main-thread callback, without a
            // composition/frame between them. StateFlow may conflate the null.
            compose.runOnUiThread {
                assertTrue(close()); assertNull(computer.transcript()!!.state.value.reply)
                assertTrue(quote()); assertTrue(computer.transcript()!!.state.value.reply!!.generation > generation)
            }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            assertFalse("The admitted close must reset the symbol before the new delay", headerPose().closeLabel)
            compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Stop Chief")
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            assertEquals(0f, headerPose().backAlpha, 0f)
            assertEquals(1f, headerPose().trailingAlpha, 0f)
            compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Close")
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("One frame header draft")
            assertTrue(api.sends.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun closeOpenCloseWithinOneFrameRetainsOnlyTheNewestReplyCopies() = checkCoalescedReplyCopies(sendFirst = false)
    @Test fun sendOpenCloseWithinOneFrameRetainsOnlyTheNewestReplyCopies() = checkCoalescedReplyCopies(sendFirst = true)
    @Test fun coalescedReplyCopiesArePurgedWhenTheirOwnerRetires() = checkCoalescedReplyCopies(sendFirst = false, retire = true)

    private fun checkCoalescedReplyCopies(sendFirst: Boolean, retire: Boolean = false) {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Coalesced reply draft")
        settledComposerGeometry()
        // The last message can be taller than the landscape viewport with the
        // keyboard open. Bring its lazy item into composition without asking
        // performScrollToNode to fit the entire action container on screen.
        compose.onNodeWithTag("ChiefMessages").performScrollToIndex(3)
        val next = compose.onNodeWithTag("ChiefReplyActions:next").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Reply" }
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        compose.onNodeWithTag("ChiefQuote:reply").performClick()
        waitForReplyThread(); compose.waitForIdle()
        val close = checkNotNull(compose.onNodeWithTag("ChiefReplyClose").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val send = checkNotNull(compose.onNodeWithTag("ChiefSend").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread { api.sendGate = gate }
        val originProbe = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().config[ChiefReplyOriginsProbe]
        var originalCopies = emptyMap<String, Float>()
        compose.runOnUiThread { originalCopies = originProbe().bubbles }
        compose.mainClock.autoAdvance = false
        try {
            var newestGeneration = 0L
            compose.runOnUiThread {
                assertTrue(if (sendFirst) send() else close())
                assertTrue(next.action())
                assertEquals("reply", computer.transcript()!!.state.value.reply!!.rootID)
                newestGeneration = computer.transcript()!!.state.value.reply!!.generation
                assertTrue(close())
                assertNull(computer.transcript()!!.state.value.reply)
                if (retire) computer.transcript()!!.close()
            }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            if (retire) {
                compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
                compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
            } else {
                val probe = compose.onNodeWithTag("ChiefReplyClosing").fetchSemanticsNode().config[ChiefReplyCopyBounds]
                var ids = emptySet<String>()
                var bounds = emptyMap<String, androidx.compose.ui.geometry.Rect>()
                compose.runOnUiThread { bounds = probe(); ids = bounds.keys }
                assertEquals("The replaced root must not survive the admitted newer root", setOf("reply", "next"), ids)
                val stateProbe = compose.onNodeWithTag("ChiefReplyClosing").fetchSemanticsNode().config[ChiefReplyDisplayProbe]
                compose.runOnUiThread {
                    val display = stateProbe()
                    assertEquals(newestGeneration, display.generation)
                    if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        assertTrue("A just-admitted normal copy must not disappear before first layout", display.alpha > .99f)
                        assertTrue(display.settles)
                        for ((id, rect) in bounds) assertEquals("First closing copy starts at its actual measured twin: $id",
                            checkNotNull(originalCopies[id]), rect.top, 2f)
                    }
                }
                saveScreenshot(if (sendFirst) "chief-copy-admission-send-close" else "chief-copy-admission-close-open-close")
                val details = JSONObject().put("generation", newestGeneration).put("sendFirst", sendFirst)
                    .put("originals", JSONObject(originalCopies)).put("copies", JSONObject(bounds.mapValues { (_, r) ->
                        org.json.JSONArray(listOf(r.left, r.top, r.width, r.height)) }))
                File(compose.activity.getExternalFilesDir(null), "screenshots/" +
                    (if (sendFirst) "chief-copy-admission-send-close.json" else "chief-copy-admission-close-open-close.json")).writeText(details.toString(2))
                compose.onNodeWithTag("ChiefComposer").assertIsFocused()
                if (!sendFirst) compose.onNodeWithTag("ChiefComposer").assertTextContains("Coalesced reply draft")
            }
            compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            assertEquals(if (sendFirst) 1 else 0, api.sends.size)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { gate.complete(Unit) }
        }
    }

    @Test fun headerKeepsTheClosedPhaseWhenSendAndReopenAreAdmittedInOneFrame() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Send then reopen in one frame")
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        val quote = checkNotNull(compose.onNodeWithTag("ChiefQuote:reply").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(quote()) }
        waitForReplyThread(); compose.waitForIdle()
        val send = checkNotNull(compose.onNodeWithTag("ChiefSend").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread { api.sendGate = gate }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread {
                assertTrue(send()); assertNull(computer.transcript()!!.state.value.reply)
                assertEquals("", computer.transcript()!!.state.value.draft)
                assertEquals(1, computer.transcript()!!.state.value.outgoing.size)
                assertTrue(quote()); assertNotNull(computer.transcript()!!.state.value.reply)
            }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            assertFalse(headerPose().closeLabel)
            compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Stop Chief")
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClose").assertContentDescriptionEquals("Close")
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            assertEquals(listOf("Send then reopen in one frame"), api.sends.map { it.first })
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { gate.complete(Unit) }
        }
    }

    @Test fun staleHeaderActionsUseTheAdmittedModeBeforeTheNextComposition() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep synchronous mode draft")
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        fun action(tag: String) = checkNotNull(compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        val quote = action("ChiefQuote:reply"); val oldBack = action("ChiefBack"); val oldStop = action("ChiefHeaderStop")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread {
                assertTrue(quote())
                assertFalse("Back must reject activation after reply admission", oldBack())
                assertNotNull(computer.selected)
                assertTrue(oldStop())
                assertNull("The old Stop control must close the admitted reply", computer.transcript()!!.state.value.reply)
                assertTrue(quote())
            }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(80); compose.waitForIdle()
            assertFalse(headerPose().closeLabel)
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            assertEquals(0f, headerPose().backAlpha, 0f)
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keep synchronous mode draft")
            assertTrue(api.sends.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun returningBackAcceptsPointerDuringItsInvisibleDelay() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openSettledReplyWithDraft("Invisible Back delay")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
            assertEquals(0f, headerPose().backAlpha, 0f)
            assertTrue(headerPose().backAcceptsPointer)
            compose.onNodeWithTag("ChiefBack").assertIsEnabled().assertHasClickAction().performTouchInput { click(center) }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            assertNull(computer.selected)
            compose.onNodeWithTag("ChiefPinnedConversation").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun returningBackSuppressesPointerDuringFadeAndRestoresItAtRest() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openSettledReplyWithDraft("Pointer fade guard")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertTrue(headerPose().backAlpha > .1f && headerPose().backAlpha < .9f)
            assertFalse(headerPose().backAcceptsPointer)
            compose.onNodeWithTag("ChiefBack").assertIsEnabled().assertHasClickAction().performTouchInput { click(center) }
            assertNotNull("A pointer tap during the actual fade must not navigate", computer.selected)
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Pointer fade guard")
            saveScreenshot("chief-header-admission-back-fade")
            compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
            assertTrue(headerPose().backAcceptsPointer)
            compose.onNodeWithTag("ChiefBack").performTouchInput { click(center) }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            assertNull(computer.selected)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun returningBackRetainsItsAccessibilityActionWhilePointerIsSuppressed() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openSettledReplyWithDraft("AX fade action")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertFalse(headerPose().backAcceptsPointer)
            val node = compose.onNodeWithTag("ChiefBack").assertIsEnabled().assertHasClickAction().fetchSemanticsNode()
            val action = checkNotNull(node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
            compose.runOnUiThread { assertTrue(action()) }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            assertNull(computer.selected)
            compose.onNodeWithTag("ChiefPinnedConversation").assertExists()
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun openSettledReplyWithDraft(draft: String) {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput(draft)
        settledComposerGeometry()
        if (compose.onAllNodesWithTag("ChiefReplyActions:reply").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefReplyActions:reply"))
        val action = compose.onNodeWithTag("ChiefReplyActions:reply").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Reply" }
        compose.runOnUiThread { assertTrue(action.action()) }
        waitForReplyThread()
        compose.waitForIdle()
    }

    @Test fun closingReplyRestoresLogicalStateImmediatelyAndWaitsForReturnAfterBackgroundEnds() {
        org.junit.Assume.assumeTrue("Enable animator duration scale for return travel", android.animation.ValueAnimator.areAnimatorsEnabled())
        openSettledReplyWithDraft("Preserve through closing")
        val start = compose.onNodeWithTag("ChiefThreadMessage:reply").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            assertNull(computer.transcript()!!.state.value.reply)
            compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
            compose.onNodeWithTag("ChiefReplyClosing").assertExists()
            compose.onNodeWithTag("ChiefMessage:next").assertExists()
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Preserve through closing")
            val target = compose.onNodeWithTag("ChiefMessage:reply").fetchSemanticsNode().boundsInRoot
            val first = closingCopy("reply")
            assertEquals(start.top, first.top, 1f)
            assertTrue("target differs from the lifted position", kotlin.math.abs(target.top - first.top) > 4f)
            saveScreenshot("chief-reply-close-start")
            compose.mainClock.advanceTimeBy(128); compose.waitForIdle()
            val middle = closingCopy("reply")
            assertTrue(middle.top in minOf(first.top, target.top)..maxOf(first.top, target.top))
            assertTrue(kotlin.math.abs(middle.top - target.top) < kotlin.math.abs(first.top - target.top))
            saveScreenshot("chief-reply-close-middle")
            compose.mainClock.advanceTimeBy(192); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertExists()
            assertTrue("copies outlive the 300 ms background until rows land", kotlin.math.abs(closingCopy("reply").top - target.top) > 1f)
            compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            compose.onNodeWithTag("ChiefMessage:reply").assertIsDisplayed()
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Preserve through closing")
            saveScreenshot("chief-reply-close-landed")
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun replySendFadesStationaryCopiesAndReleasesBackingWithoutWaitingForAcknowledgment() {
        openSettledReplyWithDraft("Send from a retained copy")
        val start = compose.onNodeWithTag("ChiefThreadMessage:reply").fetchSemanticsNode().boundsInRoot
        val gate = CompletableDeferred<Unit>()
        compose.runOnUiThread { api.sendGate = gate }
        compose.mainClock.autoAdvance = false
        try {
            tapSendWhenReady()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            assertNull(computer.transcript()!!.state.value.reply)
            compose.onNodeWithTag("ChiefReplyClosing").assertExists()
            assertNotNull("backing AX is restored even when sending scrolls old rows out of view",
                compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy))
            val first = closingCopy("reply")
            assertEquals(start.top, first.top, 1f)
            compose.mainClock.advanceTimeBy(128); compose.waitForIdle()
            assertEquals("send fades instead of returning", first.top, closingCopy("reply").top, 1f)
            saveScreenshot("chief-reply-send-fade-middle")
            compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            assertEquals("reply", computer.transcript()!!.state.value.outgoing.single().replyToID)
            assertFalse(gate.isCompleted)
        } finally { compose.mainClock.autoAdvance = true; compose.runOnUiThread { gate.complete(Unit) } }
    }

    @Test fun reopeningDuringReturnReplacesOldCopiesAndRetiringTheOwnerPurgesBothPhases() {
        openSettledReplyWithDraft("Keep through reopen")
        val generation = computer.transcript()!!.state.value.reply!!.generation
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertExists()
            val action = compose.onNodeWithTag("ChiefReplyActions:next").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Reply" }
            compose.runOnUiThread { assertTrue(action.action()) }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            assertTrue(computer.transcript()!!.state.value.reply!!.generation > generation)
            assertEquals("next", computer.transcript()!!.state.value.reply!!.targetID)
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyThread").assertExists()
            compose.onNodeWithTag("ChiefThreadMessage:parent").assertDoesNotExist()
            compose.onNodeWithTag("ChiefThreadMessage:next").assertExists()
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertExists()
            compose.runOnUiThread { computer.transcript()!!.close() }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            compose.onNodeWithTag("ChiefComposer").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            assertTrue(api.sends.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun closingCopiesWithoutVisibleTwinsFadesThemInPlaceInsteadOfInventingReturnTargets() {
        org.junit.Assume.assumeTrue("Enable animator duration scale to exercise missing-twin return", android.animation.ValueAnimator.areAnimatorsEnabled())
        openSettledReplyWithDraft("Keep while new messages arrive")
        compose.runOnUiThread {
            api.rows = api.rows + (4..103).map { message("arrival-$it", it.toULong(), "Later message $it") }
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { computer.transcript()!!.state.value.messages.lastOrNull()?.id == "arrival-103" }
        compose.waitUntil(10_000) {
            val probe = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().config[ChiefReplyVisibleRows]
            var visible = emptySet<String>()
            compose.runOnUiThread { visible = probe() }
            "reply" !in visible && "parent" !in visible
        }
        compose.waitForIdle()
        val start = compose.onNodeWithTag("ChiefThreadMessage:reply").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("ChiefMessage:reply").assertDoesNotExist()
            val first = closingCopy("reply")
            assertEquals(start.top, first.top, 1f)
            compose.mainClock.advanceTimeBy(128); compose.waitForIdle()
            assertEquals("unseen twin has no invented destination", first.top, closingCopy("reply").top, 1f)
            saveScreenshot("chief-reply-close-missing-twins-middle")
            compose.mainClock.advanceTimeBy(240); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keep while new messages arrive")
            assertTrue(api.sends.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun staleOwnershipRetiresReplyCopiesInsteadOfStartingAClosingTransition() {
        openSettledReplyWithDraft("Keep until the owner retires")
        val ownedSession = checkNotNull(computer.transcript())
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { current = false }
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            assertTrue(ownedSession.state.value.retired)
            assertNull(ownedSession.state.value.reply)
            assertEquals("", ownedSession.state.value.draft)
            compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            assertTrue(api.sends.isEmpty())
        } finally {
            compose.runOnUiThread { current = true }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun accessibilityScrollingKeepsTheReadingPositionAcrossArrivalsAndCanResumeFollowingNewest() {
        compose.runOnUiThread {
            api.homeUnread = 0; api.readMarker = 100uL
            api.rows = (1..100).map { message("ax-$it", it.toULong(), "Accessible transcript message $it") }
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:ax-100").fetchSemanticsNodes().isNotEmpty() }
        fun axis() = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        compose.waitUntil(10_000) { axis().maxValue() > 0 && axis().maxValue() - axis().value() < 1f }
        val newest = axis().value()
        compose.onNodeWithTag("ChiefMessages").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            assertTrue(it(0f, -1000f))
        }
        compose.waitUntil(10_000) { axis().value() < newest }
        compose.waitForIdle()
        val reading = axis().value()
        assertTrue("AX can move away from the tail: before=$newest after=$reading max=${axis().maxValue()}", reading < newest)
        compose.runOnUiThread {
            api.rows = api.rows + message("ax-101", 101uL, "Arrival while reading older messages")
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { computer.transcript()!!.state.value.messages.lastOrNull()?.id == "ax-101" }
        compose.waitForIdle()
        assertEquals("arrivals must not undo AX reading", reading, axis().value(), 1f)
        compose.onNodeWithTag("ChiefMessages").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            assertTrue(it(0f, 1_000_000f))
        }
        compose.waitUntil(10_000) { axis().maxValue() - axis().value() < 1f }
        compose.runOnUiThread {
            api.rows = api.rows + message("ax-102", 102uL, "Arrival after returning to newest")
            check(api.transcriptChanges.trySend(api.snapshot()).isSuccess)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:ax-102").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertTrue("reaching newest resumes following", axis().maxValue() - axis().value() < 1f)
    }

    @Test fun quoteVisibleCellCarriesItsOffscreenBubbleIntoAndBackOutOfTheThread() {
        org.junit.Assume.assumeTrue("Enable animator duration scale for partial-cell travel", android.animation.ValueAnimator.areAnimatorsEnabled())
        compose.runOnUiThread {
            api.homeUnread = 0; api.readMarker = 50uL
            api.rows = (1..50).map { index -> when (index) {
                30 -> message("partial-root", 30uL, "Partial root from your Mac.")
                31 -> message("partial-reply", 31uL, "Replying from a partial cell.", human = true).copy(threadRoot = "partial-root")
                else -> message("partial-$index", index.toULong(), "Message $index")
            } }
            computer.home.refresh()
        }
        compose.waitUntil(10_000) { computer.home.state.value.pinned?.unread == 0L }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:partial-50").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep partial quote")
        settledComposerGeometry()
        val state = computer.transcript()!!.state.value
        val index = NativeChiefTimeline.rows(state).indexOfFirst { it.item.messageID == "partial-reply" } +
            if (state.hasMore || state.hasLoadedInfo) 1 else 0
        compose.onNodeWithTag("ChiefMessages").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex) {
            assertTrue(it(index))
        }
        compose.waitForIdle()
        fun frame(tag: String): androidx.compose.ui.geometry.Rect {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val point = node.positionInRoot
            return androidx.compose.ui.geometry.Rect(point.x, point.y, point.x + node.size.width, point.y + node.size.height)
        }
        val viewport = frame("ChiefMessages")
        val quote = frame("ChiefQuote:partial-reply")
        val desired = viewport.bottom - minOf(quote.height / 2, 12 * compose.activity.resources.displayMetrics.density)
        compose.onNodeWithTag("ChiefMessages").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            assertTrue(it(0f, quote.top - desired))
        }
        compose.waitUntil(10_000) { kotlin.math.abs(frame("ChiefQuote:partial-reply").top - desired) < 2f }
        val partialQuote = frame("ChiefQuote:partial-reply")
        assertTrue(partialQuote.top < viewport.bottom && partialQuote.bottom > viewport.bottom)
        val origins = compose.onNodeWithTag("ChiefMessages").fetchSemanticsNode().config[ChiefReplyOriginsProbe]
        var original: Float? = null
        compose.runOnUiThread { original = origins().bubbles["partial-reply"] }
        val originalTop = checkNotNull(original) { "A visible cell must retain its offscreen primary bubble" }
        assertTrue("primary body is outside viewport", originalTop > viewport.bottom)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("ChiefQuote:partial-reply").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val probe = compose.onNodeWithTag("ChiefReplyThread").fetchSemanticsNode().config[ChiefReplyCopyBounds]
            fun copy(): androidx.compose.ui.geometry.Rect {
                var bounds: androidx.compose.ui.geometry.Rect? = null
                compose.runOnUiThread { bounds = probe().getValue("partial-reply") }
                return checkNotNull(bounds)
            }
            val first = copy()
            assertEquals("source aligns the offscreen body, not the quote", originalTop, first.top, 1f)
            saveScreenshot("chief-partial-cell-entry")
            compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
            val landed = copy()
            assertTrue("copy enters from beyond the viewport", landed.top < first.top)
            saveScreenshot("chief-partial-cell-landed")
            compose.onNodeWithTag("ChiefReplyClose").performClick()
            compose.mainClock.advanceTimeBy(128); compose.waitForIdle()
            val returning = closingCopy("partial-reply")
            assertTrue("visible quote supplies a return target instead of a stationary fade", returning.top > landed.top + 1f)
            saveScreenshot("chief-partial-cell-return")
            compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
            compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keep partial quote")
            assertTrue(api.sends.isEmpty())
            fun rect(value: androidx.compose.ui.geometry.Rect) = org.json.JSONArray(listOf(value.left, value.top, value.width, value.height))
            val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(directory, "chief-partial-cell.json").writeText(JSONObject().put("viewport", rect(viewport))
                .put("quote", rect(partialQuote)).put("originalBubbleTop", originalTop).put("entry", rect(first))
                .put("landed", rect(landed)).put("returning", rect(returning)).put("orientation", compose.activity.resources.configuration.orientation)
                .put("scope", "Actual message-cell/bubble layout and source entry/return on emulator; not physical or UIKit rendering acceptance.").toString(2))
        } finally { compose.mainClock.autoAdvance = true }
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
        // The source deliberately leaves this scroll content unclipped. In a
        // short landscape viewport an entire row can extend beyond its bounds;
        // clipped AX rectangles cannot measure the source's row-to-row spacing.
        fun layoutFrame(tag: String): androidx.compose.ui.geometry.Rect {
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val point = node.positionInRoot
            return androidx.compose.ui.geometry.Rect(point.x, point.y, point.x + node.size.width, point.y + node.size.height)
        }
        var lastGeometry = ""
        try { compose.waitUntil(10_000) {
            val root = layoutFrame("ChiefThreadRow:parent")
            val child = layoutFrame("ChiefThreadRow:reply")
            val bubble = layoutFrame("ChiefThreadMessage:reply")
            val field = layoutFrame("ChiefComposerField")
            lastGeometry = "root=$root child=$child bubble=$bubble field=$field density=$density"
            kotlin.math.abs(child.top - root.bottom - 10 * density) < 2 && kotlin.math.abs(field.top - bubble.bottom - 10 * density) < 2
        } } catch (error: AssertionError) {
            saveScreenshot("chief-reply-layout-diagnostic")
            throw AssertionError("Unclipped source thread spacing: $lastGeometry", error)
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
        var previousMaximum = -1f
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            var imeVisible = false
            compose.runOnUiThread { imeVisible = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            val maximum = axis().maxValue()
            val now = android.os.SystemClock.uptimeMillis()
            if (!imeVisible || maximum != previousMaximum) stableSince = now
            previousMaximum = maximum
            imeVisible && maximum > 0 && kotlin.math.abs(axis().value() - maximum) < 1 && now - stableSince >= 350
        }
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

    @Test fun reopeningTheSameLongThreadGetsFreshScrollStateAndStartsAtItsTail() {
        compose.runOnUiThread {
            api.rows = (1..24).map { i -> message("m$i", i.toULong(), "Loaded thread message $i")
                .copy(threadRoot = if (i > 1) "m1" else null) }
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefQuote:m24").fetchSemanticsNodes().isNotEmpty() }
        val reopen = checkNotNull(compose.onNodeWithTag("ChiefQuote:m24").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(reopen()) }
        waitForReplyThread(); compose.waitForIdle()
        fun axis() = compose.onNodeWithTag("ChiefReplyTranscript").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        var previousMaximum = -1f
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            var imeVisible = false
            compose.runOnUiThread { imeVisible = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            val maximum = axis().maxValue()
            val now = android.os.SystemClock.uptimeMillis()
            if (!imeVisible || maximum != previousMaximum) stableSince = now
            previousMaximum = maximum
            imeVisible && maximum > 0 && kotlin.math.abs(axis().value() - maximum) < 1 && now - stableSince >= 350
        }
        compose.onNodeWithTag("ChiefReplyTranscript").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) {
            assertTrue(it(0f, -100 * compose.activity.resources.displayMetrics.density))
        }
        compose.waitUntil(10_000) { axis().value() < axis().maxValue() - 1 }
        val close = checkNotNull(compose.onNodeWithTag("ChiefReplyClose").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { assertTrue(close()); assertTrue(reopen()) }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
            assertEquals("A new source overlay resets to the tail even with identical geometry",
                axis().maxValue(), axis().value(), 1f)
            assertEquals(24, computer.transcript()!!.state.value.reply!!.rows.size)
            assertTrue(api.sends.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun placedChiefFrame(tag: String, useUnmergedTree: Boolean = false): androidx.compose.ui.geometry.Rect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
        val point = node.layoutInfo.coordinates.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
        return androidx.compose.ui.geometry.Rect(point.x, point.y, point.x + node.size.width, point.y + node.size.height)
    }

    private fun replyCopy(id: String): androidx.compose.ui.geometry.Rect {
        val probe = compose.onNodeWithTag("ChiefReplyThread").fetchSemanticsNode().config[ChiefReplyCopyBounds]
        var bounds: androidx.compose.ui.geometry.Rect? = null
        compose.runOnUiThread { bounds = probe()[id] }
        return checkNotNull(bounds)
    }

    private fun chiefRect(rect: androidx.compose.ui.geometry.Rect) = org.json.JSONArray(listOf(rect.left, rect.top, rect.width, rect.height))
    private fun saveChiefGeometry(name: String, value: JSONObject) {
        File(compose.activity.getExternalFilesDir(null), "screenshots/$name.json").writeText(value.toString(2))
    }

    @Test fun composerGrowthDuringReplyEntryKeepsTheSourceGapAndDraft() = checkReplyComposerGrowth(false)
    @Test fun composerGrowthDuringReplyReturnDoesNotKeepOldCopiesAlive() = checkReplyComposerGrowth(true)

    private fun checkReplyComposerGrowth(duringReturn: Boolean) {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        org.junit.Assume.assumeTrue(compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Geometry draft")
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        val reopen = checkNotNull(compose.onNodeWithTag("ChiefQuote:reply").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(reopen()) }
        waitForReplyThread()
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
            val originalComposer = placedChiefFrame("ChiefComposerBar")
            val sourceGap = originalComposer.top - replyCopy("reply").bottom
            val close = checkNotNull(compose.onNodeWithTag("ChiefReplyClose").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
            compose.runOnUiThread { assertTrue(close()); assertTrue(reopen()) }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            val generation = computer.transcript()!!.state.value.reply!!.generation
            val travellingCopy = replyCopy("reply")
            assertTrue("Composer change must interrupt travelling copies", kotlin.math.abs(travellingCopy.bottom - (originalComposer.top - sourceGap)) > 1f)
            val requestClock = compose.mainClock.currentTime
            if (duringReturn) compose.runOnUiThread { assertTrue(close()) }
            val draft = "Geometry draft\nSecond line\nThird line\nFourth line"
            compose.onNodeWithTag("ChiefComposer").performTextReplacement(draft)
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            val grownComposer = placedChiefFrame("ChiefComposerBar")
            assertTrue("The test must cause actual editor growth", grownComposer.height > originalComposer.height + 20 * compose.activity.resources.displayMetrics.density)
            val changedCopy = if (duringReturn) closingCopy("reply") else replyCopy("reply")
            if (!duringReturn) assertEquals(generation, computer.transcript()!!.state.value.reply!!.generation)
            val changedClock = compose.mainClock.currentTime
            compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
            if (duringReturn) {
                compose.onNodeWithTag("ChiefReplyClosing").assertDoesNotExist()
                compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
                assertNull(computer.transcript()!!.state.value.reply)
            } else {
                assertEquals("The bottom bubble must move with the growing composer", sourceGap,
                    placedChiefFrame("ChiefComposerBar").top - replyCopy("reply").bottom, 2f)
            }
            compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains(draft)
            assertTrue(api.sends.isEmpty())
            val name = if (duringReturn) "chief-geometry-composer-return" else "chief-geometry-composer-entry"
            saveScreenshot(name)
            saveChiefGeometry(name, JSONObject().put("duringReturn", duringReturn).put("generation", generation)
                .put("sourceGap", sourceGap).put("oldComposer", chiefRect(originalComposer)).put("newComposer", chiefRect(grownComposer))
                .put("travellingCopy", chiefRect(travellingCopy)).put("changedCopy", chiefRect(changedCopy))
                .put("requestClock", requestClock).put("changedClock", changedClock)
                .put("finalGap", if (duringReturn) JSONObject.NULL else placedChiefFrame("ChiefComposerBar").top - replyCopy("reply").bottom))
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun keyboardHeightChangesRequestedDuringEntryKeepTheReplyAboveTheEditor() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keyboard geometry draft")
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        val reopen = checkNotNull(compose.onNodeWithTag("ChiefQuote:reply").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(reopen()) }
        waitForReplyThread()
        compose.mainClock.autoAdvance = false
        try {
            compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
            val sourceGap = placedChiefFrame("ChiefComposerBar").top - replyCopy("reply").bottom
            val close = checkNotNull(compose.onNodeWithTag("ChiefReplyClose").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
            for (show in listOf(false, true)) {
                compose.runOnUiThread { assertTrue(close()); assertTrue(reopen()) }
                repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
                val generation = computer.transcript()!!.state.value.reply!!.generation
                val before = placedChiefFrame("ChiefComposerBar")
                val travellingCopy = replyCopy("reply")
                assertTrue("IME request must interrupt travelling copies", kotlin.math.abs(travellingCopy.bottom - (before.top - sourceGap)) > 1f)
                val requestClock = compose.mainClock.currentTime
                compose.runOnUiThread {
                    val controller = compose.activity.window.insetsController!!
                    if (show) controller.show(android.view.WindowInsets.Type.ime()) else controller.hide(android.view.WindowInsets.Type.ime())
                }
                compose.waitUntil(10_000) {
                    var visible = false
                    compose.runOnUiThread { visible = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
                    visible == show
                }
                // OS IME animation uses wall time, independently of the held Compose clock.
                android.os.SystemClock.sleep(350)
                repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                assertEquals(generation, computer.transcript()!!.state.value.reply!!.generation)
                compose.mainClock.advanceTimeBy(1600); compose.waitForIdle()
                val after = placedChiefFrame("ChiefComposerBar")
                assertTrue("Real IME height must change the editor's location", kotlin.math.abs(before.top - after.top) > 50 * compose.activity.resources.displayMetrics.density)
                assertEquals(sourceGap, after.top - replyCopy("reply").bottom, 2f)
                compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains("Keyboard geometry draft")
                val name = if (show) "chief-geometry-ime-shown" else "chief-geometry-ime-hidden"
                saveScreenshot(name)
                saveChiefGeometry(name, JSONObject().put("imeVisible", show).put("generation", generation)
                    .put("sourceGap", sourceGap).put("oldComposer", chiefRect(before)).put("newComposer", chiefRect(after))
                    .put("travellingCopy", chiefRect(travellingCopy)).put("finalCopy", chiefRect(replyCopy("reply")))
                    .put("requestClock", requestClock).put("finalGap", after.top - replyCopy("reply").bottom))
            }
            assertTrue(api.sends.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun rtlReplyKeepsTheSourceAbsoluteSenderLanesAndTheHebrewDraft() {
        compose.runOnUiThread { testLayoutDirection = androidx.compose.ui.unit.LayoutDirection.Rtl }
        openReferenceScene("quote-dark")
        val draft = "טיוטה בעברית"
        compose.onNodeWithTag("ChiefComposer").performTextInput(draft)
        settledComposerGeometry()
        compose.onNodeWithTag("ChiefMessages").performScrollToNode(hasTestTag("ChiefQuote:reply"))
        compose.onNodeWithTag("ChiefQuote:reply").performClick()
        waitForReplyThread(); compose.waitForIdle()
        val density = compose.activity.resources.displayMetrics.density
        val viewport = placedChiefFrame("ChiefReplyThread")
        assertEquals(viewport.left + 16 * density, replyCopy("parent").left, 2f)
        assertEquals(viewport.right - 16 * density, replyCopy("reply").right, 2f)
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains(draft)
        val editorViewport = placedChiefFrame("ChiefComposerTextViewport", useUnmergedTree = true)
        val send = placedChiefFrame("ChiefSend")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("ChiefComposer").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val glyphRight = draft.indices.maxOf { layout.getBoundingBox(it).right }
        val cursorRight = layout.getCursorRect(draft.length).right
        saveScreenshot("chief-geometry-rtl-reply")
        saveChiefGeometry("chief-geometry-rtl-reply", JSONObject().put("rtl", true).put("density", density)
            .put("viewport", chiefRect(viewport)).put("incoming", chiefRect(replyCopy("parent"))).put("outgoing", chiefRect(replyCopy("reply")))
            .put("editorViewport", chiefRect(editorViewport)).put("send", chiefRect(send))
            .put("textLayoutWidth", layout.size.width).put("localGlyphRight", glyphRight).put("localCursorRight", cursorRight))
        assertTrue("RTL editor must reserve the physical Send lane: editor=$editorViewport send=$send", editorViewport.right <= send.left)
        assertTrue("The real text layout must fit its reserved viewport", layout.size.width <= editorViewport.width + 1)
        assertTrue("All Hebrew glyphs must fit the text viewport", glyphRight <= editorViewport.width + 1)
        assertTrue("The end cursor must fit the text viewport", cursorRight <= editorViewport.width + 1)
        compose.onNodeWithTag("ChiefReplyClose").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("ChiefComposer").assertIsFocused().assertTextContains(draft)
        assertTrue(api.sends.isEmpty())
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
        val annotated = nativeChiefDetectedText(source, open = opened::add)
        assertEquals(source, annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        val web = links.single { (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url == "https://example.com/path" }
        assertEquals("https://example.com/path", source.substring(web.start, web.end))
        assertTrue(links.any { (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url == "mailto:a@example.org" })
        assertTrue(links.any { (it.item as? androidx.compose.ui.text.LinkAnnotation.Url)?.url?.startsWith("tel:") == true })
        web.item.linkInteractionListener?.onClick(web.item)
        val closing = nativeChiefDetectedText(source, interactive = false, open = opened::add)
        assertEquals(annotated.text, closing.text)
        assertTrue("Retained closing text must have no link hit targets", closing.getLinkAnnotations(0, closing.length).isEmpty())
        assertEquals(links.size, closing.spanStyles.count { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.Underline })
        assertEquals(listOf("https://example.com/path"), opened)
    }

    @Test fun growingFontsWhileFollowingKeepsTheNewestMessageAboveTheComposer() {
        compose.runOnUiThread {
            api.rows = (1..40).map { i -> message("m$i", i.toULong(),
                "Loaded message $i. This sentence wraps when text grows.") }
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        fun tail() = compose.onAllNodesWithTag("ChiefMessage:m40").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        compose.waitUntil(10_000) { tail()?.let { it.bottom <= usableMessagesBounds().bottom + 2 && it.height > 0 } == true }
        val before = checkNotNull(tail())
        compose.runOnUiThread { testFontScale = 2f }
        try {
            compose.waitUntil(6_000) {
                tail()?.let { it.height > before.height + 10 && it.bottom <= usableMessagesBounds().bottom + 2 } == true
            }
        } catch (error: ComposeTimeoutException) {
            saveScreenshot("chief-geometry-font-follow-diagnostic")
            saveChiefGeometry("chief-geometry-font-follow-diagnostic", JSONObject().put("fontScale", testFontScale)
                .put("oldTail", chiefRect(before)).put("newTail", tail()?.let(::chiefRect) ?: JSONObject.NULL)
                .put("usableViewport", chiefRect(usableMessagesBounds())))
            throw AssertionError("Following newest lost its tail after font growth: before=$before after=${tail()} viewport=${usableMessagesBounds()}", error)
        }
        assertTrue(api.sends.isEmpty())
        saveScreenshot("chief-geometry-font-follow")
        saveChiefGeometry("chief-geometry-font-follow", JSONObject().put("fontScale", testFontScale)
            .put("oldTail", chiefRect(before)).put("newTail", chiefRect(checkNotNull(tail())))
            .put("usableViewport", chiefRect(usableMessagesBounds())))
    }

    @Test fun fontGrowthWhileReadingHistoryDoesNotResumeFollowingNewest() {
        compose.runOnUiThread {
            api.rows = (1..40).map { i -> message("m$i", i.toULong(),
                "Loaded message $i. This sentence wraps when text grows.") }
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:m40").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMessages").performScrollToIndex(12)
        val before = placedChiefFrame("ChiefComposerBar")
        compose.runOnUiThread { testFontScale = 2f }
        compose.waitUntil(10_000) { placedChiefFrame("ChiefComposerBar").height > before.height + 10 }
        val viewport = usableMessagesBounds()
        val tail = compose.onAllNodesWithTag("ChiefMessage:m40").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        assertTrue("A font change must not resume following after an admitted history scroll",
            tail == null || tail.bottom <= viewport.top || tail.top >= viewport.bottom)
        assertTrue(api.sends.isEmpty())
        saveScreenshot("chief-geometry-font-history")
        saveChiefGeometry("chief-geometry-font-history", JSONObject().put("fontScale", testFontScale)
            .put("oldComposer", chiefRect(before)).put("newComposer", chiefRect(placedChiefFrame("ChiefComposerBar")))
            .put("newestVisible", false))
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

    @Test fun messageActionsCopyTheCapturedTextAndRetainTheDraftWithSourceMenuMetrics() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep this draft")
        compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefActionMenu").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:REPLY").assertIsDisplayed()
        compose.onNodeWithTag("ChiefMenu:COPY").assertIsDisplayed()
        compose.onNodeWithTag("ChiefMenu:SELECT").assertIsDisplayed()
        compose.onNodeWithTag("ChiefMenu:MORE").assertIsDisplayed()
        compose.onNodeWithTag("ChiefMenu:RETRY").assertDoesNotExist()
        val platter = compose.onNodeWithTag("ChiefMenuPlatter").fetchSemanticsNode().boundsInRoot
        var density = 0f; compose.runOnUiThread { density = compose.activity.resources.displayMetrics.density }
        assertEquals(250f, platter.width / density, .7f)
        assertEquals(202f, platter.height / density, .7f)
        assertEquals(42f, compose.onNodeWithTag("ChiefMenu:COPY").fetchSemanticsNode().boundsInRoot.height / density, .7f)
        saveScreenshot("chief-menu-incoming-mounted")
        compose.onNodeWithTag("ChiefMenu:COPY").performClick()
        compose.onNodeWithTag("ChiefActionMenu").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Keep this draft").assertIsNotFocused()
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("The original message from your Mac.", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        assertTrue(api.sends.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun messageMoreSelectsRowsConfirmsLocalDeleteAndKeepsHistoryTombstoned() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep the selection draft")
        compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:MORE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:MORE").performClick()
        compose.onNodeWithTag("ChiefSelectionCancel").assertIsDisplayed()
        compose.onNodeWithTag("ChiefReplyActions:parent").assertIsSelected()
        saveScreenshot("chief-selection-initial-mounted")
        compose.onNodeWithTag("ChiefReplyActions:reply").performClick().assertIsSelected()
        compose.onNodeWithTag("ChiefComposer").assertDoesNotExist()
        compose.onNodeWithText("Forward").assertDoesNotExist()
        saveScreenshot("chief-selection-mounted")
        compose.onNodeWithTag("ChiefSelectionDelete").performClick()
        compose.onNodeWithText("Delete 2 Messages").assertIsDisplayed()
        compose.onNodeWithTag("ChiefConfirmLocalDelete").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefSelectionToolbar").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("ChiefMessage:parent").assertDoesNotExist()
        compose.onNodeWithTag("ChiefMessage:reply").assertDoesNotExist()
        compose.onNodeWithTag("ChiefMessage:next").assertIsDisplayed()
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Keep the selection draft")
        compose.runOnUiThread { api.transcriptChanges.trySend(api.snapshot()) }
        compose.waitForIdle()
        compose.onNodeWithTag("ChiefMessage:parent").assertDoesNotExist()
        assertTrue(api.sends.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun menuReplyHandsTheLiftedMessageToTheExistingThreadAndRestoresEditorFocus() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Retain my reply draft")
        compose.onNodeWithTag("ChiefReplyActions:reply").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:REPLY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:REPLY").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefActionMenu").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Retain my reply draft").assertIsFocused()
        tapStableAction("ChiefReplyClose")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isEmpty() }
        assertTrue(api.sends.isEmpty())
    }

    @Test fun retiredMenuCannotCopyCapturedMessageTextIntoTheClipboard() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:COPY").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread {
            compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("", "untouched sentinel"))
            current = false
        }
        compose.onNodeWithTag("ChiefMenu:COPY").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals("untouched sentinel", compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("ChiefActionMenu").assertDoesNotExist()
        assertTrue(api.sends.isEmpty())
    }

    @Test fun menuCopyRetainsTheCapturedTextAfterALiveMessageUpdate() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:COPY").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread {
            api.rows = api.rows.map { if (it.id == "parent") it.copy(text = "A newer message body") else it }
            api.transcriptChanges.trySend(api.snapshot())
        }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.any { it.text == "A newer message body" } == true }
        compose.onNodeWithTag("ChiefMenu:COPY").performClick()
        compose.runOnUiThread {
            assertEquals("The original message from your Mac.", compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
        }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun menuCopySurvivesRestingHistoryRetentionButReplyRequiresALiveRow() {
        compose.runOnUiThread { api.readMarker = 1uL; api.homeUnread = 0 }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyActions:hello").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefReplyActions:hello").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:COPY").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread {
            api.rows = api.rows + (2..1501).map { message("m$it", it.toULong(), "Arrival $it") }
            api.transcriptChanges.trySend(api.snapshot())
        }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.size == 1000 }
        compose.runOnUiThread {
            val session = computer.transcript()!!
            assertNull(session.actionItem("s:hello"))
            assertFalse(session.replyTo("hello"))
        }
        compose.onNodeWithTag("ChiefMenu:COPY").performClick()
        compose.onNodeWithTag("ChiefActionMenu").assertDoesNotExist()
        compose.runOnUiThread {
            assertEquals("Welcome from your Mac", compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
        }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun menuRetryRequiresAFailedRowAndReusesTheOriginalClientId() {
        compose.runOnUiThread { api.sendFailure = MobileRpcOutcomeUnknown() }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Explicit menu retry")
        tapSendWhenReady()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.outgoing?.singleOrNull()?.delivery == NativeChiefDelivery.UNCONFIRMED }
        val id = computer.transcript()!!.state.value.outgoing.single().clientMessageId
        val copy = chiefCopyAction("ChiefReplyActions:local:$id")
        compose.runOnUiThread {
            assertTrue(copy.action())
            assertEquals("Explicit menu retry", compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
            assertEquals(1, api.sends.size)
        }
        compose.onNodeWithTag("ChiefReplyActions:local:$id").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:RETRY").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { api.sendFailure = null }
        compose.onNodeWithTag("ChiefMenu:RETRY").performClick()
        compose.waitUntil(10_000) { api.sends.size == 2 && computer.transcript()?.state?.value?.outgoing?.isEmpty() == true }
        assertEquals(listOf("Explicit menu retry" to id, "Explicit menu retry" to id), api.sends.toList())
        compose.onNodeWithTag("ChiefReplyActions:echo").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:COPY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:RETRY").assertDoesNotExist()
        assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun moreCanDeselectEverythingAndCancelWithoutLosingTheDraft() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Draft after Cancel")
        compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:MORE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:MORE").performClick()
        compose.onNodeWithTag("ChiefBack").assertDoesNotExist()
        compose.onNodeWithTag("ChiefReplyActions:parent").performClick().assertIsNotSelected()
        compose.onNodeWithTag("ChiefSelectionDelete").assertIsNotEnabled()
        compose.onNodeWithTag("ChiefSelectionCancel").performClick()
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Draft after Cancel").assertIsNotFocused()
        compose.onNodeWithTag("ChiefBack").assertExists()
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    private fun chiefCopyAction(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Copy" }

    @Test fun accessibilityCopyRetainsCapturedTextAndShowsCopiedState() {
        openReferenceScene("quote-dark")
        val captured = chiefCopyAction("ChiefReplyActions:parent")
        compose.runOnUiThread { assertTrue(captured.action()) }
        compose.onNodeWithTag("ChiefReplyActions:parent").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Message copied"))
        compose.runOnUiThread {
            api.rows = api.rows.map { if (it.id == "parent") it.copy(text = "Updated after accessibility captured the action") else it }
            api.transcriptChanges.trySend(api.snapshot())
        }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.any { it.text.startsWith("Updated after") } == true }
        compose.runOnUiThread {
            assertTrue(captured.action())
            assertEquals("The original message from your Mac.", compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("ChiefActionMenu").assertDoesNotExist()
        compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun accessibilityCopyInMorePreservesSelectionAndDraftForBothSenderLanes() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep my accessibility draft")
        compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:MORE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:MORE").performClick()
        for ((id, expected) in listOf("parent" to "The original message from your Mac.", "reply" to "Replying to that message.")) {
            val action = chiefCopyAction("ChiefReplyActions:$id")
            compose.runOnUiThread {
                assertTrue(action.action())
                assertEquals(expected, compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
            }
            compose.onNodeWithTag("ChiefReplyActions:parent").assertIsSelected()
            compose.onNodeWithTag("ChiefReplyActions:reply").assertIsNotSelected()
            compose.onNodeWithTag("ChiefSelectionCancel").assertExists()
        }
        compose.onNodeWithTag("ChiefSelectionCancel").performClick()
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Keep my accessibility draft")
        val stale = chiefCopyAction("ChiefReplyActions:parent")
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "owner sentinel"))
            current = false
            assertFalse(stale.action())
            assertEquals("owner sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun replyCopyAccessibilityKeepsItsTargetAndRejectsTheClosedPresentation() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep this reply draft")
        compose.onNodeWithTag("ChiefQuote:reply").performClick()
        waitForReplyThread()
        val generation = computer.transcript()!!.state.value.reply!!.generation
        for ((id, expected) in listOf("parent" to "The original message from your Mac.", "reply" to "Replying to that message.")) {
            val action = chiefCopyAction("ChiefThreadMessage:$id")
            compose.runOnUiThread {
                assertTrue(action.action())
                assertEquals(expected, compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
                assertEquals(generation, computer.transcript()!!.state.value.reply!!.generation)
            }
        }
        val stale = chiefCopyAction("ChiefThreadMessage:parent")
        tapStableAction("ChiefReplyClose")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyThread").fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "closed copy sentinel"))
            assertFalse(stale.action())
            assertEquals("closed copy sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Keep this reply draft").assertIsFocused()
        assertTrue(api.sends.isEmpty())
    }

    @Test fun accessibilityCopyUsesTheChiefProjectionAndOmitsOnlyEmptyText() {
        compose.runOnUiThread {
            api.rows = listOf(message("empty", 1uL, ""), message("space", 2uL, " "),
                message("approval", 3uL, "", parts = listOf(NativeChiefPart("approval", null,
                    NativeChiefApproval("request", "Review before running", listOf("allow", "deny"), null), null))))
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyActions:approval").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onNodeWithTag("ChiefReplyActions:empty").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].none { it.label == "Copy" })
        for ((id, expected) in listOf("space" to " ", "approval" to "Review before running")) {
            val action = chiefCopyAction("ChiefReplyActions:$id")
            compose.runOnUiThread {
                assertTrue(action.action())
                assertEquals(expected, compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString())
            }
        }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun moreRejectsHorizontalReplyGesturesUntilSelectionEnds() {
        openReferenceScene("quote-dark")
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep this selection draft")
        compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:MORE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:MORE").performClick()
        val density = compose.activity.resources.displayMetrics.density
        fun swipeRow(id: String) {
            compose.onNodeWithTag("ChiefReplyActions:$id").performTouchInput {
                val start = androidx.compose.ui.geometry.Offset(width / 2f, height - 25 * density)
                swipe(start, start + androidx.compose.ui.geometry.Offset(80 * density, 0f), 350)
            }
        }
        for (id in listOf("parent", "reply")) {
            swipeRow(id)
            compose.runOnUiThread { assertNull("More selection must reject swipe Reply on $id", computer.transcript()!!.state.value.reply) }
            compose.onNodeWithTag("ChiefReplyThread").assertDoesNotExist()
            compose.onNodeWithTag("ChiefReplyActions:parent").assertIsSelected()
            compose.onNodeWithTag("ChiefReplyActions:reply").assertIsNotSelected()
            compose.onNodeWithTag("ChiefSelectionCancel").assertIsDisplayed()
        }
        compose.onNodeWithTag("ChiefSelectionCancel").performClick()
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Keep this selection draft")
        // Same physical gesture must still enter Reply after leaving More.
        swipeRow("parent")
        waitForReplyThread()
        compose.runOnUiThread { assertEquals("parent", computer.transcript()!!.state.value.reply!!.targetID) }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun selectUsesActualNativeTextRangeEndsOnCollapseAndRejectsRetiredClipboardActions() {
        openReferenceScene("quote-dark")
        fun openSelection() {
            compose.onNodeWithTag("ChiefReplyActions:parent").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:SELECT").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ChiefMenu:SELECT").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTextSelection").fetchSemanticsNodes().isNotEmpty() }
        }
        fun findSelection(view: android.view.View): android.widget.TextView? {
            if (view is android.widget.TextView && view.isTextSelectable) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findSelection(view.getChildAt(i))?.let { return it }
            return null
        }
        openSelection()
        compose.runOnUiThread {
            val native = checkNotNull(findSelection(compose.activity.window.decorView))
            assertEquals("The original message from your Mac.", native.text.toString())
            assertEquals(0, native.selectionStart); assertEquals(native.text.length, native.selectionEnd)
            assertTrue(native.onTextContextMenuItem(android.R.id.copy))
            assertEquals(0, native.selectionStart); assertEquals(native.text.length, native.selectionEnd)
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(native.text.toString(), clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        saveScreenshot("chief-text-selection-mounted")
        compose.runOnUiThread {
            val native = checkNotNull(findSelection(compose.activity.window.decorView))
            android.text.Selection.setSelection(native.text as android.text.Spannable, 0)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTextSelection").fetchSemanticsNodes().isEmpty() }
        openSelection()
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", "selection sentinel"))
            current = false
            checkNotNull(findSelection(compose.activity.window.decorView)).onTextContextMenuItem(android.R.id.copy)
            assertEquals("selection sentinel", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTextSelection").fetchSemanticsNodes().isEmpty() }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun pollDetailsIsReadOnlyOmitsAlwaysChoicesAndReflectsLivePendingOptions() {
        compose.runOnUiThread {
            api.rows = listOf(message("approval", 1uL, "", parts = listOf(NativeChiefPart("approval", null,
                NativeChiefApproval("request", "Run deploy?", listOf("allow", "deny", "always:allow"), null), null))))
        }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyActions:approval").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefReplyActions:approval").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:POLL").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:SELECT").assertDoesNotExist()
        compose.onNodeWithTag("ChiefMenu:POLL").performClick()
        compose.onNodeWithTag("ChiefPollDetails").assertIsDisplayed()
        compose.onNode(hasText("Run deploy?") and hasAnyAncestor(hasTestTag("ChiefPollDetails"))).assertIsDisplayed()
        compose.onNodeWithText("always:allow").assertDoesNotExist()
        compose.onAllNodesWithText("No votes yet").assertCountEquals(2)
        compose.runOnUiThread {
            api.rows = api.rows.map { it.copy(partPayloads = listOf(NativeChiefPart("approval", null,
                NativeChiefApproval("request", "Run deploy?", listOf("allow", "review", "deny"), null), null))) }
            api.transcriptChanges.trySend(api.snapshot())
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No votes yet").fetchSemanticsNodes().size == 3 }
        saveScreenshot("chief-poll-details-mounted")
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefPollDetails").fetchSemanticsNodes().isEmpty() }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun moreProtectsSelectedRowsFromTheRestingHistoryWindowUntilCancel() {
        compose.runOnUiThread { api.readMarker = 1uL; api.homeUnread = 0 }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefReplyActions:hello").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefReplyActions:hello").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMenu:MORE").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefMenu:MORE").performClick()
        compose.runOnUiThread {
            api.rows = api.rows + (2..1501).map { message("m$it", it.toULong(), "Arrival $it") }
            api.transcriptChanges.trySend(api.snapshot())
        }
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.size == 1501 }
        compose.runOnUiThread { assertTrue(computer.transcript()!!.state.value.messages.any { it.id == "hello" }) }
        compose.onNodeWithTag("ChiefSelectionCancel").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.size == 1000 }
        compose.runOnUiThread { assertFalse(computer.transcript()!!.state.value.messages.any { it.id == "hello" }) }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    /** Controlled output is explicitly UI evidence, never accepted as native model inference. */
    private inner class TranslationEngine : NativeChiefTranslating {
        var missing = false
        var download: NativeChiefTranslationDownload? = null
        var closed = 0
        var transform: (String) -> String = { "[UI translation] $it" }
        val calls = mutableListOf<Pair<Map<String, String>, String>>()
        override suspend fun availability(source: NativeChiefTranslationLanguage, target: NativeChiefTranslationLanguage) =
            if (missing) NativeChiefTranslationAvailability.DOWNLOADABLE else NativeChiefTranslationAvailability.INSTALLED
        override suspend fun supportedLanguages() = listOf("es", "en", "fr").map { NativeChiefTranslationLanguages.fromTag(it)!! }
        override suspend fun detectLanguage(text: String) = if (text == "unknown") null else NativeChiefTranslationLanguages.fromTag("es")
        override suspend fun translate(texts: Map<String, String>, source: NativeChiefTranslationLanguage,
            target: NativeChiefTranslationLanguage): Map<String, String> {
            calls += texts.toMap() to source.code
            if (missing) {
                download?.request(source, target)
                if (missing) throw NativeChiefTranslationException(NativeChiefTranslationFailure.NOT_DOWNLOADED)
            }
            return texts.mapValues { transform(it.value) }
        }
        override fun close() { closed++ }
    }
    private fun installUiTranslator(engine: TranslationEngine = TranslationEngine()): TranslationEngine {
        compose.runOnUiThread { computer.transcript()!!.installTranslations(engine, NativeChiefTranslationLanguages.fromTag("en")!!) }
        compose.waitForIdle()
        return engine
    }
    private fun openTranslationMenu(id: String) {
        compose.onNodeWithTag("ChiefReplyActions:$id").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefActionMenu").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitTranslationCaption(tag: String, text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == text } == true
        } }
    }
    private fun beginTranslationChoice(id: String) {
        openTranslationMenu(id)
        compose.onNodeWithTag("ChiefMenu:TRANSLATE").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationChoice").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun englishTranslationCaptionsKeepTheirPhysicalSenderEdgeAndGlyphOrderInRtl() {
        compose.runOnUiThread { testLayoutDirection = androidx.compose.ui.unit.LayoutDirection.Rtl }
        openReferenceScene("quote-dark")
        installUiTranslator()
        compose.runOnUiThread { owner.launch {
            computer.transcript()!!.translations.value!!.translateMessage("s:parent")
            computer.transcript()!!.translations.value!!.translateMessage("s:reply")
        } }
        for ((id, outgoing) in listOf("parent" to false, "reply" to true)) {
            val tag = "ChiefTranslationCaption:s:$id"
            awaitTranslationCaption(tag, "Show Original")
            val row = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val glyph = compose.onNodeWithTag("$tag:Glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val text = compose.onNodeWithTag("$tag:Text", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("English translation glyph must precede its text physically", glyph.right < text.left)
            if (outgoing) assertEquals("Outgoing caption must keep the physical right edge", row.right, text.right, 1f)
            else assertEquals("Incoming caption must keep the physical left edge", row.left, glyph.left, 1f)
        }
        saveScreenshot("chief-translation-rtl-physical-caption")
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun translationChoiceCaptionsAndMenuTogglesKeepTheOriginalDraft() {
        openReferenceScene("quote-dark")
        val engine = installUiTranslator()
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep the untranslated draft")
        beginTranslationChoice("parent")
        saveScreenshot("chief-translation-choice-mounted")
        compose.onNodeWithTag("ChiefTranslateMessage").performClick()
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:parent") }
        compose.onNodeWithText("[UI translation] The original message from your Mac.").assertIsDisplayed()
        compose.onNodeWithTag("ChiefTranslationCaption:s:parent").assertIsDisplayed()
        saveScreenshot("chief-translation-message-mounted")
        compose.onNodeWithTag("ChiefTranslationCaption:s:parent").performClick()
        compose.waitUntil(10_000) { !computer.transcript()!!.translations.value!!.isShowingTranslation("s:parent") }
        awaitTranslationCaption("ChiefTranslationCaption:s:parent", "View Translation")
        compose.onNodeWithTag("ChiefTranslationCaption:s:parent").assertTextContains("View Translation")
        openTranslationMenu("parent")
        compose.onNodeWithTag("ChiefMenu:SHOW_TRANSLATION").performClick()
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:parent") }
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Keep the untranslated draft")
        assertEquals(1, engine.calls.size)
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun translatedMenuSelectAndAccessibilityCopyUseDisplayedTextWithoutChangingWireOriginals() {
        openReferenceScene("quote-dark")
        installUiTranslator()
        compose.runOnUiThread { owner.launch { computer.transcript()!!.translations.value!!.translateMessage("s:parent") } }
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:parent") }
        awaitTranslationCaption("ChiefTranslationCaption:s:parent", "Show Original")
        compose.onNodeWithText("[UI translation] The original message from your Mac.").assertIsDisplayed()
        openTranslationMenu("parent")
        // Menu Copy closes over the rendered model at capture, even if the
        // backing row switches to its original while the frozen menu is open.
        compose.runOnUiThread { computer.transcript()!!.translations.value!!.toggleOriginal("s:parent") }
        compose.onNodeWithTag("ChiefMenu:COPY").performClick()
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals("[UI translation] The original message from your Mac.", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.runOnUiThread { computer.transcript()!!.translations.value!!.toggleOriginal("s:parent") }
        awaitTranslationCaption("ChiefTranslationCaption:s:parent", "Show Original")
        openTranslationMenu("parent")
        compose.onNodeWithTag("ChiefMenu:SELECT").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTextSelection").fetchSemanticsNodes().isNotEmpty() }
        fun find(view: android.view.View): android.widget.TextView? {
            if (view is android.widget.TextView && view.isTextSelectable) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        compose.runOnUiThread {
            val selected = checkNotNull(find(compose.activity.window.decorView))
            assertEquals("[UI translation] The original message from your Mac.", selected.text.toString())
            assertTrue(selected.onTextContextMenuItem(android.R.id.copy))
            val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(selected.text.toString(), clipboard.primaryClip!!.getItemAt(0).text.toString())
            android.text.Selection.setSelection(selected.text as android.text.Spannable, 0)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTextSelection").fetchSemanticsNodes().isEmpty() }
        val copy = compose.onNodeWithTag("ChiefReplyActions:parent").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Copy" }
        compose.runOnUiThread {
            assertTrue(copy.action())
            assertEquals("[UI translation] The original message from your Mac.", compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            assertEquals("The original message from your Mac.", computer.transcript()!!.state.value.messages.first { it.id == "parent" }.text)
        }
        saveScreenshot("chief-translation-rendered-copy-mounted")
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun uncertainTranslationUsesTheSourcePickerAndRejectsCapturedOldChoices() {
        compose.runOnUiThread { api.rows = listOf(message("hello", 1uL, "unknown")) }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:hello").fetchSemanticsNodes().isNotEmpty() }
        val engine = installUiTranslator()
        beginTranslationChoice("hello")
        compose.onNodeWithTag("ChiefTranslateMessage").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationFrom").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefTranslateFrom:en").assertDoesNotExist()
        val french = compose.onNodeWithTag("ChiefTranslateFrom:fr").fetchSemanticsNode()
        val spanish = compose.onNodeWithTag("ChiefTranslateFrom:es").fetchSemanticsNode()
        assertTrue(french.boundsInRoot.top < spanish.boundsInRoot.top)
        val stale = checkNotNull(spanish.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        saveScreenshot("chief-translation-from-mounted")
        compose.onNodeWithTag("ChiefTranslateFrom:fr").performClick()
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:hello") }
        compose.runOnUiThread { stale(); assertEquals(listOf("fr"), engine.calls.map { it.second }) }
        assertTrue(api.sends.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun removingTheMessageDismissesItsSourcePickerWithoutTranslatingStaleText() {
        compose.runOnUiThread { api.rows = listOf(message("hello", 1uL, "unknown")) }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:hello").fetchSemanticsNodes().isNotEmpty() }
        val engine = installUiTranslator()
        beginTranslationChoice("hello")
        compose.onNodeWithTag("ChiefTranslateMessage").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationFrom").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { computer.transcript()!!.deleteLocally(setOf("s:hello")) }
        compose.onNodeWithTag("ChiefTranslateFrom:fr").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationFrom").fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { assertTrue(engine.calls.isEmpty()) }
        assertTrue(api.sends.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun automaticTranslationWaitsForModelsRetriesAndStopsWithSourceIndicatorRoom() {
        openReferenceScene("quote-dark")
        val engine = installUiTranslator(TranslationEngine().apply { missing = true })
        compose.onNodeWithTag("ChiefComposer").performTextInput("Keep the automatic translation draft")
        beginTranslationChoice("parent")
        compose.onNodeWithTag("ChiefTranslateConversation").performClick()
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.state.value.indicator == NativeChiefTranslationIndicator.WAITING_FOR_DOWNLOAD }
        compose.onNodeWithTag("ChiefTranslationIndicator").assertIsDisplayed()
        val indicator = compose.onNodeWithTag("ChiefTranslationIndicator").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("ChiefComposerBar").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(32f, indicator.height / density, .7f)
        assertEquals(6f, (composer.top - indicator.bottom) / density, .7f)
        saveScreenshot("chief-translation-waiting-mounted")
        val attempts = engine.calls.size
        compose.runOnUiThread {
            api.rows = api.rows + message("arrival", 4uL, "Another automatic message")
            api.transcriptChanges.trySend(api.snapshot())
        }
        compose.waitUntil(10_000) { computer.transcript()!!.state.value.messages.any { it.id == "arrival" } }
        compose.runOnUiThread { assertEquals(attempts, engine.calls.size); engine.missing = false }
        compose.onNodeWithTag("ChiefTranslationIndicator").performClick()
        compose.onNodeWithTag("ChiefTranslationRetryAutomatic").performClick()
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:arrival") }
        compose.onNodeWithTag("ChiefTranslationIndicator").performClick()
        compose.onNodeWithTag("ChiefTranslationStop").performClick()
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.state.value.indicator == null }
        compose.onNodeWithTag("ChiefTranslationIndicator").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(computer.transcript()!!.translations.value!!.state.value.presentations.isEmpty()) }
        compose.onNodeWithTag("ChiefComposer").assertTextEquals("Keep the automatic translation draft")
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun translatedConversationRetainsItsModelAcrossNavigationAndRetiresWithTheComputer() {
        openReferenceScene("quote-dark")
        val engine = installUiTranslator()
        compose.runOnUiThread { owner.launch { computer.transcript()!!.translations.value!!.translateMessage("s:parent") } }
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:parent") }
        val retained = computer.transcript()!!.translations.value!!
        compose.onNodeWithTag("ChiefBack").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefPinnedConversation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationCaption:s:parent").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread {
            assertSame(retained, computer.transcript()!!.translations.value); assertEquals(0, engine.closed)
            computer.close()
            assertTrue(retained.state.value.retired); assertTrue(retained.state.value.presentations.isEmpty())
            assertEquals(1, engine.closed)
        }
        assertEquals(1, engine.calls.size)
    }

    @Test fun replyCopiesUseDisplayedTextForCopyAndRejectClosedCaptionActions() {
        openReferenceScene("quote-dark")
        installUiTranslator()
        compose.runOnUiThread { owner.launch {
            val model = computer.transcript()!!.translations.value!!
            model.translateMessage("s:parent"); model.translateMessage("s:reply")
        } }
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:reply") }
        compose.onNodeWithTag("ChiefQuote:reply").performClick()
        waitForReplyThread()
        compose.onNodeWithText("[UI translation] The original message from your Mac.").assertIsDisplayed()
        val copy = compose.onNodeWithTag("ChiefThreadMessage:parent").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions].single { it.label == "Copy" }
        compose.runOnUiThread {
            assertTrue(copy.action())
            assertEquals("[UI translation] The original message from your Mac.", compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        val caption = compose.onNodeWithTag("ChiefThreadTranslationCaption:s:parent")
        val stale = checkNotNull(caption.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        caption.performClick()
        compose.waitUntil(10_000) { !computer.transcript()!!.translations.value!!.isShowingTranslation("s:parent") }
        saveScreenshot("chief-translation-reply-copies-mounted")
        compose.onNodeWithTag("ChiefReplyClose").performClick()
        compose.runOnUiThread { stale(); assertFalse(computer.transcript()!!.translations.value!!.isShowingTranslation("s:parent")) }
        assertTrue(api.sends.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun translationKeepsThePinnedPollQuestionAndOptionsWhileCopyUsesItsPresentedMessageText() {
        compose.runOnUiThread { api.rows = listOf(message("hello", 1uL, "original wire payload", parts = listOf(
            NativeChiefPart("approval", null, NativeChiefApproval("request", "Review before running", listOf("allow", "deny"), null), null)))) }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:hello").fetchSemanticsNodes().isNotEmpty() }
        val engine = installUiTranslator()
        compose.runOnUiThread { owner.launch { computer.transcript()!!.translations.value!!.translateMessage("s:hello") } }
        awaitTranslationCaption("ChiefTranslationCaption:s:hello", "Show Original")
        compose.onNodeWithText("Review before running").assertIsDisplayed()
        compose.onNodeWithText("allow").assertIsDisplayed()
        compose.onNodeWithText("deny").assertIsDisplayed()
        compose.onNodeWithText("[UI translation] original wire payload").assertDoesNotExist()
        openTranslationMenu("hello")
        compose.onNodeWithTag("ChiefMenu:COPY").performClick()
        compose.runOnUiThread {
            assertEquals("[UI translation] original wire payload", compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            assertEquals("original wire payload", engine.calls.single().first.values.single())
            assertEquals("original wire payload", computer.transcript()!!.state.value.messages.single().text)
        }
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun decliningAutomaticDownloadDefersQueuedRowsWithoutAnotherConsentDialog() {
        openReferenceScene("quote-dark")
        val session = computer.transcript()!!
        val download = NativeChiefTranslationDownload { current && !session.state.value.retired }
        val engine = TranslationEngine().apply { missing = true; this.download = download }
        compose.runOnUiThread { session.installTranslations(engine, NativeChiefTranslationLanguages.fromTag("en")!!,
            host = NativeChiefTranslationPlatformHost(download) { error("A declined offer must not launch settings") }) }
        beginTranslationChoice("parent")
        compose.onNodeWithTag("ChiefTranslateConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationDownload").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { session.translations.value!!.state.value.indicator == NativeChiefTranslationIndicator.WAITING_FOR_DOWNLOAD }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        compose.onNodeWithTag("ChiefTranslationDownload").assertDoesNotExist()
        compose.runOnUiThread { assertEquals(1, engine.calls.size); assertNull(download.state.value) }
        compose.onNodeWithTag("ChiefTranslationIndicator").assertIsDisplayed()
        assertTrue(api.sends.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun productionTranslationDownloadHostDeclinesAndWaitsForARealSettingsReturn() {
        openReferenceScene("quote-dark")
        val session = computer.transcript()!!
        val download = NativeChiefTranslationDownload { current && !session.state.value.retired }
        val engine = TranslationEngine().apply { missing = true; this.download = download }
        var settingsLaunches = 0
        compose.runOnUiThread {
            session.installTranslations(engine, NativeChiefTranslationLanguages.fromTag("en")!!, host = NativeChiefTranslationPlatformHost(download) {
                settingsLaunches++
                compose.activity.getSystemService(android.view.translation.TranslationManager::class.java).onDeviceTranslationSettingsActivityIntent
            })
        }
        beginTranslationChoice("parent")
        compose.onNodeWithTag("ChiefTranslateMessage").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationDownload").fetchSemanticsNodes().isNotEmpty() }
        saveScreenshot("chief-translation-download-admission")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { session.translations.value!!.state.value.presentations["s:parent"]?.caption == NativeChiefTranslationCaption.NOT_DOWNLOADED }
        assertEquals(0, settingsLaunches)
        awaitTranslationCaption("ChiefTranslationCaption:s:parent", "Language not downloaded")
        compose.onNodeWithTag("ChiefTranslationCaption:s:parent").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTranslationOpenSettings").fetchSemanticsNodes().isNotEmpty() }
        val confirm = checkNotNull(compose.onNodeWithTag("ChiefTranslationOpenSettings").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(confirm()) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg("com.google.android.as").depth(0)), 10_000))
        instrumentation.runOnMainSync {
            assertEquals(1, settingsLaunches)
            assertTrue(download.state.value!!.settingsOpen)
            assertTrue(download.externalDeparted)
        }
        val directory = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "chief-translation-production-settings.png")))
        device.pressBack()
        assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(instrumentation.targetContext.packageName).depth(0)), 10_000))
        compose.waitUntil(10_000) { session.translations.value!!.state.value.presentations["s:parent"]?.caption == NativeChiefTranslationCaption.NOT_DOWNLOADED }
        compose.runOnUiThread { assertNull(download.state.value); assertNull(download.externalRequest) }
        awaitTranslationCaption("ChiefTranslationCaption:s:parent", "Language not downloaded")
        compose.onNodeWithTag("ChiefTranslationCaption:s:parent").assertTextContains("Language not downloaded")
        saveScreenshot("chief-translation-download-returned")
        assertEquals(2, engine.calls.size)
        assertTrue(api.sends.isEmpty()); assertTrue(api.approvals.isEmpty()); assertTrue(api.answers.isEmpty())
    }

    @Test fun longTranslatedTailReflowFollowsNewestAndKeepsHistoryReadingIntent() {
        compose.runOnUiThread { api.rows = (1..40).map { message("m$it", it.toULong(), "Original message $it") }; api.readMarker = 40uL }
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefMessage:m40").fetchSemanticsNodes().isNotEmpty() }
        val engine = installUiTranslator(TranslationEngine().apply { transform = { "[UI translation] A long translated message for reflow. ".repeat(80) } })
        compose.runOnUiThread { owner.launch { computer.transcript()!!.translations.value!!.translateMessage("s:m40") } }
        compose.waitUntil(10_000) { computer.transcript()!!.translations.value!!.isShowingTranslation("s:m40") }
        compose.waitUntil(10_000) {
            val caption = compose.onAllNodesWithTag("ChiefTranslationCaption:s:m40").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val composer = compose.onNodeWithTag("ChiefComposerBar").fetchSemanticsNode().boundsInRoot
            caption != null && caption.height > 0 && caption.bottom <= composer.top
        }
        saveScreenshot("chief-translation-long-tail-mounted")
        compose.runOnUiThread { computer.transcript()!!.translations.value!!.toggleOriginal("s:m40"); engine.transform = { "[UI translation] $it" } }
        compose.waitForIdle()
        swipeMessagesDown()
        val viewport = usableMessagesBounds()
        val nodes = compose.onAllNodes(SemanticsMatcher("message row") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("ChiefMessage:m") == true
        }).fetchSemanticsNodes()
        val anchor = nodes.filter { it.boundsInRoot.height > 0 && it.boundsInRoot.top >= viewport.top && it.boundsInRoot.bottom <= viewport.bottom }.minBy { it.boundsInRoot.top }
        val tag = anchor.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
        compose.runOnUiThread { owner.launch { computer.transcript()!!.translations.value!!.translateMessage("s:m40") } }
        compose.waitForIdle()
        assertEquals(anchor.boundsInRoot.top, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top, 1f)
        assertTrue(api.sends.isEmpty()); assertTrue(api.answers.isEmpty())
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
