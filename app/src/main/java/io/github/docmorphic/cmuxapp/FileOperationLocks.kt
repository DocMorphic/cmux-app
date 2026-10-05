package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** One never-unlinked inode per private store; range locks do not allocate payload bytes. */
internal class FileOperationLocks(private val root: File) {
    enum class Kind { UI, WRITER, DESTINATION, GRANTS }
    fun claim(kind: Kind, identity: String): AutoCloseable? {
        check(root.isDirectory || root.mkdirs())
        val path = File(root.canonicalFile, ".operation-locks").toPath()
        val hash = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))).long
        // Separate namespaces prevent a nested writer/destination/grant lock from
        // colliding with its UI owner. A same-kind hash collision only serializes work.
        val position = (kind.ordinal.toLong() shl 60) or (hash and ((1L shl 60) - 1))
        synchronized(channels) {
            val holder = channels.getOrPut(path.toString()) { Holder(FileChannel.open(path, CREATE, READ, WRITE, NOFOLLOW_LINKS)) }
            try {
                val lock = try { holder.channel.tryLock(position, 1, false) }
                    catch (_: OverlappingFileLockException) { null }
                if (lock == null) return null
                holder.leases++
                var closed = false
                return AutoCloseable {
                    synchronized(channels) {
                        if (!closed) {
                            closed = true
                            try { lock.release() } finally {
                                holder.leases--
                                if (holder.leases == 0) { channels.remove(path.toString()); holder.channel.close() }
                            }
                        }
                    }
                }
            } finally {
                if (holder.leases == 0) { channels.remove(path.toString()); holder.channel.close() }
            }
        }
    }
    private class Holder(val channel: FileChannel) { var leases = 0 }
    companion object {
        // Closing any channel can release the process's other locks on that inode.
        // All instances therefore share one channel until its final lease closes.
        private val channels = mutableMapOf<String, Holder>()
    }
}
