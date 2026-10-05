package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Private app storage only. Walks never follow symbolic links or trust journal paths. */
internal object PrivateFileReclamation {
    fun id(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    /** Invalid metadata is disposable after its grace period; I/O failures are not corruption. */
    fun <T> journal(read: () -> T?): T? = try { read() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { null }
        catch (_: IllegalStateException) { null }
        catch (_: org.json.JSONException) { null }
    fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    fun old(file: File, now: Long, age: Long): Boolean {
        if (!exists(file)) return true
        return runCatching {
            var old = true
            Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                private fun check(attrs: BasicFileAttributes): FileVisitResult {
                    val time = attrs.lastModifiedTime().toMillis()
                    if (time <= 0 || time > now || now - time < age) old = false
                    return if (old) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                }
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = check(attrs)
                override fun visitFile(path: Path, attrs: BasicFileAttributes) = check(attrs)
            })
            old
        }.getOrDefault(false)
    }
    fun remove(file: File) {
        if (!exists(file)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
