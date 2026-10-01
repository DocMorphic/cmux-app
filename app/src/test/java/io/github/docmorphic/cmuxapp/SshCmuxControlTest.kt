package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class SshCmuxControlTest {
    private class Pipe : SshExecPipe {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = input.receiveAsFlow()
        val sent = mutableListOf<JSONObject>()
        var closed = false
        override suspend fun write(bytes: ByteArray) { sent += JSONObject(bytes.toString(Charsets.UTF_8)) }
        override fun close() { closed = true; input.close() }
        fun feed(value: JSONObject) { check(input.trySend((value.toString()+"\n").toByteArray()).isSuccess) }
        fun reply(index: Int, data: JSONObject = JSONObject()) = feed(JSONObject().put("id", sent[index].getString("id")).put("ok", true).put("data", data))
    }
    private fun identify() = JSONObject().put("app", "cmux-tui").put("version", "fixture").put("protocol", 12)
        .put("pid", 123).put("session", "fixture").put("generation", "boot-a")
        .put("capabilities", JSONArray(listOf("workspace-registry-v1", "attach-initial-size", "view-attachment-lease-v1", "view-attachment-detach-v1")))
    private suspend fun TestScope.ready(pipe: Pipe, client: SshCmuxControl, identity: JSONObject = identify()) {
        val opening = async { client.handshake("fixture") }; runCurrent()
        pipe.reply(0, identity); runCurrent()
        assertEquals("android", pipe.sent[1].getString("kind"))
        pipe.reply(1); runCurrent(); opening.await()
    }
    @Test fun lineFramingPreservesSplitUtf8AndRejectsOversizeOrTrailingGarbage() {
        val bytes = "{\"text\":\"λ 中\"}\n\n{\"id\":\"r2\"}\n".toByteArray()
        val parser = SshCmuxLines(); val lines = bytes.flatMap { parser.feed(byteArrayOf(it)) }
        assertEquals("λ 中", lines[0].getString("text")); assertEquals(2, lines.size)
        val bounded = SshCmuxLines(8)
        assertThrows(Exception::class.java) { bounded.feed(ByteArray(9) { 65 }) }
        assertThrows(Exception::class.java) { bounded.feed("{}\n".toByteArray()) }
        assertThrows(Exception::class.java) { SshCmuxLines().feed("{}junk\n".toByteArray()) }
        assertThrows(Exception::class.java) { SshCmuxLines().feed(byteArrayOf(0xff.toByte(), 10)) }
    }
    @Test fun handshakeRejectsWrongSessionOrMissingCapabilities() = runTest {
        for (data in listOf(identify().put("session", "another"), identify().put("capabilities", JSONArray()),
            identify().put("protocol", 12.5), identify().put("pid", "123"))) {
            val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope)
            val opening = async { runCatching { control.handshake("fixture") } }; runCurrent()
            pipe.reply(0, data); runCurrent()
            assertTrue(opening.await().isFailure); assertTrue(pipe.closed); assertEquals(1, pipe.sent.size)
        }
    }
    @Test fun malformedSurfaceCannotAliasAnAttachmentAndInvalidGridRetiresRelay() = runTest {
        for (bad in listOf<Any>(4294967303L, 7.5, "7", -1, JSONObject.NULL)) {
            val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); ready(pipe, control)
            val events = mutableListOf<SshCmuxEvent>()
            val opening = async { runCatching { control.attach(7, 80, 24, events::add) } }; runCurrent()
            val frame = JSONObject().put("event", "vt-state").put("surface", 7).put("cols", 80).put("rows", 24).put("data", "")
            // NULL case tests fractional dimensions on a correctly matched ID.
            if (bad === JSONObject.NULL) frame.put("cols", 80.5) else frame.put("surface", bad)
            pipe.feed(frame); runCurrent()
            assertTrue(opening.await().isFailure); assertTrue(control.closed)
            assertEquals(listOf(SshCmuxEvent.Ended(true)), events)
        }
    }
    @Test fun responsesCanReorderAndCanceledRequestCannotAnswerAnother() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope)
        val canceled = async { control.request("first") }; runCurrent(); canceled.cancelAndJoin()
        val second = async { control.request("second") }; val third = async { control.request("third") }; runCurrent()
        pipe.reply(2, JSONObject().put("value", "third")); pipe.reply(0, JSONObject().put("value", "discard")); runCurrent()
        assertEquals("third", third.await().getString("value")); assertFalse(second.isCompleted)
        pipe.reply(1, JSONObject().put("value", "second")); runCurrent()
        assertEquals("second", second.await().getString("value")); assertEquals(3, pipe.sent.size)
        control.close()
    }
    @Test fun timeoutOverflowAndMalformedInputRetirePendingRequestsWithoutReplay() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope, timeoutMillis = 50)
        val timed = async { runCatching { control.request("uncertain") } }; runCurrent(); advanceTimeBy(50); runCurrent()
        assertTrue(timed.await().isFailure); assertTrue(pipe.closed); assertEquals(1, pipe.sent.size)
        val limitedPipe = Pipe(); val limited = SshCmuxControl(limitedPipe, backgroundScope, pendingLimit = 1)
        val first = async { runCatching { limited.request("first") } }; runCurrent()
        assertTrue(runCatching { limited.request("second") }.isFailure); runCurrent(); assertTrue(first.await().isFailure)
        val badPipe = Pipe(); val bad = SshCmuxControl(badPipe, backgroundScope)
        val malformed = async { runCatching { bad.request("pending") } }; runCurrent()
        badPipe.input.send("not-json\n".toByteArray()); runCurrent()
        assertTrue(malformed.await().isFailure); assertTrue(bad.closed)
    }
    @Test fun attachRoutesReplayBeforeReplyAndUsesLeaseForGeometryAndDetachFence() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); ready(pipe, control)
        val events = mutableListOf<SshCmuxEvent>()
        val attaching = async { control.attach(7, 80, 24, events::add) }; runCurrent()
        pipe.feed(JSONObject().put("event", "vt-state").put("surface", 7).put("cols", 80).put("rows", 24)
            .put("data", Base64.getEncoder().encodeToString("seed λ".toByteArray())))
        runCurrent(); assertEquals("seed λ", (events.single() as SshCmuxEvent.Snapshot).bytes.toString(Charsets.UTF_8))
        pipe.reply(2, JSONObject().put("lease", "lease-a")); runCurrent()
        assertEquals("set-client-sizing", pipe.sent[3].getString("cmd")); pipe.reply(3); runCurrent()
        val attachment = attaching.await()
        val resized = async { control.resize(attachment, 90, 30) }; runCurrent()
        assertEquals("lease-a", pipe.sent[4].getString("lease")); pipe.reply(4, JSONObject().put("outcome", "applied")); runCurrent()
        assertEquals("applied", resized.await())
        val release = async { control.releaseGeometry(attachment) }; runCurrent()
        assertEquals("release-attached-view-size", pipe.sent[5].getString("cmd")); pipe.reply(5, JSONObject().put("outcome", "passive")); runCurrent(); assertTrue(release.await())
        assertFalse(control.idlePolicy(7, 3600)); assertEquals(6, pipe.sent.size)
        val detaching = async { control.detach(attachment) }; runCurrent()
        pipe.feed(JSONObject().put("event", "detached").put("surface", 7)); runCurrent()
        assertFalse(detaching.isCompleted)
        assertTrue(runCatching { control.attach(7, 80, 24) {} }.isFailure)
        pipe.reply(6, JSONObject().put("outcome", "applied")); runCurrent(); detaching.await()
        assertEquals(1, events.count { it is SshCmuxEvent.Ended }); assertFalse(control.closed)
        assertTrue(runCatching { control.send(attachment, "late".toByteArray()) }.isFailure)
        control.close()
    }
    @Test fun cancellationDuringAttachClosesRelayAndImmediateOwnerRetirementClosesPipe() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); ready(pipe, control)
        val attaching = async { control.attach(1, 80, 24) {} }; runCurrent(); attaching.cancelAndJoin()
        assertTrue(control.closed); assertTrue(pipe.closed)
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        val nextPipe = Pipe(); SshCmuxControl(nextPipe, owner); owner.cancel(); runCurrent(); assertTrue(nextPipe.closed)
    }
    @Test fun legacyServerUsesResizeFallbackAndClosesRelayToFenceDetach() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope)
        ready(pipe, control, identify().put("capabilities", JSONArray(listOf("workspace-registry-v1", "attach-initial-size"))))
        assertEquals(0, pipe.sent[1].getJSONArray("capabilities").length())
        val events = mutableListOf<SshCmuxEvent>()
        val opening = async { control.attach(1, 80, 24, events::add) }; runCurrent()
        pipe.feed(JSONObject().put("event", "vt-state").put("surface", 1).put("cols", 80).put("rows", 24).put("data", ""))
        pipe.reply(2); runCurrent(); pipe.reply(3); runCurrent()
        val attachment = opening.await()
        val resize = async { control.resize(attachment, 90, 30) }; runCurrent()
        assertEquals("resize-surface", pipe.sent[4].getString("cmd"))
        pipe.reply(4, JSONObject().put("accepted", true)); runCurrent(); assertEquals("applied", resize.await())
        assertFalse(control.releaseGeometry(attachment)); control.detach(attachment)
        assertTrue(control.closed); assertTrue(pipe.closed); assertEquals(5, pipe.sent.size)
        assertEquals(1, events.count { it is SshCmuxEvent.Ended })
    }
    @Test fun advertisedIdlePolicyUsesSecondsAndOmissionForNever() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope)
        val identity = identify(); identity.getJSONArray("capabilities").put("terminal-idle-close-v1")
        ready(pipe, control, identity)
        assertTrue(runCatching { control.idlePolicy(1, 0) }.isFailure)
        assertTrue(runCatching { control.idlePolicy(1, 315360001) }.isFailure)
        val set = async { control.idlePolicy(1, 3600) }; runCurrent()
        assertEquals("set-terminal-idle-policy", pipe.sent[2].getString("cmd"))
        assertEquals(3600, pipe.sent[2].getLong("idle_close_seconds")); pipe.reply(2); runCurrent(); assertTrue(set.await())
        val never = async { control.idlePolicy(1, null) }; runCurrent()
        assertFalse(pipe.sent[3].has("idle_close_seconds")); pipe.reply(3); runCurrent(); assertTrue(never.await())
        control.close()
    }
    @Test fun resourceMutationPreservesIdempotencyIdentityAndDoesNotRetryServerError() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope)
        val close = async { runCatching { control.requestV2("terminal.close", JSONObject().put("terminal", "term_fixture"), "one-operation") } }
        runCurrent()
        val request = pipe.sent.single()
        assertEquals("cmux.protocol/2", request.getString("protocol"))
        assertEquals("request", request.getString("type")); assertEquals("one-operation", request.getString("idempotency_key"))
        pipe.feed(JSONObject().put("id", request.getString("id")).put("ok", false)
            .put("error", JSONObject().put("code", "terminal_not_found").put("message", "Terminal no longer exists")))
        runCurrent()
        val failure = close.await().exceptionOrNull() as SshCmuxFailure
        assertEquals("terminal.close", failure.operation); assertEquals("terminal_not_found", failure.code)
        assertEquals(1, pipe.sent.size); assertFalse(control.closed); control.close()
    }
}
