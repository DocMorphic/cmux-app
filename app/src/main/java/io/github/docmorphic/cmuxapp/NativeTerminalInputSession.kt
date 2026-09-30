package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

/** Retained foreground-Mac input ownership. Every method except the RPC hook uses the session dispatcher. */
internal class NativeTerminalInputSession(private val scope: CoroutineScope) : AutoCloseable {
    data class Target(val workspace: String, val surface: String)
    private class Payload(val operation: TerminalInputOperation, val mergeable: Boolean = false) {
        var response: String? = null
    }
    private inner class Context(val owner: TerminalInputSender.Owner) {
        var client: MobileRpcClient? = null
        var capabilities = emptySet<String>()
        var targets = emptySet<Target>()
        var permits: () -> Boolean = { false }
        var retired = false
        val changed = MutableStateFlow(0L)
        val records = linkedMapOf<TerminalInputSender.Key, Record>()
    }
    private inner class Record(val context: Context, val target: Target, val key: TerminalInputSender.Key) {
        var binding: TerminalInputSender<Payload>.Binding? = null
        var retainedOrder = false
        val lanes = mutableListOf<Lane>()
        val queue = TerminalInputQueue(scope) { entry ->
            awaitConnection(context, target)
            if (entry.paste) dispatch(context, TerminalInputOperation.Paste(target.workspace, target.surface, entry.text, false))
            else if (TerminalInputDelivery.CAPABILITY in context.capabilities)
                enqueue(context, this, Payload(TerminalInputOperation.Text(target.workspace, target.surface, entry.text), true))
            else dispatch(context, TerminalInputOperation.Text(target.workspace, target.surface, entry.text))
        }
    }
    private inner class Lane(val client: MobileRpcClient, val record: Record,
        val send: suspend (String, TerminalInputDelivery) -> Boolean) : AutoCloseable {
        var observer: Job? = null
        override fun close() {
            if (!record.lanes.remove(this)) return
            observer?.cancel()
            if (record.context.client === client && valid(record.context)) record.binding?.pathLost()
        }
    }
    private val sender = TerminalInputSender<Payload>(scope, merge = { previous, next ->
        val a = previous.operation as? TerminalInputOperation.Text
        val b = next.operation as? TerminalInputOperation.Text
        if (previous.mergeable && next.mergeable && a != null && b != null && a.workspace == b.workspace && a.surface == b.surface &&
            a.byteCount + b.byteCount <= TerminalLaneProtocol.MAX_INPUT)
            Payload(TerminalInputOperation.Text(a.workspace, a.surface, a.text + b.text), true) else null
    })
    val status get() = sender.status
    private var context: Context? = null
    private var closed = false

    fun retainOwner(owner: TerminalInputSender.Owner?) {
        if (context?.owner != owner) clear()
    }
    fun attach(owner: TerminalInputSender.Owner, client: MobileRpcClient, capabilities: Set<String>,
        targets: Set<Target>, permits: () -> Boolean) {
        check(!closed && permits()) { "Computer access changed" }
        retainOwner(owner)
        val ctx = context ?: Context(owner).also { context = it }
        ctx.client = client; ctx.capabilities = capabilities.toSet(); ctx.permits = permits
        updateTargets(ctx, targets)
        client.terminalInputDispatcher = { operation ->
            withContext(scope.coroutineContext.minusKey(Job)) { dispatch(ctx, operation) }
        }
        ctx.records.values.forEach { record -> record.lanes.filter { it.client !== client }.toList().forEach { it.close() }; bind(record) }
        ctx.changed.value++
    }
    fun detach(client: MobileRpcClient) {
        val ctx = context?.takeIf { it.client === client } ?: return
        ctx.client = null
        ctx.records.values.forEach { record ->
            record.lanes.toList().forEach { it.close() }
            record.binding?.close(); record.binding = null
        }
        ctx.changed.value++
    }
    fun updateTargets(client: MobileRpcClient, targets: Set<Target>) {
        context?.takeIf { it.client === client }?.let { updateTargets(it, targets) }
    }
    private fun updateTargets(ctx: Context, targets: Set<Target>) {
        ctx.targets = targets.toSet()
        ctx.records.values.filter { it.target !in targets }.forEach { remove(it) }
        ctx.changed.value++
    }
    fun key(client: MobileRpcClient?, workspace: String, surface: String): TerminalInputSender.Key? {
        val ctx = context?.takeIf { it.client === client && client != null && valid(it) } ?: return null
        val uuid = canonicalUuid(surface) ?: return null
        return TerminalInputSender.Key(ctx.owner, uuid).takeIf { Target(workspace, surface) in ctx.targets }
    }
    /** Null retains the legacy queue for hosts without the capability or non-UUID targets. */
    fun orderedQueue(client: MobileRpcClient, workspace: String, surface: String): TerminalInputQueue? {
        val ctx = context?.takeIf { it.client === client && valid(it) } ?: return null
        val existing = canonicalUuid(surface)?.let { ctx.records[TerminalInputSender.Key(ctx.owner, it)] }
        if (TerminalInputDelivery.CAPABILITY !in ctx.capabilities && existing?.retainedOrder != true) return null
        return record(ctx, Target(workspace, surface))?.also { it.retainedOrder = true }?.queue
    }
    fun registerLane(client: MobileRpcClient, workspace: String, surface: String, ready: StateFlow<Boolean>,
        send: suspend (String, TerminalInputDelivery) -> Boolean): AutoCloseable {
        val ctx = context?.takeIf { it.client === client && valid(it) } ?: return AutoCloseable { }
        if (TerminalInputDelivery.CAPABILITY !in ctx.capabilities) return AutoCloseable { }
        val record = record(ctx, Target(workspace, surface)) ?: return AutoCloseable { }
        val lane = Lane(client, record, send)
        record.lanes += lane
        lane.observer = scope.launch {
            var wasReady = false
            ready.collect { available ->
                if (!valid(ctx) || ctx.client !== client || lane !in record.lanes) return@collect
                if (available) record.binding?.resume() else if (wasReady) record.binding?.pathLost()
                wasReady = available
            }
        }
        return lane
    }
    fun receive(client: MobileRpcClient, surface: String, ack: TerminalInputAcknowledgement) {
        val ctx = context?.takeIf { it.client === client && valid(it) } ?: return
        val uuid = canonicalUuid(surface) ?: return
        ctx.records[TerminalInputSender.Key(ctx.owner, uuid)]?.binding?.receive(ack)
    }
    fun allowsTarget(key: TerminalInputSender.Key, workspace: String): Boolean = context?.let { ctx ->
        valid(ctx) && ctx.owner == key.owner && ctx.targets.any { it.workspace == workspace && canonicalUuid(it.surface) == key.surface }
    } == true
    /** Resolve only the admitted original owner, including after Activity/connection replacement. */
    suspend fun clientForTarget(key: TerminalInputSender.Key, workspace: String): MobileRpcClient {
        val ctx = checkNotNull(context?.takeIf { it.owner == key.owner && valid(it) }) { "Input owner changed" }
        val target = checkNotNull(ctx.targets.singleOrNull {
            it.workspace == workspace && canonicalUuid(it.surface) == key.surface
        }) { "The original terminal is no longer available" }
        return awaitConnection(ctx, target)
    }
    fun requiresReconnect(key: TerminalInputSender.Key): Boolean {
        val record = context?.records?.get(key) ?: return false
        return TerminalInputDelivery.CAPABILITY in record.context.capabilities && record.binding?.identityAccepted == false
    }
    fun resume(key: TerminalInputSender.Key): Boolean {
        if (requiresReconnect(key)) return false
        val record = context?.records?.get(key) ?: return false
        if (sender.status.value[key]?.failure != null && !sender.resumeAfterFailure(key)) return false
        return record.queue.resume()
    }
    private fun valid(ctx: Context) = !closed && context === ctx && !ctx.retired && ctx.permits()
    private suspend fun awaitConnection(ctx: Context, target: Target): MobileRpcClient {
        withTimeout(30_000) { ctx.changed.first { !valid(ctx) || ctx.client != null } }
        check(valid(ctx) && target in ctx.targets) { "The original terminal is no longer available" }
        return checkNotNull(ctx.client).also { check(!it.isClosed) { "Terminal reconnecting" } }
    }
    private fun record(ctx: Context, target: Target): Record? {
        if (!valid(ctx) || target !in ctx.targets) return null
        val uuid = canonicalUuid(target.surface) ?: return null
        val key = TerminalInputSender.Key(ctx.owner, uuid)
        ctx.records[key]?.let { return it.takeIf { record -> record.target == target } }
        if (ctx.records.size >= 64) {
            val idle = ctx.records.values.firstOrNull { it.lanes.isEmpty() && it.queue.status.value.pendingBytes == 0 &&
                it.queue.status.value.error == null && sender.status.value[it.key]?.let { state -> state.pendingUnits == 0 && state.failure == null } != false }
                ?: return null
            remove(idle)
        }
        return Record(ctx, target, key).also { ctx.records[key] = it; bind(it) }
    }
    private fun bind(record: Record) {
        val ctx = record.context
        val client = ctx.client ?: return
        val transport = object : TerminalInputSender.Transport<Payload> {
            override val supportsIdentifiedInput = TerminalInputDelivery.CAPABILITY in ctx.capabilities
            private fun current() = valid(ctx) && ctx.client === client && record.target in ctx.targets && !client.isClosed
            override suspend fun sendOnLane(payload: Payload, delivery: TerminalInputDelivery): TerminalInputSender.SendResult {
                if (!current()) return TerminalInputSender.SendResult.Unavailable
                val text = payload.operation as? TerminalInputOperation.Text ?: return TerminalInputSender.SendResult.Unavailable
                for (lane in record.lanes.toList()) {
                    if (lane.client === client && lane.send(text.text, delivery)) return TerminalInputSender.SendResult.AwaitingAcknowledgement
                }
                return TerminalInputSender.SendResult.Unavailable
            }
            override suspend fun sendOverRpc(payload: Payload, delivery: TerminalInputDelivery): TerminalInputSender.SendResult {
                if (!current()) return TerminalInputSender.SendResult.Unavailable
                val response = payload.operation.rpc(client, delivery)
                if (!current()) return TerminalInputSender.SendResult.Failed
                // Preserve scroll's render_grid (and other method fields), fenced to this binding.
                payload.response = response.toString()
                val ack = TerminalInputAcknowledgement.fromRpc(response)
                return if (ack == null) TerminalInputSender.SendResult.AppliedWithoutIdentity else TerminalInputSender.SendResult.Acknowledged(ack)
            }
        }
        record.binding = sender.bind(record.key, transport)
    }
    private fun enqueue(ctx: Context, record: Record, payload: Payload): TerminalInputSender.Ticket {
        check(valid(ctx) && payload.operation.workspace == record.target.workspace && payload.operation.surface == record.target.surface) { "Input owner changed" }
        return checkNotNull(sender.submit(record.key, payload, payload.operation.byteCount)) {
            "Typing paused. Check the terminal before resuming."
        }
    }
    private suspend fun dispatch(ctx: Context, operation: TerminalInputOperation): JSONObject {
        val target = Target(operation.workspace, operation.surface)
        val client = awaitConnection(ctx, target)
        val record = record(ctx, target)
        check(record == null || (sender.status.value[record.key]?.failure == null && record.queue.status.value.error == null)) { "Typing paused. Check the terminal before resuming." }
        if (TerminalInputDelivery.CAPABILITY !in ctx.capabilities) return operation.rpc(client, null)
        check(canonicalUuid(operation.surface) != null) { "The Mac returned an invalid terminal identity" }
        checkNotNull(record) { "Too many pending terminals" }
        val payload = Payload(operation)
        val ticket = enqueue(ctx, record, payload)
        when (ticket.settled.await()) {
            TerminalInputSender.Settlement.DELIVERED -> return payload.response?.let(::JSONObject) ?: JSONObject()
            TerminalInputSender.Settlement.UNDELIVERABLE -> error("The terminal did not accept this input.")
            TerminalInputSender.Settlement.ABANDONED -> error("Delivery was not confirmed. Check the terminal before resuming.")
        }
    }
    private fun remove(record: Record) {
        record.context.records.remove(record.key)
        record.lanes.toList().forEach { it.close() }; record.queue.close(); record.binding?.close()
        sender.abandonWhere { it == record.key }
    }
    fun clear() {
        val old = context ?: return
        context = null; old.retired = true; old.client = null; old.changed.value++
        old.records.values.toList().forEach(::remove)
        sender.abandonWhere { true }
    }
    override fun close() { clear(); closed = true; sender.close() }
    private fun canonicalUuid(value: String) = runCatching { UUID.fromString(value).takeIf { it.toString().equals(value, true) } }.getOrNull()
}
