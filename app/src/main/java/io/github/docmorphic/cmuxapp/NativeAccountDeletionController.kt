package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal data class NativeAccountDeletionReceipt(val id: String, val login: String, val result: NativeAccountDeletionResult)

/** Content-free encrypted receipt. A process restart cannot resubmit a destructive request. */
internal object NativeAccountDeletionRecord {
    const val KEY = "account_deletion"
    fun read(state: JSONObject?): NativeAccountDeletionReceipt? = runCatching {
        val root = state ?: return null
        if (root.optString("refresh_token").isBlank()) return null
        val value = root.optJSONObject(KEY) ?: return null
        if (value.getInt("version") != 1 || value.getString("login") != root.optString("task_session")) return null
        val id = value.getString("id"); require(UUID.fromString(id).toString() == id)
        NativeAccountDeletionReceipt(id, value.getString("login").also { require(it.isNotBlank()) },
            NativeAccountDeletionResult.valueOf(value.getString("result")))
    }.getOrNull()
    fun write(state: JSONObject, receipt: NativeAccountDeletionReceipt) {
        check(state.optString("task_session") == receipt.login && state.optString("refresh_token").isNotBlank())
        state.put(KEY, JSONObject().put("version", 1).put("id", receipt.id).put("login", receipt.login).put("result", receipt.result.name))
    }
    fun prune(state: JSONObject) { if (read(state) == null) state.remove(KEY) }
}

/** Process-owned operation survives Activity recreation; the durable receipt handles process death. */
internal class NativeAccountDeletionController(
    private val scope: CoroutineScope,
    private val load: () -> JSONObject?,
    private val update: ((JSONObject) -> Unit) -> Unit,
    private val delete: suspend (login: String) -> NativeAccountDeletionResult
) {
    private val lock = Any()
    private var job: Job? = null
    private var running: String? = null
    private val mutableState = MutableStateFlow(restored())
    val state = mutableState.asStateFlow()

    fun begin(login: String): Boolean = synchronized(lock) {
        if (job?.isActive == true || mutableState.value != null) return false
        val receipt = NativeAccountDeletionReceipt(UUID.randomUUID().toString(), login, NativeAccountDeletionResult.PROCESSING)
        try {
            update { root ->
                check(NativeAccountDeletionRecord.read(root) == null) { "A deletion result needs acknowledgement" }
                NativeAccountDeletionRecord.write(root, receipt)
            }
        } catch (_: Exception) {
            if (current(login)) mutableState.value = receipt.copy(result = NativeAccountDeletionResult.STORAGE)
            return false
        }
        running = receipt.id
        mutableState.value = receipt
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try { withTimeout(65_000) { delete(login) } }
            catch (_: TimeoutCancellationException) { NativeAccountDeletionResult.TIMED_OUT }
            catch (_: CancellationException) { NativeAccountDeletionResult.UNKNOWN }
            catch (_: Exception) { NativeAccountDeletionResult.UNKNOWN }
            synchronized(lock) {
                try { update { root ->
                    if (NativeAccountDeletionRecord.read(root)?.let { it.id == receipt.id && it.login == login } == true)
                        NativeAccountDeletionRecord.write(root, receipt.copy(result = result))
                } } catch (_: Exception) { /* Retained PROCESSING restores as unknown, never success. */ }
                if (running == receipt.id) { running = null; mutableState.value = restored() }
            }
        }.also { it.start() }
        true
    }

    fun reconcile() = synchronized(lock) {
        val saved = NativeAccountDeletionRecord.read(load())
        if (saved?.id == running && job?.isActive == true) return
        if (running != null) { job?.cancel(); running = null }
        val ephemeral = mutableState.value?.takeIf { it.result == NativeAccountDeletionResult.STORAGE && current(it.login) }
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
    private fun current(login: String): Boolean = load()?.let {
        it.optString("task_session") == login && it.optString("refresh_token").isNotBlank()
    } == true
    private fun restored(): NativeAccountDeletionReceipt? = NativeAccountDeletionRecord.read(load())?.let {
        if (it.result == NativeAccountDeletionResult.PROCESSING) it.copy(result = NativeAccountDeletionResult.UNKNOWN) else it
    }
}
