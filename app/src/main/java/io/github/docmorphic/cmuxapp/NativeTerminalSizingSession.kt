package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal object TerminalSizingTraffic {
    const val CAPABILITY = "terminal.shared_sizing.v1"
    val topics = listOf("mobile.terminal.size_state", "mobile.terminal.detached")
    fun guarded(method: String): Boolean = method.removePrefix("mobile.") in setOf(
        "terminal.input", "terminal.paste", "terminal.paste_image", "terminal.mouse", "terminal.scroll",
        "terminal.viewport", "terminal.replay")
}

/** Retained with the foreground account/Mac, including across Activity and connection replacement.
 * No RPC or external callbacks run while holding this monitor (wire callbacks take it too).
 */
internal class NativeTerminalSizingSession {
    data class Snapshot(val state: TerminalSizeState?, val selfId: String?, val detached: TerminalDetach?,
        val reconnecting: Boolean, val viewportRevision: Long, val detachRevision: Long) {
        val allowsTraffic get() = detached == null
    }
    private val lock = Any()
    private var owner: TerminalInputSender.Owner? = null
    private var client: MobileRpcClient? = null
    private var observer: AutoCloseable? = null
    private val surfaces = linkedMapOf<String, TerminalSizingSurface>()
    private val rendered = mutableMapOf<String, SharedTerminalGrid>()
    private val snapshots = MutableStateFlow<Map<String, Snapshot>>(emptyMap())
    val state = snapshots.asStateFlow()
    private var stream: String? = null

    fun retainOwner(next: TerminalInputSender.Owner?) {
        val previous = synchronized(lock) {
            if (owner == next) return
            owner = next; client = null; stream = null; surfaces.clear(); rendered.clear(); publish()
            observer.also { observer = null }
        }
        previous?.close()
    }
    fun bind(nextOwner: TerminalInputSender.Owner, next: MobileRpcClient): String {
        retainOwner(nextOwner)
        val id = UUID.randomUUID().toString()
        val previous = synchronized(lock) {
            client = next; stream = id
            surfaces.values.forEach { it.connectionEnded() }; rendered.clear(); publish()
            observer.also { observer = null }
        }
        previous?.close()
        next.terminalTrafficAllowed = { surface -> synchronized(lock) {
            client === next && surfaces[surface]?.allowsTraffic != false
        } }
        val registration = next.observeTerminalSizing { event -> receive(next, id, event) }
        val kept = synchronized(lock) {
            if (client === next && stream == id) { observer = registration; true } else false
        }
        if (!kept) registration.close()
        return id
    }
    fun unbind(previous: MobileRpcClient) {
        val registration = synchronized(lock) {
            if (client !== previous) return
            client = null; stream = null
            surfaces.values.forEach { it.connectionEnded() }; rendered.clear(); publish()
            observer.also { observer = null }
        }
        registration?.close()
    }
    private fun receive(source: MobileRpcClient, id: String, event: MobileRpcClient.Event) = synchronized(lock) {
        if (client !== source || stream != id || (event.streamId != null && event.streamId != id)) return@synchronized
        val surface = event.payload.optString("surface_id").takeIf { it.isNotBlank() } ?: return@synchronized
        // A malformed size update must not close the shared transport. Malformed detach still stops traffic.
        when (event.topic) {
            "mobile.terminal.size_state" -> runCatching {
                val value = TerminalSizeState.decode(event.payload.getJSONObject("state"))
                model(surface).apply(value, selfId(event.payload), rendered[surface])
            }
            "mobile.terminal.detached" -> {
                val eventValue = runCatching { TerminalDetach.decode(event.payload) }
                    .getOrElse { TerminalDetach("unknown", null, null, null) }
                model(surface).detach(eventValue)
            }
        }
        publish()
    }
    fun replay(source: MobileRpcClient, surface: String, response: JSONObject) = synchronized(lock) {
        if (client !== source) return@synchronized
        val model = model(surface)
        val sizing = response.optJSONObject("size_state")?.let { runCatching { TerminalSizeState.decode(it) }.getOrNull() }
        if (sizing != null) model.apply(sizing, selfId(response), null) else model.recoveredFromNetwork()
        publish()
    }
    /** Only the caller that received an acknowledged explicit reattach may invoke this. */
    fun reattached(source: MobileRpcClient, surface: String, response: JSONObject, expected: Snapshot): Boolean = synchronized(lock) {
        if (client !== source || snapshots.value[surface]?.detachRevision != expected.detachRevision) return@synchronized false
        model(surface).reattached(response.optJSONObject("size_state")?.let { runCatching { TerminalSizeState.decode(it) }.getOrNull() }, selfId(response))
        publish(); true
    }
    fun rendered(source: MobileRpcClient, surface: String, grid: SharedTerminalGrid) = synchronized(lock) {
        if (client === source) rendered[surface] = grid
    }
    fun subscription(source: MobileRpcClient): String? = synchronized(lock) { stream.takeIf { client === source } }
    fun clear() = retainOwner(null)
    private fun model(surface: String) = surfaces.getOrPut(surface) { TerminalSizingSurface() }
    private fun selfId(value: JSONObject) = if (value.isNull("self_participant_id")) null else
        value.optString("self_participant_id").takeIf { it.isNotBlank() }
    private fun publish() {
        snapshots.value = surfaces.mapValues { (_, s) -> Snapshot(s.state, s.selfParticipantId, s.detached, s.reconnecting, s.viewportRevision, s.detachRevision) }
    }
}
