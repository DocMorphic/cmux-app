package io.github.docmorphic.cmuxapp

import java.io.*

internal enum class NativeArchitecture { ARM32, ARM64, X86, X86_64, RISCV64, NONE, UNKNOWN }

internal object NativeCodeSymbols {
    private val modules = setOf("libandroidx.graphics.path.so", "libcmux_ghostty.so", "libcmux_video.so",
        "libdatastore_shared_counter.so", "libiroh_ffi.so", "libjnidispatch.so", "libc.so", "libart.so",
        "libandroid_runtime.so", "libutils.so", "libbase.so", "liblog.so", "libbinder.so", "libc++.so",
        "libdl.so", "libm.so", "libz.so", "libnativehelper.so", "libhwui.so", "libEGL.so", "libGLESv2.so",
        "libwebviewchromium.so", "libmonochrome.so")
    private val symbol = Regex("[A-Za-z_$][A-Za-z0-9_$:.<>(), *&~+\\-]{0,159}")
    private val id = Regex("[a-fA-F0-9]{8,128}")
    // AOSP MapInfo::GetFullName appends !soname for libraries mapped inside APKs.
    fun module(path: String): String = path.substringAfterLast('!').substringAfterLast('/')
        .takeIf { it in modules } ?: "other"
    fun symbol(value: String): String = value.takeIf(symbol::matches).orEmpty()
    fun buildId(value: String): String = value.takeIf(id::matches)?.lowercase().orEmpty()
}

internal data class NativeCrashFrame(val module: String, val relativePc: ULong, val buildId: String, val symbol: String) {
    init {
        require(module == NativeCodeSymbols.module(module))
        require(buildId == NativeCodeSymbols.buildId(buildId) && symbol == NativeCodeSymbols.symbol(symbol))
        require(module != "other" || symbol.isEmpty())
    }
}
internal data class NativeCrashStack(val pid: Int, val tid: Int, val architecture: NativeArchitecture,
    val frames: List<NativeCrashFrame>, val truncated: Boolean) {
    init { require(pid > 0 && tid > 0 && frames.size in 1..64) }
    fun text() = buildString {
        append("  NATIVE_STACK tid=$tid arch=$architecture truncated=$truncated\n")
        frames.forEach { frame ->
            append("    module=${frame.module} rel_pc=0x${frame.relativePc.toString(16)}")
            if (frame.buildId.isNotEmpty()) append(" build_id=${frame.buildId}")
            if (frame.symbol.isNotEmpty()) append(" symbol=${frame.symbol}")
            append('\n')
        }
    }
    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
        out.writeInt(1); out.writeInt(pid); out.writeInt(tid); out.writeInt(architecture.ordinal)
        out.writeBoolean(truncated); out.writeInt(frames.size)
        frames.forEach { out.writeUTF(it.module); out.writeLong(it.relativePc.toLong()); out.writeUTF(it.buildId); out.writeUTF(it.symbol) }
    } }.toByteArray().also { require(it.size <= 32 * 1024) }
    companion object {
        fun decode(bytes: ByteArray): NativeCrashStack {
            require(bytes.size <= 32 * 1024)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1)
                val pid = input.readInt(); val tid = input.readInt(); val arch = NativeArchitecture.entries[input.readInt()]
                val truncated = input.readBoolean(); val count = input.readInt().also { require(it in 1..64) }
                val frames = List(count) { NativeCrashFrame(input.readUTF(), input.readLong().toULong(), input.readUTF(), input.readUTF()) }
                require(input.available() == 0)
                NativeCrashStack(pid, tid, arch, frames, truncated)
            }
        }
    }
}

/** Bounded selective proto3 reader; raw tombstones are never retained or exported.
 * AOSP tombstone.proto blob 9deeeec9e185f79747acf5fb6a7e71586eb7da16.
 * Only Tombstone.pid/tid/arch, threads, and code metadata in current_backtrace are decoded. */
internal object NativeTombstone {
    private const val MAX_BYTES = 8 * 1024 * 1024L
    private class Source(val input: InputStream) {
        var position = 0L
        var fields = 0
        val discard = ByteArray(4096)
        val deadline = System.nanoTime() + 2_000_000_000L
        fun check() { require(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "Trace read expired" } }
        fun read(): Int {
            check(); val value = input.read()
            if (value >= 0) { position++; require(position <= MAX_BYTES) { "Trace too large" } }
            return value
        }
    }
    private data class Tag(val field: Int, val wire: Int)
    private class Cursor(val source: Source, val end: Long? = null) {
        fun byte(): Int {
            require(end == null || source.position < end)
            return source.read().also { require(it >= 0) { "Truncated trace" } }
        }
        fun number(first: Int = byte()): ULong {
            var value = 0uL; var next = first
            for (index in 0..9) {
                if (index == 9) require(next <= 1) { "Invalid varint" }
                value = value or ((next and 127).toULong() shl (index * 7))
                if (next and 128 == 0) return value
                next = byte()
            }
            error("Invalid varint")
        }
        fun tag(): Tag? {
            if (end != null && source.position == end) return null
            val first = if (end == null) source.read() else byte()
            if (first < 0) return null
            val raw = number(first)
            require(raw in 1uL..0xffff_ffffuL && ++source.fields <= 200_000)
            val field = (raw shr 3).toInt(); val wire = (raw and 7uL).toInt()
            require(field > 0 && (wire == 0 || wire == 1 || wire == 2 || wire == 5)) { "Invalid protobuf tag" }
            return Tag(field, wire)
        }
        fun scalar(tag: Tag): ULong { require(tag.wire == 0); return number() }
        fun message(tag: Tag): Cursor {
            require(tag.wire == 2)
            val size = number().also { require(it <= MAX_BYTES.toULong()) }.toLong()
            val limit = source.position + size
            require(limit <= MAX_BYTES && (end == null || limit <= end))
            return Cursor(source, limit)
        }
        fun skipBytes(size: Long) {
            require(size >= 0 && source.position + size <= MAX_BYTES && (end == null || source.position + size <= end))
            var left = size
            while (left > 0) {
                source.check()
                // InputStream.skip may seek beyond EOF. Consume bounded scratch
                // bytes so a truncated unknown field cannot look like a valid trace.
                val count = source.input.read(source.discard, 0, minOf(left, source.discard.size.toLong()).toInt())
                require(count >= 0) { "Truncated trace" }
                if (count == 0) { byte(); left-- } else {
                    source.position += count; left -= count
                    source.discard.fill(0, 0, count)
                }
            }
        }
        fun string(tag: Tag, maximum: Int): String {
            val value = message(tag); val size = (value.end!! - source.position).toInt()
            if (size > maximum) { value.skipBytes(size.toLong()); return "" }
            return ByteArray(size) { value.byte().toByte() }.toString(Charsets.UTF_8)
        }
        fun skip(tag: Tag) { when (tag.wire) {
            0 -> number()
            1 -> skipBytes(8)
            2 -> message(tag).let { it.skipBytes(it.end!! - source.position) }
            5 -> skipBytes(4)
        } }
    }
    private data class StackThread(val id: Int?, val frames: List<NativeCrashFrame>, val truncated: Boolean)
    private fun positive(value: ULong): Int { require(value in 1uL..Int.MAX_VALUE.toULong()); return value.toInt() }
    private fun frame(input: Cursor): NativeCrashFrame {
        var pc = 0uL; var path = ""; var symbol = ""; var id = ""
        while (true) {
            val tag = input.tag() ?: break
            when (tag.field) {
                1 -> pc = input.scalar(tag)
                4 -> symbol = input.string(tag, 512)
                6 -> path = input.string(tag, 2048)
                8 -> id = input.string(tag, 128)
                else -> input.skip(tag)
            }
        }
        val module = NativeCodeSymbols.module(path)
        return NativeCrashFrame(module, pc, NativeCodeSymbols.buildId(id), if (module == "other") "" else NativeCodeSymbols.symbol(symbol))
    }
    private fun thread(input: Cursor): StackThread {
        var id: Int? = null; val frames = mutableListOf<NativeCrashFrame>(); var truncated = false
        while (true) {
            val tag = input.tag() ?: break
            when (tag.field) {
                1 -> id = input.scalar(tag).let { if (it == 0uL) null else positive(it) }
                4 -> if (frames.size < 64) frames += frame(input.message(tag)) else { truncated = true; input.skip(tag) }
                else -> input.skip(tag)
            }
        }
        return StackThread(id, frames, truncated)
    }
    fun read(input: InputStream, expectedPid: Int): NativeCrashStack? {
        require(expectedPid > 0)
        val root = Cursor(Source(input.buffered(16 * 1024)))
        var pid: Int? = null; var tid: Int? = null; var arch = NativeArchitecture.ARM32
        val threads = mutableMapOf<Int, StackThread>(); var entries = 0
        while (true) {
            val tag = root.tag() ?: break
            when (tag.field) {
                1 -> arch = root.scalar(tag).let { NativeArchitecture.entries[if (it < 6uL) it.toInt() else 6] }
                5 -> pid = positive(root.scalar(tag))
                6 -> tid = positive(root.scalar(tag))
                16 -> {
                    require(++entries <= 256) { "Too many threads" }
                    val entry = root.message(tag); var key: Int? = null; var value: StackThread? = null
                    while (true) { val field = entry.tag() ?: break
                        when (field.field) { 1 -> key = positive(entry.scalar(field)); 2 -> value = thread(entry.message(field)); else -> entry.skip(field) }
                    }
                    require(key != null && value != null && (value.id == null || value.id == key))
                    threads[key] = value
                }
                else -> root.skip(tag)
            }
        }
        require(pid == expectedPid) { "Trace identity mismatch" }
        val selected = threads[tid] ?: return null
        if (selected.frames.isEmpty()) return null
        return NativeCrashStack(expectedPid, checkNotNull(tid), arch, selected.frames, selected.truncated)
    }
}
