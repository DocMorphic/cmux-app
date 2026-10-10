package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-local opt-out shared by Settings and the main-process runtime.
 * This store creates no identity, uploader, recorder, token or network request.
 */
internal class NativePrivacyConsent(private val preferences: SharedPreferences) : AutoCloseable {
    private val mutable = MutableStateFlow(readStored())
    val enabled: StateFlow<Boolean> = mutable.asStateFlow()
    private val gate = NativeAnalyticsConsentGate(mutable.value) { mutable.value = it.enabled }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == telemetryKey) capture()
    }
    init { preferences.registerOnSharedPreferenceChangeListener(listener); capture() }

    private fun readStored(): Boolean = preferences.all[telemetryKey] as? Boolean ?: true
    fun capture(): NativeAnalyticsConsentSnapshot {
        val beforeRead = gate.snapshot()
        return gate.synchronize(readStored(), beforeRead)
    }
    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(telemetryKey, value).apply()
        // apply updates the in-memory store immediately; off-main listeners can
        // be posted later. Revocation must be reconciled before this call returns.
        capture()
    }
    fun allows(snapshot: NativeAnalyticsConsentSnapshot): Boolean { capture(); return gate.allows(snapshot) }
    fun register(job: Job, snapshot: NativeAnalyticsConsentSnapshot): Boolean { capture(); return gate.register(job, snapshot) }
    override fun close() { preferences.unregisterOnSharedPreferenceChangeListener(listener); gate.close() }

    companion object {
        const val telemetryKey = "sendAnonymousTelemetry"
        private var shared: NativePrivacyConsent? = null
        @Synchronized fun current(context: Context): NativePrivacyConsent = shared ?: NativePrivacyConsent(
            context.applicationContext.getSharedPreferences("native_privacy", Context.MODE_PRIVATE)
        ).also { shared = it }
    }
}
