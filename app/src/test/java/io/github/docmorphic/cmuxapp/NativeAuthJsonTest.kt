package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NativeAuthJsonTest {
    private val reference by lazy{JSONObject(checkNotNull(javaClass.getResourceAsStream("/auth/native-sdk-json-reference.json"))
        .bufferedReader().use{it.readText()})}
    private fun wire(name:String):ByteArray {
        val rows=reference.getJSONArray("wire")
        val row=(0 until rows.length()).map{rows.getJSONObject(it)}.single{it.getString("case")==name}
        return Base64.getDecoder().decode(row.getString("base64"))
    }
    private fun JSONObject.text(key:String)=opt(key).takeUnless{it===JSONObject.NULL} as? String
    @Test fun byteDecoderMatchesAll368ExecutedOriginalWireProjections() {
        val rows=reference.getJSONArray("wire");assertEquals(368,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val name=row.getString("case")
            val parsed=runCatching{NativeAuthJson.objectValue(Base64.getDecoder().decode(row.getString("base64")))}
            assertEquals(name,!row.isNull("user"),parsed.isSuccess)
            if(parsed.isSuccess){val value=parsed.getOrThrow();val expected=row.getJSONObject("user")
                assertEquals(name,expected.getBoolean("eligible"),NativeSignInUser.isEligible(value))
                val user=runCatching{NativeSignInUser.decode(value)}
                assertEquals(name,expected.getBoolean("eligible"),user.isSuccess)
                if(user.isSuccess){val actual=user.getOrThrow()
                    assertEquals(name,expected.getString("id"),actual.id);assertEquals(name,expected.text("name"),actual.name)
                    assertEquals(name,expected.text("email"),actual.email);assertEquals(name,expected.text("image"),actual.profileImageUrl)
                    assertEquals(name,expected.text("selected"),actual.selectedTeam);assertEquals(name,expected.getBoolean("demo"),actual.demonstrationContentEnabled)}
            }
            val teams=parsed.getOrNull()?.let(NativeSdkTeamRows::decode).orEmpty();val expected=row.getJSONArray("teams")
            assertEquals(name,expected.length(),teams.size)
            teams.forEachIndexed{n,t->assertEquals(name,expected.getJSONObject(n).getString("id"),t.id)
                assertEquals(name,expected.getJSONObject(n).getString("name"),t.name)}
        }
    }
    @Test fun malformedLegacySelectionExtensionsCannotDiscardTheSharedAccountOrOverrideChoice() {
        val rows=reference.getJSONArray("cache");assertEquals(8,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val payload=row.getJSONObject("payload")
            val cache=JSONObject().put("version",1).put("environment","fixture").put("user",payload.opt("id"))
            for((source,target) in listOf("displayName" to "name","selected" to "selected","demonstrationContentEnabled" to "demonstration_content_enabled"))
                if(payload.has(source))cache.put(target,payload.opt(source))
            val root=JSONObject().put(NativeAuthUserCache.KEY,cache)
            NativeTeamSelectionStore.write(root,"fixture","remembered")
            val user=NativeAuthUserCache.read(root,"fixture")
            if(row.isNull("id"))assertNull(user)else{assertEquals(row.getString("id"),user!!.id);assertEquals(row.text("name"),user.name)
                assertEquals(payload.opt("selected") as? String,user.selectedTeam)}
            assertEquals("remembered",NativeTeamSelectionStore.read(root,"fixture"))
        }
    }
    private fun response(bytes:ByteArray)=MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type","text/plain; charset=us-ascii")
    @Test fun actualUserHttpUsesFoundationByteEncodingInsteadOfContentTypeOrUtf8Replacement()=runBlocking<Unit> {
        for(name in listOf("utf-16-le-bom","utf-32-be","duplicate-id","object-trailing-comma"))MockWebServer().use{server->
            server.enqueue(response(wire(name)))
            val user=NativeOAuthClient("fixture","public",server.url("/")).currentUser("fixture-access")
            assertEquals(name,"wire-user",user.id)
        }
    }
    @Test fun actualMembershipHttpUsesTheSameByteDecoderForBothUserReadsAndTeams()=runBlocking<Unit> {
        for(name in listOf("utf-16-be-bom","utf-32-le","duplicate-items","array-trailing-comma"))MockWebServer().use{server->
            server.teamUsers(2){response(wire(name))};server.enqueue(response(wire(name)))
            NativeAccountTeams({"fixture-access"},{"login"},server.url("/api/v1/")).use{c->val value=c.refresh()
                assertEquals(name,"wire-user",value.userId);assertEquals(name,"one",value.scope?.teamId)
                assertEquals(name,listOf(NativeTeam("one","One")),value.teams)}
        }
    }
    @Test fun invalidSuccessfulListRetiresAuthorityWhileInvalidUserOnlyPreservesExistingMembership()=runBlocking<Unit> {
        MockWebServer().use{server->NativeAccountTeams({"fixture-access"},{"login"},server.url("/api/v1/")).use{c->
            server.teamUsers(2){response(wire("valid"))};server.enqueue(response(wire("valid")))
            val first=c.refresh().scope!!;val proof=c.captureContinuation(first)!!
            server.teamUsers(2){response(wire("trailing-text"))}
            assertTrue(runCatching{c.refresh()}.exceptionOrNull() is NativeSessionUserUnavailable)
            assertEquals(first,c.state.value.scope);assertTrue(c.allowsContinuation(proof))
            server.teamUsers(2){response(wire("valid"))};server.enqueue(response(wire("bare-key")))
            val empty=c.refresh();assertTrue(empty.teams.isEmpty());assertTrue(c.hasLoadedMembership)
            assertNull(empty.scope);assertFalse(c.allowsContinuation(proof));assertFalse(empty.error.orEmpty().contains("private-body-canary"))
        }}
    }
    @Test fun duplicateAnonymousFlagsFollowFirstValueRatherThanAndroidLastValue()=runBlocking<Unit> {
        for((name,eligible) in listOf("duplicate-anonymous-true-first" to false,"duplicate-anonymous-false-first" to true))
            MockWebServer().use{server->server.teamUsers(2){response(wire(name))};if(eligible)server.enqueue(response(wire("valid")))
                NativeAccountTeams({"fixture-access"},{"login"},server.url("/api/v1/")).use{c->val result=runCatching{c.refresh()}
                    assertEquals(name,eligible,result.isSuccess);assertEquals(if(eligible)3 else 2,server.requestCount)
                    assertEquals(eligible,c.state.value.scope!=null)}
            }
    }
    @Test fun invalidBytePayloadHasTheExistingInteractiveAndRestoreErrorProjection()=runBlocking<Unit> {
        MockWebServer().use{server->val client=NativeOAuthClient("fixture","public",server.url("/"))
            server.enqueue(response(wire("utf8-surrogate")))
            val error=runCatching{client.currentUser("fixture-access")}.exceptionOrNull() as NativeSignInFailure
            assertEquals(NativeSignInError.SERVER_ERROR,error.reason)
            server.enqueue(response(wire("utf8-surrogate")))
            assertNull(client.restoredCurrentUser("fixture-access"){error("Unexpected refresh")})
        }
    }
    @Test fun knownErrorMessageUsesBytesAndBareHttpBodyCannotInventACode()=runBlocking<Unit> {
        MockWebServer().use{server->val client=NativeOAuthClient("fixture","public",server.url("/"))
            server.enqueue(response("{\"error\":\"wire-error\"}".toByteArray(Charsets.UTF_16BE)).setResponseCode(400).setHeader("x-hexclave-known-error","wire-error"))
            assertEquals("wire-error",(runCatching{client.currentUser("fixture-access")}.exceptionOrNull() as NativeAccountRequestFailure).code)
            server.enqueue(response("{error:'wire-error'}".toByteArray()).setResponseCode(400))
            assertEquals("http_error",(runCatching{client.currentUser("fixture-access")}.exceptionOrNull() as NativeAccountRequestFailure).code)
        }
    }
}
