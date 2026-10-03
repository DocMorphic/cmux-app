package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal class SshBiometricRequest(val key: SshKeyRecord, val operation: SshPreparedSignature) {
    val id = UUID.randomUUID()
    private val result = CompletableDeferred<Boolean>()
    fun answer(signature: java.security.Signature?) { result.complete(signature === operation.signature) }
    suspend fun await() { check(result.await()) { "SSH key authentication canceled" } }
    fun cancel() { result.cancel() }
}

internal class SshInstallTrustRequest(val question: SshTrustQuestion) {
    val id = UUID.randomUUID()
    private val result = CompletableDeferred<Boolean>()
    fun answer(trust: Boolean) { result.complete(trust) }
    suspend fun await() = result.await()
    fun cancel() { result.cancel() }
}

internal class NativeSshSession(val hosts: SshHostStore, val vault: SshKeyVault,
    lifetime: CoroutineScope, private val cmuxInstaller: SshCmuxInstaller? = null, val admitted: () -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var closed = false
    val isOpen get() = synchronized(lock) { !closed && admitted() }
    private val requests = MutableStateFlow<List<SshBiometricRequest>>(emptyList())
    val biometrics = requests.asStateFlow()
    val connections = SshConnections(hosts, lifetime, admitted) { id, owner, current, trust ->
        SshTransport.connect(hosts, vault, id, owner, current, explicit = false, askTrust = trust,
            authorize = { key, operation -> authorizeKey(key, operation, current) })
    }
    private suspend fun authorizeKey(key: SshKeyRecord, operation: SshPreparedSignature, current: () -> Boolean) {
        val request = SshBiometricRequest(key, operation)
        synchronized(lock) {
            check(!closed && current()) { "SSH connection retired" }
            requests.value += request
        }
        try { request.await(); check(current()) { "SSH connection retired" } }
        finally { synchronized(lock) { requests.value -= request } }
    }
    private val installLock = Mutex()
    private val installQuestions = MutableStateFlow<List<SshInstallTrustRequest>>(emptyList())
    val installPrompts = installQuestions.asStateFlow()
    fun answerInstallTrust(id: UUID, trust: Boolean) = synchronized(lock) {
        if (isOpen) installQuestions.value.firstOrNull()?.takeIf { it.id == id }?.answer(trust)
    }
    suspend fun installKey(expected: SshHostRecord, password: ByteArray) {
        try {
            installLock.withLock {
                check(isOpen) { "SSH account retired" }
                SshKeyInstaller.install(hosts, vault, expected, password, { isOpen }, askTrust = { question ->
                    val request = SshInstallTrustRequest(question)
                    synchronized(lock) { check(isOpen); installQuestions.value += request }
                    try { request.await() } finally { synchronized(lock) { installQuestions.value -= request } }
                }, authorize = { key, operation -> authorizeKey(key, operation) { isOpen } })
            }
        } finally { password.fill(0) }
    }

    private val composerDrafts = SshComposerPool()
    val shells = SshShells(hosts, connections, lifetime, admitted, composerDrafts)
    val tmux = SshTmuxHosts(connections, lifetime, admitted, composerDrafts)
    val cmux = SshCmuxHosts(connections, lifetime, admitted, cmuxInstaller, composerDrafts) { id -> hosts.state.value.host(id)?.idleClose?.seconds }
    init {
        lifetime.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { composerDrafts.close() }
        }
        lifetime.launch {
            var previous = hosts.state.value.hosts.map { it.id }.toSet()
            hosts.state.collect { current ->
                val ids = current.hosts.map { it.id }.toSet()
                (previous - ids).forEach { removed -> composerDrafts.discardWhere { it.startsWith("cmux-ssh-$removed") } }
                previous = ids
            }
        }
    }
    val browsers = SshBrowserNetworks(hosts, connections, lifetime) { !closed && admitted() }
    fun answerBiometric(id: UUID, signature: java.security.Signature?) = synchronized(lock) {
        if (!closed && admitted()) requests.value.firstOrNull()?.takeIf { it.id == id }?.answer(signature)
    }
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            requests.value.forEach { it.cancel() }; requests.value = emptyList()
            installQuestions.value.forEach { it.cancel() }; installQuestions.value = emptyList()
            composerDrafts.close(); browsers.close(); shells.close(); tmux.close(); cmux.close(); connections.close()
        }
    }
}

internal class NativeSshRuntime(context: Context, store: NativeCredentialStore, lifetime: CoroutineScope) : AutoCloseable {
    private val owner = SshLoginOwner(lifetime, store.revisions, store::taskSession) { scope, admitted ->
        val hosts = AndroidSshHostStore.get(context)
        val vault = SshKeyVault.get(context)
        check(admitted()) { "Account changed" }
        NativeSshSession(hosts, vault, scope, SshCmuxInstaller(SshCmuxArchive(java.io.File(context.cacheDir, "cmux-tui"))), admitted)
    }
    val state = owner.state
    fun retry() = owner.retry()
    override fun close() = owner.close()
}
