package io.github.docmorphic.cmuxapp

/** The common subsequence keeps its relative order; all other survivors moved. O(n log n). */
internal fun workspaceStableViewportKeys(previous: List<String>, target: List<String>): Set<String> {
    if (previous == target) return previous.toSet()
    val oldIndices = previous.withIndex().associate { it.value to it.index }
    val surviving = target.mapNotNull { key -> oldIndices[key]?.let { key to it } }
    if (surviving.isEmpty()) return emptySet()
    val tails = IntArray(surviving.size)
    val predecessor = IntArray(surviving.size) { -1 }
    var length = 0
    surviving.forEachIndexed { index, (_, oldIndex) ->
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high) ushr 1
            if (surviving[tails[middle]].second < oldIndex) low = middle + 1 else high = middle
        }
        if (low > 0) predecessor[index] = tails[low - 1]
        tails[low] = index
        if (low == length) length++
    }
    val stable = HashSet<String>(length)
    var index = tails[length - 1]
    while (index >= 0) {
        stable += surviving[index].first
        index = predecessor[index]
    }
    return stable
}

internal data class WorkspaceViewportItem(val key: String, val offset: Int, val size: Int)
internal data class WorkspaceViewportPosition(val index: Int, val scrollOffset: Int)

/** Offsets and visibility are physical pixels, including the one-pixel visibility threshold. */
internal fun workspaceViewportPosition(previous: List<String>, target: List<String>,
    visible: List<WorkspaceViewportItem>, viewportStart: Int, viewportEnd: Int,
    atTop: Boolean): WorkspaceViewportPosition? {
    if (target.isEmpty()) return null
    // At absolute top, show newly inserted leading rows instead of anchoring past them.
    if (atTop) return WorkspaceViewportPosition(0, 0)
    val stable = workspaceStableViewportKeys(previous, target)
    val anchor = visible.sortedBy { it.offset }.firstOrNull {
        it.key in stable && it.size > 0 &&
            minOf(it.offset.toLong() + it.size, viewportEnd.toLong()) - maxOf(it.offset, viewportStart).toLong() >= 1
    } ?: return null
    return WorkspaceViewportPosition(target.indexOf(anchor.key), -anchor.offset)
}
