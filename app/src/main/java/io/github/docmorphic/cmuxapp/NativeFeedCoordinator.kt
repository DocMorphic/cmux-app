package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground feed sessions are independent of terminal navigation. Call on the owning UI scope. */
internal class NativeFeedCoordinator(
    private val scope: CoroutineScope,
    private val connect: suspend (NativeCredentialStore.PairedMac) -> MobileRpcClient,
    private val isAllowed: (NativeCredentialStore.PairedMac) -> Boolean,
    private val workspaceSnapshots: NativeWorkspaceSnapshots = NativeWorkspaceSnapshots(),
    private val onVerified: (NativeCredentialStore.PairedMac) -> Unit = {},
    private val refreshIdentity: suspend (NativeCredentialStore.PairedMac, MobileRpcClient, JSONObject) -> Boolean = { _, _, _ -> false }
) : AutoCloseable {
    private class Handle(val mac: NativeCredentialStore.PairedMac, val revision: NativeFeedRevision, val routeKey: String?) {
        var client: MobileRpcClient? = null
        var verified = false
        var workspaceSnapshotClient: MobileRpcClient? = null
        var capabilities = emptySet<String>()
        val borrowedOperations = mutableSetOf<Job>()
        var job: Job? = null
        val mutex = Mutex()
        val refresh = NativeFeedRefresh()
        var changes: WorkspaceChangesSummarySession? = null
        var changesListEvent = false
        var power: NativeMacPowerSession? = null
        var agentFeed: NativeAgentFeedSession? = null
    }
    private val handles = mutableMapOf<String, Handle>()
    private val revisions = mutableMapOf<String, NativeFeedRevision>()
    private val mutableSources = MutableStateFlow<Map<String, NativeFeedSource>>(emptyMap())
    val sources = mutableSources.asStateFlow()
    private val mutableTaskModelIdentities = MutableStateFlow<Map<String, Any>>(emptyMap())
    val taskModelIdentities = mutableTaskModelIdentities.asStateFlow()
    private fun publishTaskModelIdentities() {
        // Topology observation only. Fresh account/route admission still occurs
        // when creating and invoking a target; Feed payload changes are not inputs.
        mutableTaskModelIdentities.value = buildMap {
            handles.values.forEach { handle -> handle.client?.takeIf { handle.verified && !it.isClosed }?.let {
                put(handle.mac.origin, it.compatibilityWire)
            } }
        }
    }

    /** Optional discovery borrows this exact verified client without affecting its health poll. */
    fun taskModelTarget(mac: NativeCredentialStore.PairedMac): TaskModelPrefetch.Target {
        val handle = handles[mac.origin]?.takeIf { it.mac == mac && it.verified }
        val client = handle?.client?.takeIf { !it.isClosed && current(handle, it) }
        if (handle == null || client == null) return TaskModelPrefetch.Target(mac.origin, null)
        return TaskModelPrefetch.Target(mac.origin, client.compatibilityWire) { provider ->
            withContext(scope.coroutineContext.minusKey(Job)) {
                check(current(handle, client) && handle.verified) { "Model discovery connection changed" }
                val operation = checkNotNull(currentCoroutineContext()[Job])
                handle.borrowedOperations += operation
                try {
                    val result = withContext(Dispatchers.IO) {
                        TaskModelParser.host(client.request("mobile.task.models.list",
                            JSONObject().put("provider", provider.wireName)))
                    }
                    ensureActive()
                    check(current(handle, client) && handle.verified) { "Model discovery connection changed" }
                    result
                } finally { handle.borrowedOperations.remove(operation) }
            }
        }
    }

    fun updateMacs(macs: List<NativeCredentialStore.PairedMac>, routeKeys: Map<String, String> = emptyMap(),
        localRouteKeys: Map<NativeMacIdentity, String> = emptyMap()) {
        fun routeKey(mac: NativeCredentialStore.PairedMac) =
            localRouteKeys[NativeMacIdentity(canonicalMacDeviceId(mac.deviceId), mac.instanceTag)] ?:
                (PairingCodeParser.parse(mac.code).getOrNull() as? PairingCode.Iroh)?.endpointId?.let(routeKeys::get)
        val allowed = macs.filter(isAllowed).associateBy { it.origin }
        handles.keys.toList().filter { origin -> allowed[origin] != handles[origin]?.mac ||
            allowed[origin]?.let(::routeKey) != handles[origin]?.routeKey }.forEach { remove(it) }
        mutableSources.value = mutableSources.value.filterKeys { it in allowed }
        revisions.keys.retainAll(allowed.keys)
        for ((origin, mac) in allowed) if (origin !in handles) {
            val handle = Handle(mac, revisions.getOrPut(origin) { NativeFeedRevision() }, routeKey(mac))
            handles[origin] = handle
            publish(handle, (mutableSources.value[origin] ?: NativeFeedSource(mac))
                .copy(mac = mac, availability = NativeFeedAvailability.CONNECTING, error = null, keepAwake = null, changes = emptyMap()))
            handle.job = scope.launch { monitor(handle) }
        }
    }

    /** Reuse this verified session's power controller; the Details page does not dial or own it. */
    fun powerSession(team: NativeTeamScope, target: NativeComputerTarget): NativeMacPowerSession? {
        val handle = handles.values.singleOrNull { NativeComputerTarget.from(it.mac, team)?.let { saved ->
            canonicalMacDeviceId(saved.deviceId) == canonicalMacDeviceId(target.deviceId) && saved.buildTag == target.buildTag
        } == true } ?: return null
        val client = handle.client ?: return null
        return handle.power?.takeIf { current(handle, client) && handle.verified }
    }

    /** Return only the exact pairing's live, capability-admitted Feed session. */
    fun agentFeedSession(mac: NativeCredentialStore.PairedMac): NativeAgentFeedSession? =
        handles[mac.origin]?.takeIf { it.mac == mac && current(it) && it.verified &&
            it.client?.isClosed == false && AGENT_FEED_CAPABILITY in it.capabilities }?.agentFeed

    /** Drop retired computers even while paused, without dialing the retained set. */
    fun retainMacs(macs: List<NativeCredentialStore.PairedMac>) {
        val allowed = macs.filter(isAllowed).associateBy { it.origin }
        handles.keys.toList().filter { allowed[it] != handles[it]?.mac }.forEach(::remove)
        mutableSources.value = mutableSources.value.filter { (origin, source) -> allowed[origin] == source.mac }
        revisions.keys.retainAll(allowed.keys)
    }

    fun pause() {
        handles.keys.toList().forEach(::remove)
        mutableSources.value = mutableSources.value.mapValues { (_, source) -> source.copy(availability = NativeFeedAvailability.OFFLINE, keepAwake = null, changes = emptyMap(),
            agentFeed = source.agentFeed.copy(loading = false, pending = emptySet())) }
    }
    override fun close() { pause(); mutableSources.value = emptyMap(); revisions.clear() }
    private fun remove(origin: String) {
        // The monitor's finally releases its client after bounded stream cleanup.
        // Closing here would prevent unsubscribe while another consumer keeps the wire alive.
        handles.remove(origin)?.let { cancelBorrowedOperations(it); it.changes?.close(); it.agentFeed?.close(); it.job?.cancel(); it.refresh.close() }
        publishTaskModelIdentities()
    }
    private fun cancelBorrowedOperations(handle: Handle) {
        handle.borrowedOperations.toList().forEach { it.cancel(CancellationException("Borrowed computer connection changed")) }
        handle.borrowedOperations.clear()
    }

    /** Fresh admission is checked on the feed's owner dispatcher, never against cached UI capabilities. */
    fun browserAccess(mac: NativeCredentialStore.PairedMac, permits: () -> Boolean): MacBrowserAccess = object : MacBrowserAccess {
        private fun handle(): Handle? = handles[mac.origin]?.takeIf {
            it.mac == mac && permits() && current(it) && it.verified && it.client?.isClosed == false
        }
        private fun availability(handle: Handle?) = when {
            handle == null -> MacBrowserAvailability.NOT_CONNECTED
            BrowserTunnelProtocol.CAPABILITY !in handle.capabilities -> MacBrowserAvailability.NEEDS_MAC_UPDATE
            handle.client?.supportsBrowserTunnels != true -> MacBrowserAvailability.ROUTE_WITHOUT_LANES
            else -> MacBrowserAvailability.AVAILABLE
        }
        override suspend fun availability() = withContext(scope.coroutineContext.minusKey(Job)) { availability(handle()) }
        private suspend fun <T> admitted(action: suspend (MobileRpcClient) -> T): T =
            withContext(scope.coroutineContext.minusKey(Job)) {
                val handle = handle()
                check(availability(handle) == MacBrowserAvailability.AVAILABLE) { "Browser computer is unavailable" }
                val active = checkNotNull(handle)
                val client = checkNotNull(active.client)
                val operation = checkNotNull(currentCoroutineContext()[Job])
                active.borrowedOperations += operation
                try {
                    val result = withContext(Dispatchers.IO) { action(client) }
                    ensureActive()
                    check(handle() === active && active.client === client) { "Browser computer changed" }
                    result
                } finally { active.borrowedOperations -= operation }
            }
        override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) = admitted { client ->
            check(client.useBrowserTunnel(host, port, connected)) { "Browser route has no tunnel lanes" }
        }
        override suspend fun listeningPorts() = admitted { client ->
            checkNotNull(client.browserListeningPorts()) { "Browser route has no listing lane" }
        }
    }
    /** Borrow only a verified live feed channel; never refresh, reconnect or select a workspace. */
    fun replyAttempt(mac: NativeCredentialStore.PairedMac, target: PhoneReplyDirectTarget,
        permits: () -> Boolean): PhoneReplyDirectAttempt? {
        if (!mac.ownsOrigin(target.origin)) return null
        val handle = handles[mac.origin]?.takeIf { it.mac == mac && it.verified && current(it) } ?: return null
        val client = handle.client?.takeUnless { it.isClosed } ?: return null
        val workspace = target.resolve(mutableSources.value[mac.origin]?.workspaces.orEmpty()) ?: return null
        fun ready() = permits() && current(handle, client) && handle.verified && !client.isClosed &&
            target.resolve(mutableSources.value[mac.origin]?.workspaces.orEmpty()) == workspace && client.terminalTrafficAllowed(target.surface)
        if (!ready()) return null
        return PhoneReplyDirectAttempt(target, ::ready) { text, allowed ->
            if (!allowed()) false else {
                checkPhoneReplyPaste(client.paste(workspace, target.surface, text, submit = true))
                true
            }
        }
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
                if (refreshIdentity(handle.mac, active, status)) return
                ensureActiveSession(handle)
                handle.verified = true
                publishTaskModelIdentities()
                onVerified(handle.mac)
                val capabilities = status.optJSONArray("capabilities")?.let { values ->
                    (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }.toSet()
                }.orEmpty()
                handle.capabilities = capabilities
                publish(handle, (mutableSources.value[handle.mac.origin] ?: NativeFeedSource(handle.mac))
                    .copy(capabilities = capabilities, keepAwake = null, macMutationTicket = active.macMutationTicket()))
                val client = active
                coroutineScope {
                    client.macMutationTicket()?.expiresAtMillis?.let { expiry -> launch {
                        val now = System.currentTimeMillis()
                        delay(if (expiry <= now) 0 else expiry - now)
                        if (current(handle, client)) mutableSources.value[handle.mac.origin]?.let { source ->
                            publish(handle, source.copy(macMutationTicket = null))
                        }
                    } }
                    val summaryFailure = CompletableDeferred<Exception>()
                    val summaryDisconnect = launch { throw summaryFailure.await() }
                    val agentFeed = if (AGENT_FEED_CAPABILITY in capabilities) NativeAgentFeedSession(this,
                        admitted = { current(handle, client) && handle.verified && !client.isClosed },
                        request = { method, params -> client.request(method, params) },
                        initial = mutableSources.value[handle.mac.origin]?.agentFeed?.snapshot,
                        fatal = { failure -> summaryFailure.complete(failure) }).also { session ->
                        handle.agentFeed = session
                    } else null
                    val agentFeedUpdates = agentFeed?.let { session -> launch(start = CoroutineStart.UNDISPATCHED) {
                        session.state.collect { state ->
                            if (current(handle, client) && handle.verified) mutableSources.value[handle.mac.origin]?.let {
                                publish(handle, it.copy(agentFeed = state))
                            }
                        }
                    } }
                    val topics = FEED_TOPICS + if (agentFeed != null) listOf("feed.changed") else emptyList()
                    if (WORKSPACE_CHANGES_CAPABILITY in capabilities) {
                        handle.changes = WorkspaceChangesSummarySession(this,
                            admitted = { current(handle, client) && handle.verified && !client.isClosed },
                            fetch = client::workspaceChangesSummaries,
                            publish = { chips -> mutableSources.value[handle.mac.origin]?.let { publish(handle, it.copy(changes = chips)) } },
                            failed = { error ->
                                if (error is MobileRpcException && error.code in setOf("unauthorized", "forbidden", "permission_denied", "team_access_revoked")) {
                                    handle.verified = false
                                    mutableSources.value[handle.mac.origin]?.let { publish(handle, it.copy(changes = emptyMap(), panelCacheToken = null)) }
                                    summaryFailure.complete(error)
                                }
                            })
                    }
                    val build = handle.mac.instanceTag ?: (status.opt("mac_instance_tag") as? String)?.takeIf { it.isNotBlank() }
                    val power = if ("caffeine.control.v1" in capabilities && build != null)
                        launch { observePower(handle, client, build) } else null
                    val events = launch(start = CoroutineStart.UNDISPATCHED) {
                        client.events.collect { event ->
                            if (event.topic == "feed.changed") {
                                agentFeed?.changed(event.payload)
                            } else if (event.topic == "notification.feed.changed") {
                                val revision = event.payload.optLong("revision", -1)
                                if (revision < 0 || handle.revision.observe(revision)) handle.refresh.request()
                            } else if (event.topic in FEED_TOPICS) {
                                handle.refresh.request()
                                if (event.topic == "workspace.list.changed") handle.changesListEvent = true
                                else {
                                    val workspaceId = (event.payload.opt("workspace_id") as? String)?.takeIf(String::isNotEmpty)
                                    handle.changes?.request(workspaceId?.let(::listOf))
                                }
                            }
                        }
                    }
                    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
                        client.disconnected.collect { throw it }
                    }
                    val stream = UUID.randomUUID().toString()
                    try {
                        client.subscribe(topics, stream)
                        agentFeed?.start()
                        handle.refresh.run { fetch(handle, client) }
                    } finally {
                        agentFeed?.close(); agentFeedUpdates?.cancel()
                        power?.cancel(); events.cancel(); disconnect.cancel(); summaryDisconnect.cancel()
                        withContext(NonCancellable) {
                            if (!client.isClosed) withTimeoutOrNull(750) { runCatching { client.unsubscribe(stream) } }
                        }
                    }
                }
            } catch (failure: Exception) {
                // A request deadline must not permanently retire this Mac's
                // monitor. Parent/account/route cancellation must still stop it.
                currentCoroutineContext().ensureActive()
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                val source = mutableSources.value[handle.mac.origin] ?: NativeFeedSource(handle.mac)
                publish(handle, source.copy(availability = NativeFeedAvailability.OFFLINE,
                    error = failure.message ?: "Computer unavailable", keepAwake = null, changes = emptyMap(),
                    agentFeed = if (NativePanelCachePolicy.retains(failure)) source.agentFeed.copy(loading = false, pending = emptySet())
                        else NativeAgentFeedState(error = "Feed access is no longer authorized"),
                    panelCacheToken = source.panelCacheToken.takeIf { NativePanelCachePolicy.retains(failure) }))
            } finally { handle.agentFeed?.close(); handle.agentFeed = null; handle.changes?.close(); handle.changes = null; handle.verified = false; handle.capabilities = emptySet(); cancelBorrowedOperations(handle); active?.close(); handle.client = null; publishTaskModelIdentities() }
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
        handle.power = session
        try { session.run() }
        finally {
            if (handle.power === session) handle.power = null
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
        try { refreshWorkspaces(handle, client) }
        catch (_: NativeWorkspaceSnapshotSuperseded) { return@withLock false }
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
        val listing = try { workspaceSnapshots.read(handle.mac, client) }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            if (!NativePanelCachePolicy.retains(failure)) mutableSources.value[handle.mac.origin]?.let {
                publish(handle, it.copy(panelCacheToken = null))
            }
            throw failure
        }
        if (!current(handle, client)) throw CancellationException("Saved computer changed")
        val source = mutableSources.value[handle.mac.origin] ?: return
        if (!listing.accept()) throw NativeWorkspaceSnapshotSuperseded()
        handle.workspaceSnapshotClient = client
        val groups = parseGroups(listing.value)
        val groupOnly = groups != source.groups && listing.workspaces == source.workspaces
        publish(handle, source.copy(workspaces = listing.workspaces, groups = groups, hasWorkspaceSnapshot = true,
            panelCacheToken = source.panelCacheToken ?: Any()))
        handle.changes?.retain(listing.workspaces.map { it.id })
        if (handle.changesListEvent && !groupOnly) handle.changes?.request()
        handle.changesListEvent = false
    }

    fun changesAccess(mac: NativeCredentialStore.PairedMac, workspace: NativeWorkspace, permits: () -> Boolean): WorkspaceChangesAccess {
        fun ownsWorkspace() = permits() && isAllowed(mac) && mutableSources.value[mac.origin]?.let { source ->
            source.mac == mac && WORKSPACE_CHANGES_CAPABILITY in source.capabilities && source.workspaces.any { it.id == workspace.id }
        } == true
        fun admitted(): Handle? = handles[mac.origin]?.takeIf { handle ->
            handle.mac == mac && permits() && current(handle) && handle.verified &&
                WORKSPACE_CHANGES_CAPABILITY in handle.capabilities && handle.client?.isClosed == false &&
                mutableSources.value[mac.origin]?.workspaces?.any { it.id == workspace.id } == true
        }
        return WorkspaceChangesAccess(workspace.id, workspace.title, ::ownsWorkspace) { request ->
            withContext(scope.coroutineContext.minusKey(Job)) {
                val handle = checkNotNull(admitted()) { "Changes computer is unavailable" }
                val client = checkNotNull(handle.client)
                val result = withContext(Dispatchers.IO) { client.readChanges(workspace.id, request) }
                check(admitted() === handle && current(handle, client)) { "Changes computer changed" }
                result
            }
        }
    }

    /** Files may retain cached content across transport loss; requests keep exact connection admission. */
    fun terminalArtifactAccess(mac: NativeCredentialStore.PairedMac, terminal: ArtifactAuthorization.Terminal,
                               permits: () -> Boolean): TerminalArtifactAccess? {
        val handle = handles[mac.origin]?.takeIf { it.mac == mac && current(it) && it.verified } ?: return null
        val client = handle.client?.takeUnless { it.isClosed } ?: return null
        val capabilities = ArtifactCapabilities.read(handle.capabilities)
        val cacheToken = mutableSources.value[mac.origin]?.panelCacheToken ?: return null
        if (handle.workspaceSnapshotClient !== client) return null
        fun cachedCurrent() = permits() && isAllowed(mac) && mutableSources.value[mac.origin]?.let { source ->
            source.mac == mac && source.panelCacheToken === cacheToken &&
                ArtifactCapabilities.read(source.capabilities) == capabilities && source.workspaces.any { workspace ->
                    workspace.id == terminal.workspaceId && workspace.terminals.any { it.id == terminal.surfaceId }
                }
        } == true
        fun admitted() = cachedCurrent() && current(handle, client) && handle.verified && !client.isClosed &&
            capabilities.terminal && ArtifactCapabilities.read(handle.capabilities) == capabilities
        if (!admitted()) return null
        suspend fun <T> use(action: suspend () -> T): T = withContext(scope.coroutineContext.minusKey(Job)) {
            if (!admitted()) throw ArtifactPreviewException(ArtifactPreviewFailure(
                if (cachedCurrent()) ArtifactPreviewFailure.Kind.MAC_UNREACHABLE else ArtifactPreviewFailure.Kind.AUTHORIZATION_FAILED),
                "Files computer or terminal is no longer available")
            val operation = checkNotNull(currentCoroutineContext()[Job])
            handle.borrowedOperations += operation
            try {
                val result = withContext(Dispatchers.IO) { action() }
                ensureActive(); check(admitted()) { "Files computer or terminal changed" }
                result
            } finally { handle.borrowedOperations -= operation }
        }
        val rpc = ArtifactRpc(capabilities,
            if (client.supportsArtifactLanes) ({ resource, consume -> use { client.useArtifactLane(resource, consume) } }) else null,
            { method, params -> use { client.request(method, params) } })
        return TerminalArtifactAccess(rpc, ::admitted, ::cachedCurrent)
    }

    /** A panel admission grants only its displayed path on this exact verified connection. */
    fun panelArtifactAccess(mac: NativeCredentialStore.PairedMac, target: NativePanelTarget,
                            permits: () -> Boolean): PanelArtifactAccess? {
        val handle = handles[mac.origin]?.takeIf { it.mac == mac && current(it) && it.verified } ?: return null
        val client = handle.client?.takeUnless { it.isClosed } ?: return null
        val cacheToken = mutableSources.value[mac.origin]?.panelCacheToken ?: return null
        fun admitted() = permits() && current(handle, client) && handle.verified && !client.isClosed &&
            "panel.artifact.v1" in handle.capabilities && mutableSources.value[mac.origin]?.let { source ->
                source.mac == mac && source.panelCacheToken === cacheToken && source.workspaces.singleOrNull { it.id == target.workspace }
                    ?.macSurfaces?.singleOrNull { it.id == target.surface }
                    ?.let { NativePanelTarget.from(target.workspace, it) == target } == true
            } == true
        if (!admitted()) return null
        suspend fun <T> use(action: suspend () -> T): T = withContext(scope.coroutineContext.minusKey(Job)) {
            check(admitted()) { "This panel is no longer available on the selected Mac." }
            val operation = checkNotNull(currentCoroutineContext()[Job])
            handle.borrowedOperations += operation
            try {
                val result = withContext(Dispatchers.IO) { action() }
                ensureActive(); check(admitted()) { "The panel changed while loading its file." }
                result
            } finally { handle.borrowedOperations -= operation }
        }
        val rpc = ArtifactRpc(ArtifactCapabilities(false, false, false, false, panel = true),
            if (client.supportsArtifactLanes) ({ resource, consume -> use { client.useArtifactLane(resource, consume) } }) else null,
            { method, params ->
                require(method in setOf("mobile.panel.artifact.stat", "mobile.panel.artifact.fetch", "mobile.panel.artifact.thumbnail") &&
                    params.optString("workspace_id") == target.workspace && params.optString("surface_id") == target.surface &&
                    params.optString("path") == target.path) { "This request isn't for the displayed panel file." }
                use { client.request(method, params) }
            })
        fun cachedCurrent() = permits() && isAllowed(mac) && mutableSources.value[mac.origin]?.let { source ->
            source.mac == mac && source.panelCacheToken === cacheToken && "panel.artifact.v1" in source.capabilities &&
                source.workspaces.singleOrNull { it.id == target.workspace }?.macSurfaces?.singleOrNull { it.id == target.surface }
                    ?.let { NativePanelTarget.from(target.workspace, it) == target } == true
        } == true
        return PanelArtifactAccess(rpc, ::admitted, ::cachedCurrent)
    }

    /** Never substitute the foreground Mac when a row's owning session is unavailable. */
    suspend fun workspaceAction(mac: NativeCredentialStore.PairedMac, workspaceId: String,
        action: String, title: String? = null, canSend: () -> Boolean = { true }): JSONObject = withContext(scope.coroutineContext.minusKey(Job)) {
        owningMutation(mac) { handle, client ->
            check(canSend()) { "Workspace action is no longer available" }
            val capability = when (action) {
                "rename", "pin", "unpin" -> "workspace.actions.v1"
                "mark_read", "mark_unread" -> "workspace.read_state.v1"
                "close" -> "workspace.close.v1"
                else -> null
            }
            check(capability == null || capability in handle.capabilities) { "This Mac does not support this workspace action." }
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
                check(base.groupAnchors == null || base.groupAnchors == source.groups.associate { it.id to it.liveAnchorWorkspaceId }) {
                    "Workspace group anchors changed. Try moving it again."
                }
                client.moveWorkspace(workspaceId, workspace.windowId, intent.groupId, intent.beforeWorkspaceId, intent.movesGroup)
            }
        }

    suspend fun customizeWorkspace(mac: NativeCredentialStore.PairedMac, workspaceId: String,
        baseline: WorkspaceCustomizationDraft, submitted: WorkspaceCustomizationDraft, canSend: () -> Boolean = { true }): WorkspaceCustomizationResult =
        withContext(scope.coroutineContext.minusKey(Job)) {
            val handle = handles[mac.origin] ?: error("Connect to this Mac to customize the workspace.")
            check(handle.mac == mac && isAllowed(mac)) { "Saved computer changed" }
            handle.mutex.withLock {
                val client = handle.client ?: error("This Mac is offline.")
                fun requireOwner() {
                    check(canSend()) { "Workspace editor is no longer active." }
                    check(handle.verified && current(handle, client)) { "Computer connection changed." }
                    check(WORKSPACE_METADATA_CAPABILITY in handle.capabilities && "workspace.actions.v1" in handle.capabilities) { "Update cmux on this Mac to customize workspaces." }
                }
                fun workspace(): NativeWorkspace = mutableSources.value[mac.origin]?.workspaces?.singleOrNull { it.id == workspaceId }
                    ?: error("This workspace is no longer available. Reopen the workspace list and try again.")
                requireOwner()
                saveWorkspaceCustomization(baseline, submitted, read = {
                    requireOwner(); refreshWorkspaces(handle, client); requireOwner()
                    WorkspaceCustomizationDraft.from(workspace())
                }, write = { field, draft ->
                    requireOwner()
                    val latest = workspace()
                    workspaceSnapshots.mutate(mac) { client.customizeWorkspace(latest, field, draft) }
                    requireOwner()
                    handle.changes?.request(listOf(workspaceId))
                })
            }
        }

    suspend fun groupAction(mac: NativeCredentialStore.PairedMac, groupId: String,
        action: String, title: String? = null, canSend: () -> Boolean = { true }): JSONObject = withContext(scope.coroutineContext.minusKey(Job)) {
        owningMutation(mac, refreshChanges = false) { _, client ->
            check(canSend()) { "Group action is no longer available" }
            val source = mutableSources.value[mac.origin] ?: error("Computer unavailable")
            check(source.canEditGroups()) { "Pair this Mac again or update cmux to change groups." }
            val group = source.groups.singleOrNull { it.id == groupId } ?: error("This group is no longer available.")
            check(action != "ungroup" || !group.isPinned) { "Unpin this group before ungrouping it." }
            client.groupAction(groupId, action, title)
        }
    }

    /** Matches iOS New Workspace Group: the Mac chooses the default name. */
    suspend fun createGroup(mac: NativeCredentialStore.PairedMac, canSend: () -> Boolean = { true }): JSONObject =
        withContext(scope.coroutineContext.minusKey(Job)) {
            owningMutation(mac, refreshChanges = false) { _, client ->
                check(canSend()) { "Group creation is no longer available" }
                val source = mutableSources.value[mac.origin] ?: error("Computer unavailable")
                check(source.canCreateGroup()) { "Pair this Mac again or update cmux to create groups." }
                client.createGroup("")
            }
        }

    /** Plain creates are pinned to an exact verified Mac, including its build and account. */
    suspend fun createWorkspace(mac: NativeCredentialStore.PairedMac, canSend: () -> Boolean = { true }): JSONObject =
        withContext(scope.coroutineContext.minusKey(Job)) {
            owningMutation(mac) { _, client ->
                check(canSend()) { "Workspace creation is no longer available" }
                client.request("workspace.create").also(::createdPlainWorkspace)
            }
        }

    suspend fun createWorkspaceInGroup(mac: NativeCredentialStore.PairedMac, groupId: String,
        canSend: () -> Boolean = { true }): JSONObject =
        withContext(scope.coroutineContext.minusKey(Job)) {
            owningMutation(mac) { _, client ->
                val source = mutableSources.value[mac.origin] ?: error("Computer unavailable")
                check(canSend()) { "Workspace creation is no longer available" }
                check(source.canCreateInGroup()) { "Pair this Mac again or update cmux to create workspaces in groups." }
                check(source.groups.any { it.id == groupId }) { "This group is no longer available." }
                client.request("workspace.create", JSONObject().put("group_id", groupId)).also {
                    createdPlainWorkspace(it) // Validate legacy list-only success without inventing a selected workspace.
                }
            }
        }

    private suspend fun owningMutation(mac: NativeCredentialStore.PairedMac, refreshChanges: Boolean = true,
        operation: suspend (Handle, MobileRpcClient) -> JSONObject): JSONObject {
        val handle = handles[mac.origin] ?: error("Connect to ${mac.name} to change this workspace.")
        check(handle.mac == mac && isAllowed(mac)) { "Saved computer changed" }
        return handle.mutex.withLock {
            val client = handle.client ?: error("${mac.name} is offline.")
            check(current(handle, client)) { "Saved computer changed" }
            check(handle.verified) { "Computer identity is still being verified." }
            try {
                val result = workspaceSnapshots.mutate(mac) { operation(handle, client) }
                if (!current(handle, client)) throw CancellationException("Saved computer changed")
                if (refreshChanges) handle.changes?.request()
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

    /** A bounded caller can await fresh workspace reads for exactly its selected Macs.
     * Cancellation releases this waiter/read; it never tears down other shared consumers.
     */
    suspend fun refreshWorkspaceLists(macs: List<NativeCredentialStore.PairedMac>) = withContext(scope.coroutineContext.minusKey(Job)) {
        check(macs.isNotEmpty()) { "Choose a computer before retrying." }
        val targets = macs.distinctBy { it.origin }.map { mac ->
            checkNotNull(handles[mac.origin]?.takeIf { it.mac == mac && current(it) }) { "Computer selection changed." }
        }
        targets.map { handle -> async {
            if (handle.client == null || !handle.verified) {
                handle.refresh.request()
                sources.first { !current(handle) || (handle.client != null && handle.verified) }
            }
            ensureActiveSession(handle)
            val client = checkNotNull(handle.client) { "Computer unavailable." }
            handle.mutex.withLock {
                check(handle.verified && current(handle, client)) { "Computer connection changed." }
                refreshWorkspaces(handle, client)
                handle.changes?.request(force = true)
            }
        } }.awaitAll()
        Unit
    }

    suspend fun refresh() = withContext(scope.coroutineContext.minusKey(Job)) {
        handles.values.toList().map { handle -> async {
            val client = handle.client
            handle.agentFeed?.refresh()
            if (client == null || !handle.verified) handle.refresh.request()
            else try { if (!fetch(handle, client)) handle.refresh.request(); handle.changes?.request(force = true) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                handle.refresh.request()
            }
        } }.awaitAll()
        Unit
    }

    suspend fun setRead(entry: NativeFeedEntry, read: Boolean, canSend: () -> Boolean = { true }) = withContext(scope.coroutineContext.minusKey(Job)) {
        val handle = handles[entry.source.mac.origin] ?: error("Connect to ${entry.computer} to change this notification.")
        check(handle.mac == entry.source.mac) { "Saved computer changed" }
        mutate(handle, listOf(entry.notification.id), read, all = false, canSend)
    }
    suspend fun markAllRead(selectedOrigin: String? = null) = withContext(scope.coroutineContext.minusKey(Job)) {
        markNotificationsRead(mutableSources.value.values.filter {
            selectedOrigin == null || it.mac.origin == selectedOrigin
        }.map { it.mac })
    }
    /** Captures exact pairings, including when the caller confirms a filtered browser view. */
    suspend fun markNotificationsRead(macs: List<NativeCredentialStore.PairedMac>, canSend: () -> Boolean = { true }) =
        withContext(scope.coroutineContext.minusKey(Job)) {
        check(macs.map { it.origin }.distinct().size == macs.size)
        val failures = macs.map { mac -> async {
            try {
                check(canSend()) { "Notification action is no longer current" }
                val source = mutableSources.value[mac.origin] ?: error("Computer unavailable")
                check(source.mac == mac) { "Saved computer changed" }
                if (source.items.any { !it.isRead }) {
                    val handle = handles[mac.origin] ?: error("Computer unavailable")
                    check(handle.mac == mac) { "Saved computer changed" }
                    mutate(handle, source.items.map { it.id }, read = true, all = true, canSend)
                }
                null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mac.name
            }
        } }.awaitAll().filterNotNull()
        check(failures.isEmpty()) { "Could not mark notifications read on: ${failures.joinToString()}. Reconnect and retry." }
    }
    private suspend fun mutate(handle: Handle, ids: List<String>, read: Boolean, all: Boolean, canSend: () -> Boolean) = handle.mutex.withLock {
        val client = handle.client ?: error("${handle.mac.name} is offline.")
        check(current(handle, client) && canSend()) { "Saved computer or notification action changed" }
        check(handle.verified) { "Computer identity is still being verified." }
        check(all || mutableSources.value[handle.mac.origin]?.items?.any { it.id == ids.single() } == true) { "This notification is no longer available" }
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
