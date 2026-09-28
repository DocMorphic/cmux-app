package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class ArtifactGalleryPresentationTest {
    private fun item(path: String, size: Long? = null) = ArtifactItem("/$path", size = size)
    @Test fun imageAndFolderKindsOverrideFilenameBucketsAndMissingFilesDefaultHidden() {
        val image = item("photo.log").copy(kind = ArtifactKind.IMAGE)
        val folder = item("folder.py").copy(kind = ArtifactKind.DIRECTORY)
        assertEquals(ArtifactFilter.IMAGES, artifactFilter(image)); assertEquals(ArtifactFilter.FOLDERS, artifactFilter(folder))
        assertEquals(ArtifactFilter.CODE, artifactFilter(item("module.PY")))
        assertEquals(ArtifactFilter.LOGS, artifactFilter(item("build.out")))
        assertEquals(ArtifactFilter.DOCS, artifactFilter(item("notes.md")))
        assertNull(artifactFilter(item("archive.unknown")))
        assertEquals(listOf(image), projectArtifacts(listOf(image, image.copy(path = "/gone", exists = false)), ArtifactFilter.ALL, ArtifactSort.RECENT, false))
    }
    @Test fun stableSortPreservesEqualNamesAndSizesAndPlacesUnknownSizeLast() {
        val rows = listOf(item("z", 3), item("Name", 6), item("name", 6), item("a", null))
        assertEquals(listOf("/a", "/Name", "/name", "/z"), projectArtifacts(rows, ArtifactFilter.ALL, ArtifactSort.NAME, true).map { it.path })
        assertEquals(listOf("/Name", "/name", "/z", "/a"), projectArtifacts(rows, ArtifactFilter.ALL, ArtifactSort.SIZE, true).map { it.path })
        assertEquals(rows, projectArtifacts(rows, ArtifactFilter.ALL, ArtifactSort.RECENT, true))
    }
    @Test fun searchIsFlatWhileNormalGalleryKeepsFixedProvenanceSections() {
        val snapshot = ArtifactGallerySnapshot(created = listOf(item("a.txt")), attached = listOf(item("b.txt")), referenced = listOf(item("c.txt")))
        val grouped = artifactGroups(snapshot, ArtifactFilter.ALL, ArtifactSort.RECENT, false, false)
        assertEquals(listOf("Created", "Attached", "Referenced"), grouped.map { it.title })
        assertEquals(listOf("/c.txt"), artifactGroups(snapshot, ArtifactFilter.ALL, ArtifactSort.RECENT, false, true).single().items.map { it.path })
    }
    @Test fun previewSwipeOrderExcludesDirectoriesAndDeduplicatesAcrossGroups() {
        val rows = listOf(item("a"), item("folder").copy(kind = ArtifactKind.DIRECTORY), item("b"), item("a"))
        assertEquals(listOf("/a", "/b"), artifactSwipeOrder(rows).map { it.path })
    }
}
