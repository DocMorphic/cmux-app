package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class DiagnosticExitTest {
    private fun event(time: Long = 100, pid: Int = 20, role: DiagnosticRole = DiagnosticRole.BROWSER) =
        DiagnosticExit(role, DiagnosticExitReason.NATIVE_CRASH, time, pid, 6)
    private fun text(file: File) = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log"))
        .bufferedReader().use { it.readText() } }
    private fun fixture(block: (File, DiagnosticFiles) -> Unit) {
        val root = Files.createTempDirectory("exit-history").toFile()
        try { block(root, DiagnosticFiles(File(root, "logs"), File(root, "exports"), "fixture")) }
        finally { root.deleteRecursively() }
    }
    @Test fun replayAcrossTwoProcessesAndReopenExportsOneFailure() = fixture { root, files ->
        files.recoverExits(listOf(event()))
        val next = DiagnosticFiles(File(root, "logs"), File(root, "exports"), "next")
        next.recoverExits(listOf(event(), event()))
        val result = text(next.export())
        assertEquals(1, Regex("PROCESS_EXIT").findAll(result).count())
        assertTrue(result.contains("BROWSER PROCESS_EXIT reason=NATIVE_CRASH pid=20 status=6"))
    }
    @Test fun delayedImportAfterClearCannotRestoreOldFailuresButNewFailuresSurvive() = fixture { root, files ->
        files.recoverExits(listOf(event()))
        val other = DiagnosticFiles(File(root, "logs"), File(root, "exports"), "other")
        files.clear(1, 200, wallMillis = 200)
        other.recoverExits(listOf(event(), event(150), event(201)))
        val result = text(other.export())
        assertEquals(1, Regex("PROCESS_EXIT").findAll(result).count())
        assertTrue(result.contains("1970-01-01T00:00:00.201Z"))
    }
    @Test fun preservesDistinctRolesAndPidsAtTheSameTimestampAndUpdatesOsClassification() = fixture { _, files ->
        files.recoverExits(listOf(event(), event(pid = 21), event(role = DiagnosticRole.APP)))
        files.recoverExits(listOf(event().copy(reason = DiagnosticExitReason.ANR)))
        val result = text(files.export())
        assertEquals(3, Regex("PROCESS_EXIT").findAll(result).count())
        assertTrue(result.contains("BROWSER PROCESS_EXIT reason=ANR pid=20"))
        assertTrue(result.contains("APP PROCESS_EXIT reason=NATIVE_CRASH"))
    }
    @Test fun retainsOnly64FailuresAndReplayingOldOsHistoryDoesNotEvictNewOnes() = fixture { _, files ->
        files.recoverExits((1L..100).map { event(it) })
        files.recoverExits((1L..32).map { event(it) })
        val result = text(files.export())
        assertEquals(64, Regex("PROCESS_EXIT").findAll(result).count())
        assertTrue(result.contains("1970-01-01T00:00:00.100Z"))
        assertFalse(result.contains("1970-01-01T00:00:00.001Z"))
    }
    @Test fun migratesExistingStateAndRejectsMalformedHistoryWithoutTruncatingLogs() = fixture { root, files ->
        val state = File(root, "logs/state").apply { parentFile.mkdirs(); writeText("verbose=true\nboot=1\ncutoff=100\n") }
        assertTrue(files.verbose()); files.recoverExits(listOf(event()))
        state.appendText("\nexits=BROWSER,NATIVE_CRASH,123,0,6\n")
        assertTrue(runCatching { files.export() }.isFailure)
        assertTrue(state.readText().contains("123,0,6"))
    }
    @Test fun unavailableOsHistoryStillExportsExistingLogsAndRetryAndClearWork() = fixture { _, files -> runBlocking {
        var unavailable = true
        val recorder = DiagnosticRecorder(files, 1, DiagnosticRole.APP, wall = { 200 }, exitHistory = {
            if (unavailable) error("fixture") else listOf(event())
        })
        try {
            recorder.record(DebugOperation.RPC_WORKSPACE, DebugOutcome.SUCCESS)
            val partial = text(recorder.export())
            assertTrue(partial.contains("EXIT_HISTORY FAILURE"))
            assertTrue(partial.contains("RPC_WORKSPACE SUCCESS"))
            unavailable = false
            assertTrue(text(recorder.export()).contains("PROCESS_EXIT"))
            recorder.clear()
            assertFalse(text(recorder.export()).contains("PROCESS_EXIT"))
        } finally { recorder.shutdown() }
    } }
    @Test fun movingClockBackAndClearingAgainCannotResurrectPreviouslyClearedHistory() = fixture { _, files ->
        files.clear(1, 100, wallMillis = 200)
        files.clear(1, 200, wallMillis = 150)
        files.recoverExits(listOf(event(180)))
        assertFalse(text(files.export()).contains("PROCESS_EXIT"))
    }
}
