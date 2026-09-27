package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Coalesce invalidations, serialize refreshes and break immediately on disconnect. */
internal suspend fun monitorNativeNotificationFeed(client: MobileRpcClient,
    receive: (List<NativeNotification>) -> Unit) = coroutineScope {
    val invalidations = Channel<Unit>(Channel.CONFLATED)
    val events = launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect { if (it.topic == "notification.feed.changed") invalidations.trySend(Unit) }
    }
    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
        client.disconnected.collect { invalidations.close(it) }
    }
    try {
        client.subscribe(listOf("notification.feed.changed"))
        while (isActive) {
            receive(parseNotifications(client.notifications()))
            withTimeoutOrNull(30_000) { invalidations.receive() }
        }
    } finally {
        events.cancel(); disconnect.cancel(); invalidations.cancel()
    }
}
