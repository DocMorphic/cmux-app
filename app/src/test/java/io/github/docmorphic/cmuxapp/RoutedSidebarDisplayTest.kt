package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutedSidebarDisplayTest {
    @Test fun pagesShareCapturedDisplayPreferencesAndNextRefreshReadsNewValues() {
        val mac = NativeCredentialStore.PairedMac("private-code", "mac", "Mac")
        val source = NativeFeedSource(mac, workspaces = (0..120).map {
            NativeWorkspace("w$it", "Workspace $it", emptyList(), null, false, null, null, false, emptyList(), null, "Preview", null)
        })
        var input = NativeSidebarInput(listOf(source), emptyList(), listOf(NativeSortComputer("mac", "Mac")),
            NativeWorkspaceSortState(), display = NativeDisplayPreferences(true, 1, scrollbackRows = 20_000, hapticFeedbackEnabled = false))
        val host = NativeRoutedSidebarHost("owner", "salt", { input }, { RoutedSidebarLease({}) {} }, {})
        val exchange = RoutedSidebarExchange(); val first = exchange.begin(host.read(RoutedSidebarQuery())!!)
        assertTrue(first.snapshot.wrapTitles); assertEquals(1, first.snapshot.previewLines)
        val wire = RoutedSidebarWire.page(first); assertEquals(first, RoutedSidebarWire.page(wire))
        assertFalse(wire.contains("scrollback")); assertFalse(wire.contains("haptic")); assertFalse(wire.contains("private-code"))
        input = input.copy(display = NativeDisplayPreferences(false, 2))
        val next = exchange.page(first.revision, first.next!!)
        assertTrue(next.snapshot.wrapTitles); assertEquals(1, next.snapshot.previewLines)
        assertEquals(next, RoutedSidebarWire.page(RoutedSidebarWire.page(next)))
        val fresh = exchange.begin(host.read(RoutedSidebarQuery())!!)
        assertFalse(fresh.snapshot.wrapTitles); assertEquals(2, fresh.snapshot.previewLines)
    }
    @Test fun legacyWireDefaultsAndUnsupportedLineCountsAreBounded() {
        val encoded = RoutedSidebarWire.page(RoutedSidebarExchange().begin(RoutedSidebarSnapshot(emptyList(), emptyList())))
        val legacy = JSONObject(encoded).apply { remove("wrap_titles"); remove("preview_lines") }
        val value = RoutedSidebarWire.page(legacy.toString()).snapshot
        assertFalse(value.wrapTitles); assertEquals(2, value.previewLines)
        for (invalid in listOf(0, -1, 3, Int.MAX_VALUE)) {
            assertTrue(runCatching { RoutedSidebarWire.page(JSONObject(encoded).put("preview_lines", invalid).toString()) }.isFailure)
        }
    }
}
