package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal enum class PhoneMacPushMode(val wire: String, val title: String) {
    AWAY("onlyWhenAway", "Only When Away"), ALWAYS("always", "Always")
}

/** Only an authenticated host response can establish these Mac-owned preferences. */
internal data class PhoneMacPushStatus(val enabled: Boolean, val mode: PhoneMacPushMode,
    val hideContent: Boolean, val admission: String, val queuePersistence: String) {
    companion object {
        fun parse(value: JSONObject?): PhoneMacPushStatus? {
            value ?: return null
            if (value.opt("account_scope") != "verified_same_account" || value.opt("api_origin") !is String) return null
            val enabled = value.opt("forwarding_enabled") as? Boolean ?: return null
            val mode = PhoneMacPushMode.entries.singleOrNull { it.wire == value.opt("mode") } ?: return null
            fun optionalString(key: String, fallback: String) = if (value.isNull(key)) fallback else value.opt(key) as? String
            val admission = optionalString("admission", "unknown") ?: return null
            val persistence = optionalString("queue_persistence", "unknown") ?: return null
            if (admission !in setOf("allowed", "forwarding_disabled", "suppressed_mac_active", "unknown") ||
                persistence !in setOf("unknown", "healthy", "load_failed", "save_failed", "clear_failed")) return null
            val hidden = if (value.isNull("hide_content")) false else value.opt("hide_content") as? Boolean ?: return null
            return PhoneMacPushStatus(enabled, mode, hidden, admission, persistence)
        }
    }
}

internal sealed interface PhoneMacPushChange {
    fun wire(): JSONObject
    data class Enabled(val value: Boolean) : PhoneMacPushChange {
        override fun wire() = JSONObject().put("forwarding_enabled", value)
    }
    data class Mode(val value: PhoneMacPushMode) : PhoneMacPushChange {
        override fun wire() = JSONObject().put("mode", value.wire)
    }
    data class HideContent(val value: Boolean) : PhoneMacPushChange {
        override fun wire() = JSONObject().put("hide_content", value)
    }
}

internal data class PhoneMacPushState(val status: PhoneMacPushStatus? = null, val supportsSettings: Boolean = false,
    val loading: Boolean = true, val busy: Boolean = false, val stale: Boolean = true, val error: String? = null) {
    val canChange get() = status != null && supportsSettings && !loading && !busy && !stale
}

/** Serialized reads/mutations; a lost mutation response is never automatically replayed. */
internal class PhoneMacPushController(private val current: () -> Boolean,
    private val read: suspend () -> JSONObject, private val write: suspend (PhoneMacPushChange) -> JSONObject) {
    private val mutex = Mutex()
    private val changing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mutable = MutableStateFlow(PhoneMacPushState())
    val state = mutable.asStateFlow()
    private fun admitted() { check(current()) { "Mac connection changed" } }
    private suspend fun readConfirmed(): PhoneMacPushState {
        admitted()
        val host = read()
        admitted()
        val capabilities = host.optJSONArray("capabilities")
        return PhoneMacPushState(status = PhoneMacPushStatus.parse(host.optJSONObject("phone_push")),
            supportsSettings = capabilities != null && (0 until capabilities.length()).any {
                capabilities.opt(it) == "phone_push.settings.v1"
            }, loading = false, stale = false)
    }
    suspend fun refresh() = mutex.withLock {
        try { mutable.value = readConfirmed().copy(busy = changing.get()) }
        catch (_: Exception) {
            mutable.value = if (current()) mutable.value.copy(loading = false, busy = false, stale = true,
                error = "Couldn’t read this Mac’s forwarding settings. Try again.") else PhoneMacPushState(loading = false)
            currentCoroutineContext().ensureActive()
        }
    }
    suspend fun change(change: PhoneMacPushChange) {
        // Wait for a read already in flight, but never queue repeated gestures.
        if (!current() || !mutable.value.canChange || !changing.compareAndSet(false, true)) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        try { mutex.withLock {
            val latest = mutable.value
            if (!current() || latest.status == null || !latest.supportsSettings || latest.stale) return@withLock
            try {
                admitted()
                write(change)
                admitted()
                // Read all settings again: another phone or Mac UI may also have changed them.
                mutable.value = readConfirmed()
            } catch (_: Exception) {
                mutable.value = if (current()) mutable.value.copy(busy = false, stale = true,
                    error = "Couldn’t confirm that change. Refresh to check the Mac’s settings.") else PhoneMacPushState(loading = false)
                currentCoroutineContext().ensureActive()
            }
        } } finally {
            changing.set(false)
            mutable.value = mutable.value.copy(busy = false)
        }
    }
}
