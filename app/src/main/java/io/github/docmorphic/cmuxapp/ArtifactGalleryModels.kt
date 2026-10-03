package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// Adapted from cmux ChatArtifactGallery* at 4c5272e9; Manaflow, GPL-3.0-or-later.
internal enum class ArtifactKind { IMAGE, TEXT, BINARY, DIRECTORY;
    companion object {
        fun read(value: Any?): ArtifactKind = entries.firstOrNull { it.name.equals(value as? String, true) } ?: BINARY
    }
}
internal enum class ArtifactProvenance { CREATED, ATTACHED, REFERENCED }
internal data class ArtifactItem(
    val path: String,
    val kind: ArtifactKind = ArtifactKind.BINARY,
    val displayName: String = path.substringAfterLast('/'),
    val size: Long? = null,
    val modifiedAt: Double? = null,
    val exists: Boolean = true,
    val childCount: Int? = null,
    val childCountIsCapped: Boolean = false,
    val provenance: ArtifactProvenance = ArtifactProvenance.REFERENCED,
) {
    companion object {
        fun read(value: JSONObject): ArtifactItem? {
            val path = value.opt("path") as? String ?: return null
            if (!validArtifactPath(path)) return null
            val modified = when (val raw = value.opt("modified_at")) {
                is Number -> raw.toDouble().takeIf { it.isFinite() }
                is String -> runCatching { Instant.parse(raw).let { it.epochSecond + it.nano / 1e9 } }.getOrNull()
                else -> null
            }
            return ArtifactItem(path, ArtifactKind.read(value.opt("kind")),
                value.opt("display_name") as? String ?: path.substringAfterLast('/'),
                value.nonnegativeLong("size"), modified, value.opt("exists") as? Boolean ?: true,
                value.nonnegativeInt("child_count"), value.optBoolean("child_count_is_capped"),
                ArtifactProvenance.entries.firstOrNull { it.name.equals(value.opt("provenance") as? String, true) }
                    ?: ArtifactProvenance.REFERENCED)
        }
    }
}

internal fun validArtifactPath(path: String): Boolean = path.startsWith('/') && '\u0000' !in path
// Terminal taps may send bare/relative names; only the Mac resolves them against its terminal cwd.
internal fun validTerminalArtifactPath(path: String): Boolean = path.isNotBlank() && '\u0000' !in path && "://" !in path
private fun JSONObject.nonnegativeLong(key: String): Long? = (opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }
private fun JSONObject.nonnegativeInt(key: String): Int? = nonnegativeLong(key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
private fun JSONArray?.artifactItems(): List<ArtifactItem> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it)?.let(ArtifactItem::read) }.distinctBy { it.path }

internal data class TerminalArtifactScan(
    val items: List<ArtifactItem>, val sessionId: String?, val sessionTotal: Int?, val galleryRowTotal: Int?,
) {
    companion object {
        fun read(value: JSONObject, includeDirectories: Boolean): TerminalArtifactScan = TerminalArtifactScan(
            value.optJSONArray("artifacts").artifactItems().filter { includeDirectories || it.kind != ArtifactKind.DIRECTORY },
            (value.opt("session_id") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            value.nonnegativeInt("session_artifact_total"), value.nonnegativeInt("gallery_row_total"))
    }
}

internal data class ArtifactGallerySnapshot(
    val created: List<ArtifactItem> = emptyList(), val createdTotal: Int = created.size,
    val attached: List<ArtifactItem> = emptyList(), val attachedTotal: Int = attached.size,
    val referenced: List<ArtifactItem> = emptyList(), val referencedTotal: Int = referenced.size,
    val nextCursor: String? = null, val generation: String = "",
) {
    val items: List<ArtifactItem> get() = created + attached + referenced
    fun append(page: ArtifactGalleryPage): ArtifactGallerySnapshot {
        if (page.requiresPagingRestart) return this
        val seen = items.mapTo(mutableSetOf()) { it.path }
        val next = page.snapshot
        val newCreated = next.created.filter { seen.add(it.path) }
        val newAttached = next.attached.filter { seen.add(it.path) }
        val newReferenced = next.referenced.filter { seen.add(it.path) }
        return next.copy(created = created + newCreated, attached = attached + newAttached, referenced = referenced + newReferenced)
    }

    /** Refresh policy: preserve the reader's position, defer insertions, or apply fresh rows first. */
    fun refresh(fresh: ArtifactGallerySnapshot, policy: ArtifactRefreshPolicy): ArtifactGallerySnapshot {
        if (policy == ArtifactRefreshPolicy.DEFER) return copy(
            createdTotal = maxOf(fresh.createdTotal, created.size), attachedTotal = maxOf(fresh.attachedTotal, attached.size),
            referencedTotal = maxOf(fresh.referencedTotal, referenced.size), nextCursor = fresh.nextCursor, generation = fresh.generation)
        val seen = mutableSetOf<String>()
        fun unique(rows: List<ArtifactItem>) = rows.filter { seen.add(it.path) }
        val first = if (policy == ArtifactRefreshPolicy.PRESERVE) this else fresh
        val second = if (policy == ArtifactRefreshPolicy.PRESERVE) fresh else this
        val firstCreated = unique(first.created); val firstAttached = unique(first.attached); val firstReferenced = unique(first.referenced)
        val mergedCreated = firstCreated + unique(second.created)
        val mergedAttached = firstAttached + unique(second.attached)
        val mergedReferenced = firstReferenced + unique(second.referenced)
        return fresh.copy(created = mergedCreated, createdTotal = maxOf(fresh.createdTotal, mergedCreated.size),
            attached = mergedAttached, attachedTotal = maxOf(fresh.attachedTotal, mergedAttached.size),
            referenced = mergedReferenced, referencedTotal = maxOf(fresh.referencedTotal, mergedReferenced.size))
    }
    fun limitReferenced(maximum: Int) = copy(referenced = referenced.take(maximum.coerceAtLeast(0)))
}
internal enum class ArtifactRefreshPolicy { PRESERVE, DEFER, APPLY }
internal data class ArtifactGalleryPage(val snapshot: ArtifactGallerySnapshot, val requiresPagingRestart: Boolean = false) {
    companion object {
        fun read(value: JSONObject, sessionId: String, includeDirectories: Boolean): ArtifactGalleryPage {
            // An omitted identity is tolerated by older iOS wire decoders; an explicit mismatch never is.
            val returnedSession = value.opt("session_id") as? String
            require(returnedSession.isNullOrEmpty() || returnedSession == sessionId) { "Files belong to another session." }
            val seen = mutableSetOf<String>()
            fun group(key: String): Pair<List<ArtifactItem>, Int> {
                val all = value.optJSONArray(key).artifactItems()
                val rows = all.filter { includeDirectories || it.kind != ArtifactKind.DIRECTORY }
                val total = ((value.nonnegativeInt("${key}_total") ?: all.size) - (all.size - rows.size)).coerceAtLeast(0)
                return rows.filter { seen.add(it.path) } to total
            }
            val (created, createdTotal) = group("created"); val (attached, attachedTotal) = group("attached")
            val (referenced, referencedTotal) = group("referenced")
            return ArtifactGalleryPage(ArtifactGallerySnapshot(created, createdTotal, attached, attachedTotal,
                referenced, referencedTotal, value.opt("next_cursor") as? String, value.opt("generation") as? String ?: ""),
                value.optBoolean("requires_paging_restart"))
        }
    }
}

internal data class ArtifactDirectoryListing(val entries: List<ArtifactItem>, val truncated: Boolean) {
    companion object {
        fun read(value: JSONObject, parent: String): ArtifactDirectoryListing {
            require(validTerminalArtifactPath(parent))
            val entries = value.optJSONArray("entries") ?: JSONArray()
            return ArtifactDirectoryListing((0 until entries.length()).mapNotNull { index ->
                val entry = entries.optJSONObject(index) ?: return@mapNotNull null
                val name = entry.opt("name") as? String ?: return@mapNotNull null
                // These are immediate POSIX children. Keep spaces, Unicode and literal backslashes.
                if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) return@mapNotNull null
                ArtifactItem(parent.trimEnd('/') + "/" + name,
                    if (entry.optBoolean("is_directory")) ArtifactKind.DIRECTORY else ArtifactKind.read(entry.opt("kind")),
                    name, entry.nonnegativeLong("size"))
            }.distinctBy { it.path }, value.optBoolean("is_truncated"))
        }
    }
}
