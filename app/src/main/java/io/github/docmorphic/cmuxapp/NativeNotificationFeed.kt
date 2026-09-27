package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Coalesce invalidations, serialize refreshes and break immediately on disconnect. */
internal suspend fun monitorNativeNotificationFeed(client: MobileRpcClient,
    receive: (List<NativeNotification>) -> Unit) = coroutineScope {
    val refresh = NativeFeedRefresh()
    val revision = NativeFeedRevision()
    val events = launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect { event ->
            if (event.topic == "notification.feed.changed") {
                val changed = event.payload.optLong("revision", -1)
                if (changed < 0 || revision.observe(changed)) refresh.request()
            }
        }
    }
    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
        client.disconnected.collect { throw it }
    }
    try {
        client.subscribe(listOf("notification.feed.changed"))
        refresh.run {
            val required = revision.required()
            val response = client.notifications()
            val accepted = revision.accept(response.optLong("revision", -1), required)
            if (accepted) receive(parseNotifications(response))
            accepted && !revision.needsRefresh()
        }
    } finally {
        events.cancel(); disconnect.cancel(); refresh.close()
    }
}
