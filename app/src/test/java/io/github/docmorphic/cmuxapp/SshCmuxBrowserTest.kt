package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SshCmuxBrowserTest {
    private fun frame(seq: Long = 1, token: Long? = 40, floor: Long? = token, status: SshCmuxBrowserStatus? = SshCmuxBrowserStatus.LIVE) =
        SshCmuxBrowserFrame(seq, 640, 480, 1280, 960, "png", status, null, floor, token)
    private fun state(frame: SshCmuxBrowserFrame? = null, floor: Long? = 40, token: Long? = 40, status: SshCmuxBrowserStatus = SshCmuxBrowserStatus.LIVE) =
        SshCmuxBrowserState(80, 24, "http://localhost", "Page", status, null, false, floor, token, frame)
    @Test fun pixelsAndExplicitPresentationAreRequiredBeforePointerInput() {
        val guard = SshCmuxBrowserPointerGuard()
        guard.apply(state()); assertNull(guard.token); assertFalse(guard.acknowledge(40))
        guard.apply(state(frame())); assertNull(guard.token)
        assertTrue(guard.acknowledge(40)); assertEquals(40L, guard.token)
        assertFalse(guard.acknowledge(40)); assertFalse(guard.acknowledge(41))
        guard.apply(state()); assertEquals(40L, guard.token)
        guard.apply(state(floor = 41, token = 41)); assertNull(guard.token); assertFalse(guard.acknowledge(41))
    }
    @Test fun newPixelsMayRetainPresentedTokenOnlyWithinTheirRange() {
        val guard = SshCmuxBrowserPointerGuard()
        guard.apply(frame()); assertTrue(guard.acknowledge(40))
        guard.apply(frame(2, 43, 39)); assertEquals(40L, guard.token)
        assertTrue(guard.acknowledge(43)); assertFalse(guard.acknowledge(40))
        guard.apply(frame(3, 44, 44)); assertNull(guard.token)
        assertTrue(guard.acknowledge(44))
        guard.apply(frame(4, 46, 47)); assertNull(guard.token)
    }
    @Test fun unknownStatusMissingAuthorityAndFailuresRevokePresentation() {
        for (invalid in listOf(frame(status = null), frame(status = SshCmuxBrowserStatus.FAILED), frame(token = null))) {
            val guard = SshCmuxBrowserPointerGuard(); guard.apply(frame()); assertTrue(guard.acknowledge(40))
            guard.apply(invalid); assertNull(guard.token)
        }
        val guard = SshCmuxBrowserPointerGuard(); guard.apply(frame()); guard.acknowledge(40)
        guard.apply(state(status = SshCmuxBrowserStatus.STARTING)); assertNull(guard.token)
    }
    @Test fun attachmentMapsImageSequenceToDifferentPointerTokenAndBoundsUndisplayedFrames() {
        val received = mutableListOf<SshCmuxBrowserEvent>()
        val attachment = SshCmuxBrowserAttachment(5, received::add)
        attachment.receive(SshCmuxBrowserEvent.State(state(frame())))
        assertEquals(40L, attachment.frames[1L]); assertTrue(attachment.seeded)
        for (i in 2L..20L) attachment.receive(SshCmuxBrowserEvent.Frame(frame(i, 100 + i, 40)))
        assertEquals((13L..20L).toList(), attachment.frames.keys.toList())
        val count = received.size
        attachment.receive(SshCmuxBrowserEvent.Frame(frame(3, 999))); assertEquals(count, received.size)
        attachment.receive(SshCmuxBrowserEvent.State(state(frame(1, 999), 999, 999)))
        assertNull((received.last() as SshCmuxBrowserEvent.State).value.frame)
        assertFalse(attachment.pointer.acknowledge(999))
        attachment.receive(SshCmuxBrowserEvent.Ended(true)); assertTrue(attachment.frames.isEmpty())
    }
    @Test fun nestedStateFrameInheritsAuthorityAndHasDistinctImageDimensions() {
        val raw = JSONObject("""{"event":"browser-state","surface":7,"status":"live","pointer_frame_seq":42,"pointer_frame_floor_seq":40,
            "frame":{"seq":9,"width":640,"height":480,"image_width":1280,"image_height":960,"data":"AA=="}}""")
        val state = (SshCmuxBrowserWire.parse(raw) as SshCmuxBrowserEvent.State).value
        assertEquals(9L, state.frame!!.sequence); assertEquals(42L, state.frame.token)
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
