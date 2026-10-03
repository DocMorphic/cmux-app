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
    val error: String? = null,
    val createdTeam: NativeTeam? = null,
    val displayName: String? = null,
    val email: String? = null,
    val cached: Boolean = false
)

/** Stack account membership is the authority for choosing the Iroh identity scope. */
internal class NativeAccountTeams(
    private val token: suspend (force: Boolean) -> String?,
    private val login: () -> String?,
    private val origin: HttpUrl = "https://api.stack-auth.com/api/v1/".toHttpUrl(),
    base: OkHttpClient = OkHttpClient(),
    private val refreshCredential: () -> String? = { null },
    private val backendOrigin: HttpUrl = "https://cmux.com/".toHttpUrl(),
    private val cache: NativeAccountProfileCache? = null,
    private val lock: Any = Any()
) : AutoCloseable {
    constructor(account: NativeAccount, store: NativeCredentialStore) : this(
        { force -> account.accessToken(force) }, { store.taskSession() },
        refreshCredential = { store.load()?.optString("refresh_token") },
        cache = NativeAccountProfileCache(store::load, store::update), lock = store.accountStateLock)

    private class StackFailure(val status: Int) : IOException("Account request failed ($status)")
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
    private val operations = Mutex()
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private var run = 0L
    private var scopeGeneration = 0L
    private var verifiedLogin: String? = null
    private var presentationLogin: String? = null
    private val cacheEnvironment = "$origin#${NativeAccount.PROJECT_ID}"
    private val mutableState = MutableStateFlow(NativeAccountTeamsState())
    val state = mutableState.asStateFlow()

    init {
        require(backendOrigin.username.isEmpty() && backendOrigin.password.isEmpty() && backendOrigin.query == null && backendOrigin.fragment == null)
        require(backendOrigin.encodedPath == "/")
        require(backendOrigin.isHttps || backendOrigin.host in setOf("localhost", "127.0.0.1", "::1"))
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.query == null && origin.fragment == null)
        require(origin.encodedPath.endsWith('/'))
        require(origin.isHttps || origin.host in setOf("localhost", "127.0.0.1", "::1"))
        presentationLogin = login()
        mutableState.value = restore(presentationLogin)
    }

    fun isCurrent(captured: NativeTeamScope): Boolean = synchronized(lock) {
        !closed && captured == mutableState.value.scope && login() == captured.login
    }

    suspend fun refresh(): NativeAccountTeamsState = perform { owner, epoch ->
        val user = request("users/me", owner, epoch)
        val userId = identifier(user.getString("id"))
        synchronized(lock) {
            checkCurrent(owner, epoch)
            if (mutableState.value.userId?.let { it != userId } == true) {
                // The authenticated identity changed even if local credentials
                // retained their incarnation. Old teams must not survive a
                // subsequent failed membership request for the new principal.
                verifiedLogin = null
                mutableState.value = NativeAccountTeamsState(loading = true)
                runCatching { cache?.remove(owner) }
            }
        }
        val selected = user.optJSONObject("selected_team")?.let { identifier(it.getString("id")) }
        val displayName = profileText(user, "display_name")
        val email = profileText(user, "primary_email")
        val teams = listTeams(owner, epoch)
        val remembered = synchronized(lock) {
            checkCurrent(owner, epoch)
            mutableState.value.takeIf { it.userId == userId }?.selectedTeamId
        }
        // Keep the same user's saved choice only while it remains a verified membership.
        publish(owner, epoch, userId, teams, (selected ?: remembered)?.takeIf { id -> teams.any { it.id == id } } ?: teams.firstOrNull()?.id)
            .copy(displayName = displayName, email = email)
    }

    private suspend fun listTeams(owner: String, epoch: Long): List<NativeTeam> {
        val items = request("teams?user_id=me", owner, epoch).getJSONArray("items")
        if (items.length() > 4096) throw IOException("Too many account teams")
        val teams = (0 until items.length()).map {
            val item = items.getJSONObject(it)
            val name = item.getString("display_name")
            if (name.length > 512) throw IOException("Invalid team name")
            NativeTeam(identifier(item.getString("id")), name)
        }
        if (teams.map { it.id }.distinct().size != teams.size) throw IOException("Duplicate account team")
        return teams
    }

    suspend fun create(displayName: String): NativeAccountTeamsState {
        val name = displayName.trim()
        require(name.isNotEmpty() && name.length <= 120) { "Enter a team name of 1–120 characters" }
        return perform(clearOnForbidden = false) { owner, epoch ->
            val before = synchronized(lock) {
                checkCurrent(owner, epoch)
                check(verifiedLogin == owner && mutableState.value.userId != null) { "Refresh your account teams first" }
                mutableState.value.copy(createdTeam = null).also { mutableState.value = it }
            }
            val created = try {
                val access = credential(token(false))
                val refresh = credential(refreshCredential())
                checkCurrent(owner, epoch)
                val request = Request.Builder().url(checkNotNull(backendOrigin.resolve("api/subrouter/teams")))
                    .header("Accept", "application/json").header("Authorization", "Bearer $access")
                    .header("X-Stack-Refresh-Token", refresh)
                    .post(JSONObject().put("displayName", name).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                // The backend can create the team before a later error. Never replay this POST.
                val team = exchange(request, owner, epoch).getJSONObject("team")
                NativeTeam(identifier(team.getString("id")), team.getString("name").also {
                    if (it.isBlank() || it.length > 512) throw IOException("Invalid team name")
                })
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw IOException("Team creation was not confirmed. Refresh your account before trying again.", failure)
            }
            synchronized(lock) {
                checkCurrent(owner, epoch)
                mutableState.value = before.copy(teams = before.teams.filterNot { it.id == created.id } + created,
                    createdTeam = created)
            }
            try {
                val teams = listTeams(owner, epoch).let { listed ->
                    if (listed.any { it.id == created.id }) listed else listed + created
                }
                val selected = request("users/me", owner, epoch, JSONObject().put("selected_team_id", created.id))
                if (selected.getString("id") != before.userId || selected.optJSONObject("selected_team")?.getString("id") != created.id)
                    throw IOException("Account team selection was not confirmed")
                publish(owner, epoch, checkNotNull(before.userId), teams, created.id)
                    .copy(createdTeam = created, displayName = before.displayName, email = before.email)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw IOException("Team created, but switching was not confirmed. Select it from Team or refresh your account.", failure)
            }
        }
    }

    suspend fun select(id: String): NativeAccountTeamsState = perform(clearOnForbidden = false) { owner, epoch ->
        val before = synchronized(lock) { mutableState.value }
        check(before.teams.any { it.id == id } && before.userId != null && verifiedLogin == owner) { "Refresh your account teams first" }
        if (id == before.selectedTeamId) return@perform before.copy(loading = false)
        val response = request("users/me", owner, epoch, JSONObject().put("selected_team_id", id))
        if (response.getString("id") != before.userId || response.optJSONObject("selected_team")?.getString("id") != id)
            throw IOException("Account team selection was not confirmed")
        publish(owner, epoch, before.userId, before.teams, id).copy(displayName = before.displayName, email = before.email)
    }

    private suspend fun perform(clearOnForbidden: Boolean = true,
                                action: suspend (String, Long) -> NativeAccountTeamsState): NativeAccountTeamsState = operations.withLock {
        val owner = login() ?: throw IOException("Sign in to cmux")
        val epoch = synchronized(lock) {
            check(!closed) { "Account team session closed" }
            if (presentationLogin != owner) {
                verifiedLogin = null
                presentationLogin = owner
                mutableState.value = restore(owner)
            }
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            run
        }
        try {
            val result = action(owner, epoch)
            synchronized(lock) {
                checkCurrent(owner, epoch)
                var cacheError: String? = null
                try { cache?.save(owner, cacheEnvironment, result) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    cacheError = "Account refreshed, but details could not be saved for offline use."
                }
                checkCurrent(owner, epoch)
                mutableState.value = result.copy(loading = false, error = cacheError)
            }
            mutableState.value
        } catch (failure: Exception) {
            synchronized(lock) {
                if (!closed && run == epoch) {
                    if (login() != owner || (failure is StackFailure &&
                            (failure.status == 401 || (failure.status == 403 && clearOnForbidden)))) {
                        verifiedLogin = null
                        // Clear only this login's display cache. A late rejection
                        // must not erase the replacement account's snapshot.
                        runCatching { cache?.remove(owner) }
                        mutableState.value = NativeAccountTeamsState(error = "Sign in to refresh your account")
                    } else mutableState.value = mutableState.value.copy(loading = false, error = failure.message ?: "Could not load account teams")
                }
            }
            throw failure
        }
    }

    private fun publish(owner: String, epoch: Long, user: String, teams: List<NativeTeam>, selected: String?): NativeAccountTeamsState = synchronized(lock) {
        checkCurrent(owner, epoch)
        verifiedLogin = owner
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
            verifiedLogin = null
            presentationLogin = null
            mutableState.value = NativeAccountTeamsState()
            calls.toList().also { calls.clear() }
        }
        old.forEach { it.cancel() }
    }

    override fun close() { synchronized(lock) { closed = true }; clear() }

    /** Auth owner changes discard live authority; cache restoration is display-only. */
    fun reconcileLogin() {
        val old = synchronized(lock) {
            if (closed) return
            val owner = login()
            if (presentationLogin == owner) return
            ++run; verifiedLogin = null; presentationLogin = owner
            mutableState.value = restore(owner)
            calls.toList().also { calls.clear() }
        }
        old.forEach { it.cancel() }
    }

    private fun restore(owner: String?): NativeAccountTeamsState =
        owner?.let { runCatching { cache?.read(it, cacheEnvironment) }.getOrNull() } ?: NativeAccountTeamsState()

    private fun profileText(user: JSONObject, key: String): String? {
        val value = user.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        if (value !is String || value.length > 512) throw IOException("Invalid account profile")
        return value.trim().takeIf { it.isNotEmpty() }
    }

    private fun credential(value: String?): String = value?.takeIf {
        it.isNotBlank() && it.length <= 8192 && it.none { ch -> ch == '\r' || ch == '\n' }
    } ?: throw IOException("Sign in to cmux")

    private fun identifier(value: String) = value.also { if (it.isBlank() || it.length > 128) throw IOException("Invalid account identity") }
    private companion object { const val MAX_REPLY = 2 * 1024 * 1024 }
}
