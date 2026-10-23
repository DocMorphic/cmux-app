package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MarkdownViewerPolicyTest {
    @Test fun decisionsMatchExecutedUnmodifiedSwiftPolicies() {
        val source = JSONObject(checkNotNull(javaClass.getResource("/parity/markdown-viewer-policy.json")).readText())
        assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009", source.getString("upstream"))
        val navigation = source.getJSONArray("navigation")
        for (index in 0 until navigation.length()) {
            val row = navigation.getJSONObject(index)
            assertEquals(row.toString(), row.getString("result"), MarkdownNavigationPolicy.decide(
                row.optString("url").takeUnless { row.isNull("url") }, row.getBoolean("activated"),
                row.getBoolean("main"), row.getBoolean("shell")).name)
        }
        val approval = source.getJSONArray("approval")
        for (index in 0 until approval.length()) {
            val row = approval.getJSONObject(index)
            assertEquals(row.toString(), row.getBoolean("approved"), MarkdownImageApprovals().approve(row.getString("url")) != null)
        }
        val record = MarkdownImageApprovals(); record.approve("https://example.com/a.png")
        val requests = source.getJSONArray("requestsAfterApprovingA")
        for (index in 0 until requests.length()) {
            val row = requests.getJSONObject(index)
            assertEquals(row.toString(), row.getBoolean("allowed"), record.approved(row.getString("url")))
        }
        record.revokeAll(); assertEquals(source.getBoolean("revocationCleared"), !record.approved("https://example.com/a.png"))
        val opens = source.getJSONArray("open")
        for (index in 0 until opens.length()) {
            val row = opens.getJSONObject(index)
            assertEquals(row.toString(), row.getBoolean("allowed"), MarkdownImageApprovals.openable(row.getString("url")))
        }
    }

    @Test fun safeUrlsCannotBeFetchedBeforeApproval() {
        val record = MarkdownImageApprovals()
        assertFalse(record.approved("https://example.com/a.png"))
        assertEquals("https://example.com/a.png", record.approve("https://example.com/a.png"))
        assertTrue(record.approved("https://example.com/a.png"))
    }

    @Test fun approvalIsExactRatherThanHostPathPrefixOrQueryEquivalent() {
        val record = MarkdownImageApprovals()
        record.approve("https://example.com/a.png?x=1")
        for (url in listOf("https://example.com/a.png", "https://example.com/a.png?x=2",
            "https://example.com/a.png?x=1&y=2", "https://example.com/b.png?x=1",
            "https://other.example/a.png?x=1", "https://example.com/a.png?x=%31")) assertFalse(url, record.approved(url))
    }

    @Test fun nativeApprovalRejectsTheLoadersUnsafeUrls() {
        val record = MarkdownImageApprovals()
        for (url in listOf("http://example.com/a.png", "https://user:pw@example.com/a.png",
            "https://localhost/a.png", "https://127.0.0.1/a.png", "https://[::2]/a.png",
            "https://example.com:8443/a.png", "javascript:alert(1)")) {
            assertNull(url, record.approve(url)); assertFalse(url, record.approved(url))
        }
    }

    @Test fun recordsArePerShellAndRevocationDoesNotGrantTheNextView() {
        val first = MarkdownImageApprovals(); val second = MarkdownImageApprovals()
        first.approve("https://example.com/a.png")
        assertFalse(second.approved("https://example.com/a.png"))
        first.revokeAll(); assertFalse(first.approved("https://example.com/a.png"))
        first.approve("https://example.com/b.png"); assertTrue(first.approved("https://example.com/b.png"))
        assertFalse(first.approved("https://example.com/a.png"))
    }

    @Test fun placeholderOpenAllowsOnlyPlainWebUrlsWithoutCredentials() {
        for (url in listOf("http://example.com/a.png", "https://example.com/a.png", "https://127.0.0.1/a.png"))
            assertTrue(url, MarkdownImageApprovals.openable(url))
        for (url in listOf("javascript:alert(1)", "file:///private/file", "cmux-remote-image://image?url=x",
            "https://user:pw@example.com/", "https://@example.com/", "http:/missing-host", "//example.com/a"))
            assertFalse(url, MarkdownImageApprovals.openable(url))
    }

    @Test fun onlyMainDocumentFragmentsStayInsideTheViewer() {
        for (activated in listOf(false, true)) {
            assertEquals(MarkdownNavigationDecision.ALLOW, MarkdownNavigationPolicy.decide("about:blank#usage", activated, true))
            assertEquals(if (activated) MarkdownNavigationDecision.OPEN_EXTERNALLY else MarkdownNavigationDecision.CANCEL,
                MarkdownNavigationPolicy.decide("about:blank#usage", activated, false))
        }
    }

    @Test fun scriptFormReloadAndSubframeNavigationsHaveNoImplicitPermission() {
        for (url in listOf("https://example.com/", "about:blank", "data:text/html,x", "file:///private/file")) {
            assertEquals(MarkdownNavigationDecision.CANCEL, MarkdownNavigationPolicy.decide(url, false, true))
            assertEquals(MarkdownNavigationDecision.CANCEL, MarkdownNavigationPolicy.decide(url, false, false))
        }
        assertEquals(MarkdownNavigationDecision.CANCEL, MarkdownNavigationPolicy.decide(null, true, true, true))
    }

    @Test fun activatedLinksGoToTheHostAndOnlyTheExplicitShellLoadIsAllowed() {
        for (main in listOf(true, false))
            assertEquals(MarkdownNavigationDecision.OPEN_EXTERNALLY, MarkdownNavigationPolicy.decide("https://example.com/docs", true, main))
        assertEquals(MarkdownNavigationDecision.ALLOW, MarkdownNavigationPolicy.decide("about:blank", false, true, true))
        assertEquals(MarkdownNavigationDecision.CANCEL, MarkdownNavigationPolicy.decide("about:blank", false, false, true))
        assertTrue(MarkdownNavigationPolicy.shellDocument("about:blank"))
        for (url in listOf("about:blank#heading", "about:other", "https://example.com/", "data:text/html,x"))
            assertFalse(MarkdownNavigationPolicy.shellDocument(url))
    }
}
