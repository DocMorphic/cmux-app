package io.github.docmorphic.cmuxapp

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** In-process leases cover transfers and prepared files awaiting system presentation. */
internal object ArtifactExportCache {
    private val held = mutableMapOf<File, Int>()
    fun hold(directory: File): AutoCloseable {
        val key = directory.absoluteFile.normalize()
        synchronized(held) { held[key] = (held[key] ?: 0) + 1 }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(held) {
                val count = (held[key] ?: 1) - 1
                if (count == 0) held.remove(key) else held[key] = count
            }
        }
    }
    /** Call on IO. Only old unleased exports are eligible; never Save's durable copies. */
    fun prune(root: File, now: Long = System.currentTimeMillis()) {
        val protected = synchronized(held) { held.keys.toSet() }
        root.listFiles()?.filter { now - it.lastModified() > 3_600_000 && it.absoluteFile.normalize() !in protected }
            ?.forEach { it.deleteRecursively() }
    }
}
