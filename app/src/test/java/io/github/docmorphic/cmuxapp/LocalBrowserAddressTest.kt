package io.github.docmorphic.cmuxapp

import com.ibm.icu.text.IDNA
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalBrowserAddressTest {
    private fun resolver(template: String = LocalBrowserAddress.DEFAULT_SEARCH) = LocalBrowserAddress(template) { host ->
        val idna = IDNA.getUTS46Instance(IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ)
        val info = IDNA.Info(); val result = StringBuilder()
        idna.nameToASCII(host, result, info); result.toString().takeUnless { info.hasErrors() }
    }
    @Test fun androidPolicyMatchesUnmodifiedPinnedSwiftResolverCorpus() {
        val fixture = JSONObject(javaClass.getResourceAsStream("/browser/local-addresses.json")!!.bufferedReader().use { it.readText() })
        assertEquals("4c5272e9153eca2033c9f40ac749f0c3a5bcb291", fixture.getString("upstream"))
        val cases = fixture.getJSONArray("cases"); val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val value = cases.getJSONObject(i)
            val expected = if (value.isNull("expected")) null else value.getString("expected")
            val actual = resolver(value.optString("template", LocalBrowserAddress.DEFAULT_SEARCH)).resolve(value.getString("input"))
            if (expected != actual) failures += "case $i ${value.getString("input")}\nexpected: $expected\nactual: $actual"
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
    @Test fun unicodeHostProcessingDoesNotMapDistinctDomainsToAsciiLookalikes() {
        assertEquals("https://xn--fa-hia.de/", resolver().resolve("https://faß.de/"))
        assertEquals("https://fass.de/", resolver().resolve("https://fass.de/"))
    }
    @Test fun emptyOrInvalidSearchTemplateCannotProduceANonWebNavigation() {
        assertNull(resolver("javascript:%@").resolve("hello world"))
        assertNull(resolver("").resolve("hello world"))
        assertNull(resolver().resolve("\u00a0\u2003\n"))
    }
}
