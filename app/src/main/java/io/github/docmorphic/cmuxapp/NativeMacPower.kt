package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.UUID

internal data class NativeMacPowerState(
    val connected: Boolean = false,
    val supported: Boolean? = null,
    val enabled: Boolean? = null,
    val busy: Boolean = false,
    val error: String? = null
) {
    companion object {
        const val READ_ERROR = "Couldn't load the Mac's Keep Mac Awake status. Check the connection and retry."
        const val SET_ERROR = "Couldn't confirm the change on your Mac. Check the connection and retry."
    }
}

/** Reads and optionally controls an existing verified connection; never dials.
 * Detail pages own a lease. A feed observer borrows its enclosing session's client.
 * No optimistic value survives this session.
 */
internal class NativeMacPowerSession(
    private val client: MobileRpcClient,
    private val target: NativeComputerTarget,
    private val permits: () -> Boolean,
    private val mutationGate: Mutex,
    private val timeoutMillis: Long = 5000,
    private val pollMillis: Long = 10_000,
    private val closeClientOnExit: Boolean = true
) {
    private val lock = Any()
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(NativeMacPowerState(connected = true, busy = true))
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var stopped = false
    private var scope: CoroutineScope? = null
    private val stream = UUID.randomUUID().toString()
    private var subscriptionAttempted = false
    private var subscribed = false
    private fun valid() = !stopped && !client.isClosed && permits()

    suspend fun run(): Unit = try {
        coroutineScope {
            synchronized(lock) { check(scope == null && !stopped); scope = this }
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.collect { event ->
                    if (event.topic == "caffeine.status.changed" && event.streamId == stream) {
                        val enabled = event.payload.opt("enabled") as? Boolean ?: return@collect
                        synchronized(lock) {
                            if (valid() && mutableState.value.supported == true) {
                                revision++
                                mutableState.value = mutableState.value.copy(enabled = enabled, error = null)
                            }
                        }
                    }
                }
            }
            launch { client.disconnected.first(); this@coroutineScope.cancel() }
            launch {
                while (isActive) {
                    delay(1000)
                    if (!synchronized(lock) { valid() }) this@coroutineScope.cancel()
                }
            }
            refreshNow()
            while (isActive) {
                delay(pollMillis)
                // Events are best effort; a bounded read also recovers a dropped event.
                if (state.value.supported == true) refreshNow()
            }
        }
    } finally {
        synchronized(lock) {
            stopped = true; revision++
            mutableState.value = NativeMacPowerState()
            scope = null
        }
        withContext(NonCancellable) {
            try {
                if (subscriptionAttempted && !client.isClosed)
                    withTimeoutOrNull(750) { runCatching { client.unsubscribe(stream) } }
            } finally { if (closeClientOnExit) client.close() }
        }
    }

    fun refresh() { synchronized(lock) { scope }?.launch { refreshNow() } }
    fun setEnabled(enabled: Boolean) { synchronized(lock) { scope }?.launch { setNow(enabled) } }

    private suspend fun refreshNow() {
        if (!operation.tryLock()) return
        try {
            val start = synchronized(lock) {
                if (!valid()) return
                mutableState.value = mutableState.value.copy(busy = true)
                revision
            }
            try {
                if (state.value.supported == null) {
                    val host = client.request("mobile.host.status", timeoutMillis = timeoutMillis)
                    NativeCredentialStore.PairedMac("", target.deviceId, target.name, target.buildTag).requireMatchingHost(host)
                    val capabilities = host.optJSONArray("capabilities")
                    val supported = capabilities != null && (0 until capabilities.length()).any {
                        capabilities.opt(it) == "caffeine.control.v1"
                    }
                    synchronized(lock) {
                        if (!valid()) return
                        mutableState.value = mutableState.value.copy(supported = supported, error = null)
                    }
                }
                if (state.value.supported != true) return
                if (!subscribed) {
                    // Subscribe before reading so an event can invalidate an older status reply.
                    subscriptionAttempted = true
                    withTimeout(timeoutMillis) { client.subscribe(listOf("caffeine.status.changed"), stream) }
                    subscribed = true
                }
                readStatus(start)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (valid() && revision == start)
                        mutableState.value = mutableState.value.copy(enabled = null, error = NativeMacPowerState.READ_ERROR)
                }
            }
        } finally {
            synchronized(lock) { if (valid()) mutableState.value = mutableState.value.copy(busy = false) }
            operation.unlock()
        }
    }

    private suspend fun readStatus(start: Long) {
        val result = client.request("caffeine.status", timeoutMillis = timeoutMillis)
        val enabled = result.opt("enabled") as? Boolean ?: error("Invalid caffeine status")
        synchronized(lock) {
            if (valid() && revision == start) {
                revision++
                mutableState.value = mutableState.value.copy(enabled = enabled, error = null)
            }
        }
    }

    private suspend fun setNow(enabled: Boolean) {
        if (!operation.tryLock()) return
        var acquired = false
        try {
            val start = synchronized(lock) {
                if (!valid() || state.value.supported != true || state.value.enabled == null) return
                // All pages for this exact account/endpoint/record/device/build share this gate.
                if (!mutationGate.tryLock()) return
                acquired = true
                revision++
                mutableState.value = mutableState.value.copy(enabled = enabled, busy = true, error = null)
                revision
            }
            try {
                val result = client.request("caffeine.set", JSONObject().put("enabled", enabled), timeoutMillis)
                val actual = result.opt("enabled") as? Boolean ?: error("Invalid caffeine status")
                synchronized(lock) {
                    if (valid() && revision == start) {
                        revision++
                        mutableState.value = mutableState.value.copy(enabled = actual, error = null)
                    }
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                val reconcile = synchronized(lock) {
                    (valid() && revision == start).also {
                        if (it) mutableState.value = mutableState.value.copy(enabled = null, error = NativeMacPowerState.SET_ERROR)
                    }
                }
                if (reconcile) {
                    // A lost/malformed reply can follow a successful mutation. Read; never replay set.
                    try {
                        readStatus(start)
                        synchronized(lock) {
                            if (valid() && revision == start + 1 && state.value.enabled != enabled)
                                mutableState.value = mutableState.value.copy(error = NativeMacPowerState.SET_ERROR)
                        }
                    }
                    catch (_: Exception) { currentCoroutineContext().ensureActive() }
                }
            }
        } finally {
            if (acquired) mutationGate.unlock()
            synchronized(lock) { if (valid()) mutableState.value = mutableState.value.copy(busy = false) }
            operation.unlock()
        }
    }
}
