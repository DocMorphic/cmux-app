package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshCmuxBrowserTest {
    private fun frame(seq: Long = 1, token: Long? = 40, floor: Long? = token, status: SshCmuxBrowserStatus? = SshCmuxBrowserStatus.LIVE) =
        SshCmuxBrowserFrame(seq.toULong(), 640, 480, 1280, 960, "png", status, null, floor?.toULong(), token?.toULong())
    private fun state(frame: SshCmuxBrowserFrame? = null, floor: Long? = 40, token: Long? = 40, status: SshCmuxBrowserStatus = SshCmuxBrowserStatus.LIVE) =
        SshCmuxBrowserState(80, 24, "http://localhost", "Page", status, null, false, floor?.toULong(), token?.toULong(), frame)
    @Test fun pixelsAndExplicitPresentationAreRequiredBeforePointerInput() {
        val guard = SshCmuxBrowserPointerGuard()
        guard.apply(state()); assertNull(guard.token); assertFalse(guard.acknowledge(40uL))
        guard.apply(state(frame())); assertNull(guard.token)
        assertTrue(guard.acknowledge(40uL)); assertEquals(40uL, guard.token)
        assertFalse(guard.acknowledge(40uL)); assertFalse(guard.acknowledge(41uL))
        guard.apply(state()); assertEquals(40uL, guard.token)
        guard.apply(state(floor = 41, token = 41)); assertNull(guard.token); assertFalse(guard.acknowledge(41uL))
    }
    @Test fun newPixelsMayRetainPresentedTokenOnlyWithinTheirRange() {
        val guard = SshCmuxBrowserPointerGuard()
        guard.apply(frame()); assertTrue(guard.acknowledge(40uL))
        guard.apply(frame(2, 43, 39)); assertEquals(40uL, guard.token)
        assertTrue(guard.acknowledge(43uL)); assertFalse(guard.acknowledge(40uL))
        guard.apply(frame(3, 44, 44)); assertNull(guard.token)
        assertTrue(guard.acknowledge(44uL))
        guard.apply(frame(4, 46, 47)); assertNull(guard.token)
    }
    @Test fun unknownStatusMissingAuthorityAndFailuresRevokePresentation() {
        for (invalid in listOf(frame(status = null), frame(status = SshCmuxBrowserStatus.FAILED), frame(token = null))) {
            val guard = SshCmuxBrowserPointerGuard(); guard.apply(frame()); assertTrue(guard.acknowledge(40uL))
            guard.apply(invalid); assertNull(guard.token)
        }
        val guard = SshCmuxBrowserPointerGuard(); guard.apply(frame()); guard.acknowledge(40uL)
        guard.apply(state(status = SshCmuxBrowserStatus.STARTING)); assertNull(guard.token)
    }
    @Test fun attachmentMapsImageSequenceToDifferentPointerTokenAndBoundsUndisplayedFrames() {
        val received = mutableListOf<SshCmuxBrowserEvent>()
        val attachment = SshCmuxBrowserAttachment(5, received::add)
        attachment.receive(SshCmuxBrowserEvent.State(state(frame())))
        assertEquals(40uL, attachment.frames[1uL]); assertTrue(attachment.seeded)
        for (i in 2L..20L) attachment.receive(SshCmuxBrowserEvent.Frame(frame(i, 100 + i, 40)))
        assertEquals((13uL..20uL).toList(), attachment.frames.keys.toList())
        val count = received.size
        attachment.receive(SshCmuxBrowserEvent.Frame(frame(3, 999))); assertEquals(count, received.size)
        attachment.receive(SshCmuxBrowserEvent.State(state(frame(1, 999), 999, 999)))
        assertNull((received.last() as SshCmuxBrowserEvent.State).value.frame)
        assertFalse(attachment.pointer.acknowledge(999uL))
        attachment.receive(SshCmuxBrowserEvent.Ended(true)); assertTrue(attachment.frames.isEmpty())
    }
    @Test fun nestedStateFrameInheritsAuthorityAndHasDistinctImageDimensions() {
        val raw = JSONObject("""{"event":"browser-state","surface":7,"status":"live","pointer_frame_seq":42,"pointer_frame_floor_seq":40,
            "frame":{"seq":9,"width":640,"height":480,"image_width":1280,"image_height":960,"data":"AA=="}}""")
        val state = (SshCmuxBrowserWire.parse(raw) as SshCmuxBrowserEvent.State).value
        assertEquals(9uL, state.frame!!.sequence); assertEquals(42uL, state.frame.token)
        assertEquals(1280, state.frame.imageWidth); assertEquals(SshCmuxBrowserStatus.LIVE, state.frame.status)
        raw.put("status", "new-future-state")
        assertEquals(SshCmuxBrowserStatus.STARTING, (SshCmuxBrowserWire.parse(raw) as SshCmuxBrowserEvent.State).value.status)
    }
    @Test fun malformedTokensAndUnboundedImagesAreNeverCoerced() {
        val raw = JSONObject("""{"event":"frame","surface":7,"seq":1,"width":10,"height":10,"data":"AA==","pointer_frame_seq":1}""")
        for (bad in listOf(1.5, "2", -1L, Double.MAX_VALUE)) {
            assertThrows(Exception::class.java) { SshCmuxBrowserWire.parse(JSONObject(raw.toString()).put("pointer_frame_seq", bad)) }
        }
        assertThrows(Exception::class.java) { SshCmuxBrowserWire.parse(JSONObject(raw.toString()).put("width", 16385)) }
        assertThrows(Exception::class.java) { SshCmuxBrowserWire.parse(JSONObject(raw.toString()).put("image_width", 16384).put("image_height", 16384)) }
        val parsed = (SshCmuxBrowserWire.parse(raw.put("image_width", 0)) as SshCmuxBrowserEvent.Frame).value
        assertEquals(10, parsed.imageWidth)
    }
    @Test fun unsignedWireBoundariesAndPointerOrderingNeverRoundOrWrap() {
        fun event(sequence: String, token: String = sequence, floor: String = token) =
            SshCmuxBrowserWire.parse(SshCmuxLines().feed(
                """{"event":"frame","surface":7,"seq":$sequence,"width":1,"height":1,"data":"AA==","status":"live","pointer_frame_seq":$token,"pointer_frame_floor_seq":$floor}
""".toByteArray()).single()) as SshCmuxBrowserEvent.Frame
        val guard = SshCmuxBrowserPointerGuard()
        val delivered = mutableListOf<SshCmuxBrowserEvent>()
        val attachment = SshCmuxBrowserAttachment(7, delivered::add)
        for (value in listOf(0uL, Long.MAX_VALUE.toULong(), 9223372036854775808uL, ULong.MAX_VALUE - 1uL, ULong.MAX_VALUE)) {
            val next = event(value.toString())
            assertEquals(value, next.value.sequence); assertEquals(value, next.value.token)
            guard.apply(next.value); assertTrue(guard.acknowledge(value)); assertEquals(value, guard.token)
            assertFalse(guard.acknowledge(value))
            attachment.receive(next)
        }
        assertEquals(5, delivered.size)
        attachment.receive(event("0")); attachment.receive(event(ULong.MAX_VALUE.toString()))
        assertEquals(5, delivered.size) // Neither wraparound nor duplicates become new pixels.
        guard.apply(event(ULong.MAX_VALUE.toString(), "9223372036854775808", ULong.MAX_VALUE.toString()).value)
        assertNull(guard.token) // Reversed unsigned range cannot authorize a pointer.
        for (bad in listOf("-1", "18446744073709551616", "184467440737095516150", "1.5", "1e2", "\"9223372036854775808\"")) {
            assertThrows(Exception::class.java) { event(bad) }
            assertThrows(Exception::class.java) { event("1", bad) }
            assertThrows(Exception::class.java) { event("1", "1", bad) }
        }
    }
    @Test fun keyMappingKeepsModifiedEnterOutOfTextAndUsesCdpModifiers() {
        assertEquals("\r", SshCmuxBrowserKeys.named("return", listOf("shift"))!!.getString("text"))
        val modified = SshCmuxBrowserKeys.named("enter", listOf("ctrl", "ALT", "meta", "shift"))!!
        assertEquals(15, modified.getInt("modifiers")); assertFalse(modified.has("text"))
        assertEquals(13, modified.getInt("windows_virtual_key_code"))
        assertEquals("Delete", SshCmuxBrowserKeys.named("forwarddelete", emptyList())!!.getString("code"))
        assertNull(SshCmuxBrowserKeys.named("unsupported", emptyList()))
    }
    @Test fun androidKeyboardTokensReachCdpWithoutDroppingShortcuts() {
        for ((token, expected) in mapOf("forward_delete" to "Delete", "page_up" to "PageUp", "page_down" to "PageDown", "insert" to "Insert"))
            assertEquals(expected, SshCmuxBrowserKeys.named(token, emptyList())!!.getString("key"))
        for (number in 1..12) assertEquals("F$number", SshCmuxBrowserKeys.named("f$number", emptyList())!!.getString("code"))
        val shortcut = SshCmuxBrowserKeys.named("a", listOf("control", "shift"))!!
        assertEquals("A", shortcut.getString("key")); assertEquals("KeyA", shortcut.getString("code"))
        assertEquals(10, shortcut.getInt("modifiers")); assertFalse(shortcut.has("text"))
        assertEquals("Space", SshCmuxBrowserKeys.named("space", listOf("control"))!!.getString("code"))
        val international = SshCmuxBrowserKeys.named("é", listOf("option"))!!
        assertEquals("é", international.getString("key")); assertEquals("", international.getString("code")); assertFalse(international.has("text"))
        assertNull(SshCmuxBrowserKeys.named("f13", emptyList()))
        assertNull(SshCmuxBrowserKeys.named("\u0001", emptyList()))
    }

}
