package io.github.docmorphic.cmuxapp.ghostty

import org.junit.Assert.*
import org.junit.Test

class GhosttyMouseTest {
    @Test fun sgrClickAndWheelUseCurrentModesWithoutEnablingMirrorReplies() {
        GhosttyTerminal(80, 24).use { terminal ->
            assertFalse(terminal.inputModes().mouseTracking)
            assertTrue(terminal.mouse(0, 1, 4, 2).isEmpty())
            assertTrue(terminal.append("\u001b[?1000h\u001b[?1006h\u001b[6n\u001b[c".toByteArray()).isEmpty())
            assertTrue(terminal.inputModes().mouseTracking)
            assertEquals("\u001b[<0;5;3M", terminal.mouse(0, 1, 4, 2).decodeToString())
            assertEquals("\u001b[<0;5;3m", terminal.mouse(1, 1, 4, 2).decodeToString())
            assertEquals("\u001b[<64;5;3M", terminal.mouse(0, 4, 4, 2).decodeToString())
            assertEquals("\u001b[<65;5;3M", terminal.mouse(0, 5, 4, 2).decodeToString())
            terminal.append("\u001b[?1000l".toByteArray())
            assertFalse(terminal.inputModes().mouseTracking)
            assertTrue(terminal.mouse(0, 1, 4, 2).isEmpty())
        }
    }

    @Test fun legacyMousePreservesNonUtf8BytesAndX10SuppressesRelease() {
        GhosttyTerminal(200, 40).use { terminal ->
            terminal.append("\u001b[?9h".toByteArray())
            assertArrayEquals(byteArrayOf(27, 91, 77, 32, 183.toByte(), 35), terminal.mouse(0, 1, 150, 2))
            assertTrue(terminal.mouse(1, 1, 150, 2).isEmpty())
            terminal.append("\u001b[?1000h".toByteArray())
            assertArrayEquals(byteArrayOf(27, 91, 77, 35, 183.toByte(), 35), terminal.mouse(1, 1, 150, 2))
        }
    }

    @Test fun pixelMouseUsesCellCentersAndResizeClampsCoordinates() {
        GhosttyTerminal(80, 24).use { terminal ->
            terminal.resize(80, 24, 8, 16)
            terminal.append("\u001b[?1000h\u001b[?1016h".toByteArray())
            // Ghostty's upstream SGR-pixel fixture keeps terminal-space positions
            // unchanged; only cell-coordinate protocols add one.
            assertEquals("\u001b[<0;36;40M", terminal.mouse(0, 1, 4, 2).decodeToString())
            terminal.resize(2, 2, 10, 20)
            assertEquals("\u001b[<0;15;30M", terminal.mouse(0, 1, 999, 999).decodeToString())
        }
    }

    @Test fun focusAndAlternateScrollFollowParserModeChangesAndReset() {
        GhosttyTerminal(80, 24).use { terminal ->
            terminal.append("\u001b[?1004h\u001b[?1007h".toByteArray())
            assertTrue(terminal.inputModes().focusEvents)
            assertTrue(terminal.inputModes().alternateScroll)
            terminal.append("\u001b[?1004l\u001b[?1007l".toByteArray())
            assertFalse(terminal.inputModes().focusEvents)
            assertFalse(terminal.inputModes().alternateScroll)
        }
    }
}
