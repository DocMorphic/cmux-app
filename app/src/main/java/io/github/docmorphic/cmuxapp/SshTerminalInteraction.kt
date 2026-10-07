package io.github.docmorphic.cmuxapp

/** Explicit local interactions, never parser query replies or replayed input. */
internal class SshTerminalInteraction(private val terminal: SshTerminal) {
    private fun ready() = terminal.state.value.phase == SshShellPhase.RUNNING

    /** Ended primary history remains readable; a running mouse client owns its wheel. */
    fun ownsLocalScrollback(): Boolean = terminal.display.activeScreen == "primary" &&
        (!ready() || !terminal.display.inputModes().mouseTracking)

    fun click(cell: TerminalGeometry.Cell): Boolean {
        if (!ready()) return false
        val display = terminal.display
        if (!display.inputModes().mouseTracking) return false
        val press = display.mouse(0, 1, cell)
        val release = display.mouse(1, 1, cell)
        val bytes = press + release
        return bytes.isNotEmpty() && terminal.sendBytes(bytes)
    }

    /** Null means primary scrollback belongs to the phone; false stops motion. */
    fun scroll(rows: Double, cell: TerminalGeometry.Cell): Boolean? {
        if (!rows.isFinite() || kotlin.math.abs(rows) > 4096) return false
        val display = terminal.display
        if (ownsLocalScrollback()) return null
        if (!ready()) return false
        val modes = display.inputModes()
        val count = kotlin.math.abs(rows).toInt()
        if (count == 0) return true
        val packet = when {
            modes.mouseTracking -> display.mouse(0, if (rows > 0) 4 else 5, cell)
            modes.alternateScroll -> TerminalKeyEncoding.encode(
                if (rows > 0) "Up" else "Down", applicationCursorKeys = display.applicationCursorKeys).toByteArray()
            else -> return false
        }
        if (packet.isEmpty() || packet.size * count > 256 * 1024) return false
        return terminal.sendBytes(ByteArray(packet.size * count) { packet[it % packet.size] })
    }

    fun focus(active: Boolean): Boolean {
        if (!ready() || !terminal.display.inputModes().focusEvents) return false
        return terminal.sendBytes(if (active) byteArrayOf(27, 91, 73) else byteArrayOf(27, 91, 79))
    }
}
