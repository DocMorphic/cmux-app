package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ArtifactPagingResult(
    val snapshot: ArtifactGallerySnapshot, val reachedSafetyCap: Boolean = false, val requiresPagingRestart: Boolean = false,
)

/** cmux's eager paging policy for complete gallery sorting/filtering; cap only Referenced rows. */
internal suspend fun loadRemainingArtifacts(
    initial: ArtifactGallerySnapshot,
    maximumReferencedRows: Int = 2_000,
    fetchPage: suspend (String) -> ArtifactGalleryPage,
): ArtifactPagingResult {
    val maximum = maximumReferencedRows.coerceAtLeast(0)
    var snapshot = initial.limitReferenced(maximum)
    val seenCursors = mutableSetOf<String>()
    var truncated = initial.referenced.size > snapshot.referenced.size
    while (snapshot.nextCursor != null && snapshot.referenced.size < maximum) {
        val cursor = checkNotNull(snapshot.nextCursor)
        if (!seenCursors.add(cursor)) break
        currentCoroutineContext().ensureActive()
        val page = fetchPage(cursor)
        currentCoroutineContext().ensureActive()
        if (page.requiresPagingRestart) return ArtifactPagingResult(initial, requiresPagingRestart = true)
        val appended = snapshot.append(page)
        truncated = truncated || appended.referenced.size > maximum
        snapshot = appended.limitReferenced(maximum)
    }
    return ArtifactPagingResult(snapshot, truncated || (snapshot.referenced.size >= maximum && snapshot.nextCursor != null))
}
