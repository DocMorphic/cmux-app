package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground feed sessions are independent of terminal navigation. Call on the owning UI scope. */
internal class NativeFeedCoordinator(
    private val scope: CoroutineScope,
    private val connect: suspend (NativeCredentialStore.PairedMac) -> MobileRpcClient,
    private val isAllowed: (NativeCredentialStore.PairedMac) -> Boolean
) : AutoCloseable {
    private class Handle(val mac: NativeCredentialStore.PairedMac) {
        var client: MobileRpcClient? = null
        var job: Job? = null
        val mutex = Mutex()
        val signal = Channel<Unit>(Channel.CONFLATED)
    }
    private val handles = mutableMapOf<String, Handle>()
    private val mutableSources = MutableStateFlow<Map<String, NativeFeedSource>>(emptyMap())
    val sources = mutableSources.asStateFlow()

    fun updateMacs(macs: List<NativeCredentialStore.PairedMac>) {
        val allowed = macs.filter(isAllowed).associateBy { it.origin }
        handles.keys.toList().filter { allowed[it] != handles[it]?.mac }.forEach { remove(it) }
        mutableSources.value = mutableSources.value.filterKeys { it in allowed }
        for ((origin, mac) in allowed) if (origin !in handles) {
            val handle = Handle(mac)
            handles[origin] = handle
            publish(handle, (mutableSources.value[origin] ?: NativeFeedSource(mac))
                .copy(mac = mac, availability = NativeFeedAvailability.CONNECTING, error = null))
            handle.job = scope.launch { monitor(handle) }
        }
    }

    fun pause() {
        handles.keys.toList().forEach(::remove)
        mutableSources.value = mutableSources.value.mapValues { (_, source) -> source.copy(availability = NativeFeedAvailability.OFFLINE) }
    }
    override fun close() { pause(); mutableSources.value = emptyMap() }
    private fun remove(origin: String) {
        handles.remove(origin)?.let { it.job?.cancel(); it.client?.close(); it.signal.close() }
    }
    private fun current(handle: Handle, client: MobileRpcClient? = handle.client) =
        handles[handle.mac.origin] === handle && handle.client === client && isAllowed(handle.mac)
    private fun publish(handle: Handle, source: NativeFeedSource) {
        if (current(handle)) mutableSources.value = mutableSources.value + (handle.mac.origin to source)
    }

    private suspend fun monitor(handle: Handle) {
        while (currentCoroutineContext().isActive && current(handle)) {
            var active: MobileRpcClient? = null
            try {
                check(handle.mac.deviceId.isNotBlank()) { "Reconnect this computer to confirm its identity." }
                active = connect(handle.mac)
                ensureActiveSession(handle)
                handle.client = active
                handle.mac.requireMatchingHost(active.hostStatus())
                val client = active
                coroutineScope {
                    val events = launch(start = CoroutineStart.UNDISPATCHED) {
                        client.events.collect { event ->
                            if (event.topic in FEED_TOPICS) handle.signal.trySend(Unit)
                        }
                    }
                    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
                        client.disconnected.collect { throw it }
                    }
                    try {
                        client.subscribe(FEED_TOPICS)
                        while (isActive && current(handle, client)) {
                            fetch(handle, client)
                            withTimeoutOrNull(30_000) { handle.signal.receive() }
                        }
                    } finally { events.cancel(); disconnect.cancel() }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val source = mutableSources.value[handle.mac.origin] ?: NativeFeedSource(handle.mac)
                publish(handle, source.copy(availability = NativeFeedAvailability.OFFLINE,
                    error = failure.message ?: "Computer unavailable"))
            } finally { active?.close(); handle.client = null }
            withTimeoutOrNull(10_000) { handle.signal.receive() }
        }
    }
    private suspend fun ensureActiveSession(handle: Handle) {
        currentCoroutineContext().ensureActive()
        if (!current(handle)) throw CancellationException("Saved computer changed")
    }
    private suspend fun fetch(handle: Handle, client: MobileRpcClient) = handle.mutex.withLock {
        if (!current(handle, client)) return@withLock
        val listing = client.workspaces()
        val response = client.notifications()
        if (!current(handle, client)) return@withLock
        val old = mutableSources.value[handle.mac.origin]
        val revision = response.optLong("revision", -1)
        if (revision >= 0 && old != null && revision < old.revision) return@withLock
        publish(handle, NativeFeedSource(handle.mac, parseNotifications(response), parseWorkspaces(listing),
            NativeFeedAvailability.CONNECTED, revision))
    }

    suspend fun refresh() = coroutineScope {
        handles.values.toList().map { handle -> async {
            val client = handle.client
            if (client == null) handle.signal.trySend(Unit)
            else try { fetch(handle, client) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                handle.signal.trySend(Unit)
            }
        } }.awaitAll()
        Unit
    }

    suspend fun setRead(entry: NativeFeedEntry, read: Boolean) {
        val handle = handles[entry.source.mac.origin] ?: error("Connect to ${entry.computer} to change this notification.")
        mutate(handle, listOf(entry.notification.id), read, all = false)
    }
    suspend fun markAllRead() = coroutineScope {
        val targets = mutableSources.value.values.filter { it.items.any { item -> !item.isRead } }
        val failures = targets.map { source -> async {
            try {
                val handle = handles[source.mac.origin] ?: error("Computer unavailable")
                mutate(handle, source.items.map { it.id }, read = true, all = true)
                null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                source.mac.name
            }
        } }.awaitAll().filterNotNull()
        check(failures.isEmpty()) { "Could not mark notifications read on: ${failures.joinToString()}. Reconnect and retry." }
    }
    private suspend fun mutate(handle: Handle, ids: List<String>, read: Boolean, all: Boolean) = handle.mutex.withLock {
        val client = handle.client ?: error("${handle.mac.name} is offline.")
        check(current(handle, client)) { "Saved computer changed" }
        val response = if (all) client.markAllNotificationsRead() else client.setNotificationRead(ids.single(), read)
        if (!current(handle, client)) return@withLock
        val source = mutableSources.value[handle.mac.origin] ?: return@withLock
        val revision = response.optLong("revision", -1)
        if (revision < 0 || revision >= source.revision) publish(handle, source.copy(
            items = source.items.map { if (all || it.id in ids) it.copy(isRead = read) else it },
            revision = maxOf(source.revision, revision)))
        handle.signal.trySend(Unit)
    }
    companion object {
        private val FEED_TOPICS = listOf("notification.feed.changed", "workspace.list.changed", "workspace.updated")
    }
}
