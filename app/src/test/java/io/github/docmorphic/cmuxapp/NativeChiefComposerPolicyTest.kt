package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefComposerPolicyTest {
    @Test fun ownerAcknowledgmentsDoNotReplaceNewerOrUnpublishedEditorText() {
        val bridge=NativeChiefDraftBridge("",0)
        bridge.published("A",1); bridge.published("AB",2)
        assertFalse(bridge.adopt("A",1,"AB"))
        assertFalse(bridge.adopt("AB",2,"AB"))
        assertFalse(bridge.adopt("",3,"ABC"))
        bridge.published("ABC",4)
        assertFalse(bridge.adopt("",3,"ABC"))
        assertTrue(bridge.adopt("",5,"ABC"))
        assertFalse(bridge.adopt("ABC",4,""))
    }
    @Test fun textOnlyGeometryAndTrimmingMatchTheActualSwiftFunctions() {
        val v=JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-composer.json")).bufferedReader().use { it.readText() })
        val trims=v.getJSONArray("trims")
        repeat(trims.length()) { val r=trims.getJSONObject(it)
            assertEquals(r.getString("text"), r.getString("trimmed"), NativeChiefComposerPolicy.trim(r.getString("text"))) }
        val rows=v.getJSONArray("rows")
        repeat(rows.length()) { val r=rows.getJSONObject(it)
            val inset=NativeChiefComposerPolicy.sideInset(r.getDouble("progress"));assertEquals(r.getDouble("inset"),inset,1e-9)
            val row=NativeChiefComposerPolicy.row(r.getDouble("width"),r.getDouble("bottom"),r.getDouble("height"),r.getDouble("oneLine"),inset,r.getDouble("scale"))
            for ((key,rect) in listOf("field" to row.field,"send" to row.send)) {
                val expected=r.getJSONArray(key)
                listOf(rect.x,rect.y,rect.width,rect.height).forEachIndexed { i,value -> assertEquals(expected.getDouble(i),value,1e-9) }
            }
        }
        val measures=v.getJSONArray("measures")
        repeat(measures.length()) { val r=measures.getJSONObject(it)
            val lines=NativeChiefComposerPolicy.lines(r.getDouble("textHeight"),r.getDouble("padding"),r.getDouble("line"),r.getDouble("leading"))
            assertEquals(r.getDouble("lines"),lines,1e-9)
            assertEquals(r.getDouble("height"),NativeChiefComposerPolicy.height(lines,r.getDouble("line")+20,r.getDouble("line"),r.getDouble("leading")),1e-9)
        }
    }
}
