package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalTextSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(view: View): TextView? = if (view.tag == "terminal-text-snapshot") view as TextView else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { text(group.getChildAt(it)) } }
    private fun snapshotView(): TextView? {
        // The sheet owns a separate window; use Compose's native root rather than the Activity decor.
        val roots = android.view.inspector.WindowInspector.getGlobalWindowViews()
        return roots.firstNotNullOfOrNull(::text)
    }

    private fun waitForText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().size == 1 }
    }

    @Test fun loadingCanBeDismissedAndLateResultCannotReopenTheSheet() {
        val result = CompletableDeferred<TerminalTextSnapshot>()
        var open by mutableStateOf(true)
        val source = TerminalTextSource({ true }) { result.await() }
        compose.setContent { CmuxTheme { if (open) TerminalTextSheet(source) { open = false } } }
        compose.onNodeWithText("Copy All").assertIsNotEnabled()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { result.complete(TerminalTextSnapshot("Late result", false, 5000)) }
        compose.onNodeWithText("Terminal Text").assertDoesNotExist()
    }

    @Test fun changedOwnerNeverPublishesPendingTextAndFailedReadsCanRetry() {
        val result = CompletableDeferred<TerminalTextSnapshot>()
        var current = true
        var source by mutableStateOf(TerminalTextSource({ current }) { result.await() })
        compose.setContent { CmuxTheme { TerminalTextSheet(source) {} } }
        compose.runOnIdle { current = false; result.complete(TerminalTextSnapshot("Retired terminal", false, 5000)) }
        waitForText("This terminal changed. Close this sheet and open its text again.")
        compose.onNodeWithText("This terminal changed. Close this sheet and open its text again.").assertExists()
        compose.onNodeWithText("Copy All").assertIsNotEnabled()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        var attempts = 0
        compose.runOnIdle { source = TerminalTextSource({ true }) {
            if (attempts++ == 0) throw java.io.IOException("Fixture failure")
            TerminalTextSnapshot("Retried terminal", false, 5000)
        } }
        waitForText("Could not read terminal text. Try again.")
        compose.onNodeWithText("Could not read terminal text. Try again.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(10_000) { compose.runOnIdle { snapshotView()?.text?.toString() == "Retried terminal" } }
        compose.onNodeWithText("Copy All").assertIsEnabled()
        compose.runOnIdle { assertEquals("Retried terminal", snapshotView()?.text?.toString()); assertEquals(2, attempts) }
    }

    @Test fun realNativeCaptureIsSelectableAndCopiesLogicalCommandWithoutChangingClipboardUntilRequested() {
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val previous = clipboard.primaryClip
        val terminal = GhosttyVtTerminal(20, 4)
        try {
            val command = "echo '" + "日本語 λ ".repeat(24) + "'"
            terminal.append(command.toByteArray())
            val source = terminalTextSource(terminal) { true }
            compose.setContent { CmuxTheme { TerminalTextSheet(source) {} } }
            compose.waitUntil(10_000) { compose.runOnIdle { snapshotView() != null } }
            compose.onNodeWithText("Copy All").assertIsEnabled()
            compose.runOnIdle {
                val view = checkNotNull(snapshotView())
                assertTrue(view.isTextSelectable); assertEquals(command, view.text.toString())
                assertEquals(previous?.getItemAt(0)?.text?.toString(), clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().also { image ->
                compose.activity.openFileOutput("terminal-logical-copy.png", android.content.Context.MODE_PRIVATE).use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            compose.onNodeWithText("Copy All").performClick()
            compose.onNodeWithText("Copied").assertExists()
            compose.runOnIdle { assertEquals(command, clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        } finally {
            compose.activityRule.scenario.close(); terminal.close()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }
}
