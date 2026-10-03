package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePairingTest {
    private val token = "a".repeat(64)

    @Test fun acceptsTailscalePairing() {
        val pairing = BridgePairing.parse("cmux-app://pair?host=100.121.78.98&port=58466&token=$token")
        assertEquals("http://100.121.78.98:58466", pairing.baseUrl)
    }

    @Test fun rejectsPublicAndLoopbackAddresses() {
        assertTrue(runCatching { BridgePairing.parse("cmux-app://pair?host=127.0.0.1&port=58466&token=$token") }.isFailure)
        assertTrue(runCatching { BridgePairing.parse("cmux-app://pair?host=8.8.8.8&port=58466&token=$token") }.isFailure)
    }
}
