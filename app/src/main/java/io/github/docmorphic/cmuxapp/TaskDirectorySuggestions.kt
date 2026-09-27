package io.github.docmorphic.cmuxapp

import java.util.Locale

internal enum class TaskDirectorySource(val label: String) {
    HOME("Home folder"), SEARCH("On this Mac"), RECENT("Recent task"), OPEN_TERMINAL("Open on this Mac"),
    OPEN_WORKSPACE("Open on this Mac"), LAST("Last used"), TEMPLATE("Template default"),
    ACTIVE_WORKSPACE("Current workspace"), ACTIVE_TERMINAL("Focused terminal")
}
internal data class TaskDirectoryCandidate(val path: String, val source: TaskDirectorySource,
    val context: String? = null, val lastUsedAt: Long? = null, val uses: Long = 0)

internal fun taskDirectoryCandidates(template: TaskTemplate, state: TaskTemplateState, origin: String,
    workspaces: List<NativeWorkspace>, selectedId: String?): List<TaskDirectoryCandidate> = buildList {
    template.defaultDirectory?.let { add(TaskDirectoryCandidate(it, TaskDirectorySource.TEMPLATE, template.name)) }
    val recent = state.recent[origin].orEmpty()
    recent.firstOrNull()?.let { add(TaskDirectoryCandidate(it.path, TaskDirectorySource.LAST)) }
    recent.forEach { add(TaskDirectoryCandidate(it.path, TaskDirectorySource.RECENT, lastUsedAt = it.lastUsedAt, uses = it.useCount)) }
    for (workspace in workspaces) {
        val active = workspace.id == selectedId
        val time = workspace.lastActivityAt?.times(1000)?.toLong()
        workspace.directory?.let { add(TaskDirectoryCandidate(it, if (active) TaskDirectorySource.ACTIVE_WORKSPACE else TaskDirectorySource.OPEN_WORKSPACE, workspace.title, time)) }
        workspace.terminals.forEach { terminal -> terminal.directory?.let { add(TaskDirectoryCandidate(it,
            if (active && terminal.isFocused) TaskDirectorySource.ACTIVE_TERMINAL else TaskDirectorySource.OPEN_TERMINAL,
            "${workspace.title} · ${terminal.title}", time)) } }
    }
    add(TaskDirectoryCandidate("~", TaskDirectorySource.HOME))
}.filter { it.path.isNotBlank() }.map { it.copy(path = it.path.trim()) }

/** Exact path identity, with iOS source/recency/usage and component-match ordering. */
internal class TaskDirectorySuggestions(raw: List<TaskDirectoryCandidate>, private val now: Long = System.currentTimeMillis()) {
    private data class Prepared(val value: TaskDirectoryCandidate, val folded: String, val components: List<String>) {
        val basename = components.lastOrNull() ?: folded
    }
    private data class Ranked(val candidate: Prepared, val tier: Int, val unmatched: Int, val recency: Int)
    private val candidates: List<Prepared>
    init {
        val merged = linkedMapOf<String, TaskDirectoryCandidate>()
        raw.filter { it.path.isNotBlank() }.forEach { next ->
            val old = merged[next.path]
            merged[next.path] = if (old == null) next else old.copy(
                source = maxOf(old.source, next.source), context = if (next.source > old.source && next.context != null) next.context else old.context ?: next.context,
                lastUsedAt = listOfNotNull(old.lastUsedAt, next.lastUsedAt).maxOrNull(), uses = maxOf(old.uses, next.uses))
        }
        candidates = merged.values.map { value -> val folded = fold(value.path); Prepared(value, folded, components(folded)) }
    }
    fun suggestions(query: String, limit: Int = 8): List<TaskDirectoryCandidate> {
        if (limit <= 0) return emptyList()
        val raw = query.trim(); val folded = fold(raw); val parts = components(folded); val basename = parts.lastOrNull() ?: folded
        val best = mutableListOf<Ranked>()
        for (candidate in candidates) {
            val tier = when {
                folded.isEmpty() -> 0
                candidate.value.path == raw -> 5
                candidate.value.path.startsWith(raw) || candidate.basename == basename -> 4
                candidate.folded.startsWith(folded) -> 3
                orderedPrefixes(parts, candidate.components) -> 2
                candidate.basename.contains(basename) || candidate.folded.contains(folded) || fuzzy(basename, candidate.components) -> 1
                else -> continue
            }
            val ranked = Ranked(candidate, tier, if (tier == 5) 0 else maxOf(0, candidate.components.size - parts.size), recency(candidate.value.lastUsedAt))
            val at = best.indexOfFirst { compare(ranked, it) < 0 }.let { if (it < 0) best.size else it }
            best.add(at, ranked); if (best.size > limit) best.removeAt(best.lastIndex)
        }
        return best.map { it.candidate.value }
    }
    fun merged(search: TaskDirectorySearch?, query: String) = ((search?.paths.orEmpty().map {
        TaskDirectoryCandidate(it, TaskDirectorySource.SEARCH) }) + suggestions(query)).distinctBy { it.path }
    private fun compare(a: Ranked, b: Ranked): Int {
        val av = a.candidate.value; val bv = b.candidate.value
        return compareValuesBy(a, b, { -it.tier }, { -it.candidate.value.source.ordinal }, { -it.recency },
            { -it.candidate.value.uses.coerceIn(0, 99) }, { it.unmatched }, { it.candidate.value.path.toByteArray().size })
            .takeUnless { it == 0 } ?: TaskDirectoryPaths.compareUtf8(av.path, bv.path)
    }
    private fun recency(time: Long?): Int {
        if (time == null) return 0
        val seconds = maxOf(0.0, (now.toDouble() - time.toDouble()) / 1000)
        return when { seconds < 3600 -> 99; seconds < 86400 -> 80; seconds < 7 * 86400 -> 60; seconds < 30 * 86400 -> 40; else -> 20 }
    }
    private fun orderedPrefixes(query: List<String>, parts: List<String>): Boolean {
        if (query.isEmpty()) return false
        var index = 0
        for (part in query) { while (index < parts.size && !parts[index].startsWith(part)) index++; if (index == parts.size) return false; index++ }
        return true
    }
    private fun fuzzy(query: String, parts: List<String>): Boolean {
        val right = taskGraphemes(query); if (right.size < 3) return false
        val max = if (right.size >= 7) 2 else 1
        return parts.any { part ->
            val left = taskGraphemes(part)
            if (kotlin.math.abs(left.size - right.size) > max) false else {
                var previous = (0..right.size).toList()
                for ((i, character) in left.withIndex()) {
                    val row = mutableListOf(i + 1)
                    for ((j, other) in right.withIndex()) row += minOf(row[j] + 1, previous[j + 1] + 1, previous[j] + if (character == other) 0 else 1)
                    previous = row
                    if (row.min() > max) break
                }
                previous.last() <= max
            }
        }
    }
    private fun fold(value: String) = NativeSearchText.fold(value, Locale.US, notification = true)
    private fun components(value: String) = value.split(Regex("[/\\s]+"), 0).filter { it.isNotEmpty() }
}

private val taskGraphemePattern = Regex("\\X")
internal fun taskGraphemes(value: String) = taskGraphemePattern.findAll(value).map { it.value }.toList()
