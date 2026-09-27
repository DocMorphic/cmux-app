package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ChangesContentTransferTest {
    private val fingerprint = "stat:8:123:4:5:6"
    private fun stat(size: Long = 8, fingerprint: String = this.fingerprint) = JSONObject()
        .put("exists", true).put("is_directory", false).put("size", size).put("kind", "text").put("content_fingerprint", fingerprint)
    private fun chunk(data: String, offset: Long, total: Long, eof: Boolean, fingerprint: String = this.fingerprint) = JSONObject()
        .put("data_b64", Base64.getEncoder().encodeToString(data.toByteArray())).put("offset", offset).put("total_size", total)
        .put("eof", eof).put("content_fingerprint", fingerprint)
    @Test fun orderedTransferPreservesCRLFAndDoesNotInventATrailingEmptyLine() = runBlocking {
        val requests = mutableListOf<Long>()
        val transfer = ChangesContentTransfer({ path, revision -> assertEquals("old/name", path); assertEquals(ChangesRevision.CURRENT, revision); stat() },
            { _, _, offset, length -> requests += offset; assertEquals(3 * 1024 * 1024, length)
                if (offset == 0L) chunk("one\r", 0, 8, false) else chunk("\ntwo", 4, 8, true) })
        val result = transfer.currentLines("old/name")
        assertEquals(listOf("one\r", "two"), result.lines); assertEquals(listOf(0L, 4L), requests)
        assertEquals(listOf("one", ""), ChangesContentTransfer.splitLines("one\n\n".toByteArray()))
        assertTrue(ChangesContentTransfer.splitLines(byteArrayOf()).isEmpty())
    }
    @Test fun fingerprintsRequireFilesystemIdentityOrBaseBlobIdentity() {
        assertTrue(changesFingerprintValid(fingerprint, true))
        assertTrue(changesFingerprintValid("stat:0:-1:18446744073709551615:0:-2", true))
        assertTrue(changesFingerprintValid("blob:abc:0"))
        listOf(null, "", "stat:8:123", "stat:-1:2:3:4:5", "stat:1:2:-3:4:5", "blob::0").forEach { assertFalse(changesFingerprintValid(it)) }
        assertFalse(changesFingerprintValid("blob:abc:0", true))
    }
    @Test fun badOffsetsTotalsEOFFingerprintsAndEmptyIntermediateChunksAreRejected() = runBlocking {
        val bad = listOf(chunk("12345678", 1, 8, true), chunk("123", 0, 8, true), chunk("12345678", 0, 8, false),
            chunk("", 0, 8, false), chunk("123456789", 0, 8, true), chunk("12345678", 0, 9, false),
            chunk("12345678", 0, 8, true, "stat:8:124:4:5:6"), chunk("12345678", 0, 8, true, ""))
        bad.forEach { response ->
            val transfer = ChangesContentTransfer({ _, _ -> stat() }, { _, _, _, _ -> response })
            try { transfer.currentLines("a"); fail("Accepted $response") } catch (_: Exception) { }
        }
    }
    @Test fun expansionRejectsOversizedStatBeforeFetchingAndLineLimitDuringDecode() = runBlocking {
        var fetched = false
        val transfer = ChangesContentTransfer({ _, _ -> stat(ChangesContentTransfer.EXPANSION_BYTES + 1) }, { _, _, _, _ -> fetched = true; error("unexpected") })
        try { transfer.currentLines("a"); fail() } catch (_: ChangesContentTooLarge) { }
        assertFalse(fetched)
        assertEquals(200_000, ChangesContentTransfer.splitLines(ByteArray(200_000) { 10 }).size)
        try { ChangesContentTransfer.splitLines(ByteArray(200_001) { 10 }); fail() } catch (_: ChangesContentTooLarge) { }
    }
    @Test fun baseRevisionAndEmptyFileUseSameValidatedStreamingPath() = runBlocking {
        val transfer = ChangesContentTransfer({ path, revision -> assertEquals("old.png", path); assertEquals(ChangesRevision.BASE, revision); stat(0, "blob:abc:0") },
            { path, revision, offset, _ -> assertEquals("old.png", path); assertEquals(ChangesRevision.BASE, revision); assertEquals(0L, offset); chunk("", 0, 0, true, "blob:abc:0") })
        val metadata = transfer.metadata("old.png", ChangesRevision.BASE)
        var received = 0
        transfer.stream("old.png", ChangesRevision.BASE, metadata, 64) { bytes, offset, total -> received++; assertTrue(bytes.isEmpty()); assertEquals(0L, offset); assertEquals(0L, total) }
        assertEquals(1, received)
    }
    @Test fun cancellationAfterNonCooperativeFetchDoesNotDeliverContent() = runBlocking {
        val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); var delivered = false
        val transfer = ChangesContentTransfer({ _, _ -> stat() }, { _, _, _, _ -> started.complete(Unit); withContext(NonCancellable) { release.await() }; chunk("12345678", 0, 8, true) })
        val job = launch { transfer.stream("a", ChangesRevision.CURRENT, transfer.metadata("a", ChangesRevision.CURRENT), 64) { _, _, _ -> delivered = true } }
        started.await(); job.cancel(); release.complete(Unit); job.join(); assertFalse(delivered)
    }
}
