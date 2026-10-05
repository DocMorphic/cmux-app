package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.SocketException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtifactLocalOutputTest {
    @Test fun quotaAndNestedErrnosFollowTheIosLocalStoragePolicy() {
        for (errno in listOf(android.system.OsConstants.ENOSPC, android.system.OsConstants.EDQUOT)) {
            val error = java.io.IOException("outer", java.io.IOException("inner", android.system.ErrnoException("write", errno)))
            assertEquals(ArtifactPreviewFailure.Kind.LOCAL_STORAGE_FULL, ArtifactLocalOutput.classify(error).failure.kind)
        }
        val denied = java.io.IOException("ENOSPC is only text here", android.system.ErrnoException("open", android.system.OsConstants.EACCES))
        assertEquals(ArtifactPreviewFailure.Kind.LOCAL_STORAGE_UNAVAILABLE, ArtifactLocalOutput.classify(denied).failure.kind)
        val cycle = java.io.IOException("cycle"); val nested = java.io.IOException("nested", cycle); cycle.initCause(nested)
        assertEquals(ArtifactPreviewFailure.Kind.LOCAL_STORAGE_UNAVAILABLE, ArtifactLocalOutput.classify(cycle).failure.kind)
    }

    @Test fun realEnospcWriteUsesStorageFullWithoutFillingTheDevice() {
        // A real proxy file returns ENOSPC through libcore; no disk space or special-device access is needed.
        val thread = android.os.HandlerThread("preview-full-disk").apply { start() }
        val storage = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.os.storage.StorageManager::class.java)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        try {
            val descriptor = storage.openProxyFileDescriptor(android.os.ParcelFileDescriptor.MODE_WRITE_ONLY,
                object : android.os.ProxyFileDescriptorCallback() {
                    override fun onGetSize() = 0L
                    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
                        writes.incrementAndGet()
                        throw android.system.ErrnoException("write", android.system.OsConstants.ENOSPC)
                    }
                    override fun onRelease() {}
                }, android.os.Handler(thread.looper))
            val failure = runCatching {
                ArtifactLocalOutput(android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)).use { it.write(byteArrayOf(1)) }
            }.exceptionOrNull()
            assertEquals(1, writes.get())
            assertTrue("Expected typed ENOSPC, got $failure", failure is ArtifactPreviewException)
            val state = ArtifactPreviewFailure.from(checkNotNull(failure))
            assertEquals(ArtifactPreviewFailure.Kind.LOCAL_STORAGE_FULL, state.kind)
            val copy = state.presentation(ArtifactAuthorization.Terminal("w", "s"), false, NativeFeedAvailability.CONNECTED)
            assertEquals("Device storage full", copy.title); assertFalse(copy.retry)
        } finally { thread.quitSafely(); thread.join(3000); assertFalse(thread.isAlive) }
    }

    @Test fun openFailureUsesStorageUnavailableAndSuccessfulWritePreservesBytes() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "storage-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        try {
            val failure = runCatching { ArtifactLocalOutput(directory).close() }.exceptionOrNull()
            assertEquals(ArtifactPreviewFailure.Kind.LOCAL_STORAGE_UNAVAILABLE, ArtifactPreviewFailure.from(checkNotNull(failure)).kind)
            val file = File(directory, "preview")
            val bytes = byteArrayOf(0, 1, -1, 10)
            ArtifactLocalOutput(file).use { it.write(bytes); it.sync() }
            assertArrayEquals(bytes, file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun socketFailureDuringDownloadRemainsNetworkFailureAndCleansPartialBytes() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "storage-test-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        val socketFailure = SocketException("test socket closed")
        val rpc = ArtifactRpc(ArtifactCapabilities(true, true, true, true)) { _, _ -> throw socketFailure }
        val files = ArtifactPreviewFiles(root, ArtifactContentTransfer(rpc, ArtifactAuthorization.Terminal("w", "s")))
        try {
            val failure = runCatching { files.download("/file", ArtifactMetadata(4, ArtifactKind.TEXT, "text/plain")) { _, _ -> } }.exceptionOrNull()
            assertSame(socketFailure, failure)
            assertEquals(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE, ArtifactPreviewFailure.from(checkNotNull(failure)).kind)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { files.close(); root.deleteRecursively() }
    }
}
