package io.github.docmorphic.cmuxapp

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Code metadata only. Never call Throwable.toString/printStackTrace or read its message. */
internal object CrashSymbols {
    const val OMITTED = "omitted"
    private val prefixes = listOf("io.github.docmorphic.cmuxapp.", "android.", "androidx.", "java.",
        "javax.", "kotlin.", "kotlinx.", "com.jcraft.jsch.", "org.json.", "com.android.")
    private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,199}")
    private val methodPattern = Regex("[A-Za-z_$][A-Za-z0-9_$-]{0,159}|<init>|<clinit>")
    fun type(value: String) = value.takeIf { prefixes.any(value::startsWith) && classPattern.matches(value) } ?: OMITTED
    fun method(value: String) = value.takeIf(methodPattern::matches) ?: OMITTED
}

internal data class CrashFrame(val type: String, val method: String, val line: Int) {
    init {
        require(type == CrashSymbols.type(type) && method == CrashSymbols.method(method))
        require(type != CrashSymbols.OMITTED || method == CrashSymbols.OMITTED)
        require(line in -2..10_000_000)
    }
}
internal data class CrashCause(val type: String, val frames: List<CrashFrame>) {
    init { require(type == CrashSymbols.type(type) && frames.size <= 64) }
}
internal data class DiagnosticCrash(val role: DiagnosticRole, val boot: Int, val elapsed: Long,
    val wall: Long, val pid: Int, val version: Long, val causes: List<CrashCause>, val truncated: Boolean) {
    init {
        require(boot >= 0 && elapsed >= 0 && wall >= 0 && pid > 0 && version > 0)
        require(causes.size in 1..4 && causes.sumOf { it.frames.size } <= 64)
    }
    fun admitted(clearBoot: Int, cutoff: Long) = boot > clearBoot || (boot == clearBoot && elapsed > cutoff)
    fun text() = buildString {
        append("${Instant.ofEpochMilli(wall)} $role JAVA_STACK pid=$pid version=$version truncated=$truncated\n")
        causes.forEachIndexed { index, cause ->
            append("  cause=$index type=${cause.type}\n")
            cause.frames.forEach { append("    at ${it.type}#${it.method} line=${it.line}\n") }
        }
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(0x434d4352); out.writeInt(1); out.writeInt(role.ordinal)
            out.writeInt(boot); out.writeLong(elapsed); out.writeLong(wall); out.writeInt(pid); out.writeLong(version)
            out.writeBoolean(truncated); out.writeInt(causes.size)
            causes.forEach { cause ->
                out.writeUTF(cause.type); out.writeInt(cause.frames.size)
                cause.frames.forEach { out.writeUTF(it.type); out.writeUTF(it.method); out.writeInt(it.line) }
            }
        }
    }.toByteArray().also { require(it.size <= MAX_BYTES) }
    companion object {
        const val MAX_BYTES = 32 * 1024
        fun capture(error: Throwable, role: DiagnosticRole, boot: Int, elapsed: Long, wall: Long, pid: Int, version: Long): DiagnosticCrash {
            val seen = IdentityHashMap<Throwable, Boolean>()
            val causes = mutableListOf<CrashCause>()
            var next: Throwable? = error
            var budget = 64
            var truncated = false
            while (next != null && causes.size < 4 && seen.put(next, true) == null) {
                val current = next
                val stack = current.stackTrace
                val frames = stack.take(budget).map { frame ->
                    val type = CrashSymbols.type(frame.className)
                    CrashFrame(type, if (type == CrashSymbols.OMITTED) CrashSymbols.OMITTED else CrashSymbols.method(frame.methodName),
                        frame.lineNumber.coerceIn(-2, 10_000_000))
                }
                if (stack.size > budget) truncated = true
                budget -= frames.size
                causes += CrashCause(CrashSymbols.type(current.javaClass.name), frames)
                next = current.cause
            }
            return DiagnosticCrash(role, boot, elapsed, wall, pid, version, causes, truncated || next != null)
        }
        fun decode(bytes: ByteArray): DiagnosticCrash {
            require(bytes.size <= MAX_BYTES)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 0x434d4352 && input.readInt() == 1)
                val role = DiagnosticRole.entries[input.readInt()]
                val boot = input.readInt(); val elapsed = input.readLong(); val wall = input.readLong()
                val pid = input.readInt(); val version = input.readLong(); val truncated = input.readBoolean()
                val count = input.readInt().also { require(it in 1..4) }
                var budget = 64
                val causes = List(count) {
                    val type = input.readUTF()
                    val size = input.readInt().also { require(it in 0..budget); budget -= it }
                    CrashCause(type, List(size) { CrashFrame(input.readUTF(), input.readUTF(), input.readInt()) })
                }
                require(input.available() == 0)
                DiagnosticCrash(role, boot, elapsed, wall, pid, version, causes, truncated)
            }
        }
    }
}

/** Crash writes bypass the normal queue and locks. Published files are complete or absent. */
internal class DiagnosticCrashFiles(private val root: File) {
    fun prepare() { check(root.mkdirs() || root.isDirectory) }
    fun write(record: DiagnosticCrash, slot: String) {
        require(Regex("[A-Z]+-[0-9]+-[0-9]+").matches(slot))
        val partial = File(root, "crash-$slot.partial")
        try {
            FileOutputStream(partial).use { out -> out.write(record.encode()); out.fd.sync() }
            Files.move(partial.toPath(), File(root, "crash-$slot.bin").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }
    /** Called under DiagnosticFiles' ordinary cross-process lock, never by a crash handler. */
    fun retained(clearBoot: Int, cutoff: Long): List<DiagnosticCrash> {
        val records = mutableListOf<Pair<File, DiagnosticCrash>>()
        root.listFiles()?.filter { it.name.startsWith("crash-") && it.extension == "bin" }?.forEach { file ->
            val record = runCatching {
                require(file.length() in 1..DiagnosticCrash.MAX_BYTES.toLong())
                // Bound the actual read as well as the stat, even if storage changes between them.
                file.inputStream().use { input ->
                    val bytes = ByteArray(DiagnosticCrash.MAX_BYTES + 1)
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count < 0) break
                        size += count
                    }
                    DiagnosticCrash.decode(bytes.copyOf(size))
                }
            }.getOrNull()
            if (record != null && record.admitted(clearBoot, cutoff)) records += file to record
            else check(file.delete()) { "Could not remove an obsolete crash record" }
        }
        val ordered = records.sortedWith(compareBy<Pair<File, DiagnosticCrash>> { it.second.boot }
            .thenBy { it.second.elapsed }.thenBy { it.second.pid })
        ordered.dropLast(32).forEach { check(it.first.delete()) { "Could not prune crash records" } }
        // An interrupted writer can leave a partial file. Never export it or delete a fresh writer's file.
        root.listFiles()?.filter { it.name.startsWith("crash-") && it.extension == "partial" &&
            System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach {
            check(it.delete()) { "Could not remove an abandoned crash record" }
        }
        return ordered.takeLast(32).map { it.second }
    }
}

internal class DiagnosticCrashHandler(private val previous: Thread.UncaughtExceptionHandler,
    private val capture: (Throwable) -> Unit) : Thread.UncaughtExceptionHandler {
    private val recording = AtomicBoolean(false)
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { if (recording.compareAndSet(false, true)) capture(error) }
        catch (_: Throwable) { /* A logging failure must never replace Android's crash handling. */ }
        finally { previous.uncaughtException(thread, error) }
    }
}
