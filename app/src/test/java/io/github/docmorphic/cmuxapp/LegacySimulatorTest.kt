package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacySimulatorTest {
    private val panel = "abcdef01-2345-6789-abcd-ef0123456789"
    private fun json() = JSONObject().put("panel_id", panel).put("seq", 1).put("format", "png")
        .put("pixel_width", 64).put("pixel_height", 96).put("display_scale", 2).put("data_base64", "YWJj")
    private fun frame(n: ULong) = LegacySimulatorFrame.read(json(), panel)!!.copy(sequence = n)

    @Test fun exactLegacyWireUsesUnsignedSequencesAndRejectsWrongScopesTypesAndBounds() {
        val maximum = MobileJson.objectValue(json().toString().replace("\"seq\":1", "\"seq\":18446744073709551615"))
        assertEquals(ULong.MAX_VALUE, LegacySimulatorFrame.read(maximum, panel)!!.sequence)
        assertNull(LegacySimulatorFrame.read(json(), "another-panel"))
        for ((key, value) in listOf("seq" to -1, "seq" to "1", "seq" to 1.5, "format" to "webp",
            "pixel_width" to "64", "pixel_height" to 8193, "display_scale" to 0, "data_base64" to "")) {
            assertNull("$key=$value", LegacySimulatorFrame.read(json().put(key, value), panel))
        }
        assertNull(LegacySimulatorFrame.read(json().put("pixel_width", 8192).put("pixel_height", 8192), panel))
        val browser = json(); browser.remove("data_base64"); browser.put("data_b64", "YWJj")
        assertNull(LegacySimulatorFrame.read(browser, panel))
    }

    @Test fun inputMatchesOfficialScopeAndHardwareTokensAndNeverUsesSurfaceId() {
        for (button in LegacySimulatorButton.entries) {
            val (method, params) = LegacySimulatorInput.Button(button).request("workspace", panel)
            assertEquals("mobile.simulator.input.button", method)
            assertEquals(button.wire, params.getString("button"))
            assertEquals("workspace", params.getString("workspace_id")); assertEquals(panel, params.getString("panel_id"))
            assertFalse(params.has("surface_id"))
        }
        assertEquals("hello 👋\n", LegacySimulatorInput.Text("hello 👋\n").request("w", panel).second.getString("text"))
        assertEquals("tap", LegacySimulatorInput.Pointer(LegacyPointerPhase.TAP, .5f, 1f)
            .request("w", panel).second.getString("phase"))
        assertTrue(runCatching { LegacySimulatorInput.Pointer(LegacyPointerPhase.MOVED, Float.NaN, 0f).request("w", panel) }.isFailure)
    }

    @Test fun tapUsesStartingPointAndLegacyLetterboxesClampWhileOtherFingersAreIgnored() {
        val rect = SimVideoRect.fit(100, 200, 400, 400); val gesture = LegacySimulatorGesture(6f)
        gesture.begin(8, 200f, 100f)
        gesture.begin(9, 300f, 300f)
        assertTrue(gesture.move(9, 400f, 400f, rect).isEmpty())
        assertTrue(gesture.move(8, 203f, 104f, rect).isEmpty())
        assertTrue(gesture.end(9, 400f, 400f, rect).isEmpty())
        assertEquals(listOf(LegacySimulatorInput.Pointer(LegacyPointerPhase.TAP, .5f, .25f)), gesture.end(8, 203f, 104f, rect))
        gesture.begin(2, 0f, 100f)
        assertEquals(listOf(LegacySimulatorInput.Pointer(LegacyPointerPhase.TAP, 0f, .25f)), gesture.end(2, 0f, 100f, rect))
    }

    @Test fun dragSendsBeginThenMoveAndEndsAtLastPointOnCancellationWithoutReplayingAfterDiscard() {
        val rect = SimVideoRect.fit(100, 200, 400, 400); val gesture = LegacySimulatorGesture(6f)
        gesture.begin(8, 200f, 100f)
        assertEquals(listOf(LegacyPointerPhase.BEGAN, LegacyPointerPhase.MOVED), gesture.move(8, 240f, 120f, rect).map { it.phase })
        assertEquals(listOf(LegacySimulatorInput.Pointer(LegacyPointerPhase.ENDED, .7f, .3f)), gesture.cancel(rect))
        assertTrue(gesture.end(8, 400f, 400f, rect).isEmpty())
        gesture.begin(1, 200f, 100f); gesture.discard()
        assertTrue(gesture.move(1, 400f, 400f, rect).isEmpty()); assertTrue(gesture.end(1, 400f, 400f, rect).isEmpty())
        gesture.begin(2, 200f, 100f)
        assertEquals(listOf(LegacyPointerPhase.BEGAN, LegacyPointerPhase.ENDED), gesture.end(2, 500f, 500f, rect).map { it.phase })
    }

    @Test fun decoderKeepsOneActiveAndOnlyNewestPendingAndAllowsStalledDuplicateButNeverOlder() = runTest {
        val gate = CompletableDeferred<Unit>(); val decoded = mutableListOf<ULong>(); val shown = mutableListOf<ULong>()
        val pipeline = LegacySimulatorFrames(this, decode = { f -> decoded += f.sequence; if (f.sequence == 1uL) gate.await(); f.sequence },
            discard = { fail("Unexpected disposal") }, presented = { _, n -> shown += n }, stalled = { fail("Unexpected stall") })
        pipeline.submit(frame(1u), false); runCurrent()
        pipeline.submit(frame(2u), false); pipeline.submit(frame(3u), false); pipeline.submit(frame(2u), true)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(1uL, 3uL), decoded); assertEquals(decoded, shown)
        pipeline.submit(frame(3u), false); runCurrent(); assertEquals(2, decoded.size)
        pipeline.submit(frame(3u), true); runCurrent(); assertEquals(listOf(1uL, 3uL, 3uL), shown)
        pipeline.awaitClosed()
    }

    @Test fun lateNonInterruptibleDecoderIsDisposedAndThreeConsecutiveFailuresRequestRecovery() = runTest {
        val gate = CompletableDeferred<Unit>(); val disposed = mutableListOf<String>(); var stalls = 0
        val pipeline = LegacySimulatorFrames(this, decode = { f -> if (f.sequence == 6uL) { gate.await(); "late" } else null },
            discard = { disposed += it }, presented = { _, _ -> fail("Published cancelled decoder") }, stalled = { stalls++ })
        for (n in 1..5) { pipeline.submit(frame(n.toULong()), false); runCurrent() }
        assertEquals(1, stalls)
        pipeline.submit(frame(6u), false); runCurrent(); pipeline.close(); gate.complete(Unit); pipeline.awaitClosed()
        assertEquals(listOf("late"), disposed)
        pipeline.submit(frame(7u), true); runCurrent(); assertEquals(1, stalls)
    }

    @Test fun immediateDecodeCompletionDoesNotLeaveAnAlreadyFinishedWorkerInstalled() = runBlocking<Unit> {
        val shown = mutableListOf<ULong>()
        val pipeline = LegacySimulatorFrames(CoroutineScope(coroutineContext + Dispatchers.Unconfined),
            decode = { it.sequence }, discard = {}, presented = { _, n -> shown += n }, stalled = {})
        repeat(4) { pipeline.submit(frame((it + 1).toULong()), false) }
        assertEquals(listOf(1uL, 2uL, 3uL, 4uL), shown)
        pipeline.awaitClosed()
    }
}
