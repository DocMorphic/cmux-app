package io.github.docmorphic.cmuxapp

/** UTF-16 offsets match Android Layout/Selection and the iOS artifact line index. */
internal class ArtifactTextDocument(val text: String) {
    val starts: IntArray = buildList { add(0); text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }.toIntArray()
    val lineCount get() = starts.size
    fun offset(line: Int) = starts[line.coerceIn(1, lineCount) - 1]
    fun line(offset: Int): Int {
        val found = starts.binarySearch(offset.coerceIn(0, text.length))
        return if (found >= 0) found + 1 else -found - 1
    }
    fun search(query: String, checkActive: () -> Unit = {}): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val results = ArrayList<IntRange>()
        var offset = 0
        while (offset < text.length) {
            checkActive()
            val next = text.indexOf(query, offset, ignoreCase = true)
            if (next < 0) break
            results.add(next until next + query.length)
            offset = next + query.length
        }
        return results
    }
}

internal enum class ArtifactTextKind(val defaultWrap: Boolean) {
    CODE(true), LOG(false), PLAIN(true);
    companion object {
        fun forPath(path: String): ArtifactTextKind = when {
            path.substringAfterLast('.', "").lowercase() in setOf("log", "out") -> LOG
            ArtifactSyntaxPolicy.language(path) != null -> CODE
            else -> PLAIN
        }
        fun fontSize(value: Float) = if (value.isFinite()) value.coerceIn(8f, 28f) else 15f
    }
}
