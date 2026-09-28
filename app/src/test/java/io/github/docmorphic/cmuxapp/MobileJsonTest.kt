package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class MobileJsonTest {
    @Test fun nestedUnsignedCursorsAreExactWhileStringsAndStandardNumbersStayUnchanged() {
        val result = MobileJson.objectValue("""{"id":"18446744073709551615","seq":18446744073709551615,"nested":[{"seq":9223372036854775808}],"small":12,"negative":-8,"float":1.25,"exp":1e2,"null":null,"bool":true,"escaped":"text\\\"18446744073709551615"}""")
        assertEquals(BigInteger("18446744073709551615"), result.get("seq"))
        assertEquals(BigInteger("9223372036854775808"), result.getJSONArray("nested").getJSONObject(0).get("seq"))
        assertEquals("18446744073709551615", result.getString("id"))
        assertEquals(12, result.getInt("small")); assertEquals(-8, result.getInt("negative"))
        assertEquals(1.25, result.getDouble("float"), 0.0); assertEquals(100.0, result.getDouble("exp"), 0.0)
        assertTrue(result.isNull("null")); assertTrue(result.getBoolean("bool"))
        assertTrue(result.getString("escaped").endsWith("18446744073709551615"))
    }

    @Test fun replayCursorParsedFromWireCanSeedUnsignedMirror() {
        val mirror = TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.BYTES, false), TerminalViewport(20, 5))
        mirror.replay(MobileJson.objectValue("""{"snapshot_data_b64":"QQ==","seq":9223372036854775808}"""))
        assertEquals(9223372036854775808uL, mirror.nativeCursor)
        assertEquals(TerminalStreamMirror.Result.APPLIED,
            mirror.bytes(MobileJson.objectValue("""{"surface_id":"s","seq":9223372036854775808,"data_b64":"Qg=="}""")))
        assertEquals("AB", RenderGrid.plainText(mirror.display.visibleLines()))
    }
}
