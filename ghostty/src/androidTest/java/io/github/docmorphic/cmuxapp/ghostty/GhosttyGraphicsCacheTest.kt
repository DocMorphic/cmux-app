package io.github.docmorphic.cmuxapp.ghostty

import org.junit.Assert.*
import org.junit.Test

class GhosttyGraphicsCacheTest {
    private fun GhosttyTerminal.write(text: String) = append(text.toByteArray())
    private val red = "\u001b_Ga=T,f=24,s=1,v=1,i=1,p=1,c=1,r=1,C=1;/wAA\u001b\\"
    private val green = "\u001b_Ga=T,f=24,s=1,v=1,i=1,p=1,c=1,r=1,C=1;AP8A\u001b\\"

    @Test fun unchangedPixelsAreReusedWhilePlacementGeometryStillUpdates() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.resize(10, 3, 10, 20)
            terminal.write(red)
            val before = terminal.graphicsSnapshot()
            before.images.getValue(1).pixels.fill(0)
            terminal.write("text\r\nnext\r\nlast\r\nscroll")
            val after = terminal.graphicsSnapshot()
            assertSame(before.images.getValue(1), after.images.getValue(1))
            assertArrayEquals(byteArrayOf(-1, 0, 0), after.images.getValue(1).pixels)
            assertEquals(-1, after.placements.single().row)
            val history = terminal.graphicsSnapshot(1)
            assertSame(before.images.getValue(1), history.images.getValue(1))
            assertEquals(0, history.placements.single().row)
            terminal.resize(10, 3, 12, 24)
            val resized = terminal.graphicsSnapshot(1)
            assertSame(before.images.getValue(1), resized.images.getValue(1))
            assertEquals(12L, resized.placements.single().pixelWidth)
            terminal.close()
            assertArrayEquals(byteArrayOf(-1, 0, 0), before.images.getValue(1).pixels)
        }
    }

    @Test fun replacementAndAlternateScreensNeverReuseAnOldGeneration() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.resize(10, 3, 10, 20)
            terminal.write(red)
            val original = terminal.graphicsSnapshot().images.getValue(1)
            terminal.write(green)
            val replacement = terminal.graphicsSnapshot().images.getValue(1)
            assertNotSame(original, replacement)
            assertArrayEquals(byteArrayOf(0, -1, 0), replacement.pixels)
            terminal.write("\u001b[?1049h" + red)
            val alternate = terminal.graphicsSnapshot().images.getValue(1)
            assertNotEquals(replacement.generation, alternate.generation)
            assertArrayEquals(byteArrayOf(-1, 0, 0), alternate.pixels)
            terminal.write("\u001b[?1049l")
            assertEquals(replacement.generation, terminal.graphicsSnapshot().images.getValue(1).generation)
            assertArrayEquals(byteArrayOf(0, -1, 0), terminal.graphicsSnapshot().images.getValue(1).pixels)
            terminal.write("\u001b_Ga=d,d=I,i=1;\u001b\\")
            assertTrue(terminal.graphicsSnapshot().images.isEmpty())
        }
    }
}
