package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import com.wireguard.android.backend.GoBackend
import com.wireguard.config.Config
import org.junit.Assert.*
import org.junit.Test

/** Loads the actual packaged Go library and parser, without VPN consent or activation. */
class CloudVpnBackendTest {
    @Test fun nativeBackendLoadsAndReportsItsVersionWithoutStartingATunnel() {
        val backend = GoBackend(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(backend.version.isNotBlank())
        assertTrue(backend.runningTunnelNames.isEmpty())
    }
    @Test fun generatedPrivateConfigurationParsesWithTheActualWireGuardLibrary() {
        val key = CloudWireGuardKey.generate()
        val enrollment = CloudTunnelEnrollment("fixture", "fixture", "fixture", "", CloudWireGuardKey.generate().publicKey,
            "192.0.2.1", 51820, listOf("10.0.0.0/8", "fd00::/8"), "10.0.0.2", "fd00::2", true, false)
        val text = CloudVpnRoutePolicy.configuration(enrollment, key).text
        val parsed = Config.parse(text.reader().buffered())
        assertEquals(2, parsed.`interface`.addresses.size)
        assertEquals(1, parsed.peers.size)
        assertEquals(2, parsed.peers.single().allowedIps.size)
        assertEquals(25, parsed.peers.single().persistentKeepalive.get().toInt())
        assertTrue(parsed.`interface`.dnsServers.isEmpty())
    }
}
