package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalSizingTest {
    private fun wire() = JSONObject("""{"generation":7,"cols":67,"rows":35,"reason":"smallest",
      "owners":["mobile:p","mac:m"],"policy":{"mode":"smallest","priority":[],"fixed":null},
      "participants":[
        {"id":"mac:m","user_id":"u","display_name":"Fixture","device_kind":"mac","device_name":"Mac",
         "via":null,"viewport":{"cols":100,"rows":35},"counts_override":null,"counts":true,"priority_key":"u/mac"},
        {"id":"mobile:p","user_id":"u","display_name":"Fixture","device_kind":"iphone","device_name":null,
         "via":null,"viewport":{"cols":67,"rows":47},"counts_override":null,"counts":true,"priority_key":"u/iphone"}]}""")
    private fun state(generation: Long = 7) = TerminalSizeState.decode(wire().put("generation", generation))

    @Test fun hostGridAndPhoneViewportRemainDistinct() {
        val value = state()
        assertEquals(SharedTerminalGrid(67, 35), value.grid)
        assertEquals(SharedTerminalGrid(67, 47), value.participants.single { it.id == "mobile:p" }.viewport)
        assertEquals(value.policy, TerminalSizePolicy.decode(value.policy.wire()))
        assertNull(value.participants.first().countsOverride)
    }

    @Test fun rejectsMalformedOwnershipAndPolicy() {
        val duplicate = wire().also { it.getJSONArray("participants").getJSONObject(1).put("id", "mac:m") }
        val absentOwner = wire().put("owners", org.json.JSONArray(listOf("missing")))
        val invalidGrid = wire().put("cols", 0)
        val fixedWithoutGrid = wire().also { it.getJSONObject("policy").put("mode", "fixed") }
        for (value in listOf(duplicate, absentOwner, invalidGrid, fixedWithoutGrid, wire().put("generation", -1)))
            assertTrue(runCatching { TerminalSizeState.decode(value) }.isFailure)
    }

    @Test fun staleGenerationCannotReplaceCurrentParticipantButNewAttachmentCanResetIt() {
        val surface = TerminalSizingSurface()
        surface.apply(state(9), "mobile:p", null)
        assertEquals(TerminalSizingEffect.NONE, surface.apply(state(8), null, null))
        assertEquals(9L, surface.state!!.generation)
        surface.apply(state(1), "mobile:new", null)
        assertEquals(1L, surface.state!!.generation)
        surface.connectionEnded()
        surface.apply(state(0), "mobile:new", null)
        assertEquals(0L, surface.state!!.generation)
    }

    @Test fun explicitDetachSurvivesNetworkEventsReplayAndConnectionReplacement() {
        val surface = TerminalSizingSurface()
        surface.detach(TerminalDetach("disconnected-by", "2026-10-03T00:00:00Z", "Fixture", "Mac"))
        assertFalse(surface.allowsTraffic)
        assertEquals(TerminalSizingEffect.NONE, surface.detach(TerminalDetach("network", null, null, null)))
        surface.connectionEnded()
        surface.apply(state(), "mobile:p", SharedTerminalGrid(80, 24))
        surface.recoveredFromNetwork()
        assertFalse(surface.allowsTraffic)
        assertEquals(0L, surface.viewportRevision)
        surface.reattached(state(), "mobile:p")
        assertTrue(surface.allowsTraffic)
        assertEquals(1L, surface.viewportRevision)
    }

    @Test fun networkRecoveryAndSizeChangeReassertOnlyWhenNeeded() {
        val surface = TerminalSizingSurface()
        assertEquals(TerminalSizingEffect.RECONNECT, surface.detach(TerminalDetach("network", null, null, null)))
        assertTrue(surface.allowsTraffic)
        assertEquals(TerminalSizingEffect.REASSERT_VIEWPORT, surface.apply(state(), "mobile:p", SharedTerminalGrid(80, 24)))
        assertFalse(surface.reconnecting)
        assertEquals(TerminalSizingEffect.NONE, surface.apply(state(8), "mobile:p", SharedTerminalGrid(80, 24)))
        assertEquals(1L, surface.viewportRevision)
    }

    @Test fun unknownDetachReasonRequiresExplicitReattachAndActorCanBeMissing() {
        val surface = TerminalSizingSurface()
        surface.detach(TerminalDetach.decode(JSONObject("""{"reason":"future-reason","by":null,"at":null}""")))
        assertFalse(surface.allowsTraffic)
        assertNull(surface.detached!!.byName)
    }
}
