package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal data class NativeChiefTranslationDownloadRequest(val id: Long, val source: NativeChiefTranslationLanguage,
    val target: NativeChiefTranslationLanguage, val settingsOpen: Boolean = false)

/** Retained owner, not an Activity callback. All calls use the transcript dispatcher. */
internal class NativeChiefTranslationDownload(private val permits: () -> Boolean) : AutoCloseable {
    private val mutableState = MutableStateFlow<NativeChiefTranslationDownloadRequest?>(null)
    val state = mutableState.asStateFlow()
    private var continuation: CancellableContinuation<Boolean>? = null
    private var generation = 0L
    private var closed = false
    var externalRequest: Long? = null; private set
    var externalDeparted = false; private set
    private fun owns(): Boolean {
        if (!closed && permits()) return true
        close(); return false
    }
    suspend fun request(source: NativeChiefTranslationLanguage, target: NativeChiefTranslationLanguage): Boolean {
        if (!owns()) throw CancellationException("Translation owner closed")
        check(continuation == null) { "Translation download is already pending" }
        return suspendCancellableCoroutine { pending ->
            val request = NativeChiefTranslationDownloadRequest(++generation, source, target)
            continuation = pending
            mutableState.value = request
            pending.invokeOnCancellation {
                if (continuation === pending) { continuation = null; mutableState.value = null }
            }
        }
    }
    fun openSettings(id: Long): Boolean {
        if (!owns() || mutableState.value?.id != id || externalRequest != null) return false
        externalRequest = id
        externalDeparted = false
        mutableState.value = mutableState.value?.copy(settingsOpen = true)
        return true
    }
    fun settingsReturned(id: Long) {
        if (externalRequest != id) return
        externalRequest = null
        externalDeparted = false
        finish(id, true)
    }
    fun settingsLeftApp() { if (externalRequest != null) externalDeparted = true }
    fun resumeFromSettings() { if (externalDeparted) externalRequest?.let(::settingsReturned) }
    fun settingsFailed(id: Long) {
        if (externalRequest != id) return
        externalRequest = null
        externalDeparted = false
        finish(id, false)
    }
    fun decline(id: Long) = finish(id, false)
    private fun finish(id: Long, admitted: Boolean) {
        if (!owns() || mutableState.value?.id != id) return
        val pending = continuation
        continuation = null; mutableState.value = null
        if (pending?.isActive == true) pending.resume(admitted)
    }
    override fun close() {
        if (closed) return
        closed = true; externalRequest = null; externalDeparted = false
        val pending = continuation
        continuation = null; mutableState.value = null
        pending?.cancel()
    }
}
