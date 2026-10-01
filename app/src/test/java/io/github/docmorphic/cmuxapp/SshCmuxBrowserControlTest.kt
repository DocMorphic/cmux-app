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
class SshCmuxBrowserControlTest {
    private class Pipe : SshExecPipe {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = incoming.receiveAsFlow()
        val sent = mutableListOf<JSONObject>()
        var guarded = true
        var block: String? = null
        var presentedState: JSONObject? = null
        var fail: String? = null
        var closed = false
        override fun close() { closed = true; incoming.close() }
        fun feed(value: JSONObject) { check(incoming.trySend((value.toString() + "\n").toByteArray()).isSuccess) }
        fun state(token: Long = 40, frame: Boolean = true) = JSONObject().put("event", "browser-state").put("surface", 7)
            .put("status", "live").put("pointer_frame_seq", token).put("cols", 80).put("rows", 24).also {
                if (frame) it.put("frame", JSONObject().put("seq", 9).put("width", 640).put("height", 480).put("data", "AA=="))
            }
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(bytes.toString(Charsets.UTF_8)); sent += request
            val command = request.getString("cmd")
            if (command == block) return
            val data = when (command) {
                "identify" -> JSONObject().put("app", "cmux-tui").put("version", "fixture").put("protocol", 12).put("session", "fixture").put("pid", 123)
                    .put("capabilities", JSONArray(listOfNotNull("workspace-registry-v1", "attach-initial-size", "view-attachment-lease-v1",
                        "view-attachment-detach-v1", SshCmuxBrowserWire.CAPABILITY.takeIf { guarded })))
                "attach-surface" -> { feed(state()); JSONObject().put("lease", "lease-${sent.size}") }
                "detach-attached-view" -> { feed(JSONObject().put("event", "detached").put("surface", 7)); JSONObject().put("outcome", "applied") }
                "get-cell-pixels" -> JSONObject().put("width_px", 8).put("height_px", 16)
                "resize-surface" -> JSONObject().put("accepted", true)
                else -> JSONObject()
            }
            if (command == "browser-frame-presented") presentedState?.let(::feed)
            feed(JSONObject().put("id", request.getString("id")).put("ok", command != fail).put("data", data).put("error", "fixture rejected"))
        }
    }
    private suspend fun ready(pipe: Pipe, client: SshCmuxControl, events: MutableList<SshCmuxBrowserEvent> = mutableListOf()): SshCmuxBrowserAttachment {
        client.handshake("fixture")
        return client.attachBrowser(7, 80, 24, events::add)
    }
    @Test fun attachNegotiatesGuardAndPresentedImageUsesItsPointerToken() = runTest {
        val pipe = Pipe(); val client = SshCmuxControl(pipe, backgroundScope)
        val view = ready(pipe, client)
        assertTrue(pipe.sent[1].getJSONArray("capabilities").toString().contains(SshCmuxBrowserWire.CAPABILITY))
        assertEquals(listOf("identify", "set-client-info", "attach-surface"), pipe.sent.map { it.getString("cmd") })
        assertFalse(client.browserInput(view, BrowserInput.Click(3.0, 4.0)))
        assertTrue(client.browserFrameDisplayed(view, 9)); assertEquals(40, pipe.sent.last().getInt("frame_seq"))
        assertFalse(client.browserFrameDisplayed(view, 9))
        assertTrue(client.browserInput(view, BrowserInput.Click(3.0, 4.0, 2)))
        val click = pipe.sent.takeLast(2)
        assertEquals(listOf("down", "up"), click.map { it.getString("kind") })
        assertTrue(click.all { it.getLong("frame_seq") == 40L && it.getInt("click_count") == 2 })
        assertTrue(client.browserInput(view, BrowserInput.Scroll(8.0, 12.0, 3.0, 4.0, "changed")))
        assertEquals(12.0, pipe.sent.last().getDouble("delta_y_px"), 0.0)
        assertFalse(pipe.sent.last().has("delta_x_px"))
        client.close()
    }
    @Test fun authorityRevokedDuringAcknowledgementNeverBecomesClickable() = runTest {
        val pipe = Pipe(); val client = SshCmuxControl(pipe, backgroundScope); val view = ready(pipe, client)
        pipe.presentedState = pipe.state(41, false)
        assertFalse(client.browserFrameDisplayed(view, 9))
        assertFalse(client.browserInput(view, BrowserInput.Click(1.0, 1.0)))
        assertEquals(4, pipe.sent.size)
        client.close()
    }
    @Test fun rejectedPointerIsNotReplayedOrReauthorizedOnResume() = runTest {
        val pipe = Pipe(); val client = SshCmuxControl(pipe, backgroundScope); val view = ready(pipe, client)
        client.browserFrameDisplayed(view, 9); pipe.fail = "browser-mouse-guarded"
        assertTrue(runCatching { client.browserInput(view, BrowserInput.Click(1.0, 1.0)) }.isFailure)
        assertEquals("down", pipe.sent.last().getString("kind"))
        assertEquals(1, pipe.sent.count { it.optString("cmd") == "browser-mouse-guarded" })
        assertFalse(client.browserInput(view, BrowserInput.Click(1.0, 1.0)))
        client.close()
    }
    @Test fun resizeAndNavigationRevokeOldPixelsAndKeysUseCdpFields() = runTest {
        val pipe = Pipe(); val client = SshCmuxControl(pipe, backgroundScope); val view = ready(pipe, client)
        assertEquals(8 to 16, client.browserCellPixels())
        client.browserFrameDisplayed(view, 9)
        assertTrue(client.resizeBrowser(view, 90, 30)); assertNull(view.pointer.token)
        assertFalse(pipe.sent.any { it.optString("cmd") == "set-client-sizing" })
        client.browserInput(view, BrowserInput.Key("return", listOf("control")))
        assertEquals("browser-key-press", pipe.sent.last().getString("cmd")); assertFalse(pipe.sent.last().has("text"))
        client.browserInput(view, BrowserInput.Text("λ 中")); assertEquals("λ 中", pipe.sent.last().getString("text"))
        client.browserInput(view, BrowserInput.Navigation("navigate", "http://localhost:3000/"))
        assertEquals("browser-navigate", pipe.sent.last().getString("cmd")); assertNull(view.pointer.token)
        client.close()
    }
    @Test fun detachIsFencedAndAReattachedSurfaceDoesNotInheritTokens() = runTest {
        val pipe = Pipe(); val client = SshCmuxControl(pipe, backgroundScope)
        val events = mutableListOf<SshCmuxBrowserEvent>(); val view = ready(pipe, client, events)
        client.browserFrameDisplayed(view, 9)
        assertTrue(runCatching { client.attach(7, 80, 24) {} }.isFailure)
        client.detach(view); assertEquals(1, events.count { it is SshCmuxBrowserEvent.Ended })
        assertTrue(runCatching { client.browserInput(view, BrowserInput.Text("stale")) }.isFailure)
        val next = client.attachBrowser(7, 80, 24) {}
        assertNull(next.pointer.token); assertNotEquals(view.lease, next.lease); assertFalse(client.closed)
        client.close()
    }
    @Test fun missingGuardRefusesAttachAndMalformedFrameRetiresTheRelay() = runTest {
        val old = Pipe().apply { guarded = false }; val unsupported = SshCmuxControl(old, backgroundScope)
        unsupported.handshake("fixture")
        assertTrue(runCatching { unsupported.attachBrowser(7, 80, 24) {} }.isFailure)
        assertEquals(2, old.sent.size); unsupported.close()
        val pipe = Pipe(); val client = SshCmuxControl(pipe, backgroundScope)
        val events = mutableListOf<SshCmuxBrowserEvent>(); ready(pipe, client, events)
        pipe.feed(JSONObject("""{"event":"frame","surface":7,"seq":9.5,"data":"AA=="}""")); runCurrent()
        assertTrue(client.closed); assertEquals(SshCmuxBrowserEvent.Ended(true), events.last())
    }
    @Test fun uncertainAttachAndInputCloseWithoutReplayingTheirCommands() = runTest {
        val pipe = Pipe().apply { block = "attach-surface" }; val client = SshCmuxControl(pipe, backgroundScope)
        client.handshake("fixture")
        val opening = async { runCatching { client.attachBrowser(7, 80, 24) {} } }; runCurrent(); opening.cancelAndJoin()
        assertTrue(client.closed); assertEquals(1, pipe.sent.count { it.optString("cmd") == "attach-surface" })
        val second = Pipe(); val next = SshCmuxControl(second, backgroundScope, timeoutMillis = 50)
        val view = ready(second, next); second.block = "browser-insert-text"
        assertTrue(runCatching { next.browserInput(view, BrowserInput.Text("once")) }.isFailure)
        assertTrue(next.closed); assertEquals(1, second.sent.count { it.optString("cmd") == "browser-insert-text" })
    }
}
