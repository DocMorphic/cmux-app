package io.github.docmorphic.cmuxapp

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class MarkdownPreviewPolicyTest {
    @Test fun renderedMarkdownUsesExactIosByteThreshold() {
        assertTrue(MarkdownPreviewPolicy.renderedAvailable(1_500_000)); assertFalse(MarkdownPreviewPolicy.renderedAvailable(1_500_001))
        assertTrue(MarkdownPreviewPolicy.isMarkdown("README.MD", null)); assertTrue(MarkdownPreviewPolicy.isMarkdown("extensionless", "text/markdown; charset=utf-8"))
        assertFalse(MarkdownPreviewPolicy.isMarkdown("notes.txt", "text/plain"))
    }
    @Test fun documentTextIsAQuotedJavascriptValueRatherThanExecutableSource() {
        val text = "</script>\";window.injected=true;//\n\u2028\u2029"
        val script = MarkdownPreviewPolicy.renderScript(text)
        assertTrue(script.contains("\\\";window.injected=true")); assertTrue(script.contains("\\u2028\\u2029"))
        assertFalse(script.contains('\u2028')); assertFalse(script.contains('\u2029'))
    }
    @Test fun linksKeepFragmentsLocalAndOpenOnlyRepresentableExternalDestinations() {
        for (url in listOf("#heading", "about:blank#heading")) assertTrue(MarkdownPreviewPolicy.inPage(url))
        for (url in listOf("https://cmux.com/#heading", "mailto:hello@example.com", "tel:+123")) assertTrue(MarkdownPreviewPolicy.external(url))
        for (url in listOf("file:///private/file", "content://private/file", "javascript:alert(1)", "relative.md", "intent://app", "data:text/html,x")) assertFalse(MarkdownPreviewPolicy.external(url))
    }
    @Test fun imageUrlPolicyRejectsCredentialsPortsAndPrivateLiteralsBeforeAnyFetch() {
        for (url in listOf("http://example.com/a.png", "https://user:pass@example.com/a.png", "https://@example.com/a.png", "https://example.com:444/a.png",
            "https://localhost/a", "https://node.local/a", "https://10.0.0.1/a", "https://100.64.0.1/a", "https://127.1/a", "https://2130706433/a",
            "https://[::1]/a", "https://[::ffff:127.0.0.1]/a", "https://[fd00::1]/a")) assertNull(url, MarkdownImagePolicy.url(url))
        assertNotNull(MarkdownImagePolicy.url("https://example.com/image.png")); assertNotNull(MarkdownImagePolicy.url("https://8.8.8.8/image.png"))
    }
    @Test fun dnsAnswersRejectEveryUpstreamLocalAddressRange() {
        val blocked = listOf("0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.1.1", "127.0.0.1", "169.254.1.2", "172.16.0.1", "172.31.0.1",
            "192.0.1.1", "192.168.1.1", "198.18.0.1", "198.19.0.1", "224.1.1.1", "255.255.255.255", "::", "::1", "::2", "fc00::1", "fd00::1", "fe80::1", "fec0::1", "ff00::1")
        for (address in blocked) assertFalse(address, MarkdownImagePolicy.allowed(InetAddress.getByName(address)))
        for (address in listOf("8.8.8.8", "100.63.1.1", "100.128.1.1", "172.32.1.1", "198.20.1.1", "2606:4700:4700::1111")) assertTrue(address, MarkdownImagePolicy.allowed(InetAddress.getByName(address)))
    }
    @Test fun redirectsCannotLeaveTheApprovedHostOrExceedThreeHops() {
        val initial = "https://example.com/image.png".toHttpUrl()
        assertTrue(MarkdownImagePolicy.redirect(initial, "https://example.com/other.png".toHttpUrl(), 3))
        assertFalse(MarkdownImagePolicy.redirect(initial, initial, 4))
        assertFalse(MarkdownImagePolicy.redirect(initial, "https://other.example/image.png".toHttpUrl(), 1))
        assertFalse(MarkdownImagePolicy.redirect(initial, "http://example.com/image.png".toHttpUrl(), 1))
    }
    @Test fun imageMimeAndDecodedByteLimitsMatchTheOriginalLoader() {
        assertEquals("image/jpeg", MarkdownImagePolicy.mime(" IMAGE/JPG; charset=binary"))
        assertEquals("image/svg+xml", MarkdownImagePolicy.mime("image/svg+xml"))
        assertNull(MarkdownImagePolicy.mime("text/html"))
        assertArrayEquals(byteArrayOf(1, 2), MarkdownImagePolicy.read(ByteArrayInputStream(byteArrayOf(1, 2)), 2, 2))
        assertTrue(runCatching { MarkdownImagePolicy.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)), -1, 2) }.isFailure)
        assertTrue(runCatching { MarkdownImagePolicy.read(ByteArrayInputStream(byteArrayOf(1)), 2, 2) }.isFailure)
        assertTrue(runCatching { MarkdownImagePolicy.read(ByteArrayInputStream(byteArrayOf()), 3, 2) }.isFailure)
    }
}
