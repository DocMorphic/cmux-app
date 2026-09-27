package io.github.docmorphic.cmuxapp

import org.json.JSONObject

internal enum class ChangeKind(val wire: String, val badge: String) {
    ADDED("added", "A"), MODIFIED("modified", "M"), DELETED("deleted", "D"), RENAMED("renamed", "R"), UNTRACKED("untracked", "U"), UNKNOWN("unknown", "?");
    companion object { fun read(value: String) = entries.firstOrNull { it.wire == value } ?: UNKNOWN }
}
internal data class ChangedFile(val path: String, val oldPath: String?, val kind: ChangeKind,
    val additions: Int, val deletions: Int, val binary: Boolean, val approximate: Boolean) {
    val filename get() = path.substringAfterLast('/')
}
internal data class ChangesSnapshot(val root: String, val branch: String?, val base: String?, val files: List<ChangedFile>,
    val fileCount: Int, val additions: Int, val deletions: Int, val truncated: Boolean) {
    companion object {
        fun read(value: JSONObject, workspace: String): ChangesSnapshot {
            val returned = value.opt("workspace_id") as? String
            require(returned.isNullOrEmpty() || returned == workspace) { "Changes belong to another workspace" }
            val array = value.optJSONArray("files")
            val files = (0 until (array?.length() ?: 0)).mapNotNull { i ->
                val item = array?.optJSONObject(i) ?: return@mapNotNull null
                val path = (item.opt("path") as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ChangedFile(path, item.opt("old_path") as? String, ChangeKind.read(item.optString("status")),
                    item.optInt("additions").coerceAtLeast(0), item.optInt("deletions").coerceAtLeast(0),
                    item.optBoolean("is_binary"), item.optBoolean("is_approximate"))
            }.distinctBy { it.path }.sortedBy { it.path }
            return ChangesSnapshot(value.opt("repo_root") as? String ?: "", value.opt("branch") as? String,
                value.opt("base_ref") as? String, files, value.optInt("files_changed", files.size).coerceAtLeast(0),
                value.optInt("additions").coerceAtLeast(0), value.optInt("deletions").coerceAtLeast(0), value.optBoolean("truncated"))
        }
    }
}
internal sealed interface ChangesTreeRow {
    val id: String
    val depth: Int
    data class Directory(val path: String, val name: String, override val depth: Int, val count: Int) : ChangesTreeRow { override val id = "directory:$path" }
    data class File(val file: ChangedFile, override val depth: Int) : ChangesTreeRow { override val id = "file:${file.path}" }
}

/** Directory-first, folded single-directory chains, with stable full-path identities. */
internal class ChangedFilesTree(files: List<ChangedFile>) {
    private class Branch {
        val branches = mutableMapOf<String, Branch>()
        val files = mutableListOf<ChangedFile>()
        fun count(): Int = files.size + branches.values.sumOf { it.count() }
    }
    private val root = Branch()
    private val names = Comparator<String> { a, b -> a.compareTo(b, ignoreCase = true).takeIf { it != 0 } ?: a.compareTo(b) }
    init {
        files.forEach { file ->
            val components = file.path.split('/').filter { it.isNotEmpty() }
            var branch = root
            components.dropLast(1).forEach { name -> branch = branch.branches.getOrPut(name) { Branch() } }
            branch.files += file
        }
    }
    fun rows(collapsed: Set<String>): List<ChangesTreeRow> = buildList {
        fun visit(branch: Branch, parent: String, depth: Int) {
            branch.branches.keys.sortedWith(names).forEach { first ->
                var child = branch.branches.getValue(first)
                var name = first
                var path = if (parent.isEmpty()) first else "$parent/$first"
                while (child.files.isEmpty() && child.branches.size == 1) {
                    val next = child.branches.entries.single()
                    name += "/${next.key}"; path += "/${next.key}"; child = next.value
                }
                add(ChangesTreeRow.Directory(path, name, depth, child.count()))
                if (path !in collapsed) visit(child, path, depth + 1)
            }
            branch.files.sortedWith { a, b -> names.compare(a.filename, b.filename) }.forEach { add(ChangesTreeRow.File(it, depth)) }
        }
        visit(root, "", 0)
    }
}

internal enum class DiffKind { HEADER, CONTEXT, ADDITION, REMOVAL, NO_NEWLINE }
internal data class ChangesDiffLine(val kind: DiffKind, val text: String, val oldNumber: Int? = null,
    val newNumber: Int? = null, val emphasis: IntRange? = null)
internal data class ChangesDiffHunk(val header: String, val oldStart: Int, val oldCount: Int,
    val newStart: Int, val newCount: Int, val lines: List<ChangesDiffLine>) {
    val copyText = (listOf(header) + lines.mapNotNull { when (it.kind) {
        DiffKind.NO_NEWLINE -> null
        DiffKind.ADDITION -> "+${it.text}"
        DiffKind.REMOVAL -> "-${it.text}"
        DiffKind.CONTEXT -> " ${it.text}"
        DiffKind.HEADER -> it.text
    } }).joinToString("\n")
}
internal data class ChangesDiffDocument(val hunks: List<ChangesDiffHunk>, val rawLineCount: Int,
    val truncated: Boolean, val binary: Boolean, val totalLines: Int?, val fingerprint: String?) {
    val maximumLineNumber = hunks.flatMap { it.lines }.maxOfOrNull { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) } ?: 1
    companion object {
        private val header = Regex("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@[^\\n]*$")
        fun read(value: JSONObject, path: String): ChangesDiffDocument {
            val returned = value.opt("path") as? String
            require(returned.isNullOrEmpty() || returned == path) { "Diff belongs to another file" }
            val raw = value.opt("unified_diff") as? String ?: ""
            val split = if (raw.isEmpty()) emptyList() else raw.split('\n')
            val hunks = mutableListOf<ChangesDiffHunk>()
            var coordinates: List<Int>? = null
            var heading = ""
            var lines = mutableListOf<ChangesDiffLine>()
            var old = 0; var new = 0
            fun flush() {
                val at = coordinates ?: return
                hunks += ChangesDiffHunk(heading, at[0], at[1], at[2], at[3], emphasize(lines))
            }
            if (!value.optBoolean("is_binary")) for (line in split) {
                val match = header.matchEntire(line)
                val at = match?.let { m -> listOf(m.groupValues[1].toIntOrNull(),
                    m.groupValues[2].ifEmpty { "1" }.toIntOrNull(), m.groupValues[3].toIntOrNull(),
                    m.groupValues[4].ifEmpty { "1" }.toIntOrNull()).takeIf { values -> values.all { it != null } }?.map { it!! } }
                if (at != null) { flush(); coordinates = at; heading = line; lines = mutableListOf(); old = at[0]; new = at[2]; continue }
                if (coordinates == null) continue
                if (line == "\\ No newline at end of file" || line == "\\ No newline at end of file\r") {
                    lines += ChangesDiffLine(DiffKind.NO_NEWLINE, ""); continue
                }
                when (line.firstOrNull()) {
                    ' ' -> lines += ChangesDiffLine(DiffKind.CONTEXT, line.drop(1), old++, new++)
                    '+' -> lines += ChangesDiffLine(DiffKind.ADDITION, line.drop(1), newNumber = new++)
                    '-' -> lines += ChangesDiffLine(DiffKind.REMOVAL, line.drop(1), oldNumber = old++)
                }
            }
            flush()
            return ChangesDiffDocument(hunks, split.size, value.optBoolean("truncated"), value.optBoolean("is_binary"),
                (value.opt("diff_total_lines") as? Number)?.toInt()?.coerceAtLeast(0), value.opt("content_fingerprint") as? String)
        }
        /** Preserve extended grapheme clusters when emphasizing a small replacement. */
        private fun emphasize(input: List<ChangesDiffLine>): List<ChangesDiffLine> {
            val result = input.toMutableList()
            val indices = input.indices.filter { input[it].kind != DiffKind.NO_NEWLINE }
            var cursor = 0
            while (cursor < indices.size) {
                if (input[indices[cursor]].kind != DiffKind.REMOVAL) { cursor++; continue }
                val removed = cursor
                while (cursor < indices.size && input[indices[cursor]].kind == DiffKind.REMOVAL) cursor++
                val added = cursor
                while (cursor < indices.size && input[indices[cursor]].kind == DiffKind.ADDITION) cursor++
                repeat(minOf(added - removed, cursor - added)) { offset ->
                    val oldIndex = indices[removed + offset]; val newIndex = indices[added + offset]
                    val old = input[oldIndex].text; val new = input[newIndex].text
                    if (old.toByteArray().size > 4096 || new.toByteArray().size > 4096) return@repeat
                    val a = taskGraphemes(old); val b = taskGraphemes(new)
                    val shorter = minOf(a.size, b.size)
                    var prefix = 0; var suffix = 0
                    while (prefix < shorter && a[prefix] == b[prefix]) prefix++
                    while (suffix < shorter - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
                    val changed = maxOf(a.size - prefix - suffix, b.size - prefix - suffix)
                    val longest = maxOf(a.size, b.size)
                    if (longest == 0 || changed.toDouble() / longest > 0.7) return@repeat
                    fun span(parts: List<String>): IntRange? {
                        val start = parts.take(prefix).sumOf { it.length }
                        val end = parts.dropLast(suffix).sumOf { it.length }
                        return if (end > start) start until end else null
                    }
                    result[oldIndex] = input[oldIndex].copy(emphasis = span(a))
                    result[newIndex] = input[newIndex].copy(emphasis = span(b))
                }
            }
            return result
        }
    }
}
internal data class DiffContinuation(val budget: Int, val document: ChangesDiffDocument, val ceiling: Boolean = false) {
    val nextBudget get() = (budget.toLong() * 4).coerceAtMost(MAX_BUDGET.toLong()).toInt()
    val canGrow get() = document.truncated && !ceiling && nextBudget > budget
    val shownLines get() = document.totalLines?.let { minOf(document.rawLineCount, it) } ?: document.rawLineCount
    companion object { const val DEFAULT_BUDGET = 6_000; const val MAX_BUDGET = 96_000 }
}
