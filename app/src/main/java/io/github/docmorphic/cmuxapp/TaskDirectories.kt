package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

internal class TaskDirectoryPathError : IllegalArgumentException("Choose another location and try again.")

internal object TaskDirectoryPaths {
    fun absolute(path: String) = path.startsWith('/') && validBytes(path)
    private fun validBytes(path: String) = '\u0000' !in path && path.toByteArray(Charsets.UTF_8).size <= 4096
    fun browsable(path: String) = validBytes(path) && (path.startsWith('/') || path == "~" || path.startsWith("~/"))
    fun normalizedAbsolute(path: String): String? {
        if (!path.startsWith('/')) return null
        val parts = mutableListOf<String>()
        path.split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(it) } }
        return "/" + parts.joinToString("/")
    }
    fun ancestry(raw: String): List<String> {
        val path = raw.trim().ifEmpty { "~" }
        val root = when { path.startsWith('/') -> "/"; path == "~" || path.startsWith("~/") -> "~"; else -> return listOf(path) }
        var current = if (root == "/") "" else root
        return (listOf(root) + path.removePrefix(root).split('/').filter { it.isNotEmpty() }.map { current += "/$it"; current }).takeLast(12)
    }
    fun name(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    fun compareUtf8(a: String, b: String): Int {
        val left = a.toByteArray(Charsets.UTF_8); val right = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(left.size, right.size)) {
            val comparison = (left[i].toInt() and 255).compareTo(right[i].toInt() and 255)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}

internal data class TaskDirectoryEntry(val name: String, val path: String, val hidden: Boolean,
    val isPackage: Boolean, val symbolicLink: Boolean, val readable: Boolean) {
    companion object {
        val order = Comparator<TaskDirectoryEntry> { a, b -> TaskDirectoryPaths.compareUtf8(a.name, b.name)
            .takeUnless { it == 0 } ?: TaskDirectoryPaths.compareUtf8(a.path, b.path) }
        fun read(raw: JSONObject): TaskDirectoryEntry {
            val name = raw.strictString("name"); val path = raw.strictString("path")
            require(name.isNotEmpty() && name !in setOf(".", "..") && '/' !in name && '\u0000' !in name && name.toByteArray().size <= 1024)
            require(TaskDirectoryPaths.absolute(path))
            return TaskDirectoryEntry(name, path, raw.strictBoolean("is_hidden"), raw.strictBoolean("is_package"),
                raw.strictBoolean("is_symbolic_link"), raw.strictBoolean("is_readable"))
        }
    }
}

internal data class TaskDirectoryPage(val path: String, val parent: String?, val entries: List<TaskDirectoryEntry>,
    val offset: Long, val limit: Int, val total: Long, val next: Long?) {
    companion object {
        fun read(raw: JSONObject): TaskDirectoryPage {
            val path = raw.strictString("current_path"); val parent = raw.optionalString("parent_path")
            val offset = raw.strictLong("offset"); val limit = raw.strictLong("limit")
            val total = raw.strictLong("total_count"); val next = raw.optionalLong("next_offset")
            require(TaskDirectoryPaths.absolute(path) && (parent == null || TaskDirectoryPaths.absolute(parent)) && (path == "/") == (parent == null))
            require(offset >= 0 && total >= offset && limit in 1..100)
            val rows = raw.getJSONArray("entries")
            require(rows.length() <= 100 && rows.length().toLong() == minOf(limit, total - offset))
            val entries = (0 until rows.length()).map { TaskDirectoryEntry.read(rows.getJSONObject(it)) }
            require(next == (offset + entries.size).takeIf { it < total })
            require(entries.map { it.path }.distinct().size == entries.size)
            require(entries.zipWithNext().all { (a, b) -> TaskDirectoryEntry.order.compare(a, b) < 0 })
            return TaskDirectoryPage(path, parent, entries, offset, limit.toInt(), total, next)
        }
    }
}

internal data class TaskDirectorySearch(val paths: List<String>, val scope: String, val complete: Boolean,
    val truncated: Boolean, val indexedCount: Long) {
    val status: String? get() = when {
        truncated -> "More indexed folders match. Refine your search to see them."
        scope != "all_indexed_volumes" -> "This Mac returned limited search results. Browse to reach every accessible folder."
        !complete -> "The Mac search index did not finish in time. Refine your search or retry."
        else -> null
    }
    companion object {
        fun read(raw: JSONObject): TaskDirectorySearch {
            val scope = raw.optionalString("search_scope") ?: "legacy_bounded"
            require(scope in setOf("all_indexed_volumes", "contextual_candidates_only", "legacy_bounded"))
            val complete = raw.optionalBoolean("gathering_complete") ?: false
            require(raw.optionalBoolean("filesystem_complete") != true)
            val count = raw.optionalLong("indexed_match_count") ?: 0L; require(count >= 0)
            val rows = raw.getJSONArray("directories")
            val valid = (0 until rows.length()).map { rows.get(it).also { value -> require(value is String) } as String }
                .filter { it.isNotBlank() && it.toByteArray().size <= 4096 }
            return TaskDirectorySearch(valid.take(64), scope, complete, (raw.optionalBoolean("truncated") ?: false) || valid.size > 64, count)
        }
    }
}

/** Value-state port of the iOS browser; a page may settle only its exact request. */
internal data class TaskDirectoryBrowse(val snapshot: TaskDirectoryPage? = null, val pending: Request? = null,
    val failed: Request? = null, val error: String? = null, val generation: Long = 0) {
    data class Request(val path: String, val offset: Long, val expectedPath: String?, val append: Boolean, val generation: Long)
    fun navigate(raw: String): TaskDirectoryBrowse {
        val path = raw.trim(); if (path.isEmpty()) return this
        val expected = TaskDirectoryPaths.normalizedAbsolute(path)
        if (pending?.let { !it.append && it.path == path } == true ||
            (pending == null && error == null && expected != null && snapshot?.path == expected)) return this
        return copy(pending = Request(path, 0, expected, false, generation + 1), failed = null, error = null, generation = generation + 1)
    }
    fun next(): TaskDirectoryBrowse {
        val page = snapshot ?: return this
        if (pending != null || error != null || page.next == null) return this
        return copy(pending = Request(page.path, page.next, page.path, true, generation + 1), generation = generation + 1)
    }
    fun retry(): TaskDirectoryBrowse = failed?.let { copy(pending = it.copy(generation = generation + 1),
        failed = null, error = null, generation = generation + 1) } ?: this
    fun fail(request: Request, message: String) = if (pending == request) copy(pending = null, failed = request, error = message) else this
    fun receive(request: Request, page: TaskDirectoryPage): TaskDirectoryBrowse {
        if (pending != request) return this
        val prior = snapshot
        val valid = page.offset == request.offset && if (!request.append) request.offset == 0L && (request.expectedPath == null || request.expectedPath == page.path)
        else prior != null && request.expectedPath == prior.path && request.path == prior.path && page.path == prior.path &&
            page.parent == prior.parent && prior.next == request.offset && prior.entries.size.toLong() == request.offset && page.total == prior.total &&
            prior.entries.none { previous -> page.entries.any { it.path == previous.path } } &&
            (prior.entries.isEmpty() || page.entries.isEmpty() || TaskDirectoryEntry.order.compare(prior.entries.last(), page.entries.first()) < 0)
        if (!valid) return fail(request, "The folder changed while loading. Return to its parent and open it again.")
        return copy(snapshot = if (request.append) page.copy(offset = 0, entries = prior!!.entries + page.entries) else page, pending = null, failed = null, error = null)
    }
}

internal fun taskDirectoryFailure(failure: Exception, search: Boolean): String {
    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
    val code = (failure as? MobileRpcException)?.code?.lowercase()
    return when {
        code in setOf("method_not_found", "unknown_method", "unsupported_method") -> if (search)
            "Update cmux on this Mac to search its folders. You can still choose a recent location." else "Install the latest cmux on the Mac to browse every accessible folder."
        failure is TimeoutCancellationException || code == "request_timeout" -> if (search) "This Mac took too long to search. Try again." else "This folder took too long to load. Check the Mac or network volume, then retry."
        code in setOf("unauthorized", "account_mismatch", "forbidden") -> "Sign in again on this device and Mac, then retry."
        code in setOf("directory_not_found", "not_a_directory") -> "This folder moved or no longer exists. Choose another location."
        code == "permission_denied" -> "cmux does not have permission to read this folder on the Mac. Allow access in Mac System Settings › Privacy & Security › Files & Folders, then retry."
        code == "directory_unreadable" -> "The Mac can see this folder but cannot read its contents."
        code == "invalid_params" || failure is TaskDirectoryPathError -> "Choose another location and try again."
        failure is java.io.IOException -> "Reconnect to this Mac, then try again."
        else -> if (search) "The folder search failed. Try again." else "The Mac could not list this folder. Check cmux’s folder access on the Mac, then retry."
    }
}

private fun JSONObject.strictString(key: String) = (get(key) as? String) ?: error("Invalid $key")
private fun JSONObject.optionalString(key: String) = if (!has(key) || isNull(key)) null else strictString(key)
private fun JSONObject.strictBoolean(key: String) = (get(key) as? Boolean) ?: error("Invalid $key")
private fun JSONObject.optionalBoolean(key: String) = if (!has(key) || isNull(key)) null else strictBoolean(key)
private fun JSONObject.strictLong(key: String): Long = when (val value = get(key)) { is Int -> value.toLong(); is Long -> value; else -> error("Invalid $key") }
private fun JSONObject.optionalLong(key: String) = if (!has(key) || isNull(key)) null else strictLong(key)
