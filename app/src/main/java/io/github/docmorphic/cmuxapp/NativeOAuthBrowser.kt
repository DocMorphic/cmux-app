package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal class NativeOAuthLaunch(val id: String, val location: String, val flow: String) {
    override fun toString() = "NativeOAuthLaunch(redacted)"
}

/** Custom-Tab fallback callbacks resolve only an already-admitted in-memory flow. */
internal object NativeOAuthRedirectCallbacks {
    private data class Entry(val owner: Any, val receive: (String) -> Boolean)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    fun register(flow: String, owner: Any, receive: (String) -> Boolean) = synchronized(lock) { entries[flow] = Entry(owner, receive) }
    fun remove(flow: String, owner: Any) { synchronized(lock) { if (entries[flow]?.owner === owner) entries.remove(flow) } }
    fun deliver(uri: String): Boolean {
        val flow = NativeOAuthCallback.flow(uri) ?: return false
        val receiver = synchronized(lock) { entries[flow]?.receive } ?: return false
        return receiver(uri)
    }
}

/** Retained across Activity recreation, but deliberately absent after process death. */
internal class NativeOAuthBrowser {
    private class Pending(val launch: NativeOAuthLaunch) {
        val result = CompletableDeferred<String>()
        var launched = false
        var closed = false
    }
    private val lock = Any()
    private var pending: Pending? = null
    private var resumed = false
    private val mutable = MutableStateFlow<NativeOAuthLaunch?>(null)
    val state = mutable.asStateFlow()
    private val presented = MutableStateFlow<List<NativeOAuthLaunch>>(emptyList())
    val presentations = presented.asStateFlow()
    private val claimed = mutableSetOf<String>()
    suspend fun authorize(location: String, flow: String): String {
        val admitted = Pending(NativeOAuthLaunch(UUID.randomUUID().toString(), nativeOAuthProviderUrl(location), flow))
        val previous = synchronized(lock) { pending.also {
            pending = admitted; mutable.value = admitted.launch; presented.value = presented.value + admitted.launch
        } }
        previous?.let {
            NativeOAuthRedirectCallbacks.remove(it.launch.flow, it)
            it.result.cancel(CancellationException("Browser flow replaced"))
        }
        NativeOAuthRedirectCallbacks.register(flow, admitted) { receive(admitted.launch.id, it) }
        try { return admitted.result.await() }
        finally {
            NativeOAuthRedirectCallbacks.remove(flow, admitted)
            synchronized(lock) {
                if (pending === admitted) { pending = null; mutable.value = null }
                if (admitted.launch.id !in claimed) presented.value = presented.value.filterNot { it.id == admitted.launch.id }
            }
        }
    }
    fun claim(id: String): Boolean = synchronized(lock) {
        val current = pending?.takeIf { it.launch.id == id && !it.launched } ?: return@synchronized false
        current.launched = true; claimed += id; true
    }
    fun finishPresentation(id: String) { synchronized(lock) {
        claimed.remove(id); presented.value = presented.value.filterNot { it.id == id }
    } }
    fun receive(id: String, uri: String): Boolean = synchronized(lock) {
        val current = pending?.takeIf { it.launch.id == id && it.launched } ?: return@synchronized false
        if (NativeOAuthCallback.flow(uri) != current.launch.flow) return@synchronized false
        current.result.complete(uri)
    }
    fun failed(id: String) { synchronized(lock) {
        pending?.takeIf { it.launch.id == id }?.result?.completeExceptionally(NativeSignInFailure(NativeSignInError.BROWSER_SIGN_IN_FAILED))
    } }
    fun closed(id: String) { synchronized(lock) {
        val current = pending?.takeIf { it.launch.id == id } ?: return
        current.closed = true
        if (resumed) current.result.completeExceptionally(NativeAccountRequestFailure("OAUTH_CANCELLED", false, "OAuth ended"))
    } }
    fun hostResumed(value: Boolean) { synchronized(lock) {
        resumed = value
        // In the Custom-Tab fallback, the redirect Activity delivers before
        // the host resumes. A CANCELED result alone must not beat that callback.
        if (value) pending?.takeIf { it.closed }?.result?.completeExceptionally(
            NativeAccountRequestFailure("OAUTH_CANCELLED", false, "OAuth ended"))
    } }
}
