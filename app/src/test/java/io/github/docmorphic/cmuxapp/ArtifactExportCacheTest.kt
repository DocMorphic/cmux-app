package io.github.docmorphic.cmuxapp

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ArtifactExportCacheTest {
    @Test fun overlappingLeasesProtectOldActiveExportsAndCloseIsIdempotent() {
        val root = Files.createTempDirectory("export-cache").toFile()
        val active = root.resolve("active").also { it.mkdirs(); it.resolve("file").writeText("active"); it.setLastModified(1) }
        val abandoned = root.resolve("old").also { it.mkdirs(); it.resolve("file").writeText("old"); it.setLastModified(1) }
        val recent = root.resolve("recent").also { it.mkdirs(); it.setLastModified(9_999_999) }
        val first = ArtifactExportCache.hold(active); val second = ArtifactExportCache.hold(active)
        try {
            first.close(); first.close(); ArtifactExportCache.prune(root, 10_000_000)
            assertTrue(active.exists()); assertFalse(abandoned.exists()); assertTrue(recent.exists())
            second.close(); ArtifactExportCache.prune(root, 10_000_000)
            assertFalse(active.exists()); assertTrue(recent.exists())
        } finally { first.close(); second.close(); root.deleteRecursively() }
    }
}
