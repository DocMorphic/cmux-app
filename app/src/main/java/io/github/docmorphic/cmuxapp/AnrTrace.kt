package io.github.docmorphic.cmuxapp

import java.io.*

internal enum class AnrThreadState { RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, NATIVE, SUSPENDED, UNKNOWN }
internal data class AnrThread(val tid: Int, val main: Boolean, val state: AnrThreadState,
    val owner: Int?, val frames: List<CrashFrame>, val nativeFrames: List<NativeCrashFrame>) {
    init { require(tid > 0 && (owner == null || owner > 0) && frames.size + nativeFrames.size <= 32) }
}
internal data class AnrStack(val pid: Int, val threads: List<AnrThread>, val truncated: Boolean) {
    init {
        require(pid > 0 && threads.size in 1..16 && threads.count { it.main } == 1)
        require(threads.map { it.tid }.distinct().size == threads.size)
        require(threads.sumOf { it.frames.size + it.nativeFrames.size } in 1..64)
    }
    fun text() = buildString {
        append("  ANR_STACK pid=$pid truncated=$truncated\n")
        threads.forEach { thread ->
            append("    thread=${thread.tid} main=${thread.main} state=${thread.state}")
            thread.owner?.let { append(" waiting_for_thread=$it") }; append('\n')
            thread.frames.forEach { append("      at ${it.type}#${it.method} line=${it.line}\n") }
            thread.nativeFrames.forEach {
                append("      module=${it.module} rel_pc=0x${it.relativePc.toString(16)}")
                if (it.buildId.isNotEmpty()) append(" build_id=${it.buildId}")
                if (it.symbol.isNotEmpty()) append(" symbol=${it.symbol}")
                append('\n')
            }
        }
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
        out.writeInt(1); out.writeInt(pid); out.writeBoolean(truncated); out.writeInt(threads.size)
        threads.forEach { thread ->
            out.writeInt(thread.tid); out.writeBoolean(thread.main); out.writeInt(thread.state.ordinal)
            out.writeInt(thread.owner ?: 0); out.writeInt(thread.frames.size)
            thread.frames.forEach { out.writeUTF(it.type); out.writeUTF(it.method); out.writeInt(it.line) }
            out.writeInt(thread.nativeFrames.size)
            thread.nativeFrames.forEach { out.writeUTF(it.module); out.writeLong(it.relativePc.toLong()); out.writeUTF(it.buildId); out.writeUTF(it.symbol) }
        }
    } }.toByteArray().also { require(it.size <= 32 * 1024) }
    companion object {
        fun decode(bytes: ByteArray): AnrStack {
            require(bytes.size <= 32 * 1024)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val pid = input.readInt(); val truncated = input.readBoolean()
                val count = input.readInt().also { require(it in 1..16) }
                var budget = 64
                val threads = List(count) {
                    val tid = input.readInt(); val main = input.readBoolean()
                    val state = AnrThreadState.entries[input.readInt()]
                    val owner = input.readInt().also { require(it >= 0) }.takeIf { it > 0 }
                    val size = input.readInt().also { require(it in 0..minOf(32, budget)); budget -= it }
                    val frames = List(size) { CrashFrame(input.readUTF(), input.readUTF(), input.readInt()) }
                    val nativeSize = input.readInt().also { require(it in 0..minOf(32 - size, budget)); budget -= it }
                    val native = List(nativeSize) { NativeCrashFrame(input.readUTF(), input.readLong().toULong(), input.readUTF(), input.readUTF()) }
                    AnrThread(tid, main, state, owner, frames, native)
                }
                require(input.available() == 0)
                AnrStack(pid, threads, truncated)
            }
        }
    }
}

/** Select ART code metadata only; never persist raw traces, names, addresses or source paths. */
internal object AnrTrace {
    private val process = Regex("----- pid ([0-9]+) at .+ -----")
    private val header = Regex("\".*\" (?:daemon )?prio=-?[0-9]+ tid=([0-9]+) ([A-Za-z]+)(?: .*)?")
    private val sysTid = Regex("  \\| sysTid=([0-9]+)(?: .*)?")
    private val managed = Regex("  at ([A-Za-z0-9_$.]+)\\.([A-Za-z0-9_$<>-]+)\\((.*)\\)")
    private val lineNumber = Regex(":([0-9]{1,8})$")
    private val owner = Regex("  - waiting to lock .+ held by thread ([0-9]+)")
    private val native = Regex("  native: #[0-9]+ pc ([0-9a-fA-F]{1,16})  (\\S+)(?: \\((.*?)\\))?(?: \\(BuildId: ([0-9a-fA-F]+)\\))?")
    private data class Candidate(val tid: Int, val state: AnrThreadState, var main: Boolean = false,
        var owner: Int? = null, val frames: MutableList<CrashFrame> = mutableListOf(),
        val native: MutableList<NativeCrashFrame> = mutableListOf())

    fun read(input: InputStream, expectedPid: Int): AnrStack? {
        require(expectedPid > 0)
        val deadline = System.nanoTime() + 2_000_000_000L
        val source = input.buffered(4096)
        var consumed = 0
        fun nextLine(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                require(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "Trace read expired" }
                val b = source.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
                require(++consumed <= 8 * 1024 * 1024) { "Trace too large" }
                if (b == 10) return bytes.toString("UTF-8").removeSuffix("\r")
                require(bytes.size() < 16 * 1024) { "Trace line too large" }
                bytes.write(b)
            }
        }
        var active = false
        var closed = false
        var current: Candidate? = null
        var truncated = false
        val threads = linkedMapOf<Int, Candidate>()
        while (true) {
            val line = nextLine() ?: break
            process.matchEntire(line)?.let { match ->
                if (active) return null // A matching process must have a complete, unambiguous section.
                active = match.groupValues[1].toIntOrNull() == expectedPid
            }
            if (!active) continue
            if (line == "----- end $expectedPid -----") { closed = true; break }
            if (line.startsWith('"')) {
                current = null // Unknown thread formats must not append to the preceding thread.
                val match = header.matchEntire(line) ?: continue
                val tid = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: continue
                require(threads.size < 256 && tid !in threads) { "Invalid thread inventory" }
                val state = when (match.groupValues[2]) {
                    "Runnable" -> AnrThreadState.RUNNABLE
                    "Blocked" -> AnrThreadState.BLOCKED
                    "Waiting" -> AnrThreadState.WAITING
                    "TimedWaiting", "Sleeping" -> AnrThreadState.TIMED_WAITING
                    "Native" -> AnrThreadState.NATIVE
                    "Suspended" -> AnrThreadState.SUSPENDED
                    else -> AnrThreadState.UNKNOWN
                }
                current = Candidate(tid, state).also { threads[tid] = it }
                continue
            }
            val thread = current ?: continue
            sysTid.matchEntire(line)?.let { thread.main = it.groupValues[1].toIntOrNull() == expectedPid }
            owner.matchEntire(line)?.let { thread.owner = it.groupValues[1].toIntOrNull()?.takeIf { id -> id > 0 } }
            val java = managed.matchEntire(line)
            val code = native.matchEntire(line)
            if (java == null && code == null) continue
            if (thread.frames.size + thread.native.size >= 32) { truncated = true; continue }
            if (java != null) {
                val type = CrashSymbols.type(java.groupValues[1])
                val method = if (type == CrashSymbols.OMITTED) CrashSymbols.OMITTED else CrashSymbols.method(java.groupValues[2])
                val location = java.groupValues[3]
                val number = if (location == "Native method") -2 else
                    lineNumber.find(location)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= 10_000_000 } ?: -1
                thread.frames += CrashFrame(type, method, number)
            } else if (code != null) {
                val module = NativeCodeSymbols.module(code.groupValues[2])
                thread.native += NativeCrashFrame(module, code.groupValues[1].toULong(16),
                    NativeCodeSymbols.buildId(code.groupValues[4]),
                    if (module == "other") "" else NativeCodeSymbols.symbol(code.groupValues[3]))
            }
        }
        if (!closed) return null
        val main = threads.values.singleOrNull { it.main } ?: return null
        // Put the main thread and its monitor-owner chain first, including cycles only once.
        val ordered = linkedSetOf<Candidate>()
        var next: Candidate? = main
        while (next != null && ordered.add(next)) next = next.owner?.let(threads::get)
        ordered.addAll(threads.values)
        var budget = 64
        val selected = ordered.take(16).map { thread ->
            val frames = thread.frames.take(budget); budget -= frames.size
            val nativeFrames = thread.native.take(budget); budget -= nativeFrames.size
            if (frames.size + nativeFrames.size < thread.frames.size + thread.native.size) truncated = true
            AnrThread(thread.tid, thread.main, thread.state, thread.owner, frames, nativeFrames)
        }
        if (selected.sumOf { it.frames.size + it.nativeFrames.size } == 0) return null
        return AnrStack(expectedPid, selected, truncated || ordered.size > selected.size)
    }
}
