package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class NativeDisplaySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsSelectionReachesProductionTerminalReplayWithNormalAndroidDispatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Synthetic account tests require the disposable emulator"
        }
        val preferences = context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
        val keys = listOf(NativeDisplayPreferences.wrapKey, NativeDisplayPreferences.previewKey, NativeDisplayPreferences.scrollbackKey)
        val peer = NativeFixturePeer()
        try {
            keys.forEach { preferences.edit().remove(it).commit() }
            NativeCredentialStore(context).clear()
            NativeCredentialStore(context).update {
                it.put("refresh_token", "emulator-fixture-only")
                it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
            }
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("cmux settings").performClick()
            compose.onNodeWithTag("settings.wrap-titles").performScrollTo().performClick()
            compose.onNodeWithTag("settings.preview-lines").performScrollTo().performClick()
            compose.onNodeWithText("1 Line").performClick()
            compose.onNodeWithTag("settings.scrollback").performScrollTo().performClick()
            compose.onNodeWithText(java.text.NumberFormat.getIntegerInstance().format(20_000) + " Rows").performClick()
            compose.waitUntil { NativeDisplayPreferences.read(preferences) == NativeDisplayPreferences(true, 1, 20_000) }
            compose.onNodeWithTag("settings.scrollback").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                java.text.NumberFormat.getIntegerInstance().format(20_000) + " Rows"))
            compose.waitForIdle()
            val folder = File(context.getExternalFilesDir(null), "display-settings").apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                File(folder, "production-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText("‹  Back").performScrollTo().performClick()
            compose.onNodeWithText("Claude Code task").performClick()
            try {
                compose.waitUntil(15_000) { compose.onAllNodesWithText("cmux Android terminal", substring = true).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                File(folder, "terminal-failure.txt").writeText("Methods: " + peer.requests.map { it.optString("method") } +
                    "\nPeer failures: " + peer.failures + "\n" + MobileDebugLog.snapshot() + "\n" + compose.onRoot().printToString())
                throw failure
            }
            assertEquals(20_000, peer.requests.first { it.optString("method") == "mobile.terminal.replay" }
                .getJSONObject("params").getInt("max_scrollback_rows"))
        } finally {
            compose.activity.finish(); peer.close()
            NativeCredentialStore(context).clear()
            keys.forEach { preferences.edit().remove(it).commit() }
        }
    }

    @Test fun realRowsWrapAndReserveChosenPreviewHeightAndPreferencesSurviveRemount() {
        val name = "display-test-${UUID.randomUUID()}"
        val preferences = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        val title = "A long workspace title that must wrap across several lines on a narrow phone"
        val workspace = NativeWorkspace("display", title, emptyList(), null, false, null, null, false,
            emptyList(), null, "First preview line\nSecond preview line\nThird hidden line", null,
            description = "Workspace description")
        var generation by mutableIntStateOf(0)
        var current = NativeDisplayPreferences()
        try {
            compose.setContent { CmuxTheme { Surface { key(generation) {
                current = rememberNativeDisplayPreferences(preferences)
                Column(Modifier.width(360.dp)) {
                    NativeDisplaySettings(preferences, current)
                    NativeWorkspaceRow(workspace, emptyList(), false, displayPreferences = current, onOpen = {}, onAction = { _, _ -> })
                }
            } } } }
            fun layout(tag: String): TextLayoutResult {
                val result = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                return result.single()
            }
            assertEquals(1, layout("workspace.title:display").lineCount)
            assertTrue(layout("workspace.title:display").isLineEllipsized(0))
            assertEquals(2, layout("workspace.preview:display").lineCount)
            compose.onNodeWithTag("settings.wrap-titles").performClick()
            compose.waitUntil { current.wrapTitles }
            assertTrue(layout("workspace.title:display").lineCount > 1)
            assertFalse(layout("workspace.title:display").hasVisualOverflow)
            compose.onNodeWithTag("settings.preview-lines").performClick()
            compose.onNodeWithText("1 Line").performClick()
            compose.waitUntil { current.previewLines == 1 }
            assertEquals(1, layout("workspace.preview:display").lineCount)
            val i = InstrumentationRegistry.getInstrumentation()
            val folder = File(i.targetContext.getExternalFilesDir(null), "display-settings").apply { mkdirs() }
            i.uiAutomation.takeScreenshot().let { bitmap ->
                File(folder, "wrapped-row.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.runOnIdle { generation++ }
            compose.waitForIdle()
            assertEquals(NativeDisplayPreferences(true, 1, 4_000), current)
            assertTrue(layout("workspace.title:display").lineCount > 1)
            // Corrupt/old values must resolve deterministically without a ClassCastException.
            compose.runOnIdle {
                preferences.edit().putString(NativeDisplayPreferences.wrapKey, "wrong type")
                    .putInt(NativeDisplayPreferences.previewKey, 90)
                    .putInt(NativeDisplayPreferences.scrollbackKey, Int.MAX_VALUE).commit()
            }
            compose.waitUntil { current == NativeDisplayPreferences(false, 2, 20_000) }
        } finally { compose.activity.deleteSharedPreferences(name) }
    }
}
