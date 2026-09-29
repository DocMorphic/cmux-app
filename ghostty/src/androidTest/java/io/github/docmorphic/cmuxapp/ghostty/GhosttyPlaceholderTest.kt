package io.github.docmorphic.cmuxapp.ghostty

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class GhosttyPlaceholderTest {
    private val placeholder = String(Character.toChars(0x10EEEE))
    private fun GhosttyTerminal.write(value: String) = append(value.toByteArray())
    private fun image(id: Long, placement: Int = 23): String {
        val pixels = byteArrayOf(-1,0,0, 0,0,-1, -1,0,0, 0,0,-1, 0,-1,0, -1,-1,-1, 0,-1,0, -1,-1,-1)
        return "\u001b_Ga=T,f=24,s=2,v=4,i=$id,p=$placement,U=1,c=2,r=2;${Base64.getEncoder().encodeToString(pixels)}\u001b\\"
    }
    private fun GhosttyGraphicsFrame.fragments() = placements.filter { it.placeholder }

    @Test fun highImageIdsExplicitPlacementAndContinuationResolveUsingNativeRules() {
        GhosttyTerminal(10, 4).use { terminal ->
            terminal.resize(10, 4, 10, 20)
            terminal.write("\u001b[?2027h" + image(0x01000011))
            terminal.write("\u001b[38;2;0;0;17;58;2;0;0;23m" +
                placeholder + "\u0305\u0305\u030d" + placeholder + "\r\n" +
                placeholder + "\u030d\u0305\u030d" + placeholder)
            val fragments = terminal.graphicsSnapshot().fragments()
            assertEquals(2, fragments.size)
            fragments.forEachIndexed { row, fragment ->
                assertEquals(0x01000011L, fragment.imageId); assertEquals(23L, fragment.placementId)
                assertEquals(row, fragment.row); assertEquals(row * 2, fragment.sourceY)
                assertEquals(2, fragment.sourceWidth); assertEquals(2, fragment.sourceHeight)
                assertEquals(20L, fragment.pixelWidth); assertEquals(20L, fragment.pixelHeight)
                assertEquals(-1, fragment.z); assertTrue(fragment.visible); assertFalse(fragment.virtual)
            }
        }
    }

    @Test fun paletteIdsHistoryAndPartialBottomRowRemainResolvable() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.resize(10, 3, 10, 20)
            terminal.write("\u001b[?2027h" + image(1, 1))
            terminal.write("\r\n\r\n\r\n\u001b[3;1H\u001b[31;58;5;1m" + placeholder + "\u0305\u030d")
            val live = terminal.graphicsSnapshot().fragments().single()
            assertEquals(1L, live.imageId); assertEquals(1L, live.placementId)
            assertEquals(1, live.sourceX); assertEquals(1, live.sourceWidth)
            assertEquals(2, live.row); assertTrue(live.visible)
            val history = terminal.graphicsSnapshot(1).fragments().single()
            assertEquals(3, history.row); assertFalse(history.visible)
            terminal.write("\r\n")
            assertEquals(1, terminal.graphicsSnapshot().fragments().single().row)
            assertEquals(3, history.row)
        }
    }

    @Test fun regularPlacementsAloneDoNotEnablePlaceholderRendering() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.resize(10, 3, 10, 20)
            terminal.write("\u001b[?2027h" + image(1, 1).replace(",U=1", ",C=1"))
            terminal.write("\u001b[H\u001b[31;58;5;1m" + placeholder + "\u0305\u0305")
            assertTrue(terminal.graphicsSnapshot().fragments().isEmpty())
            assertEquals(1, terminal.graphicsSnapshot().placements.size)
        }
    }

    @Test fun missingPlacementDeleteAndAlternateScreenDoNotInventFragments() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.resize(10, 3, 10, 20)
            terminal.write("\u001b[?2027h" + image(1, 1))
            terminal.write("\u001b[31;58;5;9m" + placeholder + "\u0305\u0305")
            assertTrue(terminal.graphicsSnapshot().fragments().isEmpty())
            terminal.write("\u001b[H\u001b[58;5;1m" + placeholder + "\u0305\u0305")
            assertEquals(1, terminal.graphicsSnapshot().fragments().size)
            terminal.write("\u001b[?1049h")
            assertTrue(terminal.graphicsSnapshot().fragments().isEmpty())
            terminal.write("\u001b[?1049l")
            assertEquals(1, terminal.graphicsSnapshot().fragments().size)
            terminal.write("\u001b_Ga=d,d=I,i=1;\u001b\\")
            assertTrue(terminal.graphicsSnapshot().fragments().isEmpty())
        }
    }
}
