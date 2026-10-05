package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Separate JVMs exercise actual OS locks, rather than only OverlappingFileLockException. */
class FileOperationLocksTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("operation-locks").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun child(root: File, key: String, hold: Boolean = false): Process {
        val classes = listOf(FileOperationLockProbe::class.java, FileOperationLocks::class.java, kotlin.Unit::class.java)
        val classpath = classes.map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
            FileOperationLockProbe::class.java.name, root.path, key, hold.toString()).redirectErrorStream(true).start()
    }
    private fun probe(root: File, key: String): String {
        val process = child(root, key)
        try {
            assertTrue("Lock probe stalled", process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(output, 0, process.exitValue()); return output
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
    }
    @Test fun failedAndUnrelatedClaimsDoNotReleaseAnotherOwnersOsLock() = fixture { root ->
        val first = FileOperationLocks(root); val other = FileOperationLocks(root)
        val held = checkNotNull(first.claim(FileOperationLocks.Kind.UI, "live"))
        try {
            repeat(8) {
                assertNull(other.claim(FileOperationLocks.Kind.UI, "live"))
                checkNotNull(other.claim(FileOperationLocks.Kind.UI, "other")).close()
            }
            assertEquals("BUSY", probe(root, "live"))
            assertEquals("ACQUIRED", probe(root, "other"))
        } finally { held.close(); held.close() }
        assertEquals("ACQUIRED", probe(root, "live"))
    }
    @Test fun killedProcessReleasesOwnershipWithoutDeletingLockInode() = fixture { root ->
        val process = child(root, "killed", hold = true)
        try {
            val ready = CompletableDeferred<String?>()
            val reader = CoroutineScope(Dispatchers.IO).launch { ready.complete(process.inputStream.bufferedReader().readLine()) }
            assertEquals("ACQUIRED", runBlocking { withTimeout(5000) { ready.await() } })
            runBlocking { reader.join() }
            assertNull(FileOperationLocks(root).claim(FileOperationLocks.Kind.UI, "killed"))
            process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            checkNotNull(FileOperationLocks(root).claim(FileOperationLocks.Kind.UI, "killed")).close()
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        assertEquals(listOf(".operation-locks"), root.list()!!.toList())
    }
    @Test fun cacheCleanupCannotRemoveAnExportOwnedByAnotherProcess() = fixture { root ->
        val directory = File(root, "remote").apply { mkdirs(); File(this, "bytes").writeText("active"); setLastModified(1) }
        val process = child(root, "remote", hold = true)
        try {
            val ready = CompletableDeferred<String?>()
            val reader = CoroutineScope(Dispatchers.IO).launch { ready.complete(process.inputStream.bufferedReader().readLine()) }
            assertEquals("ACQUIRED", runBlocking { withTimeout(5000) { ready.await() } })
            runBlocking { reader.join() }
            ArtifactExportCache.prune(root, 10_000_000)
            assertTrue(directory.exists())
            process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            ArtifactExportCache.prune(root, 10_000_000)
            assertFalse(directory.exists()); assertTrue(File(root, ".operation-locks").exists())
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
    }
    @Test fun manyIdentitiesUseOneEmptyFileAndNamespacesDoNotBlockEachOther() = fixture { root ->
        val locks = FileOperationLocks(root)
        val leases = FileOperationLocks.Kind.entries.map { checkNotNull(locks.claim(it, "same")) }
        try { repeat(2000) { checkNotNull(locks.claim(FileOperationLocks.Kind.UI, "id-$it")).close() } }
        finally { leases.forEach(AutoCloseable::close) }
        assertEquals(listOf(".operation-locks"), root.list()!!.toList())
        assertEquals(0L, File(root, ".operation-locks").length())
    }
}

object FileOperationLockProbe {
    @JvmStatic fun main(args: Array<String>) {
        val lease = FileOperationLocks(File(args[0])).claim(FileOperationLocks.Kind.UI, args[1])
        println(if (lease == null) "BUSY" else "ACQUIRED"); System.out.flush()
        try { if (lease != null && args[2].toBoolean()) System.`in`.read() } finally { lease?.close() }
    }
}
