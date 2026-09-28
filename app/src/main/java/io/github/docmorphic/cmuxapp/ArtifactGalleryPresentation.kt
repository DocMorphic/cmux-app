package io.github.docmorphic.cmuxapp

import java.text.Collator
import java.util.Locale

internal enum class ArtifactFilter(val title: String) { ALL("All"), IMAGES("Images"), CODE("Code"), LOGS("Logs"), DOCS("Docs"), FOLDERS("Folders") }
internal enum class ArtifactSort(val title: String) { RECENT("Recent"), NAME("Name"), SIZE("Size") }
internal data class ArtifactGroup(val title: String, val items: List<ArtifactItem>)
private val artifactLogExtensions = setOf("log", "out")
private val artifactDocExtensions = setOf("doc", "docx", "key", "md", "markdown", "mdown", "mkd", "numbers", "odp", "ods", "odt", "pages", "pdf", "ppt", "pptx", "rtf", "txt", "xls", "xlsx")
// Common extensions checked against Apple's UTType.sourceCode. Unknown types remain under All.
private val artifactCodeExtensions = setOf("c", "h", "m", "mm", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "java", "swift", "js", "sh", "bash", "zsh", "py", "rb", "php", "pl", "pm", "r", "f", "f90", "pas", "s")
internal fun artifactFilter(item: ArtifactItem): ArtifactFilter? {
    if (item.kind == ArtifactKind.IMAGE) return ArtifactFilter.IMAGES
    if (item.kind == ArtifactKind.DIRECTORY) return ArtifactFilter.FOLDERS
    return when (item.path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        in artifactCodeExtensions -> ArtifactFilter.CODE
        in artifactLogExtensions -> ArtifactFilter.LOGS
        in artifactDocExtensions -> ArtifactFilter.DOCS
        else -> null
    }
}
internal fun projectArtifacts(items: List<ArtifactItem>, filter: ArtifactFilter, sort: ArtifactSort, showMissing: Boolean): List<ArtifactItem> {
    val visible = items.filter { (showMissing || it.exists) && (filter == ArtifactFilter.ALL || artifactFilter(it) == filter) }
    return when (sort) {
        ArtifactSort.RECENT -> visible
        ArtifactSort.NAME -> {
            val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
            visible.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
        }
        ArtifactSort.SIZE -> visible.sortedWith(compareByDescending<ArtifactItem> { it.size != null }.thenByDescending { it.size })
    }
}
internal fun artifactGroups(snapshot: ArtifactGallerySnapshot, filter: ArtifactFilter, sort: ArtifactSort, showMissing: Boolean, searching: Boolean): List<ArtifactGroup> =
    if (searching) listOf(ArtifactGroup("", projectArtifacts(snapshot.referenced, filter, sort, showMissing)))
    else listOf(ArtifactGroup("Created", snapshot.created), ArtifactGroup("Attached", snapshot.attached), ArtifactGroup("Referenced", snapshot.referenced))
        .map { it.copy(items = projectArtifacts(it.items, filter, sort, showMissing)) }

internal fun artifactSwipeOrder(items: List<ArtifactItem>): List<ArtifactItem> = items.filter { it.kind != ArtifactKind.DIRECTORY }.distinctBy { it.path }
internal sealed interface ArtifactDestination {
    val authorization: ArtifactAuthorization
    data class Folder(val item: ArtifactItem, override val authorization: ArtifactAuthorization) : ArtifactDestination
    data class Preview(val files: List<ArtifactItem>, val initialPath: String, override val authorization: ArtifactAuthorization) : ArtifactDestination
}
