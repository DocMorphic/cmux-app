package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** A caller retains the admitted Mac/account/connection, not a reusable public locator. */
internal class NativeChiefClient(private val rpc: MobileRpcClient, private val permits: () -> Boolean) {
    private fun current() = !rpc.isClosed && permits()
    private suspend fun <T> call(method: String, params: JSONObject = JSONObject(), wait: Boolean = false,
                                 decode: (JSONObject) -> T): T {
        check(current()) { "Chief computer or account changed" }
        val response = rpc.chiefRequest(method, params, if (wait) 600_000 else 15_000, ::current)
        currentCoroutineContext().ensureActive()
        check(current()) { "Chief computer or account changed" }
        val result = decode(response)
        currentCoroutineContext().ensureActive()
        check(current()) { "Chief computer or account changed" }
        return result
    }
    suspend fun status() = call("chief.status", decode = NativeChiefWire::status)
    suspend fun conversations(afterCursor: String? = null, waitForChange: Boolean = false): NativeChiefSnapshot =
        call("chief.conversations", JSONObject().apply {
            afterCursor?.let { put("after_cursor", it) }
            if (waitForChange) put("wait_for_change", true)
        }, waitForChange, NativeChiefWire::snapshot)

    suspend fun read(conversation: String? = null, beforeSeq: ULong? = null, limit: Int = 20,
                     afterSeq: ULong? = null, afterCursor: String? = null, waitForChange: Boolean = false): NativeChiefRead =
        call("chief.read", JSONObject().put("limit", limit.coerceIn(1, 200)).apply {
            conversation?.let { put("conversation", it) }
            beforeSeq?.let { put("before_seq", NativeChiefJson.unsigned(it)) }
            afterSeq?.let { put("after_seq", NativeChiefJson.unsigned(it)) }
            afterCursor?.let { put("after_cursor", it) }
            if (waitForChange) put("wait_for_change", true)
        }, waitForChange, NativeChiefWire::read)

    suspend fun markRead(conversation: String? = null, upToSeq: ULong): NativeChiefMarked =
        call("chief.mark_read", JSONObject().put("up_to_seq", NativeChiefJson.unsigned(upToSeq)).apply {
            conversation?.let { put("conversation", it) }
        }, decode = NativeChiefWire::marked)

    suspend fun send(text: String, conversation: String? = null, from: String? = null,
                     startTurn: Boolean = true, clientMessageId: String? = null): NativeChiefSent =
        call("chief.send", JSONObject().put("text", text).put("start_turn", startTurn).apply {
            conversation?.let { put("conversation", it) }
            from?.let { put("from", it) }
            clientMessageId?.let { put("client_message_id", it) }
        }, decode = NativeChiefWire::sent)

    suspend fun stop(conversation: String? = null) = call("chief.stop", JSONObject().apply {
        conversation?.let { put("conversation", it) }
    }, decode = NativeChiefWire::stopped)

    suspend fun approve(conversation: String? = null, request: String? = null, choice: String) =
        call("chief.approve", JSONObject().put("choice", choice).apply {
            conversation?.let { put("conversation", it) }; request?.let { put("request", it) }
        }, decode = NativeChiefWire::approved)

    suspend fun answer(conversation: String? = null, message: String? = null,
                       choices: List<String> = emptyList(), text: String? = null) =
        call("chief.answer", JSONObject().put("choices", JSONArray(choices)).apply {
            conversation?.let { put("conversation", it) }; message?.let { put("message", it) }; text?.let { put("text", it) }
        }, decode = NativeChiefWire::answered)

    suspend fun open(windowId: String? = null, workspaceId: String? = null) = call("chief.open", JSONObject().apply {
        windowId?.let { put("window_id", it) }; workspaceId?.let { put("workspace_id", it) }
    }, decode = NativeChiefWire::opened)
}
