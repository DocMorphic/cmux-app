package io.github.docmorphic.cmuxapp

enum class TerminalOutputMode { GRID, HYBRID, BYTES }

data class TerminalTransport(val mode: TerminalOutputMode, val screenAnchor: Boolean) {
    val topics get() = when (mode) {
        TerminalOutputMode.GRID -> listOf("terminal.render_grid")
        TerminalOutputMode.HYBRID -> listOf("terminal.render_grid", "terminal.bytes")
        TerminalOutputMode.BYTES -> listOf("terminal.bytes")
    } + listOf("workspace.updated", "terminal.set_font")

    companion object {
        /** Mirrors TerminalOutputTransportSelection.swift at the pinned cmux revision. */
        fun resolve(capabilities: Set<String>, fidelity: String? = null): TerminalTransport {
            val grid = "terminal.render_grid.v1" in capabilities || fidelity == "render_grid"
            val anchor = grid && "terminal.render_grid.screen_anchor.v1" in capabilities
            return TerminalTransport(when {
                anchor -> TerminalOutputMode.GRID
                grid && "terminal.bytes.v1" in capabilities -> TerminalOutputMode.HYBRID
                grid -> TerminalOutputMode.GRID
                else -> TerminalOutputMode.BYTES
            }, anchor)
        }
    }
}
