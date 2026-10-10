package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Recoverable selection limits; ownership and durable draft failures remain fatal to a batch. */
internal class TaskAttachmentLimitException(message: String) : IllegalArgumentException(message)

/** Attachment identities belong to the local snapshot, never to workspace.create's wire schema. */
internal object TaskAttachments {
    const val MAX_COUNT = 10
    const val IMAGE_BYTES = 8 * 1024 * 1024
    const val COUNT_MESSAGE = "You can attach up to 10 items to a task."
    const val TOTAL_MESSAGE = "Task attachments can use up to 64 MB in total."
    private const val SNAPSHOT_KEY = "_cmux_task_attachments"
    fun validate(items: List<ComposerAttachment>) {
        if (items.size > MAX_COUNT) throw TaskAttachmentLimitException(COUNT_MESSAGE)
        require(items.map { it.id }.distinct().size == items.size) { "Duplicate task attachment" }
        if (items.sumOf { it.size.toLong() } > ComposerAttachment.TOTAL_BYTES) throw TaskAttachmentLimitException(TOTAL_MESSAGE)
        items.forEach { require(ComposerAttachment.read(it.json(), IMAGE_BYTES, allowEmpty = true) == it) { "Invalid task attachment" } }
    }
    /** Match iOS selection prefixes; only a count/aggregate rejection may continue past append. */
    suspend fun <T, P : Any> stage(items: List<T>, remaining: Int, guard: () -> Unit,
        read: suspend (T) -> P?, append: suspend (P) -> Unit, rejected: (String) -> Unit) {
        for (item in items.take(remaining.coerceIn(0, MAX_COUNT))) {
            currentCoroutineContext().ensureActive(); guard()
            val prepared = read(item) ?: continue
            currentCoroutineContext().ensureActive(); guard()
            try { append(prepared) }
            catch (failure: TaskAttachmentLimitException) {
                currentCoroutineContext().ensureActive(); guard()
                rejected(checkNotNull(failure.message))
            }
            currentCoroutineContext().ensureActive(); guard()
        }
    }
    fun read(raw: JSONArray?): List<ComposerAttachment> = (raw?.let { list ->
        (0 until list.length()).map { checkNotNull(ComposerAttachment.read(list.getJSONObject(it), IMAGE_BYTES, allowEmpty = true)) }
    } ?: emptyList()).also(::validate)
    fun snapshot(parameters: JSONObject, attachments: List<ComposerAttachment>): JSONObject = JSONObject(parameters.toString()).apply {
        validate(attachments)
        if (attachments.isNotEmpty()) put(SNAPSHOT_KEY, JSONArray(attachments.map {
            JSONObject().put("upload_id", it.id).put("byte_count", it.size)
        }))
    }
    fun requested(snapshot: JSONObject, available: List<ComposerAttachment>): List<ComposerAttachment> {
        val ids = snapshot.optJSONArray(SNAPSHOT_KEY) ?: return emptyList()
        require(ids.length() <= MAX_COUNT)
        return (0 until ids.length()).map { index ->
            val identity = ids.getJSONObject(index)
            available.singleOrNull { it.id == identity.getString("upload_id") && it.size == identity.getInt("byte_count") }
                ?: error("An attachment is unavailable. Remove it and attach it again.")
        }.also(::validate)
    }
    fun wire(snapshot: JSONObject, paths: List<String> = emptyList()): JSONObject = JSONObject(snapshot.toString()).apply {
        remove(SNAPSHOT_KEY)
        if (paths.isNotEmpty() && !optString("initial_command").isBlank()) {
            require(paths.all { it.startsWith('/') && '\u0000' !in it }) { "Invalid attachment path from Mac" }
            val env = optJSONObject("initial_env") ?: JSONObject().also { put("initial_env", it) }
            val prompt = env.optString("CMUX_TASK_PROMPT")
            env.put("CMUX_TASK_ATTACHMENTS", paths.joinToString("\n"))
            env.put("CMUX_TASK_PROMPT", prompt + "\n\nAttached files (absolute paths on this machine):\n" + paths.joinToString("\n") { "- $it" })
        }
    }
    suspend fun prepareRequest(client: MobileRpcClient, snapshot: JSONObject, available: List<ComposerAttachment>,
        supported: Boolean, reconcile: Boolean, read: suspend (ComposerAttachment) -> ByteArray,
        checkCurrent: () -> Unit): JSONObject {
        // iOS reconciliation looks up the already accepted operation without re-uploading.
        if (reconcile || snapshot.optString("initial_command").isBlank()) return wire(snapshot)
        val items = requested(snapshot, available)
        require(items.isEmpty() || supported) { "Update cmux on this Mac to send task attachments." }
        val operation = snapshot.getString("operation_id"); UUID.fromString(operation)
        val paths = items.map { attachment ->
            currentCoroutineContext().ensureActive(); checkCurrent()
            val bytes = read(attachment)
            currentCoroutineContext().ensureActive(); checkCurrent()
            client.uploadAttachment(attachment, bytes, checkCurrent, operation)
        }
        checkCurrent()
        return wire(snapshot, paths)
    }
}
