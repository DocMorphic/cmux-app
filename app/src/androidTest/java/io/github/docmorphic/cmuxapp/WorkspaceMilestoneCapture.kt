package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertTrue

internal fun workspaceMilestoneCapture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "workspace-milestone").apply { mkdirs() }
    assertTrue("Could not capture $name", UiDevice.getInstance(instrumentation).takeScreenshot(File(directory, "$name.png")))
}
