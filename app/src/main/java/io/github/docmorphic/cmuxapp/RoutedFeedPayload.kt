package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Unlinked private files carry large Feed data without exceeding Binder's transaction limit. */
internal object RoutedFeedPayload {
    suspend fun write(context: Context, text: String): ParcelFileDescriptor {
        var descriptor: ParcelFileDescriptor? = null
        try {
            return withContext(Dispatchers.IO) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                require(bytes.size <= RoutedAgentFeedWire.MAX_BYTES) { "Feed payload exceeds the transfer limit" }
                val file = File.createTempFile("routed-feed-", ".tmp", context.cacheDir)
                try {
                    file.outputStream().use { it.write(bytes) }
                    currentCoroutineContext().ensureActive()
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    check(file.delete()) { "Could not retire temporary Feed payload" }
                    checkNotNull(descriptor)
                } finally { file.delete() }
            }
        } catch (failure: Throwable) {
            descriptor?.close()
            throw failure
        }
    }
    suspend fun read(descriptor: ParcelFileDescriptor): String {
        try {
            return withContext(Dispatchers.IO) {
                require(descriptor.statSize in 0..RoutedAgentFeedWire.MAX_BYTES.toLong()) { "Invalid Feed payload file" }
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val result = ByteArrayOutputStream(); val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        require(result.size().toLong() + count <= RoutedAgentFeedWire.MAX_BYTES) { "Feed payload exceeds the transfer limit" }
                        result.write(buffer, 0, count)
                    }
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result.toByteArray())).toString()
                }
            }
        } finally { descriptor.close() }
    }
}
