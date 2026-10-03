package io.github.docmorphic.cmuxapp

/** Availability is unknown during reconnect even when the last support bit is retained. */
internal data class NativeBrowserPickerState(val known: Boolean = false, val streaming: Boolean = true) {
    val showsUpdateHint get() = known && !streaming
    companion object {
        const val UPDATE_HINT = "Update cmux on your Mac to stream browser panes"
        fun from(ready: Boolean, capabilities: Set<String>) = NativeBrowserPickerState(ready, "browser.stream.v1" in capabilities)
    }
}

internal fun NativeWorkspace.browserFallback(id: String?, state: NativeBrowserPickerState): NativeSurface? =
    if (state.streaming) null else surfaces.firstOrNull { it.id == id && it.kind == "browser" }
