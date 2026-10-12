package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One admitted client's feed and mutations share a revision fence through recreation. */
internal class NativeForegroundNotificationAccess(private val scope: CoroutineScope,
    private val client: MobileRpcClient, private val permits: () -> Boolean,
    private val publish: (List<NativeNotification>, Long) -> Unit) {
    private val mutex = Mutex()
    private val revision = NativeFeedRevision()
    private fun checkOwner(canSend: () -> Boolean) = check(!client.isClosed && permits() && canSend()) {
        "Notification connection or action changed"
    }
    private suspend fun fetch(canSend: () -> Boolean): List<NativeNotification> {
        repeat(3) {
            checkOwner(canSend)
            val required = revision.required()
            val response = client.notifications()
            currentCoroutineContext().ensureActive(); checkOwner(canSend)
            if (revision.accept(response.optLong("revision", -1), required)) {
                val items = parseNotifications(response)
                publish(items, revision.snapshot)
                return items
            }
        }
        throw IOException("Notification refresh was superseded. Refresh again.")
    }
    private suspend fun <T> use(canSend: () -> Boolean, operation: suspend () -> T): T =
        withContext(scope.coroutineContext.minusKey(Job)) {
            mutex.withLock {
                checkOwner(canSend)
                try {
                    val result = operation()
                    ensureActive(); checkOwner(canSend)
                    result
                } catch (failure: Exception) {
                    if (nativeSetupRequiresReauth(failure) && !client.isClosed) client.retireForOwnerFailure(failure)
                    throw failure
                }
            }
        }
    suspend fun refresh(canSend: () -> Boolean = { true }) = use(canSend) { fetch(canSend) }
    suspend fun setRead(id: String, read: Boolean, canSend: () -> Boolean = { true }): Unit = use(canSend) {
        require(id.isNotBlank() && id.length <= 1024)
        val latest = fetch(canSend).singleOrNull { it.id == id } ?: error("This notification is no longer available")
        if (latest.isRead != read) {
            checkOwner(canSend)
            val response = client.setNotificationRead(id, read)
            currentCoroutineContext().ensureActive(); checkOwner(canSend)
            revision.acknowledge(response.optLong("revision", -1))
            fetch(canSend)
        }
    }
    suspend fun markAllRead(canSend: () -> Boolean = { true }): Unit = use(canSend) {
        if (fetch(canSend).any { !it.isRead }) {
            checkOwner(canSend)
            val response = client.markAllNotificationsRead()
            currentCoroutineContext().ensureActive(); checkOwner(canSend)
            revision.acknowledge(response.optLong("revision", -1))
            fetch(canSend)
        }
    }
}
