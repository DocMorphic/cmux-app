/* Derived from cmux WorkspaceChangesSummary* and MobileShellComposite+WorkspaceChanges,
 * revision 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject

internal const val WORKSPACE_CHANGES_CAPABILITY = "workspace.changes.v1"
internal data class WorkspaceChangesChip(val files: Long, val additions: Long, val deletions: Long) {
    val fileText get() = "$files ${if (files == 1L) "file" else "files"}"
    val label get() = "Changes: $fileText, +$additions, −$deletions"
}

/** Strict identity/bool fields, individually lossy entries, integer counts only. */
internal fun parseWorkspaceChangesSummaries(value: JSONObject, requested: Set<String>): Map<String, WorkspaceChangesChip?> {
    val array = value.optJSONArray("summaries") ?: return emptyMap()
    return buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.opt("workspace_id") as? String ?: continue
            if (id !in requested) continue
            val repository = item.opt("is_repo") as? Boolean ?: continue
            fun count(key: String): Long = when (val n = item.opt(key)) {
                is Int -> n.toLong().coerceAtLeast(0)
                is Long -> n.coerceAtLeast(0)
                else -> 0
            }
            val files = count("files_changed")
            put(id, if (repository && files > 0) WorkspaceChangesChip(files, count("additions"), count("deletions")) else null)
        }
    }
}

/** One owning-Mac connection, confined to its foreground dispatcher. Idle feed polls never call request. */
internal class WorkspaceChangesSummarySession(
    parent: CoroutineScope,
    private val admitted: () -> Boolean,
    private val fetch: suspend (List<String>, Boolean) -> JSONObject,
    private val publish: (Map<String, WorkspaceChangesChip>) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val failed: (Exception) -> Unit = {}
) : AutoCloseable {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private var ids = emptySet<String>()
    private val chips = linkedMapOf<String, WorkspaceChangesChip>()
    private val fetched = mutableMapOf<String, Long>()
    private val expiries = mutableMapOf<String, Long>()
    private val pending = linkedSetOf<String>()
    private var forcePending = false
    private var lastEvent: Long? = null
    private var debounce: Job? = null
    private var flight: Job? = null
    private var trailing: Job? = null

    fun retain(workspaces: List<String>) {
        if (!owner.isActive) return
        val next = workspaces.filter(String::isNotEmpty).toSet()
        val added = next - ids
        ids = next
        val changed = chips.keys.retainAll(ids)
        fetched.keys.retainAll(ids); expiries.keys.retainAll(ids); pending.retainAll(ids)
        if (changed && admitted()) publish(chips.toMap())
        scheduleTrailing()
        if (added.isNotEmpty()) request(added.toList())
    }
    fun request(workspaces: List<String>? = null, force: Boolean = false) = enqueue(workspaces ?: ids.toList(), force, event = true)
    private fun enqueue(workspaces: List<String>, force: Boolean, event: Boolean) {
        if (!owner.isActive || !admitted()) return
        val wanted = workspaces.filter { it in ids }
        if (wanted.isEmpty()) return
        if (event) lastEvent = now()
        pending.addAll(wanted); forcePending = forcePending || force
        if (flight != null) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(250); debounce = null
            flight = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (pending.isNotEmpty() && admitted()) {
                        val wanted = pending.toList(); val force = forcePending
                        pending.clear(); forcePending = false
                        val eligible = wanted.filter { id ->
                            val expires = fetched[id]?.plus(15_000)
                            if (!force && expires != null && now() < expires) {
                                arm(mapOf(id to expires)); false
                            } else true
                        }
                        for (batch in eligible.chunked(64)) {
                            ensureActive()
                            if (!admitted()) return@launch
                            val currentBatch = batch.filter { it in ids }
                            if (currentBatch.isEmpty()) continue
                            try {
                                val result = fetch(currentBatch, force)
                                ensureActive()
                                if (!admitted()) return@launch
                                val retained = currentBatch.filter { it in ids }.toSet()
                                for ((id, chip) in parseWorkspaceChangesSummaries(result, retained)) {
                                    if (chip == null) chips.remove(id) else chips[id] = chip
                                }
                                publish(chips.toMap())
                                val time = now()
                                retained.forEach { fetched[it] = time; expiries.remove(it) }
                                arm(retained.associateWith { time + 15_000 })
                            } catch (error: Exception) {
                                currentCoroutineContext().ensureActive()
                                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                                // Transport errors retain the last good chip; owner handles authorization retirement.
                                failed(error)
                            }
                        }
                    }
                } finally { flight = null; scheduleTrailing() }
            }
            flight?.start()
        }
    }
    private fun arm(values: Map<String, Long>) {
        val event = lastEvent ?: return
        if (now() - event > 15_000) return
        values.forEach { (id, expiry) ->
            if (id in ids) expiries[id] = minOf(expiries[id] ?: expiry, expiry)
        }
    }
    private fun scheduleTrailing() {
        trailing?.cancel(); trailing = null
        val deadline = expiries.values.minOrNull() ?: return
        if (!owner.isActive || !admitted()) return
        trailing = scope.launch {
            delay(maxOf(5_000, deadline - now()))
            val due = expiries.filterValues { it <= deadline }.keys.toList()
            due.forEach(expiries::remove)
            trailing = null; scheduleTrailing()
            enqueue(due, force = false, event = false)
        }
    }
    override fun close() {
        owner.cancel(); ids = emptySet(); pending.clear(); fetched.clear(); expiries.clear(); chips.clear()
    }
}
