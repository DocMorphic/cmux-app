package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class NativeTeam(val id: String, val name: String)
internal data class NativeTeamScope(val login: String, val userId: String, val teamId: String, val generation: Long)
internal data class NativeAccountTeamsState(
    val userId: String? = null,
    val teams: List<NativeTeam> = emptyList(),
    val selectedTeamId: String? = null,
    val scope: NativeTeamScope? = null,
    val loading: Boolean = false,
    val error: String? = null
)

/** Stack account membership is the authority for choosing the Iroh identity scope. */
internal class NativeAccountTeams(
    private val token: suspend (force: Boolean) -> String?,
    private val login: () -> String?,
    private val origin: HttpUrl = "https://api.stack-auth.com/api/v1/".toHttpUrl(),
    base: OkHttpClient = OkHttpClient()
) : AutoCloseable {
    constructor(account: NativeAccount, store: NativeCredentialStore) : this(
        { force -> account.accessToken(force) }, { store.taskSession() })

    private class StackFailure(val status: Int) : IOException("Account request failed ($status)")
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
    private val operations = Mutex()
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private var run = 0L
    private var scopeGeneration = 0L
    private val mutableState = MutableStateFlow(NativeAccountTeamsState())
    val state = mutableState.asStateFlow()

    init {
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.query == null && origin.fragment == null)
        require(origin.encodedPath.endsWith('/'))
        require(origin.isHttps || origin.host in setOf("localhost", "127.0.0.1", "::1"))
    }

    fun isCurrent(captured: NativeTeamScope): Boolean = synchronized(lock) {
        !closed && captured == mutableState.value.scope && login() == captured.login
    }

    suspend fun refresh(): NativeAccountTeamsState = perform { owner, epoch ->
        val user = request("users/me", owner, epoch)
        val userId = identifier(user.getString("id"))
        val selected = user.optJSONObject("selected_team")?.let { identifier(it.getString("id")) }
        val result = request("teams?user_id=me", owner, epoch)
        val items = result.getJSONArray("items")
        if (items.length() > 4096) throw IOException("Too many account teams")
        val teams = (0 until items.length()).map {
            val item = items.getJSONObject(it)
            val name = item.getString("display_name")
            if (name.length > 512) throw IOException("Invalid team name")
            NativeTeam(identifier(item.getString("id")), name)
        }
        if (teams.map { it.id }.distinct().size != teams.size) throw IOException("Duplicate account team")
        // Mirrors AuthCoordinator.resolveTeamID: invalid/absent selection uses first membership.
        publish(owner, epoch, userId, teams, selected?.takeIf { id -> teams.any { it.id == id } } ?: teams.firstOrNull()?.id)
    }

    suspend fun select(id: String): NativeAccountTeamsState = perform(clearOnForbidden = false) { owner, epoch ->
        val before = synchronized(lock) { mutableState.value }
        val previousScope = before.scope
        check(before.teams.any { it.id == id } && before.userId != null && previousScope?.login == owner) { "Refresh your account teams first" }
        if (id == before.selectedTeamId) return@perform before.copy(loading = false)
        val response = request("users/me", owner, epoch, JSONObject().put("selected_team_id", id))
        if (response.getString("id") != before.userId || response.optJSONObject("selected_team")?.getString("id") != id)
            throw IOException("Account team selection was not confirmed")
        publish(owner, epoch, before.userId, before.teams, id)
    }

    private suspend fun perform(clearOnForbidden: Boolean = true,
                                action: suspend (String, Long) -> NativeAccountTeamsState): NativeAccountTeamsState = operations.withLock {
        val owner = login() ?: throw IOException("Sign in to cmux")
        val epoch = synchronized(lock) {
            check(!closed) { "Account team session closed" }
            if (mutableState.value.scope?.login?.let { it != owner } == true) mutableState.value = NativeAccountTeamsState()
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            run
        }
        try {
            val result = action(owner, epoch)
            synchronized(lock) { checkCurrent(owner, epoch); mutableState.value = result.copy(loading = false) }
            mutableState.value
        } catch (failure: Exception) {
            synchronized(lock) {
                if (!closed && run == epoch) {
                    if (login() != owner || (failure is StackFailure &&
                            (failure.status == 401 || (failure.status == 403 && clearOnForbidden))))
                        mutableState.value = NativeAccountTeamsState(error = "Sign in to refresh your account")
                    else mutableState.value = mutableState.value.copy(loading = false, error = failure.message ?: "Could not load account teams")
                }
            }
            throw failure
        }
    }

    private fun publish(owner: String, epoch: Long, user: String, teams: List<NativeTeam>, selected: String?): NativeAccountTeamsState = synchronized(lock) {
        checkCurrent(owner, epoch)
        val previous = mutableState.value.scope
        val scope = selected?.let {
            if (previous?.login == owner && previous.userId == user && previous.teamId == selected) previous
            else NativeTeamScope(owner, user, selected, ++scopeGeneration)
        }
        NativeAccountTeamsState(user, teams, selected, scope)
    }

    private suspend fun request(path: String, owner: String, epoch: Long, body: JSONObject? = null): JSONObject {
        for (attempt in 0..1) {
            checkCurrent(owner, epoch)
            val access = token(attempt == 1)?.takeIf { it.isNotBlank() } ?: throw IOException("Sign in to cmux")
            if (access.length > 8192 || access.any { it == '\r' || it == '\n' }) throw IOException("Invalid account credential")
            checkCurrent(owner, epoch)
            val builder = Request.Builder().url(checkNotNull(origin.resolve(path)))
                .header("x-stack-project-id", NativeAccount.PROJECT_ID)
                .header("x-stack-publishable-client-key", NativeAccount.PUBLISHABLE_KEY)
                .header("x-stack-client-version", "cmux-android@0.2.0")
                .header("x-stack-access-type", "client")
                .header("x-stack-access-token", access)
                .header("x-stack-override-error-status", "true")
                .header("x-stack-random-nonce", UUID.randomUUID().toString())
            if (body != null) builder.patch(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            try {
                val response = exchange(builder.build(), owner, epoch)
                checkCurrent(owner, epoch)
                return response
            } catch (failure: StackFailure) {
                // Only an explicit authentication rejection is replayable with a refreshed token.
                // A timeout or lost PATCH response is never treated as proof the mutation failed.
                if (failure.status != 401 || attempt != 0) throw failure
            }
        }
        throw IOException("Sign in to cmux")
    }

    private suspend fun exchange(request: Request, owner: String, epoch: Long): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        synchronized(lock) { checkCurrent(owner, epoch); calls += call }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(lock) { calls -= call }
                continuation.resumeWith(Result.failure(IOException("Could not reach your cmux account", e)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val status = it.header("x-stack-actual-status")?.toIntOrNull() ?: it.code
                        if (!it.isSuccessful || status !in 200..299) throw StackFailure(if (it.isSuccessful) status else it.code)
                        val body = it.body ?: throw IOException("Missing account response")
                        if (body.contentLength() > MAX_REPLY) throw IOException("Account response too large")
                        val source = body.source()
                        if (source.request(MAX_REPLY + 1L)) throw IOException("Account response too large")
                        JSONObject(source.readUtf8())
                    }
                }
                val guarded = synchronized(lock) {
                    calls -= call
                    runCatching { checkCurrent(owner, epoch); result.getOrThrow() }
                }
                continuation.resumeWith(guarded)
            }
        })
    }

    private fun checkCurrent(owner: String, epoch: Long) = synchronized(lock) {
        if (closed || run != epoch || login() != owner) throw CancellationException("Account changed")
    }

    fun clear() {
        val old = synchronized(lock) {
            ++run
            mutableState.value = NativeAccountTeamsState()
            calls.toList().also { calls.clear() }
        }
        old.forEach { it.cancel() }
    }

    override fun close() { synchronized(lock) { closed = true }; clear() }

    private fun identifier(value: String) = value.also { if (it.isBlank() || it.length > 128) throw IOException("Invalid account identity") }
    private companion object { const val MAX_REPLY = 2 * 1024 * 1024 }
}
