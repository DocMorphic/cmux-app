package io.github.docmorphic.cmuxapp

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Reference-counted local leases also exclude cleanup in the other Android process. */
internal object ArtifactExportCache {
    private class Held(val lease: AutoCloseable, var count: Int = 1)
    private val held = mutableMapOf<File, Held>()
    /** Call on IO before publishing or starting an export directory. */
    fun hold(directory: File): AutoCloseable {
        val key = File(checkNotNull(directory.parentFile).canonicalFile, directory.name)
        synchronized(held) {
            val previous = held[key]
            if (previous != null) previous.count++
            else held[key] = Held(checkNotNull(FileOperationLocks(key.parentFile!!)
                .claim(FileOperationLocks.Kind.UI, key.name)) { "This file export is already owned." })
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(held) {
                val value = checkNotNull(held[key])
                if (--value.count == 0) { held.remove(key); value.lease.close() }
            }
        }
    }
    /** Call on IO. Only old unleased exports are eligible; never Save's durable copies. */
    fun prune(root: File, now: Long = System.currentTimeMillis()) {
        for (directory in root.listFiles().orEmpty().filter { it.isDirectory }) {
            val time = directory.lastModified()
            if (time <= 0 || time > now || now - time <= 3_600_000) continue
            val lease = FileOperationLocks(root).claim(FileOperationLocks.Kind.UI, directory.name) ?: continue
            lease.use {
                val latest = directory.lastModified()
                if (latest > 0 && latest <= now && now - latest > 3_600_000) PrivateFileReclamation.remove(directory)
            }
        }
    }
}
