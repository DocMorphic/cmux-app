package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalBrowserAddressRuntimeTest {
    @Test fun platformUriAndBuiltInIcuMatchThePinnedSwiftAddressCorpus() {
        val value = InstrumentationRegistry.getInstrumentation().context.assets.open("local-addresses.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val cases = value.getJSONArray("cases"); val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val expected = if (item.isNull("expected")) null else item.getString("expected")
            val actual = LocalBrowserAddress(item.optString("template", LocalBrowserAddress.DEFAULT_SEARCH)).resolve(item.getString("input"))
            if (actual != expected) failures += "case $i: expected=$expected actual=$actual"
        }
        assertEquals(103, cases.length())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
