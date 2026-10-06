package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class CloudSystemVpnPhase { OFF, PREPARING, CONNECTING, CONNECTED, DISCONNECTING, FAILED, CLOSED }
internal data class CloudSystemVpnState(val phase: CloudSystemVpnPhase = CloudSystemVpnPhase.OFF,
    val message: String? = null, val pendingCleanup: Int = 0)
internal class CloudVpnDevice(val deviceId: String, val fingerprint: String) {
    init { require(deviceId.isNotBlank() && fingerprint.isNotBlank()) }
    override fun toString() = "CloudVpnDevice(redacted)"
}

/** Enroll is current-owner-only. Revoke must use this captured owner's credentials,
 * even after retirement, and must expose no other old-account operation. No tokens are persisted. */
internal class CloudVpnAccess(val owner: CloudVpnOwner, val isCurrent: () -> Boolean,
    val device: suspend () -> CloudVpnDevice,
    val enroll: suspend (CloudWireGuardKey, CloudVpnDevice) -> CloudTunnelEnrollment,
    val revoke: suspend (String) -> Unit) {
    override fun toString() = "CloudVpnAccess(redacted)"
}

/** OS consent is obtained by the foreground UI, before enabling this controller.
 * interrupt synchronously invalidates pending start admission and requests local stop.
 * install must recheck current at its final platform admission point. Calls to install
 * and stop are serialized until they actually finish, including after cancellation.
 * stop returns only when this application's VPN is down; it never stops another app's VPN. */
internal interface CloudSystemVpnPlatform {
    val consentGranted: Boolean
    fun interrupt()
    suspend fun install(profile: CloudVpnProfile, current: () -> Boolean)
    suspend fun stop()
    fun connected(attempt: String): Boolean
}

/** Application-owned system VPN, deliberately independent of foreground/tab leases.
 * The gate stays occupied by non-cooperative work. Timeout fences it immediately;
 * no replacement can overtake a late install or its cleanup. */
internal class CloudSystemVpnController(parent: CoroutineScope, private val store: CloudVpnStore,
    private val platform: CloudSystemVpnPlatform, dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 30_000) : AutoCloseable {
    private val workers = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = Mutex()
    private val lock = Any()
    private val mutable = MutableStateFlow(CloudSystemVpnState())
    val state = mutable.asStateFlow()
    private var account: CloudVpnAccess? = null
    private var bound = false
    private val cleanupAccess = mutableMapOf<CloudVpnOwner, CloudVpnAccess>()
    private var generation = 0L
    private var wanted = false
    private var closed = false
    private var operation: Job? = null
    private var timer: Job? = null
    private var liveAttempt: String? = null
    private val lifetime: DisposableHandle? = parent.coroutineContext[Job]?.invokeOnCompletion { close() }
    init { require(timeoutMillis in 1..300_000) }

    /** Rebinding never enrolls. It removes old local routes before account cleanup. */
    fun bind(access: CloudVpnAccess?) = synchronized(lock) {
        if (closed || (bound && account === access)) return@synchronized
        bound = true
        account?.let { cleanupAccess[it.owner] = it }
        account = access
        access?.let { cleanupAccess[it.owner] = it }
        transitionLocked(false)
    }
    fun enable(): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val access = account
        if (access == null || !access.isCurrent()) {
            mutable.value = mutable.value.copy(phase = CloudSystemVpnPhase.FAILED, message = "Sign in to use the Cloud VPN.")
            return@synchronized false
        }
        if (!platform.consentGranted) {
            mutable.value = mutable.value.copy(phase = CloudSystemVpnPhase.FAILED, message = "Allow the Cloud VPN connection in Android settings.")
            return@synchronized false
        }
        if (wanted && mutable.value.phase in setOf(CloudSystemVpnPhase.PREPARING, CloudSystemVpnPhase.CONNECTING, CloudSystemVpnPhase.CONNECTED)) return@synchronized true
        transitionLocked(true)
        true
    }
    fun disable() = synchronized(lock) { if (!closed) transitionLocked(false) }
    fun retryCleanup() = synchronized(lock) { if (!closed && !wanted) transitionLocked(false) }

    /** Service callbacks must carry the installation attempt, never just a bare UP/DOWN. */
    fun platformChanged(attempt: String, connected: Boolean) = synchronized(lock) {
        if (closed || attempt != liveAttempt) return@synchronized
        if (!connected) transitionLocked(false, "Cloud VPN disconnected. Connect again when ready.")
    }

    private fun current(at: Long, access: CloudVpnAccess?) = synchronized(lock) {
        !closed && generation == at && wanted && account === access && access?.isCurrent() == true
    }
    private fun guard(at: Long, access: CloudVpnAccess) { if (!current(at, access)) throw CancellationException("Cloud VPN owner retired") }
    private fun publish(at: Long, phase: CloudSystemVpnPhase, message: String? = null) = synchronized(lock) {
        if (generation == at && !closed) mutable.value = mutable.value.copy(phase = phase, message = message)
    }
    private fun transitionLocked(enable: Boolean, failure: String? = null) {
        val at = ++generation
        wanted = enable
        liveAttempt = null
        operation?.cancel(); timer?.cancel()
        platform.interrupt()
        val access = account
        mutable.value = mutable.value.copy(phase = if (closed) CloudSystemVpnPhase.CLOSED else if (failure != null) CloudSystemVpnPhase.FAILED
            else if (enable) CloudSystemVpnPhase.PREPARING else CloudSystemVpnPhase.DISCONNECTING, message = failure)
        val worker = workers.launch(start = CoroutineStart.LAZY) {
            try {
                gate.withLock {
                    ensureActive()
                    if (synchronized(lock) { generation != at }) return@withLock
                    // Stop even if storage is corrupt or its last write failed.
                    stopAndRetire()
                    cleanupPeers(access?.owner)
                    ensureActive()
                    if (enable && access != null) start(at, access)
                    else publish(at, if (failure == null) CloudSystemVpnPhase.OFF else CloudSystemVpnPhase.FAILED, failure)
                }
            } catch (cancel: CancellationException) {
                // Superseding transitions already advanced generation. An otherwise
                // current cancellation must not leave PREPARING without a timer.
                fail(at, "Cloud VPN setup was interrupted. Retry connection or cleanup.")
                throw cancel
            }
              catch (_: Exception) { fail(at, "Could not update the Cloud VPN. Retry connection or cleanup.") }
              catch (_: LinkageError) { fail(at, "The Cloud VPN runtime is unavailable.") }
            finally {
                synchronized(lock) {
                    if (generation == at) {
                        timer?.cancel(); timer = null; operation = null
                        if (closed) workers.cancel()
                    }
                }
            }
        }
        operation = worker
        timer = if (closed || failure != null) null else workers.launch {
            delay(timeoutMillis)
            synchronized(lock) {
                if (generation == at && operation?.isActive == true)
                    transitionLocked(false, "Cloud VPN operation timed out. Cleanup must finish before reconnecting.")
            }
        }
        worker.start()
    }
    private suspend fun start(at: Long, access: CloudVpnAccess) {
        guard(at, access)
        val device = access.device()
        guard(at, access)
        // Pending records for other unavailable accounts remain durable; this owner's
        // same fingerprint cannot be enrolled again until its cleanup is confirmed.
        val entry = store.begin(access.owner, device.fingerprint)
        var retained = false
        try {
            val key = CloudWireGuardKey.generate()
            val enrollment = access.enroll(key, device)
            guard(at, access)
            check(enrollment.fingerprint == device.fingerprint) { "Cloud VPN enrollment identity mismatch" }
            val configuration = CloudVpnRoutePolicy.configuration(enrollment, key)
            store.install(entry, configuration.text)
            guard(at, access)
            val profile = CloudVpnProfile(entry, configuration.text)
            publish(at, CloudSystemVpnPhase.CONNECTING)
            platform.install(profile) { current(at, access) }
            guard(at, access)
            check(platform.connected(entry.attempt)) { "Cloud VPN did not connect" }
            val pendingCount = (store.load().pending.size - 1).coerceAtLeast(0)
            synchronized(lock) {
                guard(at, access)
                liveAttempt = entry.attempt
                retained = true
                mutable.value = mutable.value.copy(phase = CloudSystemVpnPhase.CONNECTED, message = null,
                    pendingCleanup = pendingCount)
            }
        } finally {
            if (!retained) withContext(NonCancellable) {
                // Keep the gate until even a late installation is down. A failed stop
                // leaves both profile and peer identity durable for a later retry.
                stopAndRetire()
                cleanupPeers(access.owner)
            }
        }
    }
    private suspend fun stopAndRetire() {
        platform.stop()
        store.load().profile?.let { store.retireProfile(it.enrollment.attempt) }
    }
    private suspend fun cleanupPeers(preferred: CloudVpnOwner?) {
        val pending = store.load().pending.sortedBy { if (it.owner == preferred) 0 else 1 }
        var processed = 0
        try { for (entry in pending) {
            val access = synchronized(lock) { cleanupAccess[entry.owner] } ?: continue
            if (processed++ >= 8) break
            // Retries here are revocations only; POST enrollment is never retried.
            var lastFailure: Exception? = null
            for (retry in 0..2) {
                try {
                    withTimeout(timeoutMillis) { access.revoke(entry.fingerprint) }
                    store.acknowledge(entry)
                    lastFailure = null
                    break
                } catch (cancel: CancellationException) {
                    if (cancel !is TimeoutCancellationException) throw cancel
                    lastFailure = cancel
                } catch (failure: Exception) { lastFailure = failure }
                if (retry < 2) delay(250L shl retry)
            }
            lastFailure?.let { throw it }
        } } finally {
            val remaining = store.load().pending
            synchronized(lock) {
                mutable.value = mutable.value.copy(pendingCleanup = remaining.size)
                cleanupAccess.keys.retainAll(remaining.map { it.owner }.toSet() + listOfNotNull(account?.owner))
            }
        }
    }
    private fun fail(at: Long, message: String) = synchronized(lock) {
        if (generation == at && !closed) {
            wanted = false
            liveAttempt = null
            platform.interrupt()
            mutable.value = mutable.value.copy(phase = CloudSystemVpnPhase.FAILED, message = message)
        }
    }
    override fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        lifetime?.dispose()
        transitionLocked(false)
    }
}
