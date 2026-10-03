package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class TerminalPlaceholderRenderingTest {
    private val cells = TerminalCellMetrics(20f, 40f, 28f)
    private val placeholder = String(Character.toChars(0x10EEEE))
    private val pixels = byteArrayOf(-1,0,0, 0,0,-1, -1,0,0, 0,0,-1, 0,-1,0, -1,-1,-1, 0,-1,0, -1,-1,-1)
    private fun GhosttyVtTerminal.write(value: String) = append(value.toByteArray())
    private fun terminal() = (ghosttyTerminalFactory(cells)(10, 3) as GhosttyVtTerminal).also {
        it.write("\u001b]11;#000000\u0007\u001b[?25l\u001b[?2027h")
    }
    private fun image(id: Long = 1, placement: Int = 1) =
        "\u001b_Ga=T,f=24,s=2,v=4,i=$id,p=$placement,U=1,c=2,r=2;${Base64.getEncoder().encodeToString(pixels)}\u001b\\"
    private fun draw(painter: TerminalGridPainter, terminal: GhosttyVtTerminal, position: Double = 0.0): Bitmap {
        val viewport = TerminalScrollViewport.at(position, terminal.historyLineCount, terminal.activeScreen)
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        painter.draw(canvas, 200f, 120f, terminal, TerminalGridPainter.plan(viewport.lines(terminal)), cells,
            viewport.rowOffset, true, viewport.topClipFraction)
        assertEquals(1, canvas.saveCount)
        return bitmap
    }

    @Test fun nativePlaceholderRunsPaintAllFragmentsWithoutFallbackGlyphs() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            terminal.write(image(0x01000011, 23))
            terminal.write("\u001b[38;2;0;0;17;58;2;0;0;23m" + placeholder + "\u0305\u0305\u030d" + placeholder +
                "\r\n" + placeholder + "\u030d\u0305\u030d" + placeholder)
            val bitmap = draw(painter, terminal)
            assertEquals(Color.RED, bitmap.getPixel(5, 10)); assertEquals(Color.BLUE, bitmap.getPixel(35, 10))
            assertEquals(Color.GREEN, bitmap.getPixel(5, 70)); assertEquals(Color.WHITE, bitmap.getPixel(35, 70))
            assertTrue(terminal.visibleLines().flatten().any { it.text.startsWith(placeholder) }) // Copy text stays intact.
            for (y in 0 until 80) for (x in 0 until 40) assertNotEquals("No colored tofu at $x,$y", 0xff000011.toInt(), bitmap.getPixel(x, y))
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(directory, "terminal-placeholder-fragments.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } }
    }

    @Test fun textOnlyFragmentChangesInvalidateGeometryAndDeleteLeavesBlankCells() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            terminal.write(image() + "\u001b[31;58;5;1m" + placeholder + "\u0305\u0305")
            val generation = terminal.graphicsSnapshot(0, cells)!!.frame.generation
            val first = draw(painter, terminal)
            assertEquals(Color.RED, first.getPixel(5, 10)); first.recycle()
            terminal.write("\u001b[H" + placeholder + "\u0305\u030d")
            assertEquals(generation, terminal.graphicsSnapshot(0, cells)!!.frame.generation)
            val updated = draw(painter, terminal)
            assertEquals(Color.BLUE, updated.getPixel(15, 10)); updated.recycle()
            terminal.write("\u001b_Ga=d,d=I,i=1;\u001b\\")
            val deleted = draw(painter, terminal)
            for (y in 0 until 40) for (x in 0 until 20) assertEquals(Color.BLACK, deleted.getPixel(x, y))
            deleted.recycle()
        } }
    }

    @Test fun fractionalScrollResolvesPlaceholdersInExtraBottomRow() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            terminal.write(image() + "\r\n\r\n\r\n\u001b[3;1H\u001b[31;58;5;1m" + placeholder + "\u0305\u0305")
            val bitmap = draw(painter, terminal, 0.5)
            assertEquals(Color.BLACK, bitmap.getPixel(5, 90))
            assertEquals(Color.RED, bitmap.getPixel(5, 110)); bitmap.recycle()
        } }
    }

    @Test fun onePixelImageSplitAcrossLargerGridUsesTextureEdgeClamping() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            terminal.write("\u001b_Ga=T,f=24,s=1,v=1,i=1,p=1,U=1,c=4,r=2;AP8A\u001b\\")
            terminal.write("\u001b[31;58;5;1m" + placeholder + "\u0305\u0310\r\n" + placeholder + "\u030d\u0310")
            val bitmap = draw(painter, terminal)
            assertEquals(Color.GREEN, bitmap.getPixel(5, 20)); assertEquals(Color.GREEN, bitmap.getPixel(5, 60))
            bitmap.recycle()
        } }
    }
}
