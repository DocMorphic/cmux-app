package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal data class NativeAccountDeletionReceipt(val id: String, val login: String, val result: NativeAccountDeletionResult,
    val binding: NativeDeletionBinding? = null) {
    override fun toString() = "NativeAccountDeletionReceipt(result=$result, owner=<redacted>)"
}
private class NativeDeletionAdmissionChanged : CancellationException("Account changed")

/** Content-free encrypted receipt. A process restart cannot resubmit a destructive request. */
internal object NativeAccountDeletionRecord {
    const val KEY = "account_deletion"
    fun read(state: JSONObject?): NativeAccountDeletionReceipt? = runCatching {
        val root = state ?: return null
        val value = root.optJSONObject(KEY) ?: return null
        val version = value.getInt("version"); if (version !in 1..2) return null
        val binding = if (version == 2) NativeDeletionBinding(value.getString("user").also { require(it.isNotBlank() && it.length <= 128) },
            value.getLong("generation").also { require(it >= 0) },value.getLong("epoch").also { require(it >= 0) },
            value.getString("process").also { require(UUID.fromString(it).toString() == it) }) else null
        val login = value.getString("login").also { require(it.isNotBlank()) }
        val empty = root.optString("task_session").isBlank() && root.optString("access_token").isBlank() && root.optString("refresh_token").isBlank()
        if (!(binding != null && empty) && (root.optString("refresh_token").isBlank() || login != root.optString("task_session"))) return null
        val id = value.getString("id"); require(UUID.fromString(id).toString() == id)
        NativeAccountDeletionReceipt(id,login,NativeAccountDeletionResult.valueOf(value.getString("result")),binding)
    }.getOrNull()
    fun write(state: JSONObject, receipt: NativeAccountDeletionReceipt) {
        val empty = state.optString("task_session").isBlank() && state.optString("access_token").isBlank() && state.optString("refresh_token").isBlank()
        check(receipt.binding != null && empty || state.optString("task_session") == receipt.login && state.optString("refresh_token").isNotBlank())
        val value = JSONObject().put("version",if(receipt.binding == null)1 else 2).put("id",receipt.id).put("login",receipt.login).put("result",receipt.result.name)
        receipt.binding?.let { value.put("user",it.user).put("generation",it.generation).put("epoch",it.epoch).put("process",it.process) }
        state.put(KEY,value)
    }
    fun prune(state: JSONObject) { if (read(state) == null) state.remove(KEY) }
}

/** Process-owned operation survives Activity recreation; the durable receipt handles process death. */
internal class NativeAccountDeletionController(
    private val scope: CoroutineScope,
    private val load: () -> JSONObject?,
    private val update: ((JSONObject) -> Unit) -> Unit,
    private val authority: NativeDeletionAuthority? = null,
    private val delete: suspend (login: String) -> NativeAccountDeletionResult
) {
    private val lock = Any()
    private var job: Job? = null
    private var running: String? = null
    private val mutableState = MutableStateFlow(restored())
    val state = mutableState.asStateFlow()

    val requiresIdentity get() = authority != null
    fun confirmationOwner(login: String): NativeDeletionBinding? = authority?.capture(login)
    fun begin(login: String, binding: NativeDeletionBinding? = authority?.capture(login)): Boolean = synchronized(lock) {
        if (job?.isActive == true || mutableState.value != null) return false
        if (authority != null && (binding == null || authority.requestOwner(login,binding) == null)) return false
        val receipt = NativeAccountDeletionReceipt(UUID.randomUUID().toString(), login, NativeAccountDeletionResult.PROCESSING,binding)
        try {
            update { root ->
                if (authority != null && authority.requestOwner(login,checkNotNull(binding)) == null) throw NativeDeletionAdmissionChanged()
                NativeAccountDeletionRecord.read(root)?.let { previous ->
                    if (authority?.status(previous) != NativeDeletionReceiptStatus.STALE) throw NativeDeletionAdmissionChanged()
                }
                NativeAccountDeletionRecord.write(root, receipt)
            }
        } catch (_: NativeDeletionAdmissionChanged) { return false
        } catch (_: Exception) {
            if (current(receipt)) mutableState.value = receipt.copy(result = NativeAccountDeletionResult.STORAGE)
            return false
        }
        running = receipt.id
        mutableState.value = receipt
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try { withTimeout(125_000) {
                if (authority != null) authority.delete(login,checkNotNull(binding)) else delete(login)
            } }
            catch (_: TimeoutCancellationException) { NativeAccountDeletionResult.TIMED_OUT }
            catch (_: CancellationException) { NativeAccountDeletionResult.UNKNOWN }
            catch (_: Exception) { NativeAccountDeletionResult.UNKNOWN }
            synchronized(lock) {
                try { update { root ->
                    if (current(receipt) && NativeAccountDeletionRecord.read(root)?.let { it.id == receipt.id && it.binding == binding } == true)
                        NativeAccountDeletionRecord.write(root, receipt.copy(result = result))
                } } catch (_: Exception) { /* Retained PROCESSING restores as unknown, never success. */ }
                if (running == receipt.id) { running = null; mutableState.value = restored() }
            }
        }.also { it.start() }
        true
    }

    fun reconcile() = synchronized(lock) {
        val saved = NativeAccountDeletionRecord.read(load())
        if (saved != null && saved.id == running && current(saved) && job?.isActive == true) return
        if (running != null) { job?.cancel(); running = null }
        val ephemeral = mutableState.value?.takeIf { it.result == NativeAccountDeletionResult.STORAGE && current(it) }
        mutableState.value = restored() ?: ephemeral
    }

    fun acknowledge(receipt: NativeAccountDeletionReceipt): Boolean = synchronized(lock) {
        if (mutableState.value != receipt || receipt.result == NativeAccountDeletionResult.PROCESSING) return false
        try { update { root ->
            if (NativeAccountDeletionRecord.read(root)?.let { it.id == receipt.id && it.login == receipt.login } == true)
                root.remove(NativeAccountDeletionRecord.KEY)
        } } catch (_: Exception) { return false }
        mutableState.value = null
        true
    }
    /** No asynchronous check may authorize local logout of a replacement session. */
    fun signOut(receipt: NativeAccountDeletionReceipt, apply: (NativeAuthenticatedOwner?) -> Boolean): Boolean = synchronized(lock) {
        if (mutableState.value != receipt || !receipt.result.signsOut) return false
        if (authority == null) return apply(null)
        val owner = authority.signOutOwner(receipt)
        if (owner == null) { reconcile();return true }
        apply(owner)
    }
    private fun current(receipt: NativeAccountDeletionReceipt): Boolean = if (authority != null)
        authority.status(receipt) == NativeDeletionReceiptStatus.CURRENT else load()?.let {
            it.optString("task_session") == receipt.login && it.optString("refresh_token").isNotBlank()
        } == true
    private fun restored(): NativeAccountDeletionReceipt? = NativeAccountDeletionRecord.read(load())?.let {
        if (!current(it)) null
        else if (it.result == NativeAccountDeletionResult.PROCESSING || authority != null && it.binding == null)
            it.copy(result = NativeAccountDeletionResult.UNKNOWN) else it
    }
}
