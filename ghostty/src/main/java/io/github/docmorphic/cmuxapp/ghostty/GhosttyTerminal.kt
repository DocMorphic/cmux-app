package io.github.docmorphic.cmuxapp.ghostty

/** Owns native state. Callers must close it on replay replacement and disposal. */
class GhosttyTerminal(columns: Int, rows: Int, scrollbackBytes: Int = 16 * 1024 * 1024) : AutoCloseable {
    private var handle: Long

    init {
        require(columns in 2..1000 && rows in 2..1000)
        // The pinned C header says lines, but Screen.init implements a byte
        // budget rounded to storage pages. Keep the binding's units explicit.
        require(scrollbackBytes in 0..64 * 1024 * 1024)
        handle = nativeCreate(columns, rows, scrollbackBytes)
        check(handle != 0L) { "Could not create Ghostty terminal" }
    }

    @Synchronized fun append(bytes: ByteArray) {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(bytes.size <= 2 * 1024 * 1024) { "Terminal byte chunk is too large" }
        nativeAppend(handle, bytes)
    }

    @Synchronized fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(columns in 2..1000 && rows in 2..1000 && cellWidth in 1..4096 && cellHeight in 1..4096)
        nativeResize(handle, columns, rows, cellWidth, cellHeight)
    }

    /** Copies all values; no borrowed native pointer survives this call. */
    @Synchronized fun snapshot(scrollOffset: Int = 0): GhosttyFrame {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(scrollOffset >= 0)
        return GhosttyFrame.decode(nativeSnapshot(handle, scrollOffset))
    }

    @Synchronized override fun close() {
        val owned = handle
        handle = 0L
        if (owned != 0L) nativeDestroy(owned)
    }

    internal fun activeHandlesForTest(): Int = nativeActiveHandles()
    private external fun nativeCreate(columns: Int, rows: Int, scrollbackBytes: Int): Long
    private external fun nativeAppend(handle: Long, bytes: ByteArray)
    private external fun nativeResize(handle: Long, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int)
    private external fun nativeSnapshot(handle: Long, scrollOffset: Int): ByteArray
    private external fun nativeDestroy(handle: Long)
    private external fun nativeActiveHandles(): Int

    companion object {
        init { System.loadLibrary("cmux_ghostty") }
    }
}
