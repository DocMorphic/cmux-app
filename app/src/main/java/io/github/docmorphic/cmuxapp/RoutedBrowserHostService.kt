package io.github.docmorphic.cmuxapp

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*

/** Bound by the visible browser process, retaining the owning main process without a foreground service. */
class RoutedBrowserHostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != Process.myUid()) return@Handler true
        val peer = message.replyTo ?: return@Handler true
        val kind = message.what; val ticket = message.arg1; val args = Bundle(message.data)
        scope.launch {
            val result = Bundle()
            try {
                val entry = checkNotNull(RoutedBrowserSessions.live(args.getString(RoutedBrowserProtocol.EXTRA))) { "Browser session ended. Open it again from the workspace." }
                if (kind == RoutedBrowserProtocol.OPEN) RoutedBrowserSessions.attach(entry, peer)
                check(entry.peer?.binder == peer.binder) { "Browser session belongs to another presentation" }
                when (kind) {
                    RoutedBrowserProtocol.OPEN -> {
                        result.putAll(RoutedBrowserProtocol.context(entry.workspace, entry.modes, entry.destination.surface.linkedStreamPanelId))
                        result.putString("storage", entry.network.storageId)
                        result.putInt("port", RoutedBrowserSessions.prepare(entry, entry.initial))
                        result.putString("surface", entry.destination.surface.id)
                        result.putString("url", entry.initial)
                    }
                    RoutedBrowserProtocol.PREPARE -> result.putInt("port", RoutedBrowserSessions.prepare(entry, args.getString("url")))
                    RoutedBrowserProtocol.SNAPSHOT -> entry.destination.surface.remote(entry.attachment, RoutedBrowserProtocol.snapshot(args))
                    RoutedBrowserProtocol.FOREGROUND -> entry.probe(args.getBoolean("active"))
                    else -> error("Unknown browser request")
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                result.putString("failure", failure.message ?: "Computer unavailable")
            }
            runCatching { peer.send(Message.obtain(null, kind).apply { arg1 = ticket; data = result }) }
        }
        true
    })
    override fun onBind(intent: Intent) = endpoint.binder
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
