package io.github.docmorphic.cmuxapp

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import android.util.TypedValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real account + MainActivity, opt-in physical only. Never run store-clearing fixture setup here. */
@OptIn(ExperimentalTestApi::class)
class LiveNativeUiCheck {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test fun createdTerminalComposerKeyboardAndReopen() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_live_ui_fixture") == "true")
        val build = args.getString("cmux_live_build")
        require(build == null || build in setOf("stable", "nightly"))
        val gboard = args.getString("cmux_live_gboard") == "true"
        val sharedSizing = args.getString("cmux_live_shared_sizing") == "true"
        require(!sharedSizing || build == "nightly") { "Shared sizing requires explicit NIGHTLY selection" }
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk")) { "Physical device required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the physical device" }
        check(!File(context.filesDir, "live-ui-fixture.json").exists()) { "Inspect the previous UI fixture receipt before rerunning" }
        var stage = "existing account"
        var activity: MainActivity? = null
        var selectedCode: String? = null
        var client: MobileRpcClient? = null
        var owned: NativeWorkspace? = null
        var creationAttempted = false
        var closed = false
        var failure: Throwable? = null
        val handle = NativeAppConnections.acquire(context)
        val connections = handle.connections
        val probe = Any()
        connections.setProbeActive(probe, true)
        try {
            val login = checkNotNull(connections.store.taskSession())
            val title = "Android UI check " + UUID.randomUUID().toString().take(8)
            val created = runBlocking { withTimeout(60_000) {
                check(connections.account.isSignedIn())
                val team = checkNotNull(connections.teams.refresh().scope)
                val mac = connections.store.pairedMacs().filter {
                    connections.connector.allowsSaved(it) && PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh &&
                        (build == null || it.instanceTag == build)
                }.single()
                selectedCode = mac.code
                stage = "native connection"
                val active = connections.connector.connectSaved(mac, connections.account).also { client = it }
                mac.requireMatchingHost(active.hostStatus())
                check(connections.teams.isCurrent(team))
                val existing = parseAuthoritativeWorkspaces(active.workspaces()).map { it.id }.toSet()
                stage = "create disposable UI workspace"
                creationAttempted = true
                val workspace = TaskCreationResult.parse(active.request("workspace.create",
                    JSONObject().put("title", title), timeoutMillis = 30_000)).created
                check(workspace.id !in existing)
                owned = workspace
                // Durable private ownership receipt survives an interrupted test runner.
                File(context.filesDir, "live-ui-fixture.json").writeText(JSONObject()
                    .put("id", workspace.id).put("windowId", workspace.windowId)
                    .put("title", title).put("build", mac.instanceTag).put("deviceId", mac.deviceId)
                    .put("accountUserId", team.userId).put("accountTeamId", team.teamId).toString())
                check(workspace.title == title) { "The host did not preserve the unique fixture title" }
                workspace
            } }

            stage = "launch real MainActivity"
            // MainActivity clears a consumed pairing URI; ActivityScenario's
            // Intent matching would then miss lifecycle events during cleanup.
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(checkNotNull(selectedCode)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync {
                // Temporary Activity flag only; no persistent device sleep or app preference change.
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            stage = "find created workspace in real list"
            compose.waitUntil(30_000) { compose.onAllNodesWithText(created.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(created.title).performClick()
            stage = "real UI lazy terminal startup"
            compose.waitUntil(35_000) { compose.onAllNodesWithTag("native-terminal").fetchSemanticsNodes().isNotEmpty() }
            fun awaitHostViewport(): Pair<Int, Int> {
                val node = compose.onNodeWithTag("native-terminal").fetchSemanticsNode()
                val font = checkNotNull(node.config.getOrNull(SemanticsProperties.StateDescription))
                    .removePrefix("Terminal font size ").toFloat()
                val metrics = context.resources.displayMetrics
                val cells = TerminalCellMetrics.fromFontSize(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, font, metrics), 2f * metrics.density)
                var keyboardOverlap = 0
                compose.runOnUiThread {
                    val insets = ViewCompat.getRootWindowInsets(checkNotNull(activity).window.decorView)
                    keyboardOverlap = ((insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) -
                        (insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0)).coerceAtLeast(0)
                }
                // This fixture stays on the primary shell screen: keyboard motion
                // changes its visible area but must preserve the natural PTY grid.
                val expected = checkNotNull(TerminalViewport.fit(node.boundsInRoot.width.toInt(),
                    node.boundsInRoot.height.toInt() + keyboardOverlap, cells))
                val terminal = checkNotNull(created.terminals.firstOrNull())
                val latest = AtomicReference<Pair<Int, Int>?>(null)
                val sharedSizing = AtomicReference(false)
                val polling = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val result = polling.async {
                    withTimeout(15_000) {
                        while (true) {
                            val replay = checkNotNull(client).request("mobile.terminal.replay", JSONObject()
                                .put("workspace_id", created.id).put("surface_id", terminal.id)
                                .put("anchor", "screen").put("max_scrollback_rows", 0))
                            val frame = replay.optJSONObject("render_grid") ?: replay
                            val actual = frame.optInt("columns") to frame.optInt("rows")
                            latest.set(actual)
                            // NIGHTLY shared sizing includes the Mac's natural viewport.
                            // Prove our sole mobile participant reported the exact phone
                            // geometry AND the host grid equals the authoritative minimum.
                            // The probe deliberately sends no client/viewport fields.
                            val sizing = replay.optJSONObject("size_state")
                            val settled = if (sizing == null) actual == (expected.columns to expected.rows) else {
                                check(sizing.getJSONObject("policy").getString("mode") == "smallest")
                                val array = sizing.getJSONArray("participants")
                                val participants = (0 until array.length()).map { array.getJSONObject(it) }
                                val mobile = participants.filter { it.optString("id").startsWith("mobile:") }
                                check(mobile.size <= 1) { "Unexpected extra mobile viewport on owned fixture" }
                                val reported = mobile.singleOrNull()?.optJSONObject("viewport")
                                val counting = participants.filter { it.optBoolean("counts") }.mapNotNull { it.optJSONObject("viewport") }
                                val negotiated = sizing.getInt("cols") to sizing.getInt("rows")
                                val minimum = if (counting.isEmpty()) null else counting.minOf { it.getInt("cols") } to counting.minOf { it.getInt("rows") }
                                (reported?.optInt("cols") == expected.columns && reported.optInt("rows") == expected.rows &&
                                    negotiated == minimum && actual == negotiated).also { if (it) sharedSizing.set(true) }
                            }
                            if (settled)
                                return@withTimeout actual
                            delay(250)
                        }
                        @Suppress("UNREACHABLE_CODE") error("unreachable")
                    }
                }
                try {
                    // Keep advancing Compose effects while real networking runs off-thread.
                    // runBlocking here starves the rule's StandardTestDispatcher.
                    compose.waitUntil(20_000) { result.isCompleted }
                    return runBlocking { result.await() }
                } finally {
                    polling.cancel()
                    runBlocking { result.join() }
                    println("CMUX_LIVE_UI_VIEWPORT " + JSONObject()
                        .put("expectedColumns", expected.columns).put("expectedRows", expected.rows)
                        .put("actualColumns", latest.get()?.first).put("actualRows", latest.get()?.second)
                        .put("sharedSizingVerified", sharedSizing.get()))
                }
            }
            stage = "settled host viewport before keyboard"
            val beforeGrid = awaitHostViewport()
            val before = compose.onNodeWithTag("native-terminal").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.height
            stage = "show physical keyboard"
            compose.onNode(hasSetTextAction()).performClick()
            compose.waitUntil(15_000) {
                var visible = false
                compose.runOnUiThread { visible = ViewCompat.getRootWindowInsets(checkNotNull(activity).window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                visible
            }
            compose.waitUntil(10_000) {
                compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height < before - 100
            }
            val after = compose.onNodeWithTag("native-terminal").fetchSemanticsNode().boundsInRoot.height
            stage = "settled host viewport with keyboard"
            val keyboardGrid = awaitHostViewport()
            check(keyboardGrid == beforeGrid) { "Primary keyboard entry changed the host grid" }
            val suffix = UUID.randomUUID().toString().take(8)
            val marker = "CMUX_UI_" + suffix
            stage = "send through production composer"
            // Framework text injection into the focused composer, not a claim of tapping Gboard keys.
            compose.onNode(hasSetTextAction()).performTextInput("printf '%s%s\\n' 'CMUX_UI_' '$suffix'")
            compose.onNodeWithTag("native.composer.send").assertIsEnabled().performClick()
            fun markerLines(): Int = compose.onAllNodesWithTag("native-terminal").fetchSemanticsNodes()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .sumOf { text -> text.text.lineSequence().count { it.trim() == marker } }
            stage = "render actual command output"
            compose.waitUntil(25_000) { markerLines() == 1 }
            fun screenshot(name: String) {
                val directory = File(context.getExternalFilesDir(null), "live-ui-check").apply { mkdirs() }
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            screenshot("terminal-keyboard.png")
            if (gboard) {
                stage = "real Gboard direct input"
                val device = UiDevice.getInstance(instrumentation)
                compose.onNodeWithText("Keyboard", useUnmergedTree = true).performClick()
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Compose").fetchSemanticsNodes().isNotEmpty() }
                val keyboard = "com.google.android.inputmethod.latin"
                check(device.wait(Until.hasObject(By.pkg(keyboard)), 10_000))
                // Retain a private UI diagnostic if this installed keyboard uses
                // different accessibility labels. Never infer key coordinates.
                val dir = File(context.getExternalFilesDir(null), "live-ui-check").apply { mkdirs() }
                device.dumpWindowHierarchy(File(dir, "gboard-direct-private.xml"))
                fun tap(label: String) {
                    val key = checkNotNull(device.findObject(By.pkg(keyboard).desc(label))) {
                        "Expected Gboard key is unavailable"
                    }
                    key.click()
                    compose.waitForIdle()
                }
                for (letter in "echo") tap(letter.toString())
                tap("Space")
                for (letter in "cmuxgboard") tap(letter.toString())
                tap("Enter")
                compose.waitUntil(25_000) {
                    compose.onAllNodesWithTag("native-terminal").fetchSemanticsNodes()
                        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                        .sumOf { text -> text.text.lineSequence().count { it.trim() == "cmuxgboard" } } == 1
                }
                screenshot("terminal-gboard-direct.png")
                // Restore the initial compose mode before the existing reopen/grid check.
                compose.onNodeWithText("Compose", useUnmergedTree = true).performClick()
                println("CMUX_LIVE_UI_GBOARD " + JSONObject().put("realImeKeyTaps", true)
                    .put("directTerminalOutputExactlyOnce", true))
            }
            stage = "return to workspaces and reopen terminal"
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(created.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(created.title).performClick()
            compose.waitUntil(25_000) { markerLines() == 1 }
            stage = "settled host viewport after reopening"
            val reopenedGrid = awaitHostViewport()
            check(reopenedGrid == beforeGrid)
            screenshot("terminal-reopened.png")
            if (sharedSizing) LiveTerminalSizingScenario(compose, checkNotNull(client), created,
                { stage = it }, ::screenshot).run()
            check(connections.store.taskSession() == login && connections.account.isSignedIn())
            println("CMUX_LIVE_UI_REPORT " + JSONObject().put("openedCreatedWorkspace", true)
                .put("terminalStartedInMainActivity", true).put("imeVisible", true)
                .put("terminalHeightBeforeIme", before).put("terminalHeightWithIme", after)
                .put("hostColumnsBeforeIme", beforeGrid.first).put("hostRowsBeforeIme", beforeGrid.second)
                .put("hostColumnsWithIme", keyboardGrid.first).put("hostRowsWithIme", keyboardGrid.second)
                .put("hostColumnsReopened", reopenedGrid.first).put("hostRowsReopened", reopenedGrid.second)
                .put("composerOutputRendered", true).put("reopenedOutput", true).put("loginPreserved", true))
        } catch (problem: Throwable) {
            // Compose errors can dump private workspace semantics; retain only fixed stage/class labels.
            failure = AssertionError("Live UI check failed at $stage (${problem.javaClass.simpleName})")
        } finally {
            try {
                instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
                if (activity != null) compose.waitUntil(20_000) { activity.lifecycle.currentState == Lifecycle.State.DESTROYED }
                compose.waitForIdle()
            }
            catch (problem: Throwable) {
                if (failure == null) failure = AssertionError("Live UI Activity cleanup failed (${problem.javaClass.simpleName})")
            }
            val fixture = owned
            if (fixture != null && client != null) runBlocking { withContext(NonCancellable) {
                try { withTimeout(15_000) {
                    val receipt = File(context.filesDir, "live-ui-fixture.json")
                    receipt.writeText(JSONObject(receipt.readText()).put("cleanupAttempted", true).toString())
                    checkNotNull(client).closeWorkspace(fixture.id, fixture.windowId)
                    while (parseAuthoritativeWorkspaces(checkNotNull(client).workspaces()).any { it.id == fixture.id }) delay(250)
                    closed = true
                    check(receipt.delete())
                } } catch (_: Exception) { /* Never repeat an uncertain close. */ }
            } }
            client?.close()
            connections.setProbeActive(probe, false)
            handle.close()
        }
        println("CMUX_LIVE_UI_CLEANUP " + JSONObject().put("creationAttempted", creationAttempted).put("fixtureClosed", closed))
        failure?.let { throw it }
        check(closed) { "Live UI fixture cleanup was not verified" }
    }
}
