package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NativeFeedRowPresentationTest {
    private val parent = NativeFeedRowValue("a", NotificationPresentation("Build", "Agent", "Finished"),
        "Mac", NativeFeedAvailability.CONNECTED, false, 1.0)
    @Test fun titleOnlyHistoryRetainsItsSourceAndDifferentConnectionStatus() {
        val child = parent.copy(key = "b", presentation = parent.presentation.copy(preview = null), availability = NativeFeedAvailability.OFFLINE)
        assertEquals(NativeFeedRowContext(true, true, false, false), child.nestedUnder(parent, Locale.US))
    }
    @Test fun historySuppressesRepeatedCaseFoldedMetadataButKeepsChangedFields() {
        val child = parent.copy(key = "b", presentation = NotificationPresentation(" build ", "AGENT", "Earlier"), computer = "MAC")
        assertEquals(NativeFeedRowContext(true, true, true, true), child.nestedUnder(parent, Locale.US))
        assertEquals(NativeFeedRowContext(true, false, false, false), child.copy(
            presentation = NotificationPresentation("Other", "Runner", "Earlier"), computer = "Other Mac").nestedUnder(parent, Locale.US))
        assertEquals(NativeFeedRowContext(), child.nestedUnder(null, Locale.US))
    }
}
