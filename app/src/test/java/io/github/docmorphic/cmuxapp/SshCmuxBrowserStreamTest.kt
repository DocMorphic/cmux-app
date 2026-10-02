package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshCmuxBrowserStreamTest {
    private class Pipe : SshExecPipe {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = incoming.receiveAsFlow()
        val sent = mutableListOf<JSONObject>()
        var sequence = 9L
        var token = 40L
        var hold: String? = null
        var held: JSONObject? = null
        fun feed(value: JSONObject) { check(incoming.trySend((value.toString() + "\n").toByteArray()).isSuccess) }
        fun state(frame: Boolean = true) = JSONObject().put("event", "browser-state").put("surface", 7)
            .put("status", "live").put("pointer_frame_seq", token).put("cols", 80).put("rows", 24)
            .put("url", "http://localhost:8080/").put("title", "SSH page").also {
                if (frame) it.put("frame", JSONObject().put("seq", sequence).put("width", 320).put("height", 240)
                    .put("image_width", 640).put("image_height", 480).put("data", "fixture-png"))
            }
        fun reply(request: JSONObject, data: JSONObject = JSONObject()) =
            feed(JSONObject().put("id", request.getString("id")).put("ok", true).put("data", data))
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(bytes.toString(Charsets.UTF_8)); sent += request
            val command = request.getString("cmd")
            if (command == hold) { held = request; return }
            val data = when (command) {
                "identify" -> JSONObject().put("app", "cmux-tui").put("version", "fixture").put("protocol", 12).put("session", "fixture").put("pid", 123)
                    .put("capabilities", JSONArray(listOf("workspace-registry-v1", "attach-initial-size", "view-attachment-lease-v1",
                        "view-attachment-detach-v1", SshCmuxBrowserWire.CAPABILITY)))
                "attach-surface" -> { feed(state()); JSONObject().put("lease", "lease-${sent.size}") }
                "detach-attached-view" -> { feed(JSONObject().put("event", "detached").put("surface", 7)); JSONObject().put("outcome", "applied") }
                "get-cell-pixels" -> JSONObject().put("width_px", 8).put("height_px", 16)
                "resize-surface" -> JSONObject().put("accepted", true)
                else -> JSONObject()
            }
            reply(request, data)
        }
        override fun close() { incoming.close() }
    }
    private fun tree(content: String = "brw_a", generation: String = "owner-a", surface: Int = 7, kind: String = "browser"): SshCmuxTree =
        SshCmuxInventory.parse(JSONObject("""{"generation":"$generation","registry_id":"registry-a","workspaces":[
        {"id":1,"key":"workspace-a","resource_id":"ws_a","name":"one","screens":[{"id":2,"panes":[{"id":3,"tabs":[
        {"surface":$surface,"kind":"$kind","tab_resource_id":"tab_a","content_resource_id":"$content","title":"Browser"}]}]}]}]}"""))
    private fun selection(tree: SshCmuxTree) = SshCmuxBrowserSelection.capture("fixture", tree, tree.workspaces.single(), tree.tabs.single())
    @Test fun savedBrowserCannotResolveTerminalReplacementOtherRegistryOrNumericReuseAfterRestart() {
        val tree = tree(); val selected = selection(tree)
        assertEquals(selected, (SshWorkspaceTarget.decode(SshWorkspaceTarget.Browser(selected).encode()) as SshWorkspaceTarget.Browser).selection)
        assertNotNull(selected.resolve("fixture", tree(generation = "owner-b", surface = 11)))
        assertNull(selected.resolve("fixture", tree(content = "brw_replacement")))
        assertNull(selected.resolve("fixture", tree(kind = "pty")))
        assertNull(selected.resolve("another", tree))
        assertNull(selected.resolve("fixture", tree.copy(registry = "another")))
        val legacy = selected.copy(contentResource = null, tabResource = null)
        assertNotNull(legacy.resolve("fixture", tree))
        assertNull(legacy.resolve("fixture", tree(generation = "owner-b")))
        assertNull(selected.resolve("fixture", tree.copy(workspaces = listOf(tree.workspaces.single().copy(key = "new-workspace")))))
    }
    @Test fun initialPixelsKeepTheirImageSequenceAndOnlyDisplayedFramesAuthorizeInput() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); control.handshake("fixture")
        var resolutions = 0
        val stream = SshCmuxBrowserStream(selection(tree()), control, backgroundScope, { resolutions++; tree().tabs.single() }, { true })
        val events = mutableListOf<BrowserStreamClient.Event>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.events.collect { events += it } }
        val state = stream.start(stream.panelId, "first", 393, 655, 3.0); runCurrent()
        assertEquals(1, resolutions); assertEquals("SSH page", state.getString("title"))
        assertEquals(listOf("browser.frame", "browser.state"), events.map { it.topic })
        assertTrue(events.all { it.streamId == "first" && it.payload.getString("panel_id") == stream.panelId })
        val image = events.first().payload
        assertEquals(9L, image.getLong("seq")); assertEquals(320, image.getInt("page_width")); assertEquals(640, image.getInt("pixel_width"))
        assertEquals("png", image.getString("format")); assertEquals("fixture-png", image.getString("data_b64"))
        val attach = pipe.sent.single { it.getString("cmd") == "attach-surface" }
        assertEquals(49, attach.getInt("cols")); assertEquals(40, attach.getInt("rows")) // CSS points, not density-scaled pixels
        stream.input(stream.panelId, BrowserInput.Click(1.0, 2.0))
        assertFalse(pipe.sent.any { it.getString("cmd") == "browser-mouse-guarded" })
        stream.displayed(stream.panelId, 8); assertFalse(pipe.sent.any { it.getString("cmd") == "browser-frame-presented" })
        stream.displayed(stream.panelId, 9); stream.input(stream.panelId, BrowserInput.Click(1.0, 2.0))
        assertEquals(listOf(40L, 40L), pipe.sent.takeLast(2).map { it.getLong("frame_seq") })
        stream.close(); runCurrent(); control.close()
    }
    @Test fun viewportOnlyChangesCellGridAndOldStreamStopCannotDetachNewAttachment() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); control.handshake("fixture")
        var resolutions = 0
        val stream = SshCmuxBrowserStream(selection(tree()), control, backgroundScope, { resolutions++; tree().tabs.single() }, { true })
        stream.start(stream.panelId, "first", 393, 655, 3.0)
        stream.viewport(stream.panelId, 395, 650, 2.0)
        assertFalse(pipe.sent.any { it.getString("cmd") == "resize-surface" })
        stream.viewport(stream.panelId, 400, 650, 2.0)
        assertEquals(1, pipe.sent.count { it.getString("cmd") == "resize-surface" })
        stream.stop(stream.panelId, "first"); assertFalse(stream.closed)
        pipe.sequence = 1; pipe.token = 2
        stream.start(stream.panelId, "second", 400, 650, 2.0)
        stream.stop(stream.panelId, "first")
        assertEquals(1, pipe.sent.count { it.getString("cmd") == "detach-attached-view" })
        stream.displayed(stream.panelId, 9)
        stream.input(stream.panelId, BrowserInput.Click(1.0, 2.0))
        assertFalse(pipe.sent.any { it.getString("cmd") == "browser-mouse-guarded" })
        stream.displayed(stream.panelId, 1); stream.input(stream.panelId, BrowserInput.Text("second"))
        assertEquals(2, resolutions)
        stream.close(); runCurrent(); assertFalse(control.closed); control.close()
    }
    @Test fun replacementRevokesInputAndRetiresOnlyItsAttachment() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); control.handshake("fixture")
        val stream = SshCmuxBrowserStream(selection(tree()), control, backgroundScope, { tree().tabs.single() }, { true })
        stream.start(stream.panelId, "first", 393, 655, 3.0); stream.displayed(stream.panelId, 9)
        stream.validate(tree(content = "replacement"))
        assertTrue(stream.closed)
        assertTrue(runCatching { stream.input(stream.panelId, BrowserInput.Text("not sent")) }.isFailure)
        runCurrent()
        assertEquals(1, pipe.sent.count { it.getString("cmd") == "detach-attached-view" })
        assertFalse(pipe.sent.any { it.getString("cmd") == "browser-insert-text" }); assertFalse(control.closed)
        control.close()
    }
    @Test fun retirementWhileAttachIsPendingFencesTheLateReplyAndRefusesWrongPanel() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); control.handshake("fixture")
        val stream = SshCmuxBrowserStream(selection(tree()), control, backgroundScope, { tree().tabs.single() }, { true })
        assertTrue(runCatching { stream.start("mac-panel", "bad", 393, 655, 3.0) }.isFailure)
        assertFalse(pipe.sent.any { it.getString("cmd") == "attach-surface" })
        pipe.hold = "attach-surface"
        val opening = async { runCatching { stream.start(stream.panelId, "first", 393, 655, 3.0) } }; runCurrent()
        stream.close(); pipe.feed(pipe.state()); pipe.reply(checkNotNull(pipe.held), JSONObject().put("lease", "late")); runCurrent()
        assertTrue(opening.await().isFailure)
        assertEquals(1, pipe.sent.count { it.getString("cmd") == "detach-attached-view" })
        assertFalse(control.closed); control.close()
    }
    @Test fun slowRendererOverflowFailsClosedInsteadOfDroppingMetadataAndContinuingInput() = runTest {
        val pipe = Pipe(); val control = SshCmuxControl(pipe, backgroundScope); control.handshake("fixture")
        val stream = SshCmuxBrowserStream(selection(tree()), control, backgroundScope, { tree().tabs.single() }, { true })
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.events.collect { awaitCancellation() } }
        stream.start(stream.panelId, "first", 393, 655, 3.0)
        repeat(10) { pipe.feed(pipe.state(false)) }; runCurrent()
        assertTrue(stream.closed)
        assertTrue(runCatching { stream.input(stream.panelId, BrowserInput.Text("not sent")) }.isFailure)
        assertEquals(1, pipe.sent.count { it.getString("cmd") == "detach-attached-view" }); control.close()
    }
}
