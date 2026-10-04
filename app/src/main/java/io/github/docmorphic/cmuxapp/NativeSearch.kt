package io.github.docmorphic.cmuxapp

import java.text.Normalizer
import java.util.Locale

enum class NativeSearchScope { WORKSPACES, NOTIFICATIONS }

/** The iOS coordinator's separate committed queries and generation-bound editing session. */
data class NativeSearchState(
    val workspaceQuery: String = "", val notificationQuery: String = "",
    val active: NativeSearchScope? = null, val draft: String = "", val generation: Long = 0
) {
    fun text(scope: NativeSearchScope): String = if (active == scope) draft else committed(scope)
    private fun committed(scope: NativeSearchScope) = when (scope) {
        NativeSearchScope.WORKSPACES -> workspaceQuery
        NativeSearchScope.NOTIFICATIONS -> notificationQuery
    }
    fun begin(scope: NativeSearchScope): NativeSearchState = if (active == scope) this else commit().let {
        it.copy(active = scope, draft = it.committed(scope), generation = generation + 1)
    }
    fun edit(value: String, scope: NativeSearchScope, activation: Long): NativeSearchState =
        if (active == scope && activation == generation) copy(draft = NativeSearchText.boundQuery(value)) else this
    fun commit(): NativeSearchState = active?.let {
        withQuery(it, draft.trim()).copy(active = null, draft = "")
    } ?: this
    fun clear(scope: NativeSearchScope): NativeSearchState = withQuery(scope, "").let {
        if (active == scope) it.copy(active = null, draft = "") else it
    }
    private fun withQuery(scope: NativeSearchScope, value: String) = when (scope) {
        NativeSearchScope.WORKSPACES -> copy(workspaceQuery = value)
        NativeSearchScope.NOTIFICATIONS -> copy(notificationQuery = value)
    }
}

object NativeSearchText {
    private val marks = Regex("\\p{M}+")
    /** 128 Unicode scalars / 512 UTF-8 bytes, matching MobileSearchQueryBounds. */
    fun boundQuery(value: String): String = prefix(value, 128)
    fun prefix(value: String, scalarLimit: Int): String = buildString {
        var index = 0; var count = 0
        while (index < value.length && count < scalarLimit) {
            val scalar = value.codePointAt(index)
            appendCodePoint(if (scalar in 0xd800..0xdfff) 0xfffd else scalar)
            index += Character.charCount(scalar); count++
        }
    }
    fun fold(value: String, locale: Locale, notification: Boolean): String {
        val normalized = Normalizer.normalize(value, if (notification) Normalizer.Form.NFKD else Normalizer.Form.NFC)
        return (if (notification) normalized.replace(marks, "") else normalized).lowercase(locale)
    }
}

/** Index field boundaries separately so queries never match across unrelated metadata. */
class NativeSearchIndex(rows: List<Pair<String, List<String?>>>, private val locale: Locale,
                        private val notification: Boolean = false) {
    private val fields = rows.associate { (id, values) ->
        id to values.filterNotNull().map { NativeSearchText.fold(it, locale, notification) }
    }
    fun matches(query: String): Set<String> {
        val needle = NativeSearchText.fold(NativeSearchText.boundQuery(query).trim(), locale, notification)
        return fields.filterValues { needle.isEmpty() || it.any { field -> needle in field } }.keys
    }
}

/** Shared main/browser search fields, kept separate to prevent cross-field matches. */
internal fun workspaceSearchRows(sources: List<NativeFeedSource>, name: (NativeCredentialStore.PairedMac) -> String): List<Pair<String, List<String?>>> =
    sources.flatMap { source ->
        val groups = source.groups.associate { it.id to it.name }
        source.workspaces.map { workspace -> workspaceSearchId(source, workspace) to
            (listOf(workspace.title, workspace.description, workspace.directory, workspace.preview,
                source.mac.name, name(source.mac), groups[workspace.groupId]) + workspace.terminals.map { it.title }) }
    }
