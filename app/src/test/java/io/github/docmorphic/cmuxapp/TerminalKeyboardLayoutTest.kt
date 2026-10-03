package io.github.docmorphic.cmuxapp

import androidx.compose.ui.unit.IntSize
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class TerminalKeyboardLayoutTest {
    private fun fixture() = JSONObject(GZIPInputStream(javaClass.getResourceAsStream("/terminal-layout-0fc35d6.json.gz")!!)
        .bufferedReader().readText())

    @Test fun keyboardAbsorptionAndRevealMatchUnmodifiedIos() {
        val cases = fixture().getJSONArray("keyboard_cases")
        assertEquals(36, cases.length())
        val base = TerminalGeometry(1f, 10f, 20f, 0f, 0f, 40, 40)
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val rows = if (item.isNull("blank")) null else (800f - item.getDouble("blank").toFloat()) / 20f
            val layout = TerminalKeyboardLayout(base, 800f, 800f - item.getDouble("intrusion").toFloat(), rows,
                item.getDouble("reveal").toFloat())
            assertEquals("Case $i", item.getDouble("slide"), layout.slide.toDouble(), .001)
            assertEquals(-layout.slide, layout.geometry.originY, .001f)
        }
    }

    @Test fun primaryAndLineRevealScrollMatchUnmodifiedIos() {
        val cases = fixture().getJSONArray("scroll_cases")
        assertEquals(90, cases.length())
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            for (local in listOf(false, true)) {
                val result = TerminalKeyboardLayout.scroll(item.getDouble("position"), item.getInt("history"),
                    item.getDouble("reveal").toFloat(), 120f, item.getDouble("delta"), 20f, local)
                assertEquals("Case $i local=$local", item.getDouble(if (local) "primary_reveal" else "line_reveal"), result.reveal.toDouble(), .001)
                if (local) assertEquals("Case $i", item.getDouble("primary_position"), result.position, .001)
                else assertEquals("Case $i", item.getDouble("line_remaining"), result.remainingRows, .001)
            }
        }
    }

    @Test fun pairedMeasurementsKeepPrimaryGridStableThroughKeyboardAnimation() {
        for (keyboard in listOf(0, 57, 215, 320, 160, 0)) {
            val measured = TerminalViewportMeasurement(IntSize(400, 800 - keyboard), keyboard)
            assertEquals(IntSize(400, 800), measured.reportSize(true))
            assertEquals(measured.visible, measured.reportSize(false))
        }
        assertEquals(IntSize.Zero, TerminalViewportMeasurement().reportSize(true))
    }

    @Test fun textBelowCursorAndBlankCursorRowBothProtectContent() {
        val terminal = VtTerminal(40, 40)
        terminal.append("top\u001b[20;1Hfooter\u001b[3;1H".toByteArray())
        assertEquals(20f, TerminalKeyboardLayout.contentBottomRows(terminal, TerminalScrollViewport.at(0.0, 0))!!)
        terminal.append("\u001b[30;1H".toByteArray())
        assertEquals(30f, TerminalKeyboardLayout.contentBottomRows(terminal, TerminalScrollViewport.at(0.0, 0))!!)
        terminal.append("\u001b[?1049h".toByteArray())
        assertNull(TerminalKeyboardLayout.contentBottomRows(terminal, TerminalScrollViewport.at(0.0, 0)))
    }

    @Test fun hiddenOldestRowsAreReachableWithoutInventingScrollback() {
        val up = TerminalKeyboardLayout.scroll(10.0, 10, 0f, 200f, 5.0, 20f, true)
        assertEquals(10.0, up.position, 0.0); assertEquals(0.0, up.remainingRows, 0.0)
        assertEquals(100f, up.reveal)
        val down = TerminalKeyboardLayout.scroll(up.position, 10, up.reveal, 200f, -7.0, 20f, true)
        assertEquals(8.0, down.position, 0.0); assertEquals(0f, down.reveal)
        assertEquals(-2.0, down.remainingRows, 0.0)
        val closed = TerminalKeyboardLayout.scroll(10.0, 10, 100f, 0f, -1.0, 20f, false)
        assertEquals(-1.0, closed.remainingRows, 0.0); assertEquals(0f, closed.reveal)
    }
}
