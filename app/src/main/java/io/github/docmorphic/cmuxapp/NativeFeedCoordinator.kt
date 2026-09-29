package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.*
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
    private class Handle(val mac: NativeCredentialStore.PairedMac, val revision: NativeFeedRevision) {
        var client: MobileRpcClient? = null
        var verified = false
        var job: Job? = null
        val mutex = Mutex()
        val refresh = NativeFeedRefresh()
    }
    private val handles = mutableMapOf<String, Handle>()
    private val revisions = mutableMapOf<String, NativeFeedRevision>()
    private val mutableSources = MutableStateFlow<Map<String, NativeFeedSource>>(emptyMap())
    val sources = mutableSources.asStateFlow()

    fun updateMacs(macs: List<NativeCredentialStore.PairedMac>) {
        val allowed = macs.filter(isAllowed).associateBy { it.origin }
        handles.keys.toList().filter { allowed[it] != handles[it]?.mac }.forEach { remove(it) }
        mutableSources.value = mutableSources.value.filterKeys { it in allowed }
        revisions.keys.retainAll(allowed.keys)
        for ((origin, mac) in allowed) if (origin !in handles) {
            val handle = Handle(mac, revisions.getOrPut(origin) { NativeFeedRevision() })
            handles[origin] = handle
            publish(handle, (mutableSources.value[origin] ?: NativeFeedSource(mac))
                .copy(mac = mac, availability = NativeFeedAvailability.CONNECTING, error = null, keepAwake = null))
            handle.job = scope.launch { monitor(handle) }
        }
    }

    fun pause() {
        handles.keys.toList().forEach(::remove)
        mutableSources.value = mutableSources.value.mapValues { (_, source) -> source.copy(availability = NativeFeedAvailability.OFFLINE, keepAwake = null) }
    }
    override fun close() { pause(); mutableSources.value = emptyMap(); revisions.clear() }
    private fun remove(origin: String) {
        // The monitor's finally releases its client after bounded stream cleanup.
        // Closing here would prevent unsubscribe while another consumer keeps the wire alive.
        handles.remove(origin)?.let { it.job?.cancel(); it.refresh.close() }
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
                val status = active.hostStatus()
                handle.mac.requireMatchingHost(status)
                ensureActiveSession(handle)
                handle.verified = true
                val capabilities = status.optJSONArray("capabilities")?.let { values ->
                    (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }.toSet()
                }.orEmpty()
                publish(handle, (mutableSources.value[handle.mac.origin] ?: NativeFeedSource(handle.mac))
                    .copy(capabilities = capabilities, keepAwake = null))
                val client = active
                coroutineScope {
                    val build = handle.mac.instanceTag ?: (status.opt("mac_instance_tag") as? String)?.takeIf { it.isNotBlank() }
                    val power = if ("caffeine.control.v1" in capabilities && build != null)
                        launch { observePower(handle, client, build) } else null
                    val events = launch(start = CoroutineStart.UNDISPATCHED) {
                        client.events.collect { event ->
                            if (event.topic == "notification.feed.changed") {
                                val revision = event.payload.optLong("revision", -1)
                                if (revision < 0 || handle.revision.observe(revision)) handle.refresh.request()
                            } else if (event.topic in FEED_TOPICS) handle.refresh.request()
                        }
                    }
                    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
                        client.disconnected.collect { throw it }
                    }
                    val stream = UUID.randomUUID().toString()
                    try {
                        client.subscribe(FEED_TOPICS, stream)
                        handle.refresh.run { fetch(handle, client) }
                    } finally {
                        power?.cancel(); events.cancel(); disconnect.cancel()
                        withContext(NonCancellable) {
                            if (!client.isClosed) withTimeoutOrNull(750) { runCatching { client.unsubscribe(stream) } }
                        }
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val source = mutableSources.value[handle.mac.origin] ?: NativeFeedSource(handle.mac)
                publish(handle, source.copy(availability = NativeFeedAvailability.OFFLINE,
                    error = failure.message ?: "Computer unavailable", keepAwake = null))
            } finally { handle.verified = false; active?.close(); handle.client = null }
            handle.refresh.awaitRequest(10_000)
        }
    }
    /** This observer shares the feed's existing lease and owns only its event stream.
     * Status failure must not interrupt workspace/notification delivery.
     */
    private suspend fun observePower(handle: Handle, client: MobileRpcClient, build: String) = coroutineScope {
        val session = NativeMacPowerSession(client,
            NativeComputerTarget(handle.mac.deviceId, build, handle.mac.name),
            permits = { current(handle, client) && handle.verified }, mutationGate = Mutex(),
            closeClientOnExit = false)
        val updates = launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.collect { power ->
                if (current(handle, client) && handle.verified) {
                    val source = mutableSources.value[handle.mac.origin] ?: return@collect
                    publish(handle, source.copy(keepAwake = power.enabled.takeIf { power.connected && power.supported == true }))
                }
            }
        }
        try { session.run() }
        finally {
            updates.cancelAndJoin()
            if (current(handle, client)) mutableSources.value[handle.mac.origin]?.let {
                publish(handle, it.copy(keepAwake = null))
            }
        }
    }

    private suspend fun ensureActiveSession(handle: Handle) {
        currentCoroutineContext().ensureActive()
        if (!current(handle)) throw CancellationException("Saved computer changed")
    }
    private suspend fun fetch(handle: Handle, client: MobileRpcClient) = handle.mutex.withLock {
        if (!current(handle, client)) throw CancellationException("Saved computer changed")
        check(handle.verified) { "Computer identity is still being verified." }
        val required = handle.revision.required()
        refreshWorkspaces(handle, client)
        val response = client.notifications()
        if (!current(handle, client)) throw CancellationException("Saved computer changed")
        val revision = response.optLong("revision", -1)
        if (!handle.revision.accept(revision, required)) return@withLock false
        val source = mutableSources.value[handle.mac.origin] ?: return@withLock false
        publish(handle, source.copy(items = parseNotifications(response),
            availability = NativeFeedAvailability.CONNECTED, revision = revision, error = null))
        !handle.revision.needsRefresh()
    }

    /** Workspace snapshots are independent of notification revision floors. Caller holds the handle mutex. */
    private suspend fun refreshWorkspaces(handle: Handle, client: MobileRpcClient) {
        val listing = client.workspaces()
        if (!current(handle, client)) throw CancellationException("Saved computer changed")
        val source = mutableSources.value[handle.mac.origin] ?: return
        publish(handle, source.copy(workspaces = parseWorkspaces(listing), groups = parseGroups(listing), hasWorkspaceSnapshot = true))
    }

    /** Never substitute the foreground Mac when a row's owning session is unavailable. */
    suspend fun workspaceAction(mac: NativeCredentialStore.PairedMac, workspaceId: String,
        action: String, title: String? = null): JSONObject = withContext(scope.coroutineContext.minusKey(Job)) {
        owningMutation(mac) { _, client ->
            // Resolve the latest window/group scope, not the possibly stale row captured by a menu.
            val source = mutableSources.value[mac.origin] ?: error("Computer unavailable")
            val workspace = source.workspaces.singleOrNull { it.id == workspaceId }
                ?: error("This workspace is no longer available on ${mac.name}.")
            when {
                action == "terminal.create" -> client.createTerminal(workspace.id)
                action == "browser.create" -> client.createBrowser(workspace.id)
                action == "close" -> client.closeWorkspace(workspace.id, workspace.windowId)
                action.startsWith("move:") -> {
                    check("workspace.move.v1" in source.capabilities) { "This Mac does not support moving workspaces." }
                    val groupId = action.removePrefix("move:").takeIf(String::isNotBlank)
                    check(groupId == null || source.groups.any { it.id == groupId }) { "This group is no longer available." }
                    client.moveWorkspace(workspace.id, workspace.windowId, groupId)
                }
                else -> client.workspaceAction(workspace.id, workspace.windowId, action, title)
            }
        }
    }

    suspend fun moveWorkspace(mac: NativeCredentialStore.PairedMac, workspaceId: String,
        intent: NativeWorkspaceMove, base: NativeWorkspaceOrder, canSend: () -> Boolean) =
        withContext(scope.coroutineContext.minusKey(Job)) {
            owningMutation(mac) { _, client ->
                val source = mutableSources.value[mac.origin] ?: error("Computer unavailable")
                check(canSend() && source.canReorderWorkspaces() && base.matches(source.workspaces, source.groups)) {
                    "Workspace order changed. Try moving it again."
                }
                val workspace = source.workspaces.singleOrNull { it.id == workspaceId }
                    ?: error("This workspace is no longer available.")
                check(intent.beforeWorkspaceId == null || source.workspaces.any { it.id == intent.beforeWorkspaceId && it.windowId == workspace.windowId }) {
                    "The destination workspace is no longer available in this window."
                }
                check(intent.groupId == null || source.groups.any { it.id == intent.groupId }) { "This group is no longer available." }
                check(!intent.movesGroup || source.groups.any { it.liveAnchorWorkspaceId == workspaceId }) { "This group anchor changed." }
                client.moveWorkspace(workspaceId, workspace.windowId, intent.groupId, intent.beforeWorkspaceId, intent.movesGroup)
            }
        }

    suspend fun groupAction(mac: NativeCredentialStore.PairedMac, groupId: String,
        action: String, title: String? = null): JSONObject = withContext(scope.coroutineContext.minusKey(Job)) {
        owningMutation(mac) { _, client ->
            val source = mutableSources.value[mac.origin] ?: error("Computer unavailable")
            check("workspace.group_actions.v1" in source.capabilities) { "This Mac does not support group actions." }
            check(source.groups.any { it.id == groupId }) { "This group is no longer available." }
            client.groupAction(groupId, action, title)
        }
    }

    private suspend fun owningMutation(mac: NativeCredentialStore.PairedMac,
        operation: suspend (Handle, MobileRpcClient) -> JSONObject): JSONObject {
        val handle = handles[mac.origin] ?: error("Connect to ${mac.name} to change this workspace.")
        check(handle.mac == mac && isAllowed(mac)) { "Saved computer changed" }
        return handle.mutex.withLock {
            val client = handle.client ?: error("${mac.name} is offline.")
            check(current(handle, client)) { "Saved computer changed" }
            check(handle.verified) { "Computer identity is still being verified." }
            try {
                val result = operation(handle, client)
                if (!current(handle, client)) throw CancellationException("Saved computer changed")
                result
            } finally {
                // Rejections/no-ops must also reconcile the owner's list. Never hide the original RPC error.
                if (currentCoroutineContext().isActive && current(handle, client)) {
                    try { refreshWorkspaces(handle, client) }
                    catch (refreshFailure: Exception) {
                        if (refreshFailure is CancellationException) throw refreshFailure
                        handle.refresh.request()
                    }
                    handle.refresh.request()
                }
            }
        }
    }

    suspend fun refresh() = withContext(scope.coroutineContext.minusKey(Job)) {
        handles.values.toList().map { handle -> async {
            val client = handle.client
            if (client == null || !handle.verified) handle.refresh.request()
            else try { if (!fetch(handle, client)) handle.refresh.request() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                handle.refresh.request()
            }
        } }.awaitAll()
        Unit
    }

    suspend fun setRead(entry: NativeFeedEntry, read: Boolean) = withContext(scope.coroutineContext.minusKey(Job)) {
        val handle = handles[entry.source.mac.origin] ?: error("Connect to ${entry.computer} to change this notification.")
        mutate(handle, listOf(entry.notification.id), read, all = false)
    }
    suspend fun markAllRead(selectedOrigin: String? = null) = withContext(scope.coroutineContext.minusKey(Job)) {
        val targets = mutableSources.value.values.filter {
            (selectedOrigin == null || it.mac.origin == selectedOrigin) && it.items.any { item -> !item.isRead }
        }
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
        check(handle.verified) { "Computer identity is still being verified." }
        val response = if (all) client.markAllNotificationsRead() else client.setNotificationRead(ids.single(), read)
        if (!current(handle, client)) throw CancellationException("Saved computer changed")
        val source = mutableSources.value[handle.mac.origin] ?: return@withLock
        val revision = response.optLong("revision", -1)
        if (handle.revision.acknowledge(revision)) publish(handle, source.copy(
            items = source.items.map { if (all || it.id in ids) it.copy(isRead = read) else it }))
        handle.refresh.request()
    }
    companion object {
        private val FEED_TOPICS = listOf("notification.feed.changed", "workspace.list.changed", "workspace.updated")
    }
}
