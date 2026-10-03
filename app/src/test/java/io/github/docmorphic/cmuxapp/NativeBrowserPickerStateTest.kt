package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeBrowserPickerStateTest {
    private val workspace = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","title":"Fixture","terminals":[{"id":"t"}],"surfaces":[
        {"surface_id":"b","kind":"browser","title":"Preview"},{"surface_id":"m","kind":"markdown","title":"Notes"}]}]}""")).single()
    @Test fun updateHintRequiresAnAuthoritativeUnsupportedSnapshot() {
        assertFalse(NativeBrowserPickerState.from(false, emptySet()).showsUpdateHint)
        assertFalse(NativeBrowserPickerState.from(false, setOf("browser.stream.v1")).showsUpdateHint)
        assertFalse(NativeBrowserPickerState.from(true, setOf("browser.stream.v1")).showsUpdateHint)
        assertTrue(NativeBrowserPickerState.from(true, emptySet()).showsUpdateHint)
    }
    @Test fun legacyBrowserKeepsWireIdentityButMovesToSurfaceSection() {
        val state = NativeBrowserPickerState(true, false)
        val rows = nativePanePickerRows(workspace, state)
        assertEquals(listOf("t", "m", "b"), rows.map { it.id })
        assertEquals(NativePanePickerRow("browser", "b", "Preview", fallbackBrowser = true), rows.last())
        assertEquals("b", workspace.browserFallback("b", state)?.id)
        assertNull(workspace.browserFallback("missing", state))
        assertNull(workspace.browserFallback("m", state))
    }
    @Test fun supportedBrowserHasOneStreamRowAndNoFallback() {
        val state = NativeBrowserPickerState(true, true)
        assertEquals(1, nativePanePickerRows(workspace, state).count { it.id == "b" })
        assertFalse(nativePanePickerRows(workspace, state).last().fallbackBrowser)
        assertNull(workspace.browserFallback("b", state))
    }
    @Test fun unavailableStreamInventoryDoesNotInventALegacySurface() {
        val noSurfaces = workspace.copy(surfaces = emptyList())
        assertFalse(nativePanePickerRows(noSurfaces, NativeBrowserPickerState(true, false)).any { it.kind == "browser" })
        assertNull(noSurfaces.browserFallback("b", NativeBrowserPickerState(true, false)))
    }
}
