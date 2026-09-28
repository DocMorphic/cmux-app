package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*

/** Activity recreation and the notification service share one enrolled endpoint per process. */
internal class NativeAppConnections private constructor(context: Context) : AutoCloseable {
    val store = NativeCredentialStore(context)
    val account = NativeAccount(store)
    val teams = NativeAccountTeams(account, store)
    val native = NativeIrohRuntime(teams.state, teams::isCurrent, { account.accessToken() },
        { team, current -> NativeIrohBackend.create(context, team, account, current) })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tailscale = TailscaleConnector(context)
    val connector = object : NativeConnector {
        override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount) = tailscale.connect(pairing, account)
        override suspend fun connectIroh(pairing: PairingCode.Iroh, account: NativeAccount) = native.connect(pairing)
        override fun allowsSaved(pairing: PairingCode): Boolean {
            if (pairing is PairingCode.Tailscale) return true
            pairing as PairingCode.Iroh
            val team = teams.state.value.scope ?: return false
            return teams.isCurrent(team) && (pairing.userId == null || pairing.userId == team.userId) &&
                (pairing.teamId == null || pairing.teamId == team.teamId)
        }
    }

    init {
        scope.launch {
            var observedLogin: String? = null
            var nextRefresh = 0L
            while (isActive) {
                val login = store.taskSession()
                if (login != observedLogin) {
                    teams.clear()
                    observedLogin = login
                    nextRefresh = 0
                }
                val time = System.nanoTime() / 1_000_000
                if (login != null && time >= nextRefresh) {
                    nextRefresh = time + try { teams.refresh(); 60_000 } catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        15_000
                    }
                }
                delay(2000)
            }
        }
    }

    override fun close() { scope.cancel(); native.close(); teams.close() }

    class Handle internal constructor(val connections: NativeAppConnections) : AutoCloseable {
        private var released = false
        override fun close() = synchronized(sharedLock) {
            if (!released) {
                released = true
                references--
                if (references == 0) { shared = null; connections.close() }
            }
        }
    }

    companion object {
        private val sharedLock = Any()
        private var shared: NativeAppConnections? = null
        private var references = 0
        fun acquire(context: Context): Handle = synchronized(sharedLock) {
            val value = shared ?: NativeAppConnections(context.applicationContext).also { shared = it }
            references++
            Handle(value)
        }
    }
}

internal class NativeConnectionsViewModel(context: Context) : ViewModel() {
    private val handle = NativeAppConnections.acquire(context)
    val connections get() = handle.connections
    override fun onCleared() = handle.close()
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeConnectionsViewModel::class.java)
            return NativeConnectionsViewModel(context.applicationContext) as T
        }
    }
}
