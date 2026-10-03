package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class DiagnosticFilesTest {
    private fun record(time: Long = 100, boot: Int = 1, kind: DebugOperation = DebugOperation.RPC_TERMINAL,
        outcome: DebugOutcome = DebugOutcome.SUCCESS) = DiagnosticRecord(kind, outcome, DiagnosticRole.APP, time, time, boot, id = time)
    private fun read(file: File) = ZipFile(file).use { zip -> zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() } } }
    private fun withFiles(block: (File, File, DiagnosticFiles) -> Unit) {
        val parent = Files.createTempDirectory("diagnostics").toFile()
        try { val root = File(parent, "logs"); val exports = File(parent, "exports"); block(root, exports, DiagnosticFiles(root, exports, "fixture")) }
        finally { parent.deleteRecursively() }
    }
    @Test fun exportsExactlyTwoMembersAndReopensPriorProcessHistory() = withFiles { root, exports, files ->
        files.record(listOf(record(kind = DebugOperation.APP_START), record(kind = DebugOperation.RPC_TERMINAL), record(kind = DebugOperation.SSH_CONNECT)))
        val next = DiagnosticFiles(root, exports, "reopened")
        next.record(listOf(record(200, kind = DebugOperation.BROWSER_PREPARE).copy(role = DiagnosticRole.BROWSER)))
        val members = read(next.export())
        assertEquals(setOf("cmux-diagnostics/app-events.log", "cmux-diagnostics/networking.log"), members.keys)
        assertTrue(members.values.all { it.contains("APP_START") && it.contains("reopened") })
        assertTrue(members.getValue("cmux-diagnostics/app-events.log").contains("BROWSER BROWSER_PREPARE"))
        assertFalse(members.getValue("cmux-diagnostics/app-events.log").contains("SSH_CONNECT"))
        assertTrue(members.getValue("cmux-diagnostics/networking.log").contains("SSH_CONNECT"))
    }
    @Test fun enablingAndDisablingVerbosePreservesExistingLogs() = withFiles { root, exports, files ->
        files.record(listOf(record(100)))
        files.setVerbose(true); assertTrue(DiagnosticFiles(root, exports, "fixture").verbose())
        files.record(listOf(record(200, outcome = DebugOutcome.STARTED)))
        files.setVerbose(false); files.record(listOf(record(300, outcome = DebugOutcome.STARTED), record(400)))
        val text = read(files.export()).values.joinToString()
        assertTrue(text.contains("id=100")); assertTrue(text.contains("id=200")); assertTrue(text.contains("id=400"))
        assertFalse(text.contains("id=300")); assertFalse(files.verbose())
    }
    @Test fun clearRejectsPreClearWritesFromAnotherRecorderButAllowsNewBoot() = withFiles { root, exports, files ->
        val other = DiagnosticFiles(root, exports, "browser")
        files.setVerbose(true); files.record(listOf(record(100))); val stale = files.export()
        files.clear(1, 200); assertFalse(stale.exists()); assertTrue(files.verbose())
        other.record(listOf(record(150), record(201), record(1, boot = 2)))
        val text = read(other.export()).values.joinToString()
        assertFalse(text.contains("id=100")); assertFalse(text.contains("id=150"))
        assertTrue(text.contains("id=201")); assertTrue(text.contains("id=1 "))
    }
    @Test fun rotationBoundsBothGenerationsAndTotalBytes() = withFiles { root, exports, _ ->
        val files = DiagnosticFiles(root, exports, "fixture", DiagnosticPolicy(256, 600, 3))
        repeat(100) { files.record(listOf(record(it.toLong()))) }
        val generations = root.listFiles()!!.filter { it.name.startsWith("cmux-app.log") }
        assertTrue(generations.size <= 4); assertTrue(generations.sumOf(File::length) <= 600)
        assertTrue(read(files.export()).getValue("cmux-diagnostics/app-events.log").contains("id=99"))
        repeat(5) { files.export() }; assertEquals(3, exports.listFiles()!!.size)
    }
    @Test fun cancelledExportRemovesPartialFileAndDoesNotDeletePreviousExport() = withFiles { _, exports, files ->
        files.record(listOf(record())); val previous = files.export()
        var checks = 0
        val error = runCatching { files.export { if (++checks == 4) throw CancellationException("fixture") } }.exceptionOrNull()
        assertTrue(error is CancellationException); assertTrue(previous.exists()); assertEquals(listOf(previous), exports.listFiles()!!.toList())
    }
    @Test fun failedClearReportsFailureAndKeepsTheGlobalBarrier() = withFiles { root, exports, files ->
        files.record(listOf(record(100)))
        val blocked = File(exports, "cmux-diagnostics-blocked").apply { mkdirs(); resolve("child").writeText("fixture") }
        assertTrue(runCatching { files.clear(1, 200) }.isFailure)
        assertEquals(200L, files.clearCutoff(1)); assertTrue(blocked.exists())
        files.record(listOf(record(150)))
        assertFalse(read(files.export()).values.joinToString().contains("id=150"))
    }
    @Test fun recorderBarrierDrainsAdmittedEventsAndClearDropsOnlyOlderOnes() = runBlocking {
        val parent = Files.createTempDirectory("diagnostic-recorder").toFile()
        var clock = 1L
        val files = DiagnosticFiles(File(parent, "logs"), File(parent, "exports"), "fixture")
        val recorder = DiagnosticRecorder(files, 1, DiagnosticRole.APP, elapsed = { clock }, capacity = 32)
        try {
            recorder.record(DebugOperation.RPC_HOST, DebugOutcome.SUCCESS, id = 1)
            assertTrue(read(recorder.export()).values.joinToString().contains("RPC_HOST"))
            clock = 2; recorder.clear(); clock = 3
            recorder.record(DebugOperation.RPC_WORKSPACE, DebugOutcome.SUCCESS, id = 2)
            val text = read(recorder.export()).values.joinToString()
            assertFalse(text.contains("RPC_HOST")); assertTrue(text.contains("RPC_WORKSPACE"))
        } finally { recorder.shutdown(); parent.deleteRecursively() }
    }
    @Test fun saturatedIngressReportsDroppedEventsAndRemovesAbandonedPartialExports() = runBlocking {
        val parent = Files.createTempDirectory("diagnostic-pressure").toFile()
        val exports = File(parent, "exports").apply { mkdirs() }
        val abandoned = File(exports, "cmux-diagnostics-old.zip.partial").apply { writeText("interrupted") }
        val files = DiagnosticFiles(File(parent, "logs"), exports, "fixture")
        val recorder = DiagnosticRecorder(files, 1, DiagnosticRole.APP, capacity = 2)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocker = Thread { files.export { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }.delete() }
        try {
            blocker.start(); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            repeat(500) { recorder.record(DebugOperation.RPC_TERMINAL, DebugOutcome.SUCCESS) }
            release.countDown(); blocker.join(5000)
            val text = read(recorder.export()).values.joinToString()
            assertTrue(text.contains("LOG_DROPPED")); assertFalse(abandoned.exists())
        } finally { release.countDown(); blocker.join(5000); recorder.shutdown(); parent.deleteRecursively() }
    }
}
