package io.github.docmorphic.cmuxapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Click the real Android floating toolbar, not a test-only replacement action. */
internal fun clickSystemPaste(idle: () -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val service = automation.serviceInfo
    val previousFlags = service.flags
    service.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
    automation.serviceInfo = service
    val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "composer-system-paste").apply { mkdirs() }
    fun capture(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            java.io.File(output, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    val seen = StringBuilder()
    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        seen.append("${node.className} text=${node.text} description=${node.contentDescription} clickable=${node.isClickable}\n")
        if (node.text?.toString()?.equals("Paste", ignoreCase = true) == true ||
            node.contentDescription?.toString()?.equals("Paste", ignoreCase = true) == true) return node
        for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
        return null
    }
    try {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            idle(); seen.setLength(0)
            val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
            for (root in roots) {
                var node = find(root)
                while (node != null && !node.isClickable) node = node.parent
                if (node?.isEnabled == true) {
                    capture("system-menu")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                }
            }
            Thread.sleep(50)
        }
        capture("missing-menu")
        java.io.File(output, "missing-menu.txt").writeText(seen.toString())
        throw AssertionError("System Paste menu item not found")
    } finally {
        service.flags = previousFlags; automation.serviceInfo = service
    }
}
