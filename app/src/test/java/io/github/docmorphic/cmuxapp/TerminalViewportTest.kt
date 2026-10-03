package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalViewportTest {
    @Test fun viewportTracksVisibleAreaWithoutChangingCellSize() {
        val cells = TerminalCellMetrics(widthPx = 8.5f, heightPx = 17.2f, fontSizePx = 14f)
        assertEquals(TerminalViewport(48, 46), TerminalViewport.fit(412, 800, cells))
        assertEquals(TerminalViewport(48, 23), TerminalViewport.fit(412, 400, cells))
        assertNull(TerminalViewport.fit(0, 400, cells))
    }
}
