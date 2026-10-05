package io.github.docmorphic.cmuxapp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileSaveSnapshotTest {
    private fun snapshot(name: String = "résumé 日本語.txt") =
        FileSaveSnapshot(UUID.randomUUID().toString(), name, "text/plain", FileSavePhase.PREPARING)

    @Test fun pickerAndWriterStateRoundTripWithoutSerializingFileBytes() {
        for (phase in FileSavePhase.entries) {
            val value = snapshot().copy(phase = phase, destination = "content://documents/document/12", ownsGrant = true)
            assertEquals(value, FileSaveSnapshot.decode(value.encode()))
            assertTrue(value.encode().length < 1024)
        }
        assertEquals(snapshot().mime, "text/plain")
    }
    @Test fun corruptRestorationCannotNameFilesOutsideThePrivateSnapshot() {
        val base = snapshot().encode()
        for ((key, value) in listOf("id" to "../source", "filename" to "../source", "filename" to "dir\\source",
            "filename" to ".", "filename" to "..", "filename" to "bad\u0000name", "phase" to "UNKNOWN",
            "destination" to "file:///private/other")) {
            assertNull("$key=$value", FileSaveSnapshot.decode(JSONObject(base).put(key, value).toString()))
        }
        assertNull(FileSaveSnapshot.decode(JSONObject(base).put("phase", "WRITING").toString()))
        assertNull(FileSaveSnapshot.decode(JSONObject(base).put("owns_grant", true).toString()))
        assertNull(FileSaveSnapshot.decode("{")); assertNull(FileSaveSnapshot.decode(null))
    }
    @Test fun preparedSnapshotSurvivesPreviewDeletionAndKeepsExactBytesForThePicker() = runBlocking {
        val root = Files.createTempDirectory("save-snapshot").toFile()
        try {
            val source = root.resolve("preview.txt").also { it.writeText("hello 日本語\n") }
            val bytes = source.readBytes(); val files = FileSaveFiles(root.resolve("saves")); val value = snapshot()
            val snapshot = files.prepare(value, source, bytes.size.toLong()); source.delete()
            val output = ByteArrayOutputStream(); files.write(value) { output }
            assertArrayEquals(bytes, output.toByteArray()); assertTrue(snapshot.isFile)
            files.remove(value); assertFalse(snapshot.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun destinationFailureLeavesTheSnapshotAvailableForRetry() = runBlocking {
        val root = Files.createTempDirectory("save-retry").toFile()
        try {
            val source = root.resolve("source").also { it.writeBytes(ByteArray(200_000) { (it % 127).toByte() }) }
            val files = FileSaveFiles(root.resolve("saves")); val value = snapshot()
            val snapshot = files.prepare(value, source, source.length())
            var closed = false
            val failed = runCatching { files.write(value) { object : OutputStream() {
                override fun write(byte: Int) { throw IOException("destination unavailable") }
                override fun close() { closed = true }
            } } }
            assertTrue(failed.isFailure); assertTrue(closed); assertTrue(snapshot.isFile)
            val output = ByteArrayOutputStream(); files.write(value) { output }
            assertArrayEquals(source.readBytes(), output.toByteArray())
            files.remove(value)
        } finally { root.deleteRecursively() }
    }
    @Test fun sizeMismatchRemovesPartialSnapshotAndNeverTouchesItsSource() = runBlocking {
        val root = Files.createTempDirectory("save-short").toFile()
        try {
            val source = root.resolve("source").also { it.writeText("short") }
            val files = FileSaveFiles(root.resolve("saves")); val value = snapshot()
            assertTrue(runCatching { files.prepare(value, source, 10) }.isFailure)
            assertFalse(files.file(value).exists()); assertEquals("short", source.readText())
            assertTrue(root.resolve("saves").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun longUnicodePickerNameDoesNotBecomeAnOversizedPrivateCacheFilename() = runBlocking {
        val root = Files.createTempDirectory("save-unicode").toFile()
        try {
            val source = root.resolve("source").also { it.writeText("content") }
            val files = FileSaveFiles(root.resolve("saves")); val value = snapshot("界".repeat(120) + ".txt")
            val file = files.prepare(value, source, source.length())
            assertEquals("content", file.name); assertEquals(source.readText(), file.readText())
            assertEquals(value, FileSaveSnapshot.decode(value.encode())); files.remove(value)
        } finally { root.deleteRecursively() }
    }
    @Test fun cleanupDoesNotDeleteAnotherActivitysPendingSaveOrOverwriteAnExistingSnapshot() = runBlocking {
        val root = Files.createTempDirectory("save-owners").toFile()
        try {
            val source = root.resolve("source").also { it.writeText("first") }
            val files = FileSaveFiles(root.resolve("saves")); val a = snapshot(); val b = snapshot()
            files.prepare(a, source, source.length()); source.writeText("second")
            assertTrue(runCatching { files.prepare(a, source, source.length()) }.isFailure)
            val other = files.prepare(b, source, source.length())
            assertEquals("first", files.file(a).readText()); files.remove(a)
            assertEquals("second", other.readText()); files.remove(b)
        } finally { root.deleteRecursively() }
    }
}
