package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** Rendering contract shared by native Mac RPC and SSH cmux-tui streams.
 * Implementations own their subscriptions; SSH identities never enter Mac RPC. */
internal interface BrowserStreamClient {
    data class Event(val topic: String, val payload: JSONObject, val streamId: String?)
    val clearsFrameOnRestart: Boolean get() = false
    val pageDescription: String get() = "Mac browser page"
    val reportsHistory: Boolean get() = true
    val events: Flow<Event>
    val disconnected: Flow<Throwable>
    suspend fun start(panel: String, stream: String, width: Int, height: Int, scale: Double): JSONObject
    suspend fun stop(panel: String, stream: String)
    suspend fun input(panel: String, input: BrowserInput)
    suspend fun viewport(panel: String, width: Int, height: Int, scale: Double)
    suspend fun displayed(panel: String, sequence: Long)
    suspend fun respondDialog(panel: String, id: String, button: String, text: String?)
}

internal class MacBrowserStreamClient(private val client: MobileRpcClient) : BrowserStreamClient {
    override val events = client.events.map { BrowserStreamClient.Event(it.topic, it.payload, it.streamId) }
    override val disconnected = client.disconnected
    override suspend fun start(panel: String, stream: String, width: Int, height: Int, scale: Double): JSONObject {
        val subscription = client.subscribe(listOf("browser.frame", "browser.state", "browser.closed", "browser.dialog", "browser.dialog.resolved"), stream)
        check(subscription.optString("stream_id") == stream) { "Browser subscription identity changed" }
        return client.startBrowserStream(panel, width, height, scale)
    }
    override suspend fun stop(panel: String, stream: String) {
        // Independent fences: a slow stop must not prevent subscription cleanup.
        try { kotlinx.coroutines.withTimeout(2_000) { client.stopBrowserStream(panel) } }
        finally { kotlinx.coroutines.withTimeout(2_000) { client.unsubscribe(stream) } }
    }
    override suspend fun input(panel: String, input: BrowserInput) { client.request(input.method, input.parameters(panel)) }
    override suspend fun viewport(panel: String, width: Int, height: Int, scale: Double) { client.browserViewport(panel, width, height, scale) }
    override suspend fun displayed(panel: String, sequence: Long) { client.acknowledgeBrowserFrame(panel, sequence) }
    override suspend fun respondDialog(panel: String, id: String, button: String, text: String?) { client.respondBrowserDialog(panel, id, button, text) }
}
