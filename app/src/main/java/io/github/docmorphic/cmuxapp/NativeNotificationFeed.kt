package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Coalesce invalidations, serialize refreshes and break immediately on disconnect. */
internal suspend fun monitorNativeNotificationFeed(client: MobileRpcClient,
    sync: NativeNotificationSync? = null,
    receive: (List<NativeNotification>) -> Unit) = coroutineScope {
    val refresh = NativeFeedRefresh()
    val revision = NativeFeedRevision()
    val events = launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect { event ->
            if (event.topic == "notification.dismissed") {
                notificationSyncIDs(event.payload, "ids")?.let { sync?.handled?.invoke(it) }
            }
            if (event.topic == "notification.feed.changed") {
                val changed = event.payload.optLong("revision", -1)
                if (changed < 0 || revision.observe(changed)) refresh.request()
            }
        }
    }
    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
        client.disconnected.collect { throw it }
    }
    var reconciliation: kotlinx.coroutines.Job? = null
    try {
        client.subscribe(listOf("notification.feed.changed", "notification.dismissed"))
        if (sync != null) reconciliation = launch {
            while (isActive) {
                reconcileNativeNotifications(sync, client::reconcileNotifications)
                delay(30_000)
            }
        }
        refresh.run {
            val required = revision.required()
            val response = client.notifications()
            val accepted = revision.accept(response.optLong("revision", -1), required)
            if (accepted) receive(parseNotifications(response))
            accepted && !revision.needsRefresh()
        }
    } finally {
        reconciliation?.cancel(); events.cancel(); disconnect.cancel(); refresh.close()
    }
}
