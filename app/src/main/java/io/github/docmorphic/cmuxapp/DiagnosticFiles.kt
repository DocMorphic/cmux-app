package io.github.docmorphic.cmuxapp

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.withLock

internal enum class DiagnosticRole { APP, BROWSER }
internal data class DiagnosticRecord(val operation: DebugOperation, val outcome: DebugOutcome,
    val role: DiagnosticRole, val wallMillis: Long, val elapsedNanos: Long, val boot: Int,
    val durationMillis: Long = 0, val id: Long = 0, val count: Long = 1)
internal data class DiagnosticPolicy(val generationBytes: Long = 5_000_000, val retainedBytes: Long = 12_000_000,
    val archives: Int = 3) {
    init { require(generationBytes >= 256 && retainedBytes >= generationBytes && archives in 0..3) }
}

/** Shared by both app processes. No open log handle survives an operation or rotation. */
internal class DiagnosticFiles(private val root: File, private val exports: File, private val build: String,
    private val policy: DiagnosticPolicy = DiagnosticPolicy(), private val debugVerbose: Boolean = false) {
    val crashes = DiagnosticCrashFiles(File(root, "crashes"))
    private data class State(val verbose: Boolean = false, val boot: Int = -1, val clearedThrough: Long = -1,
        val exitCutoff: Long = -1, val exits: List<DiagnosticExit> = emptyList())
    private val names = listOf("cmux-app.log", "cmux-network.log")
    private fun state(): State {
        val file = File(root, "state")
        if (!file.exists()) return State()
        val value = Properties().apply { file.inputStream().use(::load) }
        return State(value.getProperty("verbose")?.toBooleanStrictOrNull() ?: throw IOException("Invalid diagnostic state"),
            value.getProperty("boot")?.toIntOrNull() ?: throw IOException("Invalid diagnostic state"),
            value.getProperty("cutoff")?.toLongOrNull() ?: throw IOException("Invalid diagnostic state"),
            value.getProperty("exitCutoff", "-1").toLong(),
            value.getProperty("exits", "").split(';').filter(String::isNotEmpty).map(DiagnosticExit::decode)
                .also { require(it.size <= 64) })
    }
    private fun save(value: State) {
        val temporary = File(root, "state.partial")
        temporary.outputStream().use { output ->
            Properties().apply { setProperty("verbose", value.verbose.toString()); setProperty("boot", value.boot.toString());
                setProperty("cutoff", value.clearedThrough.toString()); setProperty("exitCutoff", value.exitCutoff.toString())
                setProperty("exits", value.exits.joinToString(";") { it.encode() }) }.store(output, null)
            output.fd.sync()
        }
        Files.move(temporary.toPath(), File(root, "state").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun <T> locked(checkCurrent: () -> Unit = {}, action: () -> T): T {
        check(root.mkdirs() || root.isDirectory) { "Diagnostic storage unavailable" }
        return locks.computeIfAbsent(root.absolutePath) { ReentrantLock() }.withLock {
            RandomAccessFile(File(root, "lock"), "rw").channel.use { channel ->
                val deadline = System.nanoTime() + 15_000_000_000L
                var lock = channel.tryLock()
                while (lock == null) {
                    checkCurrent()
                    check(System.nanoTime() < deadline) { "Diagnostic storage busy" }
                    Thread.sleep(10); lock = channel.tryLock()
                }
                lock.use { checkCurrent(); action() }
            }
        }
    }
    fun verbose(): Boolean = locked { state().verbose }
    fun clearCutoff(boot: Int): Long = locked { state().let { if (it.boot == boot) it.clearedThrough else -1 } }
    fun setVerbose(enabled: Boolean) = locked {
        // Probe both destinations before reporting that recording is enabled.
        if (enabled) names.forEach { java.io.FileOutputStream(file(it), true).use { stream -> stream.fd.sync() } }
        save(state().copy(verbose = enabled))
    }
    fun recoverExits(exits: List<DiagnosticExit>) = locked {
        val current = state()
        crashes.retained(current.boot, current.clearedThrough)
        // The shared, atomic snapshot avoids append/checkpoint crash windows and duplicate imports.
        val merged = (current.exits + exits.filter { it.timestamp > current.exitCutoff })
            .associateBy { it.key }.values.sortedWith(compareBy<DiagnosticExit> { it.timestamp }.thenBy { it.pid })
            .takeLast(64)
        if (merged != current.exits) save(current.copy(exits = merged))
    }
    private fun file(name: String) = File(root, name)
    private fun generations(name: String) = (policy.archives downTo 1).map { file("$name.$it") }.filter(File::exists) + listOf(file(name)).filter(File::exists)
    private fun append(name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val active = file(name)
        if (active.exists() && active.length() + bytes.size > policy.generationBytes) {
            if (policy.archives == 0) check(active.delete())
            else {
                val oldest = file("$name.${policy.archives}")
                check(!oldest.exists() || oldest.delete())
                for (index in policy.archives - 1 downTo 1) {
                    val previous = file("$name.$index")
                    if (previous.exists()) check(previous.renameTo(file("$name.${index + 1}")))
                }
                check(active.renameTo(file("$name.1")))
            }
        }
        active.appendBytes(bytes)
        var total = generations(name).sumOf(File::length)
        for (old in generations(name).filter { it != active }) {
            if (total <= policy.retainedBytes) break
            val size = old.length(); check(old.delete()); total -= size
        }
    }
    fun record(records: List<DiagnosticRecord>) = locked {
        val settings = state()
        // A clear in either process rejects pre-clear records still queued in the other.
        val admitted = records.filter { (it.boot != settings.boot || it.elapsedNanos > settings.clearedThrough) &&
            (debugVerbose || settings.verbose || it.outcome != DebugOutcome.STARTED) }
        var index = 0
        while (index < admitted.size) {
            val first = admitted[index++]
            var count = first.count; var duration = first.durationMillis
            while (index < admitted.size && admitted[index].let { it.operation == first.operation && it.outcome == first.outcome && it.role == first.role && it.boot == first.boot }) {
                val next = admitted[index++]; count += next.count; duration = maxOf(duration, next.durationMillis)
            }
            val stamp = if (first.operation == DebugOperation.APP_START) " build=$build" else ""
            val text = "${Instant.ofEpochMilli(first.wallMillis)} ${first.role} ${first.operation} ${first.outcome} id=${first.id} max_ms=$duration count=$count$stamp\n"
            val both = first.operation in setOf(DebugOperation.APP_START, DebugOperation.APP_FOREGROUND, DebugOperation.APP_BACKGROUND, DebugOperation.LOG_DROPPED)
            val network = first.operation in setOf(DebugOperation.RPC_CONNECT, DebugOperation.RPC_DISCONNECT, DebugOperation.RPC_HOST,
                DebugOperation.SSH_CONNECT, DebugOperation.SSH_DISCONNECT, DebugOperation.SSH_IO)
            if (!network || both) append(names[0], text)
            if (network || both) append(names[1], text)
        }
    }
    fun clear(boot: Int, cutoff: Long, wallMillis: Long = System.currentTimeMillis()) = locked {
        // Persist the barrier first, including when deletion subsequently fails.
        val current = state()
        save(current.copy(boot = boot, clearedThrough = cutoff, exitCutoff = maxOf(current.exitCutoff, wallMillis), exits = emptyList()))
        crashes.retained(boot, cutoff)
        var success = true
        names.flatMap(::generations).forEach { if (!it.delete()) success = false }
        exports.listFiles()?.filter { it.name.startsWith("cmux-diagnostics-") }?.forEach { if (!it.delete()) success = false }
        check(success) { "Could not clear every diagnostic file" }
    }
    fun export(checkCurrent: () -> Unit = {}): File = locked(checkCurrent) {
        check(exports.mkdirs() || exports.isDirectory)
        exports.listFiles()?.filter { it.name.startsWith("cmux-diagnostics-") && it.name.endsWith(".partial") }
            ?.forEach { check(it.delete()) { "Could not remove an incomplete log export" } }
        val target = File(exports, "cmux-diagnostics-${UUID.randomUUID()}.zip")
        val partial = File(exports, "${target.name}.partial")
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                names.zip(listOf("app-events.log", "networking.log")).forEach { (source, member) ->
                    checkCurrent(); zip.putNextEntry(ZipEntry("cmux-diagnostics/$member"))
                    zip.write("cmux Android diagnostics · $build\nExported ${Instant.now()}\nTerminal contents and credentials are not recorded.\n\n".toByteArray())
                    generations(source).forEach { file -> file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { checkCurrent(); val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n) }
                    } }
                    if (source == names[0]) {
                        val current = state()
                        zip.write("\nLocal Java/Kotlin crashes (at most 32; code symbols only; no messages or thread names):\n".toByteArray())
                        crashes.retained(current.boot, current.clearedThrough).forEach {
                            checkCurrent(); zip.write(it.text().toByteArray())
                        }
                        zip.write("\nPrevious process failures (Android 11+ system history; at most 64; no stack traces):\n".toByteArray())
                        state().exits.forEach { checkCurrent(); zip.write(it.line().toByteArray()) }
                    }
                    zip.closeEntry()
                }
            }
            checkCurrent()
            check(partial.renameTo(target)) { "Could not finish log export" }
            // The receiving app may read after the chooser closes. Keep bounded snapshots.
            exports.listFiles()?.filter { it != target && it.name.startsWith("cmux-diagnostics-") && it.extension == "zip" }
                ?.sortedByDescending(File::lastModified)?.forEachIndexed { index, old ->
                    if (index >= 2 || System.currentTimeMillis() - old.lastModified() > 86_400_000) check(old.delete()) { "Could not remove an old log export" }
                }
            target
        } catch (failure: Throwable) { partial.delete(); target.delete(); throw failure }
    }
    companion object { private val locks = ConcurrentHashMap<String, ReentrantLock>() }
}
