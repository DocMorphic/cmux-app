package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceShellTest {
    @Test fun landscapePhoneStaysStackedEvenWithExpandedWidth() {
        assertFalse(usesWorkspaceSidebar(900, 412))
        assertFalse(usesWorkspaceSidebar(1200, 479))
    }
    @Test fun splitRequiresBothAndroidThresholds() {
        assertFalse(usesWorkspaceSidebar(839, 1000))
        assertTrue(usesWorkspaceSidebar(840, 480))
        assertTrue(usesWorkspaceSidebar(1200, 900))
    }
}
