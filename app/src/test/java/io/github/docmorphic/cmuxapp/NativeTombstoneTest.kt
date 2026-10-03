package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile

class NativeTombstoneTest {
    private fun varint(number: ULong): ByteArray {
        var n = number; val out = ByteArrayOutputStream()
        do { val low = (n and 127uL).toInt(); n = n shr 7; out.write(low or if (n != 0uL) 128 else 0) } while (n != 0uL)
        return out.toByteArray()
    }
    private fun scalar(field: Int, value: ULong) = varint((field * 8).toULong()) + varint(value)
    private fun field(field: Int, value: ByteArray) = varint((field * 8 + 2).toULong()) + varint(value.size.toULong()) + value
    private fun string(field: Int, value: String) = field(field, value.toByteArray())
    private fun frame(module: String = "libc.so", symbol: String = "abort", pc: ULong = 0x123uL) =
        scalar(1, pc) + scalar(2, 0xdeadbeefuL) + scalar(3, 0xcafebabeuL) + string(4, symbol) +
            string(6, "/private/SECRET_PATH/$module") + string(8, "aabbccddeeff0011")
    private fun entry(id: Int, frames: List<ByteArray>, threadId: Int = id) = field(16,
        field(2, scalar(1, threadId.toULong()) + string(2, "SECRET_THREAD") + field(3, "SECRET_REGISTER".toByteArray()) +
            frames.fold(byteArrayOf()) { bytes, frame -> bytes + field(4, frame) } + field(5, "SECRET_MEMORY".toByteArray())) + scalar(1, id.toULong()))
    private fun tombstone(frames: List<ByteArray> = listOf(frame())) =
        string(14, "SECRET_ABORT") + entry(99, listOf(frame(symbol = "SECRET_OTHER_THREAD"))) + entry(42, frames) +
            scalar(6, 42uL) + scalar(5, 20uL) + scalar(1, 1uL) + string(9, "SECRET_COMMAND_LINE") +
            field(17, "SECRET_MAPPINGS".toByteArray()) + field(18, "SECRET_LOGS".toByteArray()) + field(19, "SECRET_FDS".toByteArray())
    private fun read(bytes: ByteArray) = NativeTombstone.read(ByteArrayInputStream(bytes), 20)
    @Test fun unorderedFieldsSelectOnlyCrashingThreadAndExcludePrivateTombstoneFields() {
        val stack = read(tombstone())!!
        assertEquals(20, stack.pid); assertEquals(42, stack.tid); assertEquals(NativeArchitecture.ARM64, stack.architecture)
        assertEquals(listOf(NativeCrashFrame("libc.so", 0x123uL, "aabbccddeeff0011", "abort")), stack.frames)
        val text = stack.text()
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("deadbeef")); assertFalse(text.contains("cafebabe"))
        assertFalse(text.contains("/private")); assertTrue(text.contains("symbol=abort"))
        assertEquals(stack, NativeCrashStack.decode(stack.encode()))
    }
    @Test fun unknownModulesAndUnsafeSymbolsAreFilteredAndUnsignedOffsetsSurvive() {
        val stack = read(tombstone(listOf(frame("SECRET.so", "SECRET_SYMBOL", ULong.MAX_VALUE), frame(symbol = "abort\nSECRET"))))!!
        assertEquals("other", stack.frames[0].module); assertEquals("", stack.frames[0].symbol)
        assertEquals(ULong.MAX_VALUE, NativeCrashStack.decode(stack.encode()).frames[0].relativePc)
        assertFalse(stack.text().contains("SECRET")); assertEquals("", stack.frames[1].symbol)
    }
    @Test fun framesAreCappedAndExplicitZeroThreadIdUsesTheMapIdentity() {
        val stack = read(tombstone(List(100) { frame() }))!!
        assertEquals(64, stack.frames.size); assertTrue(stack.truncated)
        assertNotNull(read(entry(42, listOf(frame()), threadId = 0) + scalar(6, 42uL) + scalar(5, 20uL)))
    }
    @Test fun apkMappedLibrariesKeepOnlyRecognizedCodeModuleNames() {
        val stack = read(tombstone(listOf(frame("base.apk!libcmux_ghostty.so"),
            frame("base.apk!lib/arm64-v8a/libiroh_ffi.so"), frame("base.apk!SECRET.so"))))!!
        assertEquals(listOf("libcmux_ghostty.so", "libiroh_ffi.so", "other"), stack.frames.map { it.module })
        assertFalse(stack.text().contains("base.apk")); assertFalse(stack.text().contains("SECRET"))
        assertFalse(stack.text().contains("/"))
    }
    @Test fun rejectsWrongIdentityBrokenLengthsAndMalformedWireNumbers() {
        val valid = tombstone()
        val failures = listOf(valid + scalar(5, 21uL), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            valid + byteArrayOf(11), valid + byteArrayOf(0x80.toByte()),
            valid + varint(100uL * 8uL + 2uL) + varint(8_388_609uL),
            scalar(5, 20uL) + scalar(6, 42uL) + field(16, scalar(1, 42uL) + field(2, scalar(1, 43uL))),
            byteArrayOf(8) + ByteArray(10) { 0xff.toByte() })
        failures.forEachIndexed { index, bytes -> assertTrue("Malformed case $index accepted", runCatching { read(bytes) }.isFailure) }
    }
    @Test fun truncatedSkippedFieldCannotSeekPastARealFileEnd() {
        val file = Files.createTempFile("native-trace", ".bin").toFile()
        try {
            file.writeBytes(tombstone() + varint(100uL * 8uL + 2uL) + varint(100uL) + byteArrayOf(1))
            assertTrue(runCatching { file.inputStream().use { NativeTombstone.read(it, 20) } }.isFailure)
        } finally { file.delete() }
    }
    @Test fun absentCrashThreadIsNotSubstitutedWithAnotherThreadsStack() {
        assertNull(read(entry(99, listOf(frame())) + scalar(6, 42uL) + scalar(5, 20uL)))
        assertNull(read(entry(42, emptyList()) + scalar(6, 42uL) + scalar(5, 20uL)))
    }
    @Test fun excessiveThreadTablesAndInputsAreRejected() {
        val excessive = (1..257).fold(byteArrayOf()) { bytes, id -> bytes + entry(id, emptyList()) } + scalar(5, 20uL)
        assertTrue(runCatching { read(excessive) }.isFailure)
        val huge = field(100, ByteArray(8 * 1024 * 1024)) + tombstone()
        assertTrue(runCatching { read(huge) }.isFailure)
    }
    @Test fun persistedHistoryKeepsRecoveredFramesWhenTheOsEvictsTheTraceAndClearWins() {
        val root = Files.createTempDirectory("native-history").toFile()
        fun files() = DiagnosticFiles(root.resolve("logs"), root.resolve("exports"), "fixture")
        fun export(files: DiagnosticFiles) = ZipFile(files.export()).use { zip ->
            zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log")).bufferedReader().use { it.readText() }
        }
        try {
            val summary = DiagnosticExit(DiagnosticRole.BROWSER, DiagnosticExitReason.NATIVE_CRASH, 100, 20, 6)
            val enriched = summary.copy(nativeStack = read(tombstone()))
            assertEquals(summary, DiagnosticExit.decode(summary.encode()))
            assertEquals(enriched, DiagnosticExit.decode(enriched.encode()))
            val first = files(); first.recoverExits(listOf(summary)); first.recoverExits(listOf(enriched))
            val reopened = files(); reopened.recoverExits(listOf(summary))
            assertEquals(1, Regex("NATIVE_STACK tid=").findAll(export(reopened)).count())
            reopened.clear(1, 200, 200); first.recoverExits(listOf(enriched))
            assertFalse(export(first).contains("NATIVE_STACK tid="))
            first.recoverExits(listOf(enriched.copy(timestamp = 201)))
            assertTrue(export(first).contains("NATIVE_STACK tid="))
            first.recoverExits(listOf(summary.copy(timestamp = 201, reason = DiagnosticExitReason.ANR)))
            assertFalse(export(first).contains("NATIVE_STACK tid="))
        } finally { root.deleteRecursively() }
    }
}
