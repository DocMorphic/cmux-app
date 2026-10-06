package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class CloudVpnRoutePolicyTest {
    private val key = CloudWireGuardKey.generate()
    private fun enrollment(text: String = "", routes: List<String> = listOf("10.0.0.0/8", "fd00::/8")) =
        CloudTunnelEnrollment("id", "provider", "fingerprint", text, CloudWireGuardKey.generate().publicKey,
            "vpn.example.test", 51820, routes, "10.0.0.2", "fd00::2", true, false)
    @Test fun routesMustBeEntirelyInsidePrivateRanges() {
        listOf("10.0.0.0/8", "10.255.255.255/32", "172.16.0.0/12", "172.31.255.255/32", "192.168.0.0/16",
            "100.64.0.0/10", "100.127.255.255/32", "fc00::/7", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128")
            .forEach { assertTrue(it, CloudVpnRoutePolicy.permits(it)) }
        listOf("0.0.0.0/0", "10.0.0.0/7", "172.16.0.0/11", "172.32.0.0/12", "192.168.0.0/15", "100.64.0.0/9",
            "100.128.0.0/32", "127.0.0.1/32", "169.254.1.1/32", "224.0.0.0/4", "::/0", "fc00::/6",
            "fe80::/64", "2001:db8::/32", "::ffff:10.0.0.1/128", "fd00::1%wlan0/128", "fd00::/129")
            .forEach { assertFalse(it, CloudVpnRoutePolicy.permits(it)) }
    }
    @Test fun malformedOrNonNumericInputCannotTriggerDnsOrPermissiveAddressParsing() {
        listOf("example.test/32", "10.1/32", "012.0.0.1/32", "0xa000001/32", "167772161/32", "10.256.0.1/32",
            "10.0.0.0/-1", "10.0.0.0/+8", "10.0.0.1", "10.0.0.1/32/32", "[fd00::]/64", "fd00:::1/64",
            "10.0.0.1\n/32", "10.0.0.0/99999999999", "10.0.0.0/")
            .forEach { assertFalse(it, CloudVpnRoutePolicy.permits(it)) }
    }
    @Test fun structuredFieldsAndFinalTextBothHaveToPass() {
        val text = CloudVpnRoutePolicy.configuration(enrollment(), key).text
        assertTrue(CloudVpnRoutePolicy.permitsConfiguration(text))
        val publicText = text.replace("AllowedIPs = 10.0.0.0/8, fd00::/8", "AllowedIPs = 0.0.0.0/0")
        assertThrows(IllegalArgumentException::class.java) { CloudVpnRoutePolicy.configuration(enrollment(publicText), key) }
        assertThrows(IllegalArgumentException::class.java) { CloudVpnRoutePolicy.configuration(enrollment(text, listOf("0.0.0.0/0")), key) }
        assertThrows(IllegalArgumentException::class.java) { CloudVpnRoutePolicy.configuration(enrollment(routes = emptyList()), key) }
        assertFalse(CloudVpnRoutePolicy.permitsConfiguration(text.replace("10.0.0.2/32", "8.8.8.8/32")))
    }
    @Test fun configurationRejectsDnsHooksOverridesAndMissingOrMisplacedRoutes() {
        val text = CloudVpnRoutePolicy.configuration(enrollment(), key).text
        for (extra in listOf("DNS = 1.1.1.1", "DNS = corp.example", "PostUp = echo unsafe", "Table = off", "IncludedApplications = pkg"))
            assertFalse(extra, CloudVpnRoutePolicy.permitsConfiguration(text.replace("[Interface]", "[Interface]\n$extra")))
        assertFalse(CloudVpnRoutePolicy.permitsConfiguration(text.replace("AllowedIPs = 10.0.0.0/8, fd00::/8", "AllowedIPs =")))
        assertFalse(CloudVpnRoutePolicy.permitsConfiguration(text.replace("AllowedIPs = 10.0.0.0/8, fd00::/8", "AllowedIPs = 10.0.0.0/8,")))
        assertFalse(CloudVpnRoutePolicy.permitsConfiguration(text + "\n[Peer]\nAllowedIPs=10.0.0.0/8"))
        assertFalse(CloudVpnRoutePolicy.permitsConfiguration(text.replace("[Interface]", "[Interface]\nAllowedIPs=10.0.0.0/8")))
        assertTrue(CloudVpnRoutePolicy.permitsConfiguration(text.replace("[Peer]", "[Peer] # comment").replace("\n", "\r\n")))
    }
}
