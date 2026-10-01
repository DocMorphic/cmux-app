package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.UUID

/** Main-process presentation registry. The other process only receives page metadata and an opaque proxy binding. */
internal object RoutedBrowserSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transitions = Mutex()
    private val networks = mutableMapOf<String, RoutedBrowserNetwork>()
    private var active: Entry? = null
    private var completed: Entry? = null

    internal class Entry(val id: String, val network: RoutedBrowserNetwork, val destination: LocalBrowserDestination,
        var workspace: NativeWorkspace, val release: () -> Unit, val probe: (Boolean) -> Unit) {
        val exited = CompletableDeferred<Unit>()
        var surfaceWatch: Job? = null
        var peer: Messenger? = null
        var death: IBinder.DeathRecipient? = null
        val attachment = destination.surface.attach()
        val initial = destination.surface.takeWork().url ?: destination.surface.state.value.url
        fun send(kind: Int, data: Bundle = Bundle()) { runCatching { peer?.send(Message.obtain(null, kind).apply { this.data = data }) } }
    }

    suspend fun register(context: Context, network: RoutedBrowserNetwork, destination: LocalBrowserDestination,
        workspace: NativeWorkspace, release: () -> Unit, probe: (Boolean) -> Unit): Entry = transitions.withLock {
        val app = context.applicationContext
        stopBrowserProcess(app)
        active?.let(::finished)
        completed = null
        if (networks.put(network.storageId, network) == null) scope.launch {
            network.retired.await()
            transitions.withLock {
                if (active?.network === network) { active?.send(RoutedBrowserProtocol.RETIRE); stopBrowserProcess(app); active?.let(::finished) }
                networks.remove(network.storageId)
                cleanStorage(app)
            }
        }
        cleanStorage(app)
        check(!network.retired.isCompleted) { "Browser account or computer changed" }
        Entry(UUID.randomUUID().toString(), network, destination, workspace, release, probe).also { entry ->
            active = entry; probe(true)
            entry.surfaceWatch = scope.launch {
                destination.surface.state.first { it.closed }
                abandon(app, entry.id)
            }
        }
    }
    fun find(id: String?) = active?.takeIf { it.id == id } ?: completed?.takeIf { it.id == id }
    fun live(id: String?) = active?.takeIf { it.id == id && !it.exited.isCompleted && !it.network.retired.isCompleted }
    fun attach(entry: Entry, peer: Messenger) {
        check(active === entry && !entry.network.retired.isCompleted)
        check(entry.peer == null || entry.peer?.binder == peer.binder) { "Browser request already attached" }
        if (entry.peer != null) return
        val death = IBinder.DeathRecipient { scope.launch { finished(entry) } }
        peer.binder.linkToDeath(death, 0)
        entry.peer = peer; entry.death = death
    }
    fun refresh(id: String?, workspace: NativeWorkspace) {
        val entry = live(id) ?: return
        if (entry.workspace != workspace) { entry.workspace = workspace; entry.send(RoutedBrowserProtocol.CONTEXT, RoutedBrowserProtocol.context(workspace)) }
    }
    fun finished(entry: Entry) {
        if (entry.exited.isCompleted) return
        entry.death?.let { runCatching { entry.peer?.binder?.unlinkToDeath(it, 0) } }
        entry.surfaceWatch?.cancel(); entry.surfaceWatch = null
        entry.probe(false); entry.destination.surface.detach(entry.attachment); entry.release()
        entry.exited.complete(Unit)
        if (active === entry) { active = null; completed = entry }
    }
    suspend fun abandon(context: Context, id: String?) = transitions.withLock {
        live(id)?.let { it.send(RoutedBrowserProtocol.RETIRE); stopBrowserProcess(context); finished(it) }
    }
    fun consume(id: String?) { if (completed?.id == id) completed = null }
    suspend fun prepare(entry: Entry, url: String?): Int {
        check(live(entry.id) === entry)
        val parsed = url?.toHttpUrlOrNull()
        return entry.network.prepare(parsed?.takeIf { BrowserLoopbackHost.matches(it.host) }?.port)
    }
    private suspend fun cleanStorage(context: Context) {
        val keep = networks.filterValues { !it.retired.isCompleted }.keys.toSet()
        withContext(Dispatchers.IO) { RoutedBrowserStorage(File(context.applicationInfo.dataDir), context.cacheDir).retain(keep) }
    }
    private suspend fun stopBrowserProcess(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun browsers() = manager.runningAppProcesses.orEmpty().filter {
            it.uid == Process.myUid() && it.processName == context.packageName + RoutedBrowserEnvironment.PROCESS && it.pid != Process.myPid()
        }
        browsers().forEach { Process.killProcess(it.pid) }
        withTimeout(5_000) { while (browsers().isNotEmpty()) delay(20) }
    }
}
