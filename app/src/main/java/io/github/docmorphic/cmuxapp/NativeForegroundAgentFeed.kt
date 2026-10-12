package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Persisted read markers follow the account/team, not a membership refresh generation. */
internal fun nativeAgentFeedReadOwner(login: String?, team: NativeTeamScope?): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(org.json.JSONArray(listOf(login, team?.userId, team?.teamId)).toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
internal fun nativeAgentFeedLegacyReadOwner(login: String?, team: NativeTeamScope?): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(org.json.JSONArray(listOf(login, team?.toString())).toString().toByteArray())
        .joinToString("") { "%02x".format(it) }

/** One existing admitted handle owns reads, events and actions across Activity recreation. */
internal class NativeForegroundAgentFeed(parent: CoroutineScope, private val client: MobileRpcClient,
    private val permits: () -> Boolean, private val publish: (NativeAgentFeedState) -> Unit) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private var active = false
    private var fresh = false
    private var events: Job? = null
    fun current() = job.isActive && !client.isClosed && permits()
    private fun admitted() = active && current()
    val session = NativeAgentFeedSession(scope, ::admitted, { method, params -> client.agentFeedRequest(method, params, ::admitted) },
        fatal = { failure -> if (!client.isClosed) client.retireForOwnerFailure(failure) },
        requestCurrent = { method, params, canUse ->
            client.agentFeedRequest(method, params) { admitted() && canUse() && (method == "feed.list" || fresh) }
        }, snapshotAccepted = { fresh = admitted() }, cacheCurrent = ::current)
    init { scope.launch { session.state.collect { if (current()) publish(it) } } }
    fun readySession(): NativeAgentFeedSession? = session.takeIf { admitted() && fresh }
    fun setActive(value: Boolean) {
        val wanted = value && current()
        if (active == wanted) return
        active = wanted; fresh = false
        if (!wanted) { session.pause(); events?.cancel(); events = null; return }
        session.start()
        events = scope.launch {
            val stream = UUID.randomUUID().toString()
            try { client.useEventSession {
                coroutineScope {
                    val observer = launch { client.events.collect { event ->
                        if (admitted() && event.topic == "feed.changed") {
                            val revision = (event.payload.opt("revision") as? Number)?.toLong()
                            if (revision == null || revision < 0 || revision > (session.state.value.snapshot?.revision ?: -1)) fresh = false
                            session.changed(event.payload)
                        }
                    } }
                    try { check(admitted()); client.subscribe(listOf("feed.changed"), stream); awaitCancellation() }
                    finally {
                        observer.cancel()
                        withContext(NonCancellable) { withTimeoutOrNull(1000) { runCatching { client.unsubscribe(stream) } } }
                    }
                }
            } } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (nativeSetupRequiresReauth(failure) && !client.isClosed) client.retireForOwnerFailure(failure)
            }
        }
    }
    fun refresh() { if (admitted()) session.refresh() }
    override fun close() { active = false; fresh = false; session.close(); job.cancel() }
}
