package io.github.docmorphic.cmuxapp.iroh

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.presetMinimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IrxEndpointStatusTest {
    @Test fun nativeEndpointReportsOpenAndClosedWithoutInventingHomeRelay() = runBlocking {
        IrohRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        val endpoint = Endpoint.bind(EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0"))
        try {
            val open = IrxEndpointStatus.read(endpoint)
            assertTrue(open.open)
            assertNull(open.homeRelayUrl)
            endpoint.shutdown()
            assertEquals(IrxEndpointStatus(false, null), IrxEndpointStatus.read(endpoint))
        } finally { endpoint.shutdown(); endpoint.close() }
    }
}
