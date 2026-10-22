package io.github.docmorphic.cmuxapp

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Enables the real framework event path on the disposable emulator; records nothing. */
class ChiefAccessibilityProbe : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
