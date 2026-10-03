package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class DiagnosticCrashTest {
    private fun error(): Throwable = IllegalStateException("SECRET_MESSAGE", IOException("SECRET_CAUSE")).apply {
        stackTrace = arrayOf(
            StackTraceElement("io.github.docmorphic.cmuxapp.TerminalScreen", "render", "/private/SECRET_FILE.kt", 42),
            StackTraceElement("untrusted.SECRET_CLASS", "SECRET_METHOD", "SECRET_FILE", 3),
            StackTraceElement("java.lang.Thread", "bad\nSECRET_METHOD", null, -2))
    }
    private fun record(time: Long = 100, boot: Int = 1) =
        DiagnosticCrash.capture(error(), DiagnosticRole.BROWSER, boot, time, 1000, 55, 441)
    private fun text(files: DiagnosticFiles) = ZipFile(files.export()).use {
        it.getInputStream(it.getEntry("cmux-diagnostics/app-events.log")).bufferedReader().use { reader -> reader.readText() }
    }
    private fun fixture(block: (File, DiagnosticFiles) -> Unit) {
        val root = Files.createTempDirectory("crash-capture").toFile()
        try {
            val files = DiagnosticFiles(File(root, "logs"), File(root, "exports"), "fixture")
            files.crashes.prepare(); block(root, files)
        } finally { root.deleteRecursively() }
    }
    @Test fun roundTripIncludesUsefulSymbolsAndNeverThrowableTextOrPaths() {
        val captured = record()
        assertEquals(captured, DiagnosticCrash.decode(captured.encode()))
        val text = captured.text()
        assertTrue(text.contains("BROWSER JAVA_STACK pid=55 version=441"))
        assertTrue(text.contains("io.github.docmorphic.cmuxapp.TerminalScreen#render line=42"))
        assertTrue(text.contains("java.io.IOException"))
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("/private"))
    }
    @Test fun cyclesAndDeepStacksStayWithinTheRecordBudget() {
        val a = IllegalStateException(); val b = IOException()
        a.initCause(b); b.initCause(a)
        a.stackTrace = Array(1000) { StackTraceElement("java.lang.Thread", "run", null, 1) }
        val captured = DiagnosticCrash.capture(a, DiagnosticRole.APP, 1, 1, 1, 1, 1)
        assertEquals(2, captured.causes.size); assertEquals(64, captured.causes.sumOf { it.frames.size })
        assertTrue(captured.truncated); assertTrue(captured.encode().size <= DiagnosticCrash.MAX_BYTES)
        val bad = captured.encode() + byteArrayOf(1)
        assertTrue(runCatching { DiagnosticCrash.decode(bad) }.isFailure)
    }
    @Test fun handlerAlwaysDelegatesEvenWhenCaptureFailsAndRecordsOnlyOnce() {
        var attempts = 0; val seen = mutableListOf<Throwable>(); val thread = Thread.currentThread()
        val handler = DiagnosticCrashHandler({ passedThread, failure -> assertSame(thread, passedThread); seen += failure }) {
            attempts++; throw OutOfMemoryError("fixture")
        }
        val error = error(); handler.uncaughtException(thread, error); handler.uncaughtException(thread, error)
        assertEquals(1, attempts); assertEquals(listOf(error, error), seen)
    }
    @Test fun publicationSurvivesReopenExportsOnceAndRejectsLatePreClearRecords() = fixture { root, files ->
        files.crashes.write(record(), "BROWSER-55-1")
        val reopened = DiagnosticFiles(File(root, "logs"), File(root, "exports"), "reopened")
        repeat(2) { assertEquals(1, Regex("JAVA_STACK pid=55").findAll(text(reopened)).count()) }
        reopened.clear(1, 200, 2000)
        files.crashes.write(record(150), "BROWSER-55-2") // A dying process publishes after clear.
        assertFalse(text(reopened).contains("JAVA_STACK pid=55"))
        files.crashes.write(record(201), "BROWSER-55-3")
        assertTrue(text(reopened).contains("JAVA_STACK pid=55"))
        reopened.clear(2, 1, 3000)
        files.crashes.write(record(999, 1), "BROWSER-55-4")
        assertFalse(text(reopened).contains("JAVA_STACK pid=55"))
        files.crashes.write(record(2, 2), "BROWSER-55-5")
        assertTrue(text(reopened).contains("JAVA_STACK pid=55"))
    }
    @Test fun clearPreservesCrashWhichOccurredAfterItsAdmissionCutoff() = fixture { _, files ->
        files.crashes.write(record(201), "BROWSER-55-1")
        files.clear(1, 200, 5000)
        assertTrue(text(files).contains("JAVA_STACK pid=55"))
    }
    @Test fun retentionAndCorruptOrPartialRecordsNeverExportRawBytes() = fixture { root, files ->
        repeat(50) { files.crashes.write(record(it.toLong()), "BROWSER-55-$it") }
        val directory = File(root, "logs/crashes")
        File(directory, "crash-invalid.bin").writeText("SECRET_CORRUPT")
        val partial = File(directory, "crash-old.partial").apply { writeText("SECRET_PARTIAL"); setLastModified(1) }
        val recent = File(directory, "crash-current.partial").apply { writeText("SECRET_PARTIAL") }
        val output = text(files)
        assertEquals(32, Regex("JAVA_STACK pid=55").findAll(output).count())
        assertEquals(32, directory.listFiles()!!.count { it.extension == "bin" })
        assertFalse(output.contains("SECRET")); assertFalse(partial.exists()); assertTrue(recent.exists())
    }
    @Test fun crashPublicationDoesNotWaitForTheOrdinaryExportLock() = fixture { _, files ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val holding = CompletableFuture.runAsync { files.export { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var delegated = false
            val crash = CompletableFuture.runAsync {
                DiagnosticCrashHandler({ _, _ -> delegated = true }) { files.crashes.write(record(), "APP-55-1") }
                    .uncaughtException(Thread.currentThread(), error())
            }
            crash.get(5, TimeUnit.SECONDS); assertTrue(delegated)
        } finally { release.countDown(); holding.get(5, TimeUnit.SECONDS) }
        assertTrue(text(files).contains("JAVA_STACK"))
    }
    @Test fun hostileThrowableAccessorsCannotPreventDelegation() {
        var delegated = false
        val error = object : RuntimeException() { override fun getStackTrace(): Array<StackTraceElement> = throw IOException("SECRET") }
        DiagnosticCrashHandler({ _, supplied -> assertSame(error, supplied); delegated = true }) {
            DiagnosticCrash.capture(it, DiagnosticRole.APP, 1, 1, 1, 1, 1)
        }.uncaughtException(Thread.currentThread(), error)
        assertTrue(delegated)
    }
}
