package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class AnrTraceTest {
    private fun thread(tid: Int, system: Int = 200 + tid, owner: Int? = null, frames: Int = 1) = buildString {
        append("\"PRIVATE_THREAD_$tid\" prio=5 tid=$tid Blocked\n  | sysTid=$system nice=0\n")
        repeat(frames) { append("  at io.github.docmorphic.cmuxapp.Terminal.render(PRIVATE_SOURCE.kt:42)\n") }
        owner?.let { append("  - waiting to lock <0xdeadbeef> (a PRIVATE_OBJECT) held by thread $it\n") }
    }
    private fun trace(body: String, pid: Int = 100) = "----- pid $pid at PRIVATE_DATE -----\nCmd line: PRIVATE_COMMAND\n$body----- end $pid -----\n"
    private fun read(value: String) = AnrTrace.read(ByteArrayInputStream(value.toByteArray()), 100)
    private fun text(file: File) = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log"))
        .bufferedReader().use { it.readText() } }

    @Test fun selectsExactProcessAndMainBySystemTidWithOwnerChainAndFiltersPrivateFields() {
        val stack = read(trace(thread(1, 999), 999) + trace(thread(20, owner = 21) + thread(7, 100, owner = 20) +
            thread(21, owner = 7) + "  at secret.account.Private.run(PRIVATE_PATH:1)\n"))!!
        assertEquals(listOf(7, 20, 21), stack.threads.map { it.tid })
        assertTrue(stack.threads.first().main); assertEquals(AnrThreadState.BLOCKED, stack.threads.first().state)
        assertEquals(20, stack.threads.first().owner)
        assertTrue(stack.text().contains("cmuxapp.Terminal#render line=42"))
        listOf("PRIVATE", "deadbeef", "secret.account", "999", ".kt").forEach { assertFalse(stack.text().contains(it)) }
        assertEquals(stack, AnrStack.decode(stack.encode()))
    }
    @Test fun nativeFramesContainOnlyRecognizedModuleAndCodeMetadata() {
        val stack = read(trace(thread(1, 100) + """
  native: #00 pc ffffffffffffffff  /apex/PRIVATE/lib64/libc.so (__futex_wait_ex+12) (BuildId: 0123456789abcdef)
  native: #01 pc 00000042  /data/PRIVATE/base.apk!libcmux_ghostty.so (cmux_wait+4)
  native: #02 pc 00000043  /data/PRIVATE/secret.so (PRIVATE_SYMBOL)
  at java.lang.Object.wait(Native method)
"""))!!
        val frames = stack.threads.single().nativeFrames
        assertEquals(3, frames.size); assertEquals(ULong.MAX_VALUE, frames[0].relativePc)
        assertEquals("0123456789abcdef", frames[0].buildId)
        assertEquals("libcmux_ghostty.so", frames[1].module); assertEquals("", frames[2].symbol)
        assertFalse(stack.text().contains("PRIVATE")); assertFalse(stack.text().contains("/data/"))
        assertEquals(-2, stack.threads.single().frames.last().line)
    }
    @Test fun missingWrongAmbiguousAndIncompleteSectionsDoNotProduceStacks() {
        assertNull(read(trace(thread(1, 100), 101)))
        assertNull(read(trace(thread(1, 201))))
        assertNull(read(trace(thread(1, 100) + thread(2, 100))))
        assertNull(read(trace(thread(1, 100)).substringBefore("----- end")))
        assertNull(read(trace(thread(1, 100, frames = 0))))
        assertNull(read(trace(thread(1, 100) + "----- pid 200 at X -----\n")))
        assertTrue(runCatching { read(trace(thread(1, 100) + thread(1))) }.isFailure)
    }
    @Test fun unknownThreadHeadersCannotContaminatePrecedingThread() {
        val stack = read(trace(thread(1, 100) + "\"PRIVATE_UNATTACHED\" prio=5 (not attached)\n" +
            "  at java.lang.Thread.sleep(PRIVATE:12)\n"))!!
        assertEquals(1, stack.threads.single().frames.size)
    }
    @Test fun threadFrameAndInputBudgetsAreEnforcedAndOwnerHasPriority() {
        val stack = read(trace(thread(1, 100, owner = 200, frames = 40) +
            (2..40).joinToString("") { thread(it, frames = 4) } + thread(200, frames = 20)))!!
        assertTrue(stack.truncated); assertEquals(16, stack.threads.size)
        assertEquals(200, stack.threads[1].tid)
        assertEquals(64, stack.threads.sumOf { it.frames.size + it.nativeFrames.size })
        assertTrue(runCatching { read(trace((1..257).joinToString("") { thread(it) })) }.isFailure)
        assertTrue(runCatching { read("x".repeat(16 * 1024 + 1)) }.isFailure)
        assertTrue(runCatching { read("ignored\n".repeat(1_100_000)) }.isFailure)
    }
    @Test fun codecsRejectTrailingBytesMismatchedPidReasonAndInvalidVersion() {
        val stack = read(trace(thread(1, 100)))!!
        val exit = DiagnosticExit(DiagnosticRole.APP, DiagnosticExitReason.ANR, 100, 100, 0, anrStack = stack)
        assertEquals(exit, DiagnosticExit.decode(exit.encode()))
        assertTrue(DiagnosticExit.decode("APP,ANR,100,100,0").line().contains("ANR_STACK unavailable"))
        assertTrue(runCatching { AnrStack.decode(stack.encode() + 0) }.isFailure)
        assertTrue(runCatching { AnrStack.decode(stack.encode().apply { this[3] = 9 }) }.isFailure)
        assertTrue(runCatching { exit.copy(pid = 101) }.isFailure)
        assertTrue(runCatching { exit.copy(reason = DiagnosticExitReason.JAVA_CRASH) }.isFailure)
        assertTrue(runCatching { DiagnosticExit.decode("APP,JAVA_CRASH,100,100,0," + exit.encode().substringAfter(",anr:", "").let { "anr:$it" }) }.isFailure)
    }
    @Test fun enrichedHistorySurvivesReopenEvictionAndClearRejectsLateImports() {
        val root = Files.createTempDirectory("anr-history").toFile()
        try {
            fun files() = DiagnosticFiles(File(root, "logs"), File(root, "exports"), "fixture")
            val summary = DiagnosticExit(DiagnosticRole.BROWSER, DiagnosticExitReason.ANR, 100, 100, 0)
            val enriched = summary.copy(anrStack = read(trace(thread(1, 100))))
            files().recoverExits(listOf(summary)); assertTrue(text(files().export()).contains("ANR_STACK unavailable"))
            files().recoverExits(listOf(enriched)); files().recoverExits(listOf(summary))
            repeat(2) {
                val result = text(files().export())
                assertEquals(1, Regex("ANR_STACK pid=100").findAll(result).count())
                assertTrue(result.contains("Terminal#render")); assertFalse(result.contains("PRIVATE"))
            }
            files().clear(1, 1, wallMillis = 100); files().recoverExits(listOf(enriched))
            assertFalse(text(files().export()).contains("ANR_STACK"))
            files().recoverExits(listOf(enriched.copy(timestamp = 101)))
            assertTrue(text(files().export()).contains("ANR_STACK pid=100"))
        } finally { root.deleteRecursively() }
    }
}
