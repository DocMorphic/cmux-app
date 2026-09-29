package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class GhosttyMirrorTest {
    @Test fun capturedVimSessionRendersThroughNativeEngineAndReturnsToShell() {
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("vim-session.json").bufferedReader().use { it.readText() })
        GhosttyVtTerminal(fixture.getInt("columns"), fixture.getInt("rows")).use { terminal ->
            terminal.append("shell baseline".toByteArray())
            fun feed(key: String) {
                Base64.getDecoder().decode(fixture.getString(key)).toList().chunked(7)
                    .forEach { terminal.append(it.toByteArray()) }
            }
            fun text() = RenderGrid.plainText(terminal.visibleLines())
            feed("opened_b64")
            assertEquals("alternate", terminal.activeScreen)
            assertTrue(text().contains("cmux terminal fixture"))
            assertTrue(text().contains("日本語"))
            feed("edited_b64")
            assertTrue(text().startsWith("Edited cmux terminal fixture"))
            feed("exited_b64")
            assertEquals("primary", terminal.activeScreen)
            assertTrue(text().contains("shell baseline"))
        }
    }

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private fun replay(value: String, seq: Int) = JSONObject().put("snapshot_data_b64", b64(value)).put("seq", seq)
    private fun bytes(value: String, seq: Int) = JSONObject().put("surface_id", "s").put("data_b64", b64(value)).put("seq", seq)
    private fun text(mirror: TerminalStreamMirror) = RenderGrid.plainText(mirror.display.visibleLines())
    private fun grid(value: String, seq: Int, revision: Int, screen: String = "primary") = JSONObject()
        .put("format", "cmux.render-grid.v1").put("surface_id", "s").put("columns", 20).put("rows", 4)
        .put("full", true).put("render_epoch", "e").put("render_revision", revision).put("state_seq", seq)
        .put("active_screen", screen).put("cursor", JSONObject().put("row", 0).put("column", value.length).put("visible", true))
        .put("row_spans", JSONArray().put(JSONObject().put("row", 0).put("column", 0).put("text", value).put("cell_width", value.length)))

    @Test fun nativeMirrorRecoversByteGapsAndReseedsHybridPrimary() {
        TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.HYBRID, false), TerminalViewport(20, 4), ::GhosttyVtTerminal).use { mirror ->
            mirror.replay(JSONObject().put("render_grid", grid("prompt", 10, 1)))
            mirror.bytes(bytes("!", 10)); mirror.bytes(bytes("!", 10))
            assertEquals("prompt!", text(mirror))
            mirror.grid(grid("alternate", 20, 2, "alternate"))
            assertTrue(mirror.display is RenderGrid)
            mirror.bytes(bytes("ignored", 20))
            assertEquals("alternate", text(mirror))
            mirror.grid(grid("returned", 40, 3))
            assertTrue(mirror.display is GhosttyVtTerminal)
            mirror.bytes(bytes("!", 40))
            assertEquals("returned!", text(mirror))
            assertEquals(TerminalStreamMirror.Result.REPLAY, mirror.bytes(bytes("gap", 50)))
            mirror.replay(replay("recovered", 53))
            assertEquals("recovered", text(mirror))
            mirror.bytes(bytes("!", 53))
            assertEquals("recovered!", text(mirror))
        }
    }

    @Test fun adapterPreservesStylesAndClosedDisplayCanFinishPainting() {
        val terminal = GhosttyVtTerminal(12, 3)
        terminal.append("\u001b[?2027h\u001b[4:3;58;2;255;0;0me\u0301🧑‍💻".toByteArray())
        val line = terminal.visibleLines()[0]
        assertEquals("e\u0301", line[0].text)
        assertEquals("🧑‍💻", line[1].text)
        assertEquals(2, line[1].width)
        assertEquals(3, line[0].style.underlineStyle)
        assertEquals("#ff0000", line[0].style.underlineColor)
        terminal.close(); terminal.close()
        assertEquals(line, terminal.visibleLines()[0])
        assertEquals(12, terminal.columns)
        assertThrows(IllegalStateException::class.java) { terminal.append(byteArrayOf(65)) }
    }

    @Test fun nativeUnderlineVariantsProduceDistinctColoredPixels() {
        GhosttyVtTerminal(8, 6).use { terminal ->
            val output = buildString {
                append("\u001b]11;#000000\u0007\u001b[?25l")
                for (style in 1..5) {
                    if (style == 5) append("\u001b[2m")
                    append("\u001b[${style};1H\u001b[4:$style;58;2;255;0;0m        ")
                }
                append("\u001b[0m")
            }
            terminal.append(output.toByteArray())
            val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            TerminalGridPainter().draw(canvas, 160f, 240f, terminal,
                TerminalGridPainter.plan(terminal.visibleLines()), TerminalCellMetrics(20f, 40f, 28f), 0, true)
            val masks = (0..4).map { row -> buildString {
                for (y in row * 40 until row * 40 + 40) for (x in 0 until 160) {
                    val pixel = bitmap.getPixel(x, y)
                    append(if (Color.red(pixel) > 100 && Color.green(pixel) < 30 && Color.blue(pixel) < 30) '1' else '0')
                }
            } }
            assertTrue(masks.all { it.count { pixel -> pixel == '1' } > 50 })
            assertEquals(5, masks.toSet().size)
            assertTrue((160 until 200).flatMap { y -> (0 until 160).map { x -> Color.red(bitmap.getPixel(x, y)) } }.max() in 100..150)
            assertEquals(Color.BLACK, bitmap.getPixel(10, 210))
            assertEquals(1, canvas.saveCount)
            val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(directory, "ghostty-underlines.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
