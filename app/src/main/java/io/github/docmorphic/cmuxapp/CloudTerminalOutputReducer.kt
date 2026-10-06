package io.github.docmorphic.cmuxapp

/** One reducer per fresh emulator, matching iOS resnapshot reset and host-grid ordering. */
internal class CloudTerminalOutputReducer {
    sealed interface Action {
        data class Grid(val columns: Int, val rows: Int) : Action
        class Write(val bytes: ByteArray) : Action { override fun toString() = "CloudWrite(byteCount=${bytes.size})" }
        data object Exited : Action
    }
    private var snapshotSeen = false
    fun reduce(event: CloudTerminalOutput): List<Action> = buildList {
        fun grid() { if (event.columns > 0 && event.rows > 0) add(Action.Grid(event.columns, event.rows)) }
        fun write() {
            // Ghostty's existing JNI append boundary accepts at most 2 MiB per call.
            var start = 0
            while (start < event.bytes.size) {
                val end = minOf(start + 2 * 1024 * 1024, event.bytes.size)
                add(Action.Write(if (start == 0 && end == event.bytes.size) event.bytes else event.bytes.copyOfRange(start, end)))
                start = end
            }
        }
        when (event.kind) {
            1 -> { grid(); if (snapshotSeen) add(Action.Write(byteArrayOf(0x1b, 0x63))); write(); snapshotSeen = true }
            2 -> write()
            3 -> grid()
            4 -> add(Action.Exited)
            else -> error("Invalid Cloud terminal output")
        }
    }
}
