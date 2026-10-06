package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudWireGuardConfigTest {
    private val key = CloudWireGuardKey.generate()
    private fun enrollment(config: String = "", host: String? = "2001:db8::1", v4: String = "10.7.0.2", v6: String = "fd00::2/64") =
        CloudTunnelEnrollment("t", "p", "f", config, "server-public-key", host, 51820,
            listOf("10.7.0.0/24", "fd00::/64"), v4, v6, false, false)

    @Test fun fillsServerTemplateWithoutChangingRoutesAndPinsNatKeepalive() {
        val config = CloudWireGuardConfig.make(enrollment("""
            [Interface]
            PrivateKey =
            Address = 10.7.0.2/32
            MTU = 1200
            [Peer]
            PublicKey = server-public-key
            AllowedIPs = 10.7.0.0/24, fd00::/64
            Endpoint = example.test:51820
            PersistentKeepalive = 0
        """.trimIndent()), key)
        assertTrue(config.text.contains("PrivateKey = ${key.privateKeyBase64()}"))
        assertTrue(config.text.contains("PersistentKeepalive = 25"))
        assertTrue(config.text.contains("AllowedIPs = 10.7.0.0/24, fd00::/64"))
        assertTrue(config.text.contains("Endpoint = example.test:51820"))
        assertFalse(config.toString().contains(key.privateKeyBase64()))
    }
    @Test fun insertsMissingFieldsHandlesCommentsCaseAndWindowsNewlines() {
        val config = CloudWireGuardConfig.complete(" [INTERFACE] # generated\r\nAddress = 10.7.0.2/32\r\n[peer]\r\nPublicKey = fixture\r\n", key).text
        assertTrue(config.startsWith("[Interface]\nPrivateKey = "))
        assertTrue(config.contains("[Peer]\nPersistentKeepalive = 25\n"))
        assertFalse(config.contains('\r'))
    }
    @Test fun emptyServerConfigUsesDualStackAddressesIpv6EndpointAndDefaultMtu() {
        val config = CloudWireGuardConfig.make(enrollment(" \n"), key).text
        assertTrue(config.contains("Address = 10.7.0.2/32\nAddress = fd00::2/64\nMTU = 1200"))
        assertTrue(config.contains("Endpoint = [2001:db8::1]:51820\nPersistentKeepalive = 25\n"))
        assertTrue(CloudWireGuardConfig.make(enrollment(host = "[2001:db8::1]"), key).text.contains("Endpoint = [2001:db8::1]:51820"))
        assertFalse(CloudWireGuardConfig.make(enrollment(host = null), key).text.contains("Endpoint"))
    }
    @Test fun rejectsAmbiguousKeysSectionsAndInjectedFallbackFieldsWithoutEchoingSecrets() {
        for (config in listOf("[Interface]", "[Peer]", "[Interface]\n[Peer]\n[Peer]",
            "[Interface]\nPrivateKey=secret\nPrivateKey=other\n[Peer]",
            "[Interface]\n[Peer]\nPrivateKey=secret", "[Interface]\n[Peer]\n[Other]")) {
            val failure = runCatching { CloudWireGuardConfig.complete(config, key) }.exceptionOrNull()!!
            assertFalse(failure.toString().contains("secret")); assertFalse(failure.toString().contains(key.privateKeyBase64()))
        }
        assertTrue(runCatching { CloudWireGuardConfig.make(enrollment(host = "host\nPrivateKey = injected"), key) }.isFailure)
        assertTrue(runCatching { CloudWireGuardConfig.make(enrollment(v4 = "address\n[Peer]"), key) }.isFailure)
        assertTrue(runCatching { CloudWireGuardConfig.complete(" ".repeat(65537), key) }.isFailure)
    }
    @Test fun decodingAllowsEmptyConfigFallbackButRejectsAbsentAndNonStringValues() {
        val value = JSONObject("""{"tunnelId":"t","provider":"p","deviceFingerprint":"f","serverPublicKey":"s","clientConfig":"","endpointPort":51820}""")
        assertEquals("", CloudResponseDecoding.enrollment(value).clientConfig)
        value.put("clientConfig", 1)
        assertTrue(runCatching { CloudResponseDecoding.enrollment(value) }.isFailure)
        value.remove("clientConfig")
        assertTrue(runCatching { CloudResponseDecoding.enrollment(value) }.isFailure)
    }
}
