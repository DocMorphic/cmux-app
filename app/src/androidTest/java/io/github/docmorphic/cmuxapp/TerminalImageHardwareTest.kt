package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Base64

@OptIn(ExperimentalTestApi::class)
class TerminalImageHardwareTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun keyboardHoldRetainsImagePixelsThroughCacheReplacementUntilRedraw() {
        val cells = TerminalCellMetrics(20f, 40f, 28f)
        val terminal = ghosttyTerminalFactory(cells)(10, 3) as GhosttyVtTerminal
        val revision = mutableIntStateOf(0)
        val presentation = TerminalKeyboardPresentation()
        val full = TerminalViewport(10, 3); val short = TerminalViewport(10, 2)
        fun image(pixels: ByteArray) = "\u001b_Ga=T,f=24,s=1,v=1,i=1,p=1,c=2,r=2,C=1;" +
            Base64.getEncoder().encodeToString(pixels) + "\u001b\\"
        fun pixel() = compose.onNodeWithTag("held-image").captureToImage().asAndroidBitmap().getPixel(10, 20)
        try {
            terminal.append(("\u001b[?1049h\u001b]11;#000000\u0007\u001b[?25l" +
                image(byteArrayOf(-1, 0, 0))).toByteArray())
            presentation.transition(true, false, 0, full)
            presentation.reportPublished(1, full); presentation.reportConfirmed(1); presentation.outputApplied(1, 0)
            compose.setContent { RenderGridView(terminal, cells, revision.intValue,
                Modifier.size(200.dp).testTag("held-image"),
                displayGeometry = TerminalGeometry(1f, 20f, 40f, 0f, 0f, 10, 3), keyboardPresentation = presentation) }
            assertEquals(Color.RED, pixel())
            compose.runOnIdle {
                presentation.transition(true, true, 120, short); presentation.reportPublished(2, short)
                terminal.append(image(byteArrayOf(0, -1, 0)).toByteArray()); revision.intValue = 1
                presentation.outputApplied(2, 1)
            }
            assertEquals("Replacing the painter's cache cannot mutate the held image", Color.RED, pixel())
            compose.runOnIdle {
                presentation.reportConfirmed(2)
                terminal.append(image(byteArrayOf(0, 0, -1)).toByteArray()); revision.intValue = 2
                presentation.outputApplied(2, 2)
            }
            assertEquals(Color.RED, pixel())
            compose.runOnIdle { presentation.transition(true, false, 120, short) }
            assertEquals(Color.BLUE, pixel())
        } finally { compose.runOnIdle { terminal.close() } }
    }

    @Test fun hardwareCanvasPreservesFragmentMatricesAndReplacesCachedImagePixels() {
        val cells = TerminalCellMetrics(20f, 40f, 28f)
        val terminal = ghosttyTerminalFactory(cells)(10, 3) as GhosttyVtTerminal
        val revision = mutableIntStateOf(0)
        val placeholder = String(Character.toChars(0x10EEEE))
        fun image(bytes: ByteArray) = "\u001b_Ga=T,f=24,s=2,v=4,i=1,p=1,U=1,c=2,r=2;${Base64.getEncoder().encodeToString(bytes)}\u001b\\"
        val original = byteArrayOf(-1,0,0, 0,0,-1, -1,0,0, 0,0,-1, 0,-1,0, -1,-1,-1, 0,-1,0, -1,-1,-1)
        try {
            terminal.append(("\u001b]11;#000000\u0007\u001b[?25l\u001b[?2027h" + image(original) +
                "\u001b[31;58;5;1m" + placeholder + "\u0305\u0305" + placeholder + "\r\n" +
                placeholder + "\u030d\u0305" + placeholder).toByteArray())
            compose.setContent { RenderGridView(terminal, cells, revision.intValue, Modifier.fillMaxSize().testTag("images")) }
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(compose.activity.window.decorView.isHardwareAccelerated) }
            val initial = compose.onNodeWithTag("images").captureToImage().asAndroidBitmap()
            val x = (initial.width - 200) / 2; val y = (initial.height - 120) / 2
            assertEquals(Color.RED, initial.getPixel(x + 5, y + 10))
            assertEquals(Color.BLUE, initial.getPixel(x + 35, y + 10))
            assertEquals(Color.GREEN, initial.getPixel(x + 5, y + 70))
            assertEquals(Color.WHITE, initial.getPixel(x + 35, y + 70))
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(directory, "terminal-placeholder-hardware.png").outputStream().use { initial.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val cyan = ByteArray(24) { if (it % 3 == 0) 0 else -1 }
            compose.runOnIdle {
                terminal.append(image(cyan).toByteArray())
                assertArrayEquals(cyan, terminal.graphicsSnapshot(0, cells)!!.frame.images.getValue(1).pixels)
                revision.intValue++
            }
            compose.waitForIdle()
            val replaced = compose.onNodeWithTag("images").captureToImage().asAndroidBitmap()
            assertEquals(Color.CYAN, replaced.getPixel(x + 5, y + 10))
            assertEquals(Color.CYAN, replaced.getPixel(x + 35, y + 70))
        } finally { compose.runOnIdle { terminal.close() } }
    }
}
