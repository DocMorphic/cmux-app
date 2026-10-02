package io.github.docmorphic.cmuxapp

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real account + MainActivity, opt-in physical only. Never run store-clearing fixture setup here. */
@OptIn(ExperimentalTestApi::class)
class LiveNativeUiCheck {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test fun createdTerminalComposerKeyboardAndReopen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cmux_live_ui_fixture") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk")) { "Physical device required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the physical device" }
        var stage = "existing account"
        var scenario: ActivityScenario<MainActivity>? = null
        var activity: MainActivity? = null
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
                    connections.connector.allowsSaved(it) && PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh
                }.single()
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
                check(workspace.title == title) { "The host did not preserve the unique fixture title" }
                workspace
            } }

            stage = "launch real MainActivity"
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                activity = it
                // Temporary Activity flag only; no persistent device sleep or app preference change.
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            stage = "find created workspace in real list"
            compose.waitUntil(30_000) { compose.onAllNodesWithText(created.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(created.title).performClick()
            stage = "real UI lazy terminal startup"
            compose.waitUntil(35_000) { compose.onAllNodesWithTag("native-terminal").fetchSemanticsNodes().isNotEmpty() }
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
            val suffix = UUID.randomUUID().toString().take(8)
            val marker = "CMUX_UI_" + suffix
            stage = "send through production composer"
            // Framework text injection into the focused composer, not a claim of tapping Gboard keys.
            compose.onNode(hasSetTextAction()).performTextInput("printf '%s%s\\n' 'CMUX_UI_' '$suffix'")
            compose.onNodeWithText("Send").assertIsEnabled().performClick()
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
            stage = "return to workspaces and reopen terminal"
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(created.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(created.title).performClick()
            compose.waitUntil(25_000) { markerLines() == 1 }
            screenshot("terminal-reopened.png")
            check(connections.store.taskSession() == login && connections.account.isSignedIn())
            println("CMUX_LIVE_UI_REPORT " + JSONObject().put("openedCreatedWorkspace", true)
                .put("terminalStartedInMainActivity", true).put("imeVisible", true)
                .put("terminalHeightBeforeIme", before).put("terminalHeightWithIme", after)
                .put("composerOutputRendered", true).put("reopenedOutput", true).put("loginPreserved", true))
        } catch (problem: Throwable) {
            // Compose errors can dump private workspace semantics; retain only fixed stage/class labels.
            failure = AssertionError("Live UI check failed at $stage (${problem.javaClass.simpleName})")
        } finally {
            try { scenario?.close() }
            catch (problem: Throwable) {
                if (failure == null) failure = AssertionError("Live UI Activity cleanup failed (${problem.javaClass.simpleName})")
            }
            val fixture = owned
            if (fixture != null && client != null) runBlocking { withContext(NonCancellable) {
                try { withTimeout(15_000) {
                    checkNotNull(client).closeWorkspace(fixture.id, fixture.windowId)
                    while (parseAuthoritativeWorkspaces(checkNotNull(client).workspaces()).any { it.id == fixture.id }) delay(250)
                    closed = true
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
