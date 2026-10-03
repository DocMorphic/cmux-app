package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class NativeMacCompatibilityRuntime(context: Context, private val store: NativeCredentialStore, active: StateFlow<IrxProbeActivity>,
    teams: StateFlow<NativeAccountTeamsState>, private val isCurrent: (NativeTeamScope) -> Boolean) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("native_mac_compatibility", Context.MODE_PRIVATE)
    private val stamp = context.packageManager.getPackageInfo(context.packageName, 0)
    // lastUpdateTime also separates local debug APKs with the same versionCode.
    private val key = cacheKey(ORIGIN, "${context.packageName}:${stamp.versionName}:${BuildConfig.VERSION_CODE}:${stamp.lastUpdateTime}")
    private val cache = NativeMacPolicyCache({ preferences.getString(key, null) }) {
        preferences.edit().clear().putString(key, it).apply()
    }
    val audience = NativeMacBuildAudience.consumer
    val gate = NativeMacCompatibilityGate(isCurrent, cache.policy, audience) { owner, _, _ -> persist(owner) }
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val refreshLock = Mutex()
    private var lastAttemptNanos: Long? = null

    init {
        preferences.all.keys.filter { it != key }.takeIf { it.isNotEmpty() }?.let { old ->
            preferences.edit().apply { old.forEach(::remove) }.apply()
        }
        scope.launch { teams.collect { gate.reconcile() } }
        scope.observeMacVersionHistory(store, teams, isCurrent, gate)
        scope.launch { while (isActive) { refresh(); delay(60 * 60 * 1000L) } }
        scope.launch { active.collectLatest { if (it.active) refresh() } }
    }

    private fun persist(owner: NativeTeamScope) {
        persistMacVersionHistory(store, gate, owner, isCurrent)
    }

    private suspend fun refresh() = refreshLock.withLock {
        val now = System.nanoTime()
        if (lastAttemptNanos?.let { now - it < TimeUnit.MINUTES.toNanos(5) } == true) return@withLock
        lastAttemptNanos = now
        try {
            val raw = runInterruptible {
                http.newCall(Request.Builder().url("$ORIGIN/api/mobile-mac-compat").get().build()).execute().use { response ->
                    check(response.isSuccessful)
                    val body = checkNotNull(response.body)
                    require(body.contentLength() <= NativeMacCompatibilityPolicy.MAX_BYTES)
                    val source = body.source()
                    val oversized = source.request(NativeMacCompatibilityPolicy.MAX_BYTES.toLong() + 1)
                    require(!oversized)
                    source.readUtf8()
                }
            }
            currentCoroutineContext().ensureActive()
            cache.accept(raw)?.let(gate::replace)
        } catch (_: Exception) { currentCoroutineContext().ensureActive() /* retain cached/baked policy */ }
    }

    override fun close() { scope.cancel(); http.dispatcher.cancelAll(); http.connectionPool.evictAll() }

    companion object {
        const val ORIGIN = "https://cmux.com"
        fun cacheKey(origin: String, stamp: String, profile: String = AndroidMacProtocolProfile.ID): String =
            MessageDigest.getInstance("SHA-256").digest("$origin\n$profile\n$stamp".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
