package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactSyntaxPolicyTest {
    @Test fun exactIosThresholdsAndLanguageSelection() {
        assertEquals("haskell", ArtifactSyntaxPolicy.language("App.PURS"))
        assertEquals("haskell", ArtifactSyntaxPolicy.language("Main.hs"))
        assertEquals("kotlin", ArtifactSyntaxPolicy.language("build.KTS"))
        assertEquals("html", ArtifactSyntaxPolicy.language("index.htm"))
        assertEquals("scss", ArtifactSyntaxPolicy.language("style.sass"))
        assertEquals("objectivec", ArtifactSyntaxPolicy.language("main.mm"))
        assertTrue(ArtifactSyntaxPolicy.decision("code.kt", 1_500_000).enabled)
        assertFalse(ArtifactSyntaxPolicy.decision("code.kt", 1_500_001).enabled)
        assertTrue(ArtifactSyntaxPolicy.decision("README", 255_999).enabled)
        assertNull(ArtifactSyntaxPolicy.decision("README", 255_999).language)
        assertFalse(ArtifactSyntaxPolicy.decision("README", 256_000).enabled)
        assertFalse(ArtifactSyntaxPolicy.decision("code.kt", -1).enabled)
    }
    private fun payload(text: String, vararg ranges: IntArray) = JSONObject().put("text", text).put("language", "kotlin")
        .put("runs", JSONArray(ranges.map { JSONArray(it.toList()) })).toString()
    @Test fun nativeRunsPreserveUtf16AndRejectChangedOrMalformedDocuments() {
        val text = "🙂\r\nval"
        val valid = payload(text, intArrayOf(0, 4, -1, 0), intArrayOf(4, 7, 0xFFFC5FA3.toInt(), 1))
        assertEquals(2, ArtifactSyntaxResult.decode(valid, text)!!.runs.size)
        assertNull(ArtifactSyntaxResult.decode(valid, text.replace("\r", "")))
        assertNull(ArtifactSyntaxResult.decode(payload(text, intArrayOf(0, 4, -1, 0), intArrayOf(3, 7, -1, 0)), text))
        assertNull(ArtifactSyntaxResult.decode(payload(text, intArrayOf(0, 6, -1, 0)), text))
        assertNull(ArtifactSyntaxResult.decode(payload(text, intArrayOf(0, 8, -1, 0)), text))
        assertNull(ArtifactSyntaxResult.decode(payload(text, intArrayOf(0, 7, 0, 0)), text))
        assertNull(ArtifactSyntaxResult.decode(payload(text, intArrayOf(0, 7, -1, 4)), text))
        assertNull(ArtifactSyntaxResult.decode("null", text))
    }
}
