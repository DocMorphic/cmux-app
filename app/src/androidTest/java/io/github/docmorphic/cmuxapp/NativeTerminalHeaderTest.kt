package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeTerminalHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pickerUsesLiveInventoryGroupsPanesAndMarksSelectedTerminal() {
        val first = NativeTerminal("first", "Shell")
        val second = NativeTerminal("second", "Build")
        val simulator = NativeSimulator("sim", "workspace", "iPhone Simulator", null, null, "ready",
            true, true, true, true, true, null, null)
        var workspace by mutableStateOf(NativeWorkspace("workspace", "Fixture", listOf(first, second),
            null, false, null, null, false, listOf(NativeBrowser("web", "Preview"), NativeBrowser("sim", "Duplicate simulator")),
            null, null, null, surfaces = listOf(NativeSurface("notes", "markdown", "Notes")), simulators = listOf(simulator)))
        var selected by mutableStateOf(first)
        var chosenSurface: String? = null
        var chosenBrowser: String? = null
        compose.setContent { CmuxTheme {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF0A0B0D), contentColor = Color.White) { Column {
            NativeTerminalHeader(selected, workspace, 1, emptySet(), true, false, {},
                { chosenSurface = it.id }, {}, {}, {}, {}, { chosenBrowser = it.id }, { selected = it })
            } }
        } }
        fun open() = compose.onNodeWithTag("terminal-picker").performClick()
        open()
        compose.onNodeWithText("Terminals").assertExists()
        compose.onNodeWithTag("terminal-picker-terminal-first").assertIsSelected()
        compose.onNodeWithTag("terminal-picker-terminal-second").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals(second, selected) }
        open()
        compose.onNodeWithTag("terminal-picker-terminal-second").assertIsSelected()
        compose.runOnIdle { workspace = workspace.copy(terminals = listOf(second.copy(title = "Renamed build"))) }
        compose.onNodeWithTag("terminal-picker-terminal-first").assertDoesNotExist()
        compose.onNodeWithText("Renamed build").assertExists()
        compose.onNodeWithText("Mac Surfaces").assertExists()
        compose.onNodeWithText("Mac Simulators").assertExists()
        compose.onNodeWithText("Mac Browsers").assertExists()
        compose.onNodeWithText("Duplicate simulator").assertDoesNotExist()
        compose.onNodeWithText("Notes").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("notes", chosenSurface) }
        open()
        compose.onNodeWithText("iPhone Simulator").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("sim", chosenSurface) }
        open()
        compose.onNodeWithText("Preview").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("web", chosenBrowser) }
        open()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithText("Renamed build").assertIsDisplayed()
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(Until.hasObject(By.text("Renamed build")), 5_000))
        device.waitForIdle(1_000)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "terminal-picker-groups.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
}
