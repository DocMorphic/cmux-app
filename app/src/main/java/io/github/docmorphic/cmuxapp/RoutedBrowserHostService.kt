package io.github.docmorphic.cmuxapp

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*

/** Bound by the visible browser process, retaining the owning main process without a foreground service. */
class RoutedBrowserHostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saves = mutableMapOf<Pair<String, Int>, Job>()
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != Process.myUid()) return@Handler true
        val peer = message.replyTo ?: return@Handler true
        val kind = message.what; val ticket = message.arg1; val args = Bundle(message.data)
        scope.launch {
            val result = Bundle()
            try {
                val entry = checkNotNull(RoutedBrowserSessions.live(args.getString(RoutedBrowserProtocol.EXTRA))) { "Browser session ended. Open it again from the workspace." }
                if (kind == RoutedBrowserProtocol.OPEN) RoutedBrowserSessions.attach(entry, peer)
                check(!entry.menuRetired) { "Browser workspace ended" }
                check(entry.peer?.binder == peer.binder) { "Browser session belongs to another presentation" }
                when (kind) {
                    RoutedBrowserProtocol.OPEN -> {
                        result.putAll(entry.context())
                        entry.sidebar?.initialQuery()?.let { result.putString("sidebar_query", RoutedSidebarWire.query(it)) }
                        result.putString("storage", entry.network.storageId)
                        result.putInt("port", RoutedBrowserSessions.prepare(entry, entry.initial))
                        result.putString("surface", entry.destination.surface.id)
                        result.putString("url", entry.initial)
                    }
                    RoutedBrowserProtocol.CUSTOMIZE -> {
                        val key = entry.id to ticket
                        check(key !in saves) { "Workspace save already submitted" }
                        saves[key] = currentCoroutineContext().job
                        try {
                            result.putAll(RoutedWorkspaceCustomizationProtocol.result(RoutedBrowserSessions.customize(entry,
                                RoutedWorkspaceCustomizationProtocol.draft(checkNotNull(args.getBundle("baseline"))),
                                RoutedWorkspaceCustomizationProtocol.draft(checkNotNull(args.getBundle("submitted"))))))
                        } finally { saves.remove(key) }
                    }
                    RoutedBrowserProtocol.CANCEL_CUSTOMIZE, RoutedBrowserProtocol.CANCEL_NOTIFICATION -> { saves[entry.id to ticket]?.cancel(); return@launch }
                    RoutedBrowserProtocol.PREPARE -> result.putInt("port", RoutedBrowserSessions.prepare(entry, args.getString("url")))
                    RoutedBrowserProtocol.SNAPSHOT -> entry.destination.surface.remote(entry.attachment, RoutedBrowserProtocol.snapshot(args))
                    RoutedBrowserProtocol.FOREGROUND -> RoutedBrowserSessions.foreground(entry, args.getBoolean("active"), args.getBoolean("sidebar_visible"))
                    RoutedBrowserProtocol.SIDEBAR -> result.putString("sidebar", RoutedSidebarWire.page(RoutedBrowserSessions.sidebar(entry, args)))
                    RoutedBrowserProtocol.SIDEBAR_STATE -> RoutedBrowserSessions.sidebarState(entry, RoutedSidebarWire.query(checkNotNull(args.getString("query"))))
                    RoutedBrowserProtocol.SIDEBAR_SORT -> RoutedBrowserSessions.sortSidebar(entry, RoutedSidebarWire.sort(checkNotNull(args.getString("sort"))))
                    RoutedBrowserProtocol.SIDEBAR_NOTIFICATION -> {
                        val key = entry.id to ticket
                        check(key !in saves) { "Notification update already submitted" }
                        saves[key] = currentCoroutineContext().job
                        try { RoutedBrowserSessions.notifications(entry,
                            RoutedSidebarWire.notification(checkNotNull(args.getString("notification")))) }
                        finally { saves.remove(key) }
                    }
                    RoutedBrowserProtocol.SIDEBAR_SELECT -> result.putString("selection", RoutedBrowserSessions.selectSidebar(entry, checkNotNull(args.getString("key"))))
                    RoutedBrowserProtocol.DEBUG_LOGS -> {
                        check(BuildConfig.DEBUG) { "Debug logs unavailable" }
                        result.putString("debug_logs", debugLogSnapshot(this@RoutedBrowserHostService))
                    }
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
