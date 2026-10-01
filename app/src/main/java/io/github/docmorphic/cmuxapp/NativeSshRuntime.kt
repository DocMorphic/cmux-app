package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal class SshBiometricRequest(val key: SshKeyRecord, val operation: SshPreparedSignature) {
    val id = UUID.randomUUID()
    private val result = CompletableDeferred<Boolean>()
    fun answer(signature: java.security.Signature?) { result.complete(signature === operation.signature) }
    suspend fun await() { check(result.await()) { "SSH key authentication canceled" } }
    fun cancel() { result.cancel() }
}

internal class NativeSshSession(val hosts: SshHostStore, val vault: SshKeyVault,
    lifetime: CoroutineScope, private val cmuxInstaller: SshCmuxInstaller? = null, val admitted: () -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    val isOpen get() = synchronized(lock) { !closed && admitted() }
    private val requests = MutableStateFlow<List<SshBiometricRequest>>(emptyList())
    val biometrics = requests.asStateFlow()
    val connections = SshConnections(hosts, lifetime, admitted) { id, owner, current, trust ->
        SshTransport.connect(hosts, vault, id, owner, current, explicit = false, askTrust = trust,
            authorize = { key, operation ->
                val request = SshBiometricRequest(key, operation)
                synchronized(lock) {
                    check(!closed && current()) { "SSH connection retired" }
                    requests.value += request
                }
                try { request.await(); check(current()) { "SSH connection retired" } }
                finally { synchronized(lock) { requests.value -= request } }
            })
    }
    val shells = SshShells(hosts, connections, lifetime, admitted)
    val tmux = SshTmuxHosts(connections, lifetime, admitted)
    val cmux = SshCmuxHosts(connections, lifetime, admitted, cmuxInstaller) { id -> hosts.state.value.host(id)?.idleClose?.seconds }
    fun answerBiometric(id: UUID, signature: java.security.Signature?) = synchronized(lock) {
        if (!closed && admitted()) requests.value.firstOrNull()?.takeIf { it.id == id }?.answer(signature)
    }
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            requests.value.forEach { it.cancel() }; requests.value = emptyList()
            shells.close(); tmux.close(); cmux.close(); connections.close()
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
