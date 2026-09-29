package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared native-computer routing uses the same encrypted grants as the explicit QR flow. */
internal class NativeTailscaleRoutes(private val context: Context, store: NativeCredentialStore,
    private val team: NativeTeamScope) {
    private val saved = TailscaleGrantStore(store::load, store::update)
    val revisions = store.revisions
    fun grants(target: NativeComputerTarget) = saved.computer(team, target)
    fun transport(grants: List<TailscaleSavedGrant>, permits: () -> Boolean): MobileRpcTransport =
        TailscaleCandidateTransport(grants.map { it.route }, permits) { route ->
            withContext(Dispatchers.IO) { TailscaleRoute.resolve(context, route, permits) }
        }
}

/** Tries only the captured authorized TCP coordinates; never falls back to Iroh or the default network. */
internal class TailscaleCandidateTransport(private val routes: List<PairingCode.Route>,
    private val permits: () -> Boolean,
    private val create: suspend (PairingCode.Route) -> MobileRpcTransport) : MobileRpcTransport {
    private val lock = Any()
    private val connecting = Mutex()
    private var candidate: MobileRpcTransport? = null
    private var connected = false
    private var closed = false

    override suspend fun connect() = connecting.withLock {
        require(routes.isNotEmpty()) { "Add a Tailscale connection in Computer Details first." }
        synchronized(lock) { check(!closed); if (connected) return@withLock }
        var last: Exception? = null
        for (route in routes) {
            currentCoroutineContext().ensureActive()
            synchronized(lock) { check(!closed) }
            check(permits()) { "The Tailscale authorization changed" }
            var transport: MobileRpcTransport? = null
            try {
                transport = create(route)
                synchronized(lock) { check(!closed); candidate = transport }
                check(permits()) { "The Tailscale authorization changed" }
                transport.connect()
                currentCoroutineContext().ensureActive()
                check(permits()) { "The Tailscale authorization changed" }
                synchronized(lock) { check(!closed && candidate === transport); connected = true }
                return@withLock
            } catch (failure: Exception) {
                transport?.close()
                synchronized(lock) { if (candidate === transport) candidate = null }
                if (failure is CancellationException) throw failure
                last = failure
            }
        }
        close()
        throw last ?: IllegalStateException("No saved Tailscale address is reachable")
    }
    private fun active(): MobileRpcTransport {
        check(permits()) { "The Tailscale authorization changed" }
        return synchronized(lock) { check(!closed && connected); checkNotNull(candidate) }
    }
    override suspend fun read(): ByteArray? = try { active().read().also { active() } }
        catch (failure: Throwable) { close(); throw failure }
    override suspend fun write(bytes: ByteArray) = try { active().write(bytes) }
        catch (failure: Throwable) { close(); throw failure }
    override fun close() {
        val previous = synchronized(lock) { closed = true; connected = false; candidate.also { candidate = null } }
        previous?.close()
    }
}
