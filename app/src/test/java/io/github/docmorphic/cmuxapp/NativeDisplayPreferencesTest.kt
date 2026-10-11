package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeDisplayPreferencesTest {
    @Test fun missingAndMalformedBooleanValuesUseShippingDefaults() {
        assertEquals(NativeDisplayPreferences(), NativeDisplayPreferences.fromStored(emptyMap<String, Any?>()))
        val booleans = listOf(NativeDisplayPreferences.wrapKey, NativeDisplayPreferences.altScreenNoticeKey,
            NativeDisplayPreferences.fullTerminalHeightKey, NativeDisplayPreferences.hapticsKey,
            NativeDisplayPreferences.feedTabKey, NativeDisplayPreferences.feedBubblesKey,
            NativeDisplayPreferences.feedReplacesNotificationsKey, NativeDisplayPreferences.folderTapKey,
            NativeDisplayPreferences.missingFilesKey)
        for (invalid in listOf("false", 0, 1L, setOf("true"), null))
            assertEquals(NativeDisplayPreferences(), NativeDisplayPreferences.fromStored(booleans.associateWith { invalid }))
    }

    @Test fun folderAndMissingSettingsKeepExistingKeysAndResolveInTheSameSnapshot() {
        val enabled = NativeDisplayPreferences.fromStored(mapOf("terminal-folder-tap" to false, "show-missing-files" to true))
        assertFalse(enabled.terminalFolderTapEnabled); assertTrue(enabled.showMissingFiles)
        assertEquals(NativeDisplayPreferences(terminalFolderTapEnabled = false, showMissingFiles = true), enabled)
        val changed = NativeDisplayPreferences.fromStored(mapOf("terminal-folder-tap" to true, "show-missing-files" to false))
        assertEquals(NativeDisplayPreferences(), changed)
    }

    @Test fun malformedNumericValuesDefaultAndStoredDepthsClampWithoutChangingOtherFlags() {
        assertEquals(NativeDisplayPreferences(), NativeDisplayPreferences.fromStored(mapOf(
            NativeDisplayPreferences.previewKey to "1", NativeDisplayPreferences.scrollbackKey to 10_000L)))
        assertEquals(NativeDisplayPreferences(previewLines = 1, scrollbackRows = 0),
            NativeDisplayPreferences.fromStored(mapOf(NativeDisplayPreferences.previewKey to Int.MIN_VALUE,
                NativeDisplayPreferences.scrollbackKey to -1)))
        assertEquals(NativeDisplayPreferences(previewLines = 2, scrollbackRows = 20_000),
            NativeDisplayPreferences.fromStored(mapOf(NativeDisplayPreferences.previewKey to Int.MAX_VALUE,
                NativeDisplayPreferences.scrollbackKey to Int.MAX_VALUE)))
    }
}
