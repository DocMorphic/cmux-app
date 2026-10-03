package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.ClipData
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DebugLogMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private fun copied() = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    @Test fun nativePickerCopiesTheCurrentVisibleTextOnlyOnRequest() {
        var reads = 0
        var visible by mutableStateOf("initial text")
        compose.setContent { CmuxTheme { CompositionLocalProvider(LocalDebugTerminalText provides { reads++; visible }) {
            NativePanePicker("Terminal", emptyList(), null, onSelect = {})
        } } }
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.runOnIdle { assertEquals(0, reads); visible = "Visible terminal λ 中\n" + "x".repeat(40_000) }
        compose.onNodeWithTag("copy-debug-logs").performScrollTo().performClick()
        compose.waitUntil(5000) { copied().contains("Visible terminal λ 中") }
        compose.runOnIdle {
            assertEquals(1, reads)
            assertTrue(copied().contains(context.packageName))
            assertTrue(copied().contains("Installed update:"))
            assertTrue(copied().contains("[Visible text truncated at 32,000 characters]"))
            assertTrue(copied().length < 130_000)
            assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
            assertFalse(MobileDebugLog.snapshot().contains("Visible terminal λ 中"))
        }
        compose.onNodeWithTag("copy-debug-logs").assertDoesNotExist()
    }
    @Test fun sshPickerUsesTheSharedDebugActionWithoutChangingSelection() {
        var selected = false
        compose.setContent { CmuxTheme { SshPanePicker("SSH", SshPickerLayout(emptyList()), null, true, onSelect = { selected = true }) } }
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "before")) }
        val operation = MobileDebugLog.begin(DebugOperation.SSH_CONNECT)
        MobileDebugLog.finish(operation, DebugOutcome.SUCCESS)
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithTag("copy-debug-logs").assertIsDisplayed()
        val bitmap = compose.onNode(isPopup()).captureToImage().asAndroidBitmap()
        val dir = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(dir, "ssh-copy-debug-logs.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.onNodeWithTag("copy-debug-logs").performClick()
        compose.waitUntil(5000) { copied().contains("SSH_CONNECT SUCCESS") }
        assertFalse(selected)
        assertFalse(copied().contains("Visible terminal\n"))
    }
    @Test fun dismissingPendingCopyCancelsItAndKeepsTheClipboard() {
        val gate = CompletableDeferred<String>()
        var cancelled = false
        compose.setContent { CmuxTheme { CompositionLocalProvider(LocalDebugLogSource provides {
            try { gate.await() } finally { cancelled = true }
        }) { NativePanePicker("Terminal", emptyList(), null, onSelect = {}) } } }
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "keep clipboard")) }
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithTag("copy-debug-logs").performClick()
        compose.onNodeWithTag("copy-debug-logs").assertIsNotEnabled()
        androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        compose.waitUntil(5000) { cancelled }
        gate.complete("late logs")
        compose.runOnIdle { assertEquals("keep clipboard", copied()) }
    }
}
