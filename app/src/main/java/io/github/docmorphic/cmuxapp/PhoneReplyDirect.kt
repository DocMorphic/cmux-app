package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*

internal enum class PhoneReplyDirectResult { DELIVERED, UNAVAILABLE, UNKNOWN, SUBMIT_REQUIRED }

/** Authenticated action metadata, with no text; local topology cannot change its confinement. */
internal data class PhoneReplyDirectTarget(val team: NativeTeamScope, val origin: String, val epoch: String,
    val peer: PhonePushPeer, val workspace: String?, val surface: String, val retarget: Boolean) {
    fun resolve(workspaces: List<NativeWorkspace>): String? {
        fun contains(row: NativeWorkspace) = row.terminals.any { it.id == surface && it.isReady }
        workspaces.singleOrNull { it.id == workspace && contains(it) }?.let { return it.id }
        return if (retarget) workspaces.singleOrNull(::contains)?.id else null
    }
}

/** Construct only from a currently admitted, already-open connection. Never dials or changes selection. */
internal class PhoneReplyDirectAttempt(val target: PhoneReplyDirectTarget,
    private val ready: () -> Boolean,
    private val deliver: suspend (String, () -> Boolean) -> Boolean) {
    private var used = false
    suspend fun send(text: String, permits: () -> Boolean): PhoneReplyDirectResult {
        if (used) return PhoneReplyDirectResult.UNKNOWN
        used = true
        var active = true
        val allowed = { active && ready() && permits() }
        try {
            if (!allowed()) return PhoneReplyDirectResult.UNAVAILABLE
            return withTimeoutOrNull(4_000) {
                if (deliver(text, allowed)) PhoneReplyDirectResult.DELIVERED else PhoneReplyDirectResult.UNAVAILABLE
            } ?: PhoneReplyDirectResult.UNKNOWN
        } catch (_: PhoneReplySubmitRequired) {
            return PhoneReplyDirectResult.SUBMIT_REQUIRED
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return PhoneReplyDirectResult.UNKNOWN
        } finally { active = false } // A cancelled ordered operation must not begin a late write.
    }
}

/** UI dispatcher only; background receivers inspect registered owners without constructing an app runtime. */
internal object PhoneReplyDirect {
    private val providers = linkedMapOf<Any, (PhoneReplyDirectTarget) -> PhoneReplyDirectAttempt?>()
    fun register(owner: Any, provider: (PhoneReplyDirectTarget) -> PhoneReplyDirectAttempt?) { providers[owner] = provider }
    fun remove(owner: Any) { providers.remove(owner) }
    fun prepare(target: PhoneReplyDirectTarget): PhoneReplyDirectAttempt? =
        providers.values.toList().firstNotNullOfOrNull { it(target) }
}

@Composable internal fun ObservePhoneReplyDirect(lifecycle: Lifecycle,
    provider: (PhoneReplyDirectTarget) -> PhoneReplyDirectAttempt?) {
    val current by rememberUpdatedState(provider)
    DisposableEffect(lifecycle) {
        val owner = Any()
        PhoneReplyDirect.register(owner) { target ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) null else current(target)
        }
        onDispose { PhoneReplyDirect.remove(owner) }
    }
}

/** A successful paste RPC can still report that its separate submit key was refused. */
internal class PhoneReplySubmitRequired : Exception("Reply pasted but submit key was not accepted")
internal fun checkPhoneReplyPaste(response: org.json.JSONObject) {
    when (response.opt("submitted")) {
        true -> Unit
        false -> throw PhoneReplySubmitRequired()
        else -> error("Reply submission was not confirmed")
    }
}
