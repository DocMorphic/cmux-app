package io.github.docmorphic.cmuxapp.ghostty

/** Owns native state. Callers must close it on replay replacement and disposal. */
class GhosttyTerminal(columns: Int, rows: Int, scrollbackBytes: Int = 16 * 1024 * 1024, replyToQueries: Boolean = false) : AutoCloseable {
    private var handle: Long
    private var imageCache: Map<Long, GhosttyGraphicsFrame.Image> = emptyMap()

    init {
        require(columns in 1..1000 && rows in 1..1000)
        // The pinned C header says lines, but Screen.init implements a byte
        // budget rounded to storage pages. Keep the binding's units explicit.
        require(scrollbackBytes in 0..64 * 1024 * 1024)
        handle = nativeCreate(columns, rows, scrollbackBytes, replyToQueries)
        check(handle != 0L) { "Could not create Ghostty terminal" }
    }

    /** Returned protocol replies must go only to this terminal's owning PTY.
     * Default mirror mode returns no replies. No clipboard/effect callbacks run. */
    @Synchronized fun append(bytes: ByteArray): ByteArray {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(bytes.size <= 2 * 1024 * 1024) { "Terminal byte chunk is too large" }
        return nativeAppend(handle, bytes) ?: EMPTY
    }

    @Synchronized fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): ByteArray {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(columns in 1..1000 && rows in 1..1000 && cellWidth in 1..4096 && cellHeight in 1..4096)
        return nativeResize(handle, columns, rows, cellWidth, cellHeight) ?: EMPTY
    }

    @Synchronized fun inputModes(): GhosttyInputModes {
        check(handle != 0L) { "Ghostty terminal is closed" }
        val modes = nativeInputModes(handle)
        return GhosttyInputModes(modes and 1 != 0, modes and 2 != 0, modes and 4 != 0)
    }

    /** Explicit user input only; does not enable query replies for mirrors.
     * Coordinates are zero-based cells; Ghostty applies the negotiated format. */
    @Synchronized fun mouse(action: Int, button: Int, column: Int, row: Int): ByteArray {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(action in 0..2 && button in 1..11 && column in 0..999 && row in 0..999)
        return nativeMouse(handle, action, button, column, row) ?: EMPTY
    }

    /** Copies all values; no borrowed native pointer survives this call. */
    @Synchronized fun snapshot(scrollOffset: Int = 0): GhosttyFrame {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(scrollOffset >= 0)
        return GhosttyFrame.decode(nativeSnapshot(handle, scrollOffset))
    }

    /** Owned pixels and geometry; reading history does not move the live viewport. */
    @Synchronized fun graphicsSnapshot(scrollOffset: Int = 0): GhosttyGraphicsFrame {
        check(handle != 0L) { "Ghostty terminal is closed" }
        require(scrollOffset >= 0)
        val known = imageCache.values.map { it.generation }.toLongArray()
        val frame = GhosttyGraphicsFrame.decode(nativeGraphicsSnapshot(handle, scrollOffset, known), imageCache)
        imageCache = frame.images // Deleted images and previous screens are released.
        return frame
    }

    @Synchronized override fun close() {
        val owned = handle
        handle = 0L
        imageCache = emptyMap()
        if (owned != 0L) nativeDestroy(owned)
    }

    internal fun activeHandlesForTest(): Int = nativeActiveHandles()
    private external fun nativeCreate(columns: Int, rows: Int, scrollbackBytes: Int, replyToQueries: Boolean): Long
    private external fun nativeAppend(handle: Long, bytes: ByteArray): ByteArray?
    private external fun nativeResize(handle: Long, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): ByteArray?
    private external fun nativeInputModes(handle: Long): Int
    private external fun nativeMouse(handle: Long, action: Int, button: Int, column: Int, row: Int): ByteArray?
    private external fun nativeSnapshot(handle: Long, scrollOffset: Int): ByteArray
    private external fun nativeGraphicsSnapshot(handle: Long, scrollOffset: Int, cachedGenerations: LongArray): ByteArray
    private external fun nativeDestroy(handle: Long)
    private external fun nativeActiveHandles(): Int

    companion object {
        private val EMPTY = byteArrayOf()
        init { System.loadLibrary("cmux_ghostty") }
    }
}

data class GhosttyInputModes(val mouseTracking: Boolean, val alternateScroll: Boolean, val focusEvents: Boolean)
