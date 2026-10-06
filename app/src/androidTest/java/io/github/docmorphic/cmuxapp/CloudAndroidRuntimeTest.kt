package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import io.github.docmorphic.cmuxapp.iroh.IrohInstallationStore
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real libraries/class loader on Android; no enrollment, VM creation or account mutation. */
class CloudAndroidRuntimeTest {
    @Test fun cloudAndIrohContextsCoexistAndRepeatedInitializationIsSafe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val installation = IrohInstallationStore(context)
        val originalId = installation.storedDeviceId()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val calls = (1..12).map { workers.submit(Callable { CloudNativeBindings.initialize(context) }) }
            calls.forEach { it.get(20, TimeUnit.SECONDS) }
            // Enter the real Rust C ABI after initialization. Invalid config must fail
            // without opening a tunnel, rather than failing library/JNI/class loading.
            val failure = runCatching { CloudNativeBindings.startTunnel("invalid fixture".toByteArray()) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Cloud tunnel could not start", failure?.message)
            assertEquals(originalId, installation.storedDeviceId())
            assertTrue(context.assets.open("licenses/CloudTerminal.txt").bufferedReader().use { it.readText() }.isNotBlank())
        } finally { workers.shutdownNow() }
    }
}
