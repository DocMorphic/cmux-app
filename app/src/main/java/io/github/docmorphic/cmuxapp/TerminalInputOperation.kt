package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Captures the destination and immutable bytes before an operation enters a session queue. */
internal sealed class TerminalInputOperation(val workspace: String, val surface: String) {
    abstract val byteCount: Int
    abstract suspend fun rpc(client: MobileRpcClient, delivery: TerminalInputDelivery?): JSONObject
    class Text(workspace: String, surface: String, val text: String) : TerminalInputOperation(workspace, surface) {
        override val byteCount = text.toByteArray(Charsets.UTF_8).size
        override suspend fun rpc(client: MobileRpcClient, delivery: TerminalInputDelivery?) = client.input(workspace, surface, text, delivery)
    }
    class Paste(workspace: String, surface: String, val text: String, val submit: Boolean) : TerminalInputOperation(workspace, surface) {
        override val byteCount = text.toByteArray(Charsets.UTF_8).size
        override suspend fun rpc(client: MobileRpcClient, delivery: TerminalInputDelivery?) = client.paste(workspace, surface, text, submit, delivery)
    }
    class Image(workspace: String, surface: String, bytes: ByteArray, val format: String) : TerminalInputOperation(workspace, surface) {
        private val snapshot = bytes.copyOf()
        override val byteCount = snapshot.size
        override suspend fun rpc(client: MobileRpcClient, delivery: TerminalInputDelivery?) = client.pasteImage(workspace, surface, snapshot, format, delivery)
    }
    class Click(workspace: String, surface: String, val cell: TerminalGeometry.Cell) : TerminalInputOperation(workspace, surface) {
        override val byteCount = 1
        override suspend fun rpc(client: MobileRpcClient, delivery: TerminalInputDelivery?) = client.terminalClick(workspace, surface, cell, delivery)
    }
    class Scroll(workspace: String, surface: String, val scroll: TerminalScroll) : TerminalInputOperation(workspace, surface) {
        override val byteCount = 1
        override suspend fun rpc(client: MobileRpcClient, delivery: TerminalInputDelivery?) = client.terminalScroll(workspace, surface, scroll, delivery)
    }
}
