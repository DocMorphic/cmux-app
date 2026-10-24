package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NativeAccessTokenValidityTest {
    private fun token(payload:String)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())+".fixture"
    @Test fun expiryAndBothRefreshLeadsMatchExecutionOfWholeOriginalSdkDeclarations() {
        val reference=JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-token-validity-reference.json")).bufferedReader().use { it.readText() })
        assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009",reference.getString("upstream"))
        val rows=reference.getJSONArray("rows");assertEquals(16,rows.length())
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index);val value=if(row.isNull("token"))null else row.getString("token")
            val now=row.getDouble("nowMillis").toLong();val id=row.getString("id")
            assertEquals(id,row.getBoolean("expired"),nativeAccessTokenIsExpired(value,now))
            assertEquals(id,row.getBoolean("fresh60"),nativeCapturedAccessIsFresh(value,now,60_000))
            assertEquals(id,row.getBoolean("fresh120"),nativeCapturedAccessIsFresh(value,now,120_000))
        }
    }
    @Test fun actualExpiryUsesIntegerMillisecondsWithoutApplyingTheRefreshLead() {
        assertTrue(nativeAccessTokenIsExpired(token("{\"exp\":100}"),100_000))
        assertTrue(nativeAccessTokenIsExpired(token("{\"exp\":100.0009}"),100_000))
        assertFalse(nativeAccessTokenIsExpired(token("{\"exp\":100.001}"),100_000))
        assertFalse(nativeAccessTokenIsExpired(token("{\"exp\":110}"),100_000))
        assertFalse(nativeCapturedAccessIsFresh(token("{\"exp\":110}"),100_000,60_000))
    }
}
