package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ArtifactPlaybackFilesTest {
    private fun fixture(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("media-playback").toFile()
        try {
            val source = File(root, "source.mp4").apply { writeBytes(ByteArray(8192) { (it % 251).toByte() }) }
            block(root, source)
        } finally { root.deleteRecursively() }
    }
    @Test fun independentNameSurvivesSourceDeletionAndClosesWithoutOtherFiles() = fixture { root, source ->
        val bytes = source.readBytes()
        val playback = ArtifactPlaybackFiles(File(root, "sessions"), source)
        source.delete()
        assertArrayEquals(bytes, playback.file.readBytes())
        val unrelated = File(root, "other").apply { writeText("keep") }
        playback.close(); playback.close()
        assertFalse(playback.directory.exists()); assertTrue(unrelated.exists())
    }
    @Test fun unsupportedHardLinkFallsBackToExactOwnedCopy() = fixture { root, source ->
        val playback = ArtifactPlaybackFiles(File(root, "sessions"), source, link = { _, _ -> throw IOException("unsupported") })
        assertArrayEquals(source.readBytes(), playback.file.readBytes())
        playback.close(); assertTrue(source.exists())
    }
    @Test fun failedOrCancelledCopyDoesNotLeaveAnOwnedDirectory() = fixture { root, source ->
        val sessions = File(root, "sessions")
        var checks = 0
        try {
            ArtifactPlaybackFiles(sessions, source, link = { _, _ -> throw IOException("unsupported") },
                checkActive = { if (++checks > 1) throw CancellationException("retired") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(sessions.listFiles().orEmpty().none { it.isDirectory })
        assertTrue(source.exists())
    }
    @Test fun rejectsSymbolicLinksDirectoriesAndEmptyMedia() = fixture { root, source ->
        val symlink = File(root, "link.mp4")
        Files.createSymbolicLink(symlink.toPath(), source.toPath())
        val empty = File(root, "empty.mp4").apply { writeBytes(byteArrayOf()) }
        for (invalid in listOf(symlink, root, empty)) {
            try { ArtifactPlaybackFiles(File(root, "sessions"), invalid); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(File(root, "sessions").exists())
    }
    @Test fun pruningCannotDeletePlaybackHeldByAnActivity() = fixture { root, source ->
        val sessions = File(root, "sessions")
        val playback = ArtifactPlaybackFiles(sessions, source)
        val now = System.currentTimeMillis()
        playback.directory.setLastModified(now - 7_200_000)
        ArtifactExportCache.prune(sessions, now)
        assertTrue(playback.file.isFile)
        playback.close()
        assertFalse(playback.directory.exists())
    }
}
