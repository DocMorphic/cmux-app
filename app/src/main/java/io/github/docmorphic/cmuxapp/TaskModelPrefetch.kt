/* Scoped port of cmux MobileShellComposite+TaskModelPrefetch.swift at f4b1509.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Foreground/account owner only; no terminal input, account token or prompt enters the catalog. */
internal class TaskModelPrefetch(
    private val scope: CoroutineScope,
    private val models: TaskModelRepository,
    private val catalog: suspend () -> Map<TaskAgentCommand, TaskModelResult> = TaskModelCatalog::loadAll,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    data class Target(val origin: String, val identity: Any?,
        val host: (suspend (TaskAgentCommand) -> TaskModelResult)? = null)
    private data class Key(val model: TaskModelRepository.Key, val identity: Any?)
    private class Work { lateinit var job: Job; var retired = false }
    private data class Catalog(val startedAt: Long, val result: Deferred<Result<Map<TaskAgentCommand, TaskModelResult>>>)
    private var desired = emptyMap<Key, Target>()
    private val attempted = mutableSetOf<Key>()
    private val running = mutableMapOf<Key, Work>()
    private var wave: Catalog? = null
    private var pumpJob: Job? = null

    fun update(targets: List<Target>) {
        desired = buildMap {
            targets.forEach { target ->
                require(target.origin.isNotBlank())
                TaskAgentCommand.entries.forEach { put(Key(TaskModelRepository.Key(target.origin, it), target.identity), target) }
            }
        }
        attempted.retainAll(desired.keys)
        // Retain cancelling work until it actually exits, so a replacement
        // cannot grow the number of concurrent probes past the four-slot limit.
        running.filterKeys { it !in desired }.values.toList().forEach { it.retired = true; it.job.cancel() }
        if (desired.isEmpty()) {
            pumpJob?.cancel(); pumpJob = null
            wave?.result?.cancel(); wave = null
        } else schedulePump()
    }

    private fun schedulePump() {
        if (desired.isEmpty() || pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            // Coalesce topology updates and avoid recursive starts when a warm
            // cache completes synchronously on Main.immediate.
            yield()
            pumpJob = null
            pump()
        }
    }

    private fun catalogSnapshot(): Catalog {
        wave?.takeIf { now() - it.startedAt in 0 until CACHE_AGE }?.let { return it }
        wave?.result?.cancel()
        return Catalog(now(), scope.async(start = CoroutineStart.LAZY) {
            try { Result.success(catalog()) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { Result.failure(failure) }
        }).also { wave = it }
    }

    private fun pump() {
        while (running.size < MAX_WORKERS) {
            val key = desired.keys.firstOrNull { it !in attempted && it !in running } ?: return
            val target = checkNotNull(desired[key])
            val backend = catalogSnapshot()
            val work = Work()
            work.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    models.refreshShared(key.model, key.identity, scope,
                        host = {
                            val loader = target.host ?: throw MobileRpcException("not_connected", "Mac unavailable")
                            loader(key.model.provider)
                        },
                        catalog = {
                            val results = try { backend.result.await().getOrThrow() }
                            catch (failure: CancellationException) {
                                // Retiring a shared wave is an unavailable fallback,
                                // not cancellation of another caller's host probe.
                                currentCoroutineContext().ensureActive()
                                error("Provider catalog unavailable")
                            }
                            results[key.model.provider] ?: error("Provider catalog unavailable")
                        },
                        maximumCacheAge = CACHE_AGE)
                } catch (failure: Exception) {
                    // One failed optional probe must not retire another Mac's
                    // queued providers or classify its connection as unhealthy.
                    if (failure is CancellationException) throw failure
                } finally {
                    if (running[key] === work) {
                        running.remove(key)
                        if (key in desired && !work.retired) attempted += key
                        schedulePump()
                    }
                }
            }
            running[key] = work
            work.job.start()
        }
    }

    companion object {
        const val MAX_WORKERS = 4
        const val CACHE_AGE = 300_000L
    }
}
