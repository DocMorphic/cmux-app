package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** One foreground subscription for the current login/team; no persisted tokens or metadata. */
internal class NativeMacPresenceRuntime(
    teams: StateFlow<NativeAccountTeamsState>, active: StateFlow<IrxProbeActivity>,
    private val isCurrent: (NativeTeamScope) -> Boolean,
    private val accessToken: suspend (Boolean) -> String?,
    private val origin: HttpUrl = "https://presence.cmux.dev/".toHttpUrl(),
    client: OkHttpClient = OkHttpClient(), private val retryBaseMs: Long = 1000
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .pingInterval(30, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(NativeMacPresenceState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var closed = false

    init {
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.query == null && origin.fragment == null)
        require(origin.encodedPath == "/" && (origin.isHttps || origin.host in setOf("localhost", "127.0.0.1", "::1")))
        scope.launch {
            combine(teams.map { it.scope }, active.map { it.active }) { team, foreground -> team.takeIf { foreground } }
                .distinctUntilChanged().collectLatest { team ->
                    clear()
                    if (team == null) return@collectLatest
                    var attempt = 0
                    var forceRefresh = false
                    while (currentCoroutineContext().isActive && isCurrent(team)) {
                        var waitMs = retryBaseMs.coerceIn(1, 60_000)
                        try {
                            val token = accessToken(forceRefresh) ?: throw IOException("Presence sign-in unavailable")
                            forceRefresh = false
                            currentCoroutineContext().ensureActive()
                            if (!isCurrent(team)) return@collectLatest
                            subscribe(team, token) { next ->
                                synchronized(lock) { if (!closed && isCurrent(team)) mutableState.value = next }
                                attempt = 0
                            }
                        } catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            clear()
                            if (failure is PresenceHttpFailure) {
                                forceRefresh = failure.status == 401
                                waitMs = maxOf(waitMs, failure.retryDelay())
                            }
                            waitMs = maxOf(waitMs, (retryBaseMs.coerceIn(1, 60_000) * (1L shl attempt.coerceAtMost(6))).coerceAtMost(60_000))
                            attempt = (attempt + 1).coerceAtMost(6)
                        }
                        delay(waitMs)
                    }
                }
        }
    }

    private suspend fun subscribe(team: NativeTeamScope, token: String, publish: (NativeMacPresenceState) -> Unit) {
        val frames = Channel<String>(8)
        val socket = client.newWebSocket(Request.Builder().url(origin.resolve("v1/presence/subscribe")!!)
            .header("Authorization", "Bearer $token").header("X-Cmux-Team-Id", team.teamId).build(), object : WebSocketListener() {
            private fun accept(socket: WebSocket, text: String) {
                if (text.length > NativeMacPresenceReducer.MAX_FRAME || !frames.trySend(text).isSuccess) {
                    frames.cancel(CancellationException("Presence stream overflow")); socket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = accept(webSocket, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > NativeMacPresenceReducer.MAX_FRAME) {
                    frames.close(IOException("Presence frame too large")); webSocket.cancel()
                } else accept(webSocket, bytes.utf8())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                frames.close(IOException("Presence stream closed")); webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { frames.close(IOException("Presence stream closed")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                frames.close(PresenceHttpFailure(response?.code, response?.header("Retry-After")))
            }
        })
        try {
            val reducer = NativeMacPresenceReducer(team)
            publish(reducer.apply(withTimeout(15_000) { frames.receive() }))
            // Deadline-bounded subscriptions refresh auth and heal a quiet/dead stream.
            withTimeout(5 * 60_000L) {
                for (text in frames) {
                    if (!isCurrent(team)) return@withTimeout
                    publish(reducer.apply(text))
                }
            }
        } finally { socket.cancel(); frames.cancel() }
    }
    private fun clear() = synchronized(lock) { mutableState.value = NativeMacPresenceState() }
    override fun close() {
        synchronized(lock) { closed = true; mutableState.value = NativeMacPresenceState() }
        scope.cancel()
    }
}

internal class PresenceHttpFailure(val status: Int?, private val retryAfter: String?) : IOException("Presence unavailable") {
    fun retryDelay(now: Long = System.currentTimeMillis()): Long {
        if (status != 429) return 0
        val raw = retryAfter?.trim()
        val seconds = raw?.toLongOrNull()?.takeIf { it >= 0 }?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000)
        return seconds ?: runCatching {
            (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
        }.getOrNull() ?: 60_000
    }
}
