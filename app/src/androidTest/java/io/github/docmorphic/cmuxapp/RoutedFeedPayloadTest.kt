package io.github.docmorphic.cmuxapp

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoutedFeedPayloadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun largePayloadRoundTripsAfterUnlinkAndClosesItsDescriptor() = runBlocking {
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        val text = "Feed 🙂\n".repeat(200_000)
        val descriptor = RoutedFeedPayload.write(context, text)
        assertTrue(descriptor.statSize > 1_048_576)
        assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
        assertEquals(text, RoutedFeedPayload.read(descriptor))
        assertFalse(descriptor.fileDescriptor.valid())
    }
    @Test fun cancelledReadClosesDescriptorAndRejectsPipes() = runBlocking {
        val descriptor = RoutedFeedPayload.write(context, "unused")
        val cancelled = Job().also { it.cancel() }
        // Enter the helper with an already cancelled context to cover cancellation before IO dispatch.
        val scope = CoroutineScope(coroutineContext + cancelled)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { RoutedFeedPayload.read(descriptor) }.join()
        assertFalse(descriptor.fileDescriptor.valid())
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            try { RoutedFeedPayload.read(pipe[0]); fail("Pipe accepted") } catch (_: IllegalArgumentException) { }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally { pipe.forEach { it.close() } }
    }
}
