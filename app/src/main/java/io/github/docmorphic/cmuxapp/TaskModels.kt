/* Derived from cmux MobileTaskModelCatalogClient, MobileShellComposite+TaskModels,
 * MobileTaskModelAvailability and MobileTaskModelRefreshLoop at
 * 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class TaskEffort(val id: String, val name: String, val description: String? = null)
internal data class TaskModel(val id: String, val name: String, val efforts: List<TaskEffort> = emptyList(),
    val defaultEffortId: String? = null)
internal enum class TaskModelSource { DISCOVERED, BACKEND, FALLBACK }
internal enum class TaskModelError { PROVIDER_UNAVAILABLE, QUERY_FAILED, HOST_UNAVAILABLE }
internal data class TaskModelResult(val models: List<TaskModel>, val source: TaskModelSource,
    val defaultModel: TaskModel? = null, val error: TaskModelError? = null) {
    val usable get() = models.isNotEmpty() || defaultModel != null
}

internal object TaskModelParser {
    private fun JSONObject.string(key: String) = opt(key) as? String
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    fun host(value: JSONObject): TaskModelResult {
        val source = TaskModelSource.valueOf(value.getString("source").uppercase())
        val discoveryError = if (value.has("error")) TaskModelError.valueOf(value.getString("error").uppercase()) else null
        fun model(raw: JSONObject): TaskModel {
            val id = raw.string("id") ?: error("Missing model id")
            val name = raw.string("display_name") ?: error("Missing model name")
            require(id.isNotBlank() && name.isNotEmpty())
            val seen = mutableSetOf<String>()
            val efforts = raw.optJSONArray("efforts")?.objects()?.map {
                val effortId = it.string("id") ?: error("Missing effort id")
                val label = it.string("display_name") ?: error("Missing effort name")
                require(effortId.isNotBlank() && label.isNotEmpty() && seen.add(effortId))
                TaskEffort(effortId, label, it.string("description"))
            }.orEmpty()
            val default = raw.string("default_effort_id")?.takeIf { id -> efforts.any { it.id == id } }
            return TaskModel(id, name, efforts, default)
        }
        val models = value.getJSONArray("models").objects().map(::model)
        require(models.map { it.id }.distinct().size == models.size)
        return TaskModelResult(models, source, value.optJSONObject("default_model")?.let(::model), discoveryError)
    }

    fun catalog(value: JSONObject, provider: TaskAgentCommand): TaskModelResult {
        require(value.getInt("schemaVersion") == 1) { "Unsupported model catalog" }
        val raw = value.getJSONObject("providers").getJSONObject(provider.wireName)
        val seenModels = mutableSetOf<String>()
        val models = raw.getJSONArray("models").objects().mapNotNull { row ->
            val id = row.getString("id").trim(); val name = row.getString("label").trim()
            if (id.isEmpty() || name.isEmpty() || !seenModels.add(id)) return@mapNotNull null
            val seenEfforts = mutableSetOf<String>()
            val efforts = row.optJSONArray("efforts")?.objects()?.mapNotNull { effort ->
                val effortId = effort.getString("value").trim(); val label = effort.getString("label").trim()
                if (effortId.isEmpty() || label.isEmpty() || !seenEfforts.add(effortId)) null
                else TaskEffort(effortId, label, effort.string("description")?.trim()?.takeIf { it.isNotEmpty() })
            }.orEmpty()
            val default = row.string("defaultEffort")?.trim()?.takeIf { id -> efforts.any { it.id == id } }
            TaskModel(id, name, efforts, default)
        }
        require(models.isNotEmpty()) { "Empty model catalog" }
        val defaultId = raw.string("defaultModel")?.trim()
        return TaskModelResult(models, TaskModelSource.BACKEND, models.firstOrNull { it.id == defaultId })
    }
}

/** The catalog request contains no pairing, account or prompt information. Cancellation closes its socket. */
internal object TaskModelCatalog {
    suspend fun load(provider: TaskAgentCommand): TaskModelResult = suspendCancellableCoroutine { continuation ->
        val connection = URL("https://cmux.com/api/agent-models").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        continuation.invokeOnCancellation { connection.disconnect() }
        Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
            try {
                if (!continuation.isActive) return@Runnable
                check(connection.responseCode in 200..299) { "Model catalog unavailable" }
                val data = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 1_048_576) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                require(data.size <= 1_048_576) { "Model catalog too large" }
                continuation.resume(TaskModelParser.catalog(JSONObject(data.toString(Charsets.UTF_8)), provider))
            } catch (failure: Exception) { continuation.resumeWithException(failure) }
            finally { connection.disconnect() }
        })
    }
}

/** Account-owned, pairing/provider-scoped cache. Invoke on its owning UI scope. */
internal class TaskModelRepository {
    data class Key(val origin: String, val provider: TaskAgentCommand)
    private val cache = mutableMapOf<Key, TaskModelResult>()
    private val generations = mutableMapOf<Key, Long>()
    private var epoch = 0L
    fun cached(key: Key) = cache[key]
    fun clear() { epoch++; cache.clear(); generations.clear() }
    fun retainOrigins(origins: Set<String>) {
        val forgotten = (cache.keys + generations.keys).filter { it.origin !in origins }
        forgotten.forEach { cache.remove(it); generations[it] = (generations[it] ?: 0) + 1 }
    }

    /** A discovered host result wins in either completion order; transient errors preserve valid data. */
    suspend fun refresh(key: Key, host: suspend () -> TaskModelResult,
        catalog: suspend () -> TaskModelResult, update: (TaskModelResult) -> Unit): Boolean = coroutineScope {
        val capturedEpoch = epoch
        val generation = (generations[key] ?: 0) + 1
        generations[key] = generation
        fun current() = epoch == capturedEpoch && generations[key] == generation
        fun publish(value: TaskModelResult) {
            if (current()) { cache[key] = value; update(value) }
        }
        data class Result(val host: Boolean, val value: TaskModelResult?)
        val results = Channel<Result>(2)
        val jobs = listOf(launch {
            val result = try { host() } catch (failure: Exception) {
                if (failure is CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (failure !is TimeoutCancellationException) throw failure
                }
                TaskModelResult(emptyList(), TaskModelSource.FALLBACK, error = TaskModelError.HOST_UNAVAILABLE)
            }
            results.send(Result(true, result))
        }, launch {
            val result = try { catalog() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null
            }
            results.send(Result(false, result))
        })
        var hostResult: TaskModelResult? = null
        var backend: TaskModelResult? = null
        try {
            repeat(2) {
                val event = results.receive()
                ensureActive()
                if (!current()) return@coroutineScope false
                if (event.host) {
                    hostResult = event.value
                    val value = event.value!!
                    if (value.source == TaskModelSource.DISCOVERED && value.error == null && value.usable) {
                        publish(value); return@coroutineScope false
                    }
                    if (value.error == TaskModelError.PROVIDER_UNAVAILABLE) {
                        (backend ?: cache[key])?.let { publish(it.copy(error = value.error)) }
                    }
                } else {
                    val value = event.value
                    if (value != null && value.usable && cache[key]?.source != TaskModelSource.DISCOVERED) {
                        backend = value
                        publish(value.copy(error = hostResult?.error?.takeIf { it == TaskModelError.PROVIDER_UNAVAILABLE }))
                    }
                }
            }
            val failed = hostResult
            if (cache[key]?.usable != true && backend == null && failed != null) publish(failed.copy(models = emptyList(), defaultModel = null))
            hostResult?.error != TaskModelError.PROVIDER_UNAVAILABLE
        } finally { jobs.forEach { it.cancel() }; results.close() }
    }

    companion object { fun retryDelay(attempt: Int) = minOf(15_000L, 500L * (1L shl attempt.coerceIn(0, 5))) }
}

/** Keeps the concrete model from the presented menu if a catalog replacement delists it. */
internal data class TaskModelSelection(val explicit: TaskModel? = null, val effortId: String? = null) {
    fun model(result: TaskModelResult?) = explicit?.let { chosen -> result?.models?.firstOrNull { it.id == chosen.id } ?: chosen }
    fun effective(result: TaskModelResult?) = model(result) ?: result?.defaultModel
    fun reconcile(result: TaskModelResult?): TaskModelSelection {
        val model = effective(result)
        return copy(effortId = effortId?.takeIf { id -> model?.efforts?.any { it.id == id } == true } ?: model?.defaultEffortId)
    }
    fun choose(model: TaskModel?, result: TaskModelResult?) = TaskModelSelection(model, (model ?: result?.defaultModel)?.defaultEffortId)
}
