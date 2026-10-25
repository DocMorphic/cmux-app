package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeChiefUnreadFormatTest {
    @Test fun positiveCountsMatchTheWholeOriginalFoundationHelperIncludingWideIntegerConversion() {
        val fixture=JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-unread-format.json"))
            .bufferedReader().use { it.readText() })
        val rows=fixture.getJSONArray("rows")
        assertEquals(8,rows.length())
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index)
            assertEquals(row.getString("count"),row.getString("value"),
                NativeChiefUnreadFormat.positive(row.getString("count").toLong(),"1 unread","%1\$d unread"))
        }
    }
}
