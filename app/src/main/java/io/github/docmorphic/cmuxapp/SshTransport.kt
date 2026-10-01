package io.github.docmorphic.cmuxapp

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SshTrustQuestion(val hostId: UUID, val endpoint: SshEndpoint,
    val prior: SshTrustSnapshot, val presented: SshHostKey)
internal data class SshExecResult(val stdout: ByteArray, val stderr: ByteArray, val exitStatus: Int)

internal interface SshManagedConnection : AutoCloseable {
    val plan: SshDialPlan
    val isConnected: Boolean
    val disconnected: kotlinx.coroutines.flow.StateFlow<Boolean>
}

/** One saved route, including all jump sessions, owned by a caller's account lifetime.
 * Does not perform reconnect/retry or send passwords. UI admission and prompt
 * presentation are supplied by the owner; no caller may use a signed-out scope. */
internal class SshTransport private constructor(
    private val hosts: SshHostStore,
    private val vault: SshKeyVault,
    override val plan: SshDialPlan,
    lifetime: CoroutineScope,
    private val admitted: () -> Boolean,
    private val askTrust: suspend (SshTrustQuestion) -> Boolean,
    private val authorize: suspend (SshKeyRecord, SshPreparedSignature) -> Unit,
) : SshManagedConnection {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val sockets = mutableListOf<Socket>()
    private val sessions = mutableListOf<Session>()
    private val channels = mutableSetOf<Channel>()
    private val proxies = mutableListOf<Proxy>()
    private val identities = mutableListOf<VaultIdentity>()
    private val serverKeys = mutableMapOf<UUID, SshHostKey>()
    @Volatile private var ready: Session? = null
    private val ended = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val disconnected: kotlinx.coroutines.flow.StateFlow<Boolean> = ended
    override val isConnected: Boolean get() = !closed.get() && ready?.isConnected == true
    internal val activeChannels: Int get() = synchronized(lock) { channels.size }

    init {
        // Cancellation finally-block also covers owner cancellation while no UI
        // action is running. Poll only the external admission predicate; host
        // metadata changes have their own immediate observer.
        scope.launch {
            try { while (isActive) { guard(); delay(100) } }
            catch (_: Exception) { /* Close is the authoritative terminal state. */ }
            finally { close() }
        }
        scope.launch {
            hosts.state.collect { if (!valid()) close() }
        }
    }

    private fun valid(): Boolean = !closed.get() && job.isActive && admitted() && hosts.isCurrent(plan) &&
        (ready == null || synchronized(lock) { sessions.all { it.isConnected } }) &&
        synchronized(lock) { serverKeys.toMap() }.all { (id, key) ->
            val hop = plan.hops.first { it.hostId == id }
            hosts.trustSnapshot(hop.endpoint).pinned == key
        }
    private fun guard() { if (!valid()) throw CancellationException("SSH connection owner or route retired") }
    private fun target(): Session { guard(); return checkNotNull(ready).also { check(it.isConnected) { "SSH connection closed" } } }

    private fun connectBlocking() {
        var parent: Session? = null
        for (hop in plan.hops) {
            guard()
            val key = checkNotNull(vault.state.value.firstOrNull { it.id == hop.keyId }) { "Choose an available SSH key" }
            val identity = VaultIdentity(key)
            synchronized(lock) { guard(); identities += identity }
            val client = JSch().apply {
                addIdentity(identity, null)
                hostKeyRepository = Repository(hop)
            }
            val session = client.getSession(hop.endpoint.username, hop.endpoint.host, hop.endpoint.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "publickey")
                setHostKeyAlias(hop.endpoint.hostKeyIdentity)
                setDaemonThread(true)
                serverAliveInterval = 15_000
                serverAliveCountMax = 3
            }
            synchronized(lock) { guard(); sessions += session }
            var jumpProxy: JumpProxy? = null
            if (parent == null) session.setSocketFactory(object : SocketFactory {
                override fun createSocket(host: String, port: Int): Socket {
                    val socket = Socket()
                    synchronized(lock) { guard(); sockets += socket }
                    // DNS may finish after cancellation; never connect afterward.
                    val address = InetSocketAddress(host, port)
                    guard(); socket.connect(address, CONNECT_TIMEOUT)
                    socket.tcpNoDelay = true
                    return socket
                }
                override fun getInputStream(socket: Socket) = socket.getInputStream()
                override fun getOutputStream(socket: Socket) = socket.getOutputStream()
            }) else {
                val proxy = JumpProxy(parent)
                jumpProxy = proxy
                synchronized(lock) { guard(); proxies += proxy }
                session.setProxy(proxy)
            }
            try { session.connect(CONNECT_TIMEOUT); jumpProxy?.connected(); guard() }
            finally { identity.clear() } // Private imported bytes need not survive authentication.
            parent = session
        }
        guard()
        ready = checkNotNull(parent)
    }

    private inner class VaultIdentity(private val record: SshKeyRecord) : Identity {
        @Volatile private var lease: SshImportedKeyLease? = null
        private val cleared = AtomicBoolean(false)
        override fun getName() = record.id.toString()
        override fun getAlgName() = record.publicKey.algorithm
        override fun getPublicKeyBlob() = Base64.getDecoder().decode(record.publicKey.openSsh.substringAfter(' '))
        override fun isEncrypted() = false
        override fun setPassphrase(passphrase: ByteArray?) = false
        override fun getSignature(data: ByteArray): ByteArray {
            guard(); check(!cleared.get())
            val signature = when (record.kind) {
                SshKeyKind.GENERATED -> {
                    val operation = vault.prepareSignature(record.id)
                    if (record.requiresBiometrics) runBlocking(job) { authorize(record, operation) }
                    guard(); operation.sign(data)
                }
                SshKeyKind.IMPORTED -> {
                    val active = synchronized(this) {
                        check(!cleared.get())
                        lease ?: vault.openImportedKey(record.id).also { lease = it }
                    }
                    guard(); active.sign(data)
                }
            }
            guard(); return signature
        }
        override fun getSignature(data: ByteArray, algorithm: String): ByteArray {
            require(algorithm == algName); return getSignature(data)
        }
        override fun clear() = synchronized(this) {
            cleared.set(true); lease?.close(); lease = null
        }
    }

    private inner class Repository(private val hop: SshDialHop) : HostKeyRepository {
        override fun check(host: String, blob: ByteArray): Int {
            guard()
            if (host != hop.endpoint.hostKeyIdentity) return HostKeyRepository.NOT_INCLUDED
            val presented = SshHostKey.parse(HostKey(host, blob).type + " " + Base64.getEncoder().encodeToString(blob))
            val question = hosts.trustSnapshot(hop.endpoint)
            if (question.verdict(presented) != SshHostTrustVerdict.TRUSTED) {
                val accepted = runBlocking(job) { askTrust(SshTrustQuestion(hop.hostId, hop.endpoint, question, presented)) }
                guard()
                if (!accepted) {
                    hosts.setAutoConnectPaused(plan, hop.hostId, true)
                    return if (question.pinned == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
                }
                check(hosts.confirmHostKey(plan, hop.hostId, question, presented)) { "SSH identity question is stale" }
            }
            synchronized(lock) { serverKeys[hop.hostId] = presented }
            guard()
            return HostKeyRepository.OK
        }
        override fun getHostKey(): Array<HostKey> = getHostKey(null, null)
        override fun getHostKey(host: String?, type: String?): Array<HostKey> {
            val key = hosts.trustSnapshot(hop.endpoint).pinned ?: return emptyArray()
            if (host != null && host != hop.endpoint.hostKeyIdentity || type != null && type != key.algorithm) return emptyArray()
            return arrayOf(HostKey(hop.endpoint.hostKeyIdentity, Base64.getDecoder().decode(key.openSsh.substringAfter(' '))))
        }
        override fun getKnownHostsRepositoryID() = "cmux phone SSH identities"
        override fun add(key: HostKey, ui: UserInfo?) { error("Use the scoped host identity decision") }
        override fun remove(host: String, type: String?) { error("Use the saved-host store") }
        override fun remove(host: String, type: String?, key: ByteArray?) { error("Use the saved-host store") }
    }

    private inner class JumpProxy(private val parent: Session) : Proxy {
        @Volatile private var channel: ChannelDirectTCPIP? = null
        @Volatile private var input: SshTimedInputStream? = null
        @Volatile private var output: OutputStream? = null
        override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) {
            guard()
            val opened = parent.openChannel("direct-tcpip") as ChannelDirectTCPIP
            own(opened); channel = opened
            opened.setHost(host); opened.setPort(port)
            opened.setOrgIPAddress("127.0.0.1"); opened.setOrgPort(0)
            input = SshTimedInputStream(opened.inputStream, timeout, workers); output = opened.outputStream
            opened.connect(timeout); guard()
        }
        fun connected() { input?.timeoutMillis = 15_000 }
        override fun getInputStream() = checkNotNull(input)
        override fun getOutputStream() = checkNotNull(output)
        override fun getSocket(): Socket? = null
        override fun close() { input?.close(); input = null; channel?.let(::release); channel = null }
    }

    private fun own(channel: Channel) {
        try { synchronized(lock) { guard(); channels += channel } }
        catch (failure: Throwable) { channel.disconnect(); throw failure }
    }
    private fun release(channel: Channel) { synchronized(lock) { channels.remove(channel) }; channel.disconnect() }

    suspend fun exec(command: String, timeoutMillis: Long = 30_000, maxOutputBytes: Int = 8 * 1024 * 1024): SshExecResult {
        require(timeoutMillis > 0 && maxOutputBytes > 0)
        val channel = target().openChannel("exec") as ChannelExec
        own(channel)
        return blocking(cancel = { release(channel) }) {
            try {
                guard(); channel.setCommand(command)
                val input = channel.inputStream; val error = channel.errStream
                channel.connect(CONNECT_TIMEOUT)
                val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
                val started = System.nanoTime()
                fun drain(source: InputStream, sink: ByteArrayOutputStream) {
                    val bytes = ByteArray(8192)
                    while (source.available() > 0) {
                        val count = source.read(bytes, 0, minOf(bytes.size, source.available()))
                        if (count < 0) break
                        check(out.size().toLong() + err.size() + count <= maxOutputBytes) { "SSH command output exceeded its limit" }
                        sink.write(bytes, 0, count)
                    }
                }
                while (true) {
                    guard(); drain(input, out); drain(error, err)
                    if (channel.isClosed && input.available() == 0 && error.available() == 0) break
                    check(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) { "SSH command timed out; its channel was closed" }
                    Thread.sleep(10)
                }
                SshExecResult(out.toByteArray(), err.toByteArray(), channel.exitStatus)
            } finally { release(channel) }
        }
    }

    suspend fun openPty(columns: Int, rows: Int): SshPty {
        require(columns > 0 && rows > 0)
        val channel = target().openChannel("shell") as ChannelShell
        own(channel)
        return blocking(cancel = { release(channel) }) {
            try {
                channel.setPtyType("xterm-256color", columns, rows, 0, 0)
                val input = channel.inputStream; val output = channel.outputStream
                channel.connect(CONNECT_TIMEOUT); guard()
                SshPty(input, send = { bytes -> blocking(cancel = { release(channel) }) {
                    guard(); check(channel.isConnected); output.write(bytes); output.flush(); guard()
                } }, resize = { width, height -> blocking(cancel = { release(channel) }) {
                    require(width > 0 && height > 0); guard(); check(channel.isConnected)
                    channel.setPtySize(width, height, 0, 0); guard()
                } }, closeBlock = { release(channel) })
            } catch (failure: Throwable) { release(channel); throw failure }
        }
    }

    suspend fun <T> withSftp(block: (ChannelSftp) -> T): T {
        val channel = target().openChannel("sftp") as ChannelSftp
        own(channel)
        return blocking(cancel = { release(channel) }) {
            try { guard(); channel.connect(CONNECT_TIMEOUT); guard(); block(channel).also { guard() } }
            finally { release(channel) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Raw socket closure precedes JSch teardown so blocked reads/writes and
        // nested proxy connects cannot keep teardown waiting on their own locks.
        job.cancel()
        val resources = synchronized(lock) {
            CloseSet(sockets.toList(), sessions.toList(), channels.toList(), proxies.toList(), identities.toList())
                .also { sockets.clear(); sessions.clear(); channels.clear(); proxies.clear(); identities.clear() }
        }
        resources.sockets.forEach { runCatching { it.close() } }
        resources.identities.forEach { runCatching { it.clear() } }
        resources.channels.asReversed().forEach { runCatching { it.disconnect() } }
        resources.proxies.asReversed().forEach { runCatching { it.close() } }
        resources.sessions.asReversed().forEach { runCatching { it.disconnect() } }
        ready = null
        ended.value = true
    }
    private data class CloseSet(val sockets: List<Socket>, val sessions: List<Session>, val channels: List<Channel>,
        val proxies: List<Proxy>, val identities: List<VaultIdentity>)

    companion object {
        private const val CONNECT_TIMEOUT = 10_000
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "cmux-ssh-io").apply { isDaemon = true } }
        private suspend fun <T> blocking(cancel: () -> Unit, action: () -> T): T = suspendCancellableCoroutine { continuation ->
            val future = workers.submit {
                try {
                    val result = action()
                    continuation.resume(result, onCancellation = { _, _, _ -> cancel() })
                } catch (failure: Throwable) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
            continuation.invokeOnCancellation { cancel(); future.cancel(true) }
        }
        suspend fun connect(hosts: SshHostStore, vault: SshKeyVault, hostId: UUID, lifetime: CoroutineScope,
            admitted: () -> Boolean, explicit: Boolean,
            askTrust: suspend (SshTrustQuestion) -> Boolean,
            authorize: suspend (SshKeyRecord, SshPreparedSignature) -> Unit,
        ): SshTransport {
            check(admitted() && lifetime.isActive) { "Sign in before connecting an SSH computer" }
            var plan = hosts.dialPlan(hostId)
            if (explicit) for (hop in plan.hops) {
                check(hosts.setAutoConnectPaused(plan, hop.hostId, false)) { "SSH route changed" }
                plan = hosts.dialPlan(hostId)
            }
            check(hosts.mayAutoConnect(plan)) { "Automatic SSH connection is paused; open the computer to retry" }
            val transport = SshTransport(hosts, vault, plan, lifetime, admitted, askTrust, authorize)
            try {
                blocking(cancel = transport::close) { transport.connectBlocking() }
                transport.guard(); hosts.markUsed(hostId)
                return transport
            } catch (failure: Throwable) { transport.close(); throw failure }
        }
    }
}

internal class SshPty internal constructor(val output: InputStream,
    private val send: suspend (ByteArray) -> Unit, private val resize: suspend (Int, Int) -> Unit,
    private val closeBlock: () -> Unit) : AutoCloseable {
    private val operations = Mutex()
    suspend fun write(bytes: ByteArray) {
        val owned = bytes.copyOf()
        operations.withLock { send(owned) }
    }
    suspend fun resize(columns: Int, rows: Int) = operations.withLock { resize.invoke(columns, rows) }
    override fun close() = closeBlock()
}
