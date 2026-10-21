package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefHeaderPolicyTest {
    private fun rect(expected: JSONArray, actual: NativeChiefHeaderPolicy.Rect) {
        listOf(actual.x,actual.y,actual.width,actual.height).forEachIndexed { i,v -> assertEquals(expected.getDouble(i),v,1e-8) }
    }
    @Test fun geometryAndTypeCategoriesMatchThePinnedExecutedReferences() {
        val v=JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-header.json")).bufferedReader().use{it.readText()})
        v.getJSONArray("compact").let{ a -> repeat(a.length()) {
            val r=a.getJSONObject(it);val c=NativeChiefHeaderPolicy.compact(r.getDouble("width"),r.getDouble("name"),21.0,safeTop=r.getDouble("safeTop"))
            rect(r.getJSONArray("avatar"),c.avatar);rect(r.getJSONArray("label"),c.label);rect(r.getJSONArray("chevron"),c.chevron)
            assertEquals(r.getDouble("height"),c.height,1e-8)
        } }
        v.getJSONArray("details").let{ a -> repeat(a.length()) {
            val r=a.getJSONObject(it);val d=NativeChiefHeaderPolicy.details(r.getDouble("width"),r.getDouble("offset"),r.getDouble("titleHeight"),r.getDouble("actionSize"))
            rect(r.getJSONArray("avatar"),d.avatar);rect(r.getJSONArray("actions"),d.actions)
            for((key,n) in listOf("titleTop" to d.titleTop,"titleScale" to d.titleScale,"alpha" to d.actionsAlpha,"height" to d.height))assertEquals(r.getDouble(key),n,1e-8)
        } }
        v.getJSONArray("unread").let{ a -> repeat(a.length()) {
            val r=a.getJSONObject(it);assertEquals(r.getDouble("width"),NativeChiefHeaderPolicy.backWidth(r.getDouble("text")),1e-8)
            rect(r.getJSONArray("pill"),NativeChiefHeaderPolicy.unreadFrame(r.getDouble("text")))
        } }
        assertEquals(44.0,NativeChiefHeaderPolicy.backWidth(null),0.0)
        v.getJSONArray("names").let{ a -> repeat(a.length()) { val r=a.getJSONObject(it);assertEquals(r.getDouble("output"),NativeChiefHeaderPolicy.nameSize(r.getDouble("input")),0.0) } }
        v.getJSONArray("typography").let{ a -> repeat(a.length()) {
            val r=a.getJSONObject(it);val body=r.getDouble("body")
            assertEquals(r.getDouble("title"),NativeChiefHeaderPolicy.titleSize(body),1e-8)
            assertEquals(r.getDouble("caption"),NativeChiefHeaderPolicy.captionSize(body),1e-8)
            assertEquals(v.getJSONArray("quickSizes").getDouble(it),NativeChiefHeaderPolicy.actionSize(body),1e-8)
        } }
        assertEquals(false,v.getJSONObject("defaults").getBoolean("muted"));assertEquals(true,v.getJSONObject("defaults").getBoolean("readReceipts"))
    }
    @Test fun portraitHeaderCapsItsNameButKeepsSourceControlAndAvatarFrames() {
        val h=NativeChiefHeaderPolicy.header(402.0,16.0,44.0,21.0,21.0,null,false)
        assertEquals(92.0,h.height,0.0);assertEquals(NativeChiefHeaderPolicy.Rect(171.0,0.0,60.0,60.0),h.avatar)
        assertEquals(55.0,h.name.y,0.0);assertEquals(97.0/3,h.name.height,1e-9)
        assertEquals(16.0,h.back.x,0.0);assertEquals(342.0,h.stop.x,0.0)
        val large=NativeChiefHeaderPolicy.header(402.0,16.0,400.0,26.0,21.0,23.0,false)
        assertEquals(250.0,large.name.width,0.0);assertEquals(97.0/3+5,large.name.height,1e-9)
        for(top in listOf(24.0,48.0)) {
            val compact=NativeChiefHeaderPolicy.header(844.0,20.0,44.0,21.0,21.0,null,true,safeTop=top)
            assertEquals(54.0,compact.height,0.0);assertEquals(0.0,compact.avatar.y,0.0)
        }
    }
}
