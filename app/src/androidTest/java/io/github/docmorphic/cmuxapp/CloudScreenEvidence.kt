package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Fixture screens only; call after UI assertions, never while showing an account. */
internal fun captureCloudScreen(name: String) {
    require(name.matches(Regex("[a-z0-9-]+")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "cloud-integration").apply { mkdirs() }
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try { File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
    finally { bitmap.recycle() }
}
