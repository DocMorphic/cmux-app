package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class TerminalImageRenderingTest {
    private val cells = TerminalCellMetrics(20f, 40f, 28f)
    private val black = "\u001b]11;#000000\u0007\u001b[?25l"
    private fun terminal() = (ghosttyTerminalFactory(cells)(10, 3) as GhosttyVtTerminal).also { it.write(black) }
    private fun GhosttyVtTerminal.write(text: String) = append(text.toByteArray())
    private fun image(options: String, pixels: ByteArray = byteArrayOf()) =
        "\u001b_G$options;${Base64.getEncoder().encodeToString(pixels)}\u001b\\"
    private fun draw(painter: TerminalGridPainter, terminal: TerminalDisplay, position: Double = 0.0): Bitmap {
        val viewport = TerminalScrollViewport.at(position, terminal.historyLineCount, terminal.activeScreen)
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        painter.draw(canvas, 200f, 120f, terminal, TerminalGridPainter.plan(viewport.lines(terminal)), cells,
            viewport.rowOffset, true, viewport.topClipFraction)
        assertEquals(1, canvas.saveCount)
        return bitmap
    }
    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pngAlphaCropOffsetsAndPlacementScalingReachCanvas() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            val source = Bitmap.createBitmap(intArrayOf(Color.RED, 0x8000ff00.toInt()), 2, 1, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            source.recycle()
            terminal.write(image("a=T,f=100,i=1,p=1,c=2,r=1,x=1,w=1,X=4,Y=5,C=1", png))
            val bitmap = draw(painter, terminal)
            assertEquals(Color.BLACK, bitmap.getPixel(2, 10))
            assertEquals(Color.BLACK, bitmap.getPixel(10, 3))
            assertEquals(Color.BLACK, bitmap.getPixel(45, 10))
            val pixel = bitmap.getPixel(20, 20)
            assertEquals(0, Color.red(pixel)); assertEquals(0, Color.blue(pixel))
            assertTrue(Color.green(pixel) in 127..129)
            save(bitmap, "terminal-image-alpha.png"); bitmap.recycle()
        } }
    }

    @Test fun threeZLayersRespectExplicitBackgroundTextAndDefaultTransparency() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            terminal.write("\u001b[41m███\u001b[0m\u001b[H")
            terminal.write(image("a=t,f=24,s=1,v=1,i=1", byteArrayOf(0, 0, -1)))
            listOf(Int.MIN_VALUE, -1, 0, Int.MIN_VALUE).forEachIndexed { column, z ->
                terminal.write("\u001b[1;${column + 1}H" + image("a=p,i=1,p=${column + 1},c=1,r=1,z=$z,C=1"))
            }
            val bitmap = draw(painter, terminal)
            // Corners expose the cell background, centers expose the block glyph.
            assertTrue(Color.red(bitmap.getPixel(1, 1)) > 100)
            assertEquals(Color.BLUE, bitmap.getPixel(21, 1))
            assertTrue(Color.red(bitmap.getPixel(10, 20)) > 100)
            assertTrue(Color.red(bitmap.getPixel(30, 20)) > 100)
            assertEquals(Color.BLUE, bitmap.getPixel(50, 20))
            assertEquals(Color.BLUE, bitmap.getPixel(70, 20))
            save(bitmap, "terminal-image-layers.png"); bitmap.recycle()
        } }
    }

    @Test fun replacementDeletionAndCloseNeverPaintStalePixels() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            terminal.write(image("a=T,f=24,s=1,v=1,i=1,p=1,c=1,r=1,C=1", byteArrayOf(-1, 0, 0)))
            val first = draw(painter, terminal)
            assertEquals(Color.RED, first.getPixel(10, 20)); first.recycle()
            terminal.write(image("a=T,f=24,s=1,v=1,i=1,p=1,c=1,r=1,C=1", byteArrayOf(0, 0, -1)))
            val replaced = draw(painter, terminal)
            assertEquals(Color.BLUE, replaced.getPixel(10, 20)); replaced.recycle()
            terminal.write(image("a=d,d=I,i=1"))
            val deleted = draw(painter, terminal)
            assertEquals(Color.BLACK, deleted.getPixel(10, 20)); deleted.recycle()
            terminal.write(image("a=T,f=24,s=1,v=1,i=1,p=1,c=1,r=1,C=1", byteArrayOf(0, -1, 0)))
            draw(painter, terminal).recycle()
            terminal.close()
            val closed = draw(painter, terminal)
            assertEquals(Color.GREEN, closed.getPixel(10, 20)); closed.recycle()
        } }
    }

    @Test fun fractionalHistoryShowsImageInExtraBottomRowAndClipsToViewport() {
        terminal().use { terminal -> TerminalGridPainter().use { painter ->
            terminal.write("\r\n\r\n\r\n\u001b[3;1H")
            terminal.write(image("a=T,f=24,s=1,v=1,i=1,p=1,c=1,r=1,C=1", byteArrayOf(0, 0, -1)))
            assertEquals(1, terminal.historyLineCount)
            val viewport = TerminalScrollViewport.at(.5, terminal.historyLineCount)
            assertEquals(3f, TerminalKeyboardLayout.contentBottomRows(terminal, viewport,
                graphics = terminal.graphicsSnapshot(viewport.rowOffset, cells))!!)
            val bitmap = draw(painter, terminal, 0.5)
            assertEquals(Color.BLACK, bitmap.getPixel(10, 90))
            assertEquals(Color.BLUE, bitmap.getPixel(10, 110))
            save(bitmap, "terminal-image-fractional-history.png"); bitmap.recycle()
        } }
    }

    @Test fun byteReplayReplacementReconstructsImagePixelsThroughProductionMirror() {
        TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.BYTES, false), TerminalViewport(10, 3),
            ghosttyTerminalFactory(cells)).use { mirror -> TerminalGridPainter().use { painter ->
            fun replay(pixels: ByteArray, seq: Int) = JSONObject().put("snapshot_data_b64", Base64.getEncoder().encodeToString(
                (black + image("a=T,f=32,s=1,v=1,i=1,p=1,c=1,r=1,C=1", pixels)).toByteArray())).put("seq", seq)
            mirror.replay(replay(byteArrayOf(-1, 0, 0, -1), 1))
            val before = draw(painter, mirror.display)
            assertEquals(Color.RED, before.getPixel(10, 20)); before.recycle()
            mirror.beginReplay()
            mirror.replay(replay(byteArrayOf(0, 0, -1, -1), 2))
            val after = draw(painter, mirror.display)
            assertEquals(Color.BLUE, after.getPixel(10, 20)); after.recycle()
        } }
    }
}
