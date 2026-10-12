package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf

/** ViewModel-owned connection data. No views, Activity callbacks or saved-state serialization. */
internal class NativeScreenConnectionState(initialCode: String,
    private val changed: (MobileRpcClient?, MobileRpcClient?) -> Unit = { previous, _ -> previous?.close() }) : AutoCloseable {
    private var selectedCode by mutableStateOf(initialCode)
    var code: String
        get() = selectedCode
        set(value) {
            if (selectedCode == value) return
            selectedCode = value; hostName = "cmux"; hostCapabilities = emptySet(); notifications = emptyList()
            terminalTransport = TerminalTransport.resolve(emptySet())
        }
    private var ownedClient by mutableStateOf<MobileRpcClient?>(null)
    var client: MobileRpcClient?
        get() = ownedClient
        set(value) {
            val previous = ownedClient
            if (previous === value) return
            ownedClient = value
            changed(previous, value)
        }
    var connectedCode by mutableStateOf<String?>(null)
    var connectionReady by mutableStateOf(false)
    var admittedRetry: Int? = null
    val replayGeneration = mutableIntStateOf(0)
    val viewportGeneration = mutableLongStateOf(0L)
    var hostName by mutableStateOf("cmux")
    var hostCapabilities by mutableStateOf<Set<String>>(emptySet())
    var notifications by mutableStateOf<List<NativeNotification>>(emptyList())
    var terminalTransport by mutableStateOf(TerminalTransport.resolve(emptySet()))

    fun retained(requested: String, retry: Int, permits: (MobileRpcClient) -> Boolean): MobileRpcClient? =
        client?.takeIf { !it.isClosed && connectionReady && connectedCode == requested && code == requested &&
            admittedRetry == retry && permits(it) }

    override fun close() { client = null; connectionReady = false; connectedCode = null; admittedRetry = null; code = "" }
}
