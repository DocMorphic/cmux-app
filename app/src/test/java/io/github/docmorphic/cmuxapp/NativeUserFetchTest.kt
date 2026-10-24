package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class NativeUserFetchTest {
    private fun normalized(value:Any?):Any?=when(value) {
        null,JSONObject.NULL->null
        is JSONObject->value.keys().asSequence().toList().sorted().associateWith{normalized(value.opt(it))}
        is JSONArray->(0 until value.length()).map{normalized(value.opt(it))}
        is Number->java.math.BigDecimal(value.toString()).stripTrailingZeros()
        else->value
    }
    private fun sdkError(code:String):Nothing {
        val request=Request.Builder().url("https://fixture.invalid/api/v1/users/me").build()
        val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("fixture")
            .header("x-hexclave-known-error",code).header("x-hexclave-request-id","request")
            .body("{\"message\":\"private-canary\"}".toResponseBody(null)).build()
        response.use{nativeSdkCheckResponse(it,"{\"message\":\"private-canary\"}".toByteArray())}
        error("Expected SDK error")
    }
    @Test fun completeOriginalGetUserAdaptersAndSignInProjection()=runBlocking<Unit> {
        val reference=JSONObject(checkNotNull(javaClass.getResource("/auth/native-user-fetch-reference.json")).readText())
        val rows=reference.getJSONArray("cases");assertEquals(252,rows.length())
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val input=row.getJSONObject("input");val consumer=row.getString("consumer")
            val name=input.getString("case")+"/"+consumer;val calls=JSONArray()
            suspend fun user(throwOnMissing:Boolean):NativeSignInUser?=nativeSdkGetUser(throwOnMissing,input.optBoolean("hasTokens",true)) {
                calls.put("/users/me")
                (input.opt("error") as? String)?.let{sdkError(it)}
                when(input.optString("errorKind")) {
                    "network"->throw IOException("private-canary")
                    "cancel"->throw CancellationException()
                    "foreign-rate"->throw NativeAccountRequestFailure("RATE_LIMITED",false,"private-canary")
                }
                runCatching{NativeAuthJson.objectValue(Base64.getDecoder().decode(input.getString("wire")))}.getOrNull()
            }
            val outcome=try {
                when(consumer) {
                    "sdk-throw","sdk-null"->user(consumer=="sdk-throw")?.let{JSONObject().put("id",it.id).put("name",it.name ?: JSONObject.NULL)
                        .put("selected",it.selectedTeam ?: JSONObject.NULL)} ?: JSONObject().put("nil",true)
                    "client-throw","client-null"->user(consumer=="client-throw")?.let{JSONObject().put("id",it.id).put("name",it.name ?: JSONObject.NULL)} ?: JSONObject().put("nil",true)
                    "selected"->JSONObject().put("selected",user(false)?.selectedTeam ?: JSONObject.NULL)
                    "teams"->{user(true) ?: throw NativeSessionUserUnavailable();calls.put("/teams?user_id=me")
                        (input.opt("teamsError") as? String)?.let{sdkError(it)}
                        val raw=(input.opt("teamsWire") as? String)?.let{Base64.getDecoder().decode(it)} ?: "{\"items\":[{\"id\":\"team\",\"display_name\":\"Team\"}]}".toByteArray()
                        val value=runCatching{NativeAuthJson.objectValue(raw)}.getOrElse{JSONObject()}
                        JSONObject().put("teams",JSONArray(NativeSdkTeamRows.decode(value).map{JSONObject().put("id",it.id).put("name",it.name)}))}
                    else->{val value=user(true) ?: throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED);JSONObject().put("id",value.id)}
                }
            } catch(failure:Exception) {
                val code=when(failure) {
                    is NativeAccountRequestFailure->failure.code
                    is NativeSessionUserUnavailable->"user_unavailable"
                    is NativeSignInFailure->failure.reason.name
                    else->error("Unexpected failure in $name: ${failure.javaClass.simpleName}")
                }
                val expected=row.getJSONObject("outcome");val detail=expected.getString("displayDetail").substringBefore(" ·")
                val expectedRole=when(detail) {"AUTH_FAILED"->NativeSignInError.SERVER_ERROR;"UNAUTHORIZED"->NativeSignInError.UNAUTHORIZED
                    "RATE_LIMITED","RATE_LIMIT"->NativeSignInError.RATE_LIMIT;"SCHEMA_ERROR"->NativeSignInError.INVALID_EMAIL;else->error("Unmapped source role: $detail")}
                assertEquals(name,expectedRole,nativeSignInError(failure))
                JSONObject().put("error",code)
            }
            val expectedProjection=JSONObject(row.getJSONObject("outcome").toString()).apply{remove("displayDetail")}
            assertEquals(name,normalized(expectedProjection),normalized(outcome))
            assertEquals(name,normalized(row.getJSONArray("calls")),normalized(calls))
        }
    }
    @Test fun parentCancellationCannotBecomeAnAccountError()=runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>();var failure:Throwable?=null
        val job=launch {failure=runCatching{nativeSdkGetUser{entered.complete(Unit);awaitCancellation()}}.exceptionOrNull()}
        entered.await();job.cancelAndJoin();assertTrue(failure is CancellationException);assertTrue(currentCoroutineContext().isActive)
    }
    @Test fun realHttpSignInUserUsesSourceRolesAndHidesProviderErrorMessages()=runBlocking<Unit> {
        val cases=listOf(Triple("bad",null,NativeSignInError.UNAUTHORIZED),Triple("{\"is_anonymous\":true}",null,NativeSignInError.SERVER_ERROR),
            Triple("{\"is_restricted\":true}",null,NativeSignInError.SERVER_ERROR),Triple("{}","SCHEMA_ERROR",NativeSignInError.SERVER_ERROR),
            Triple("{}","UNAUTHORIZED",NativeSignInError.SERVER_ERROR),Triple("{}","RATE_LIMITED",NativeSignInError.RATE_LIMIT),
            Triple("{}","rate_limited",NativeSignInError.SERVER_ERROR))
        for((body,known,role) in cases)MockWebServer().use{server->
            server.enqueue(MockResponse().setBody(if(known!=null)"{\"message\":\"private-canary\"}" else body).apply{known?.let{setHeader("x-hexclave-known-error",it)}})
            val failure=runCatching{NativeOAuthClient("p","k",server.url("/")).currentUser("fixture-access")}.exceptionOrNull() as Exception
            assertEquals("$body/$known",role,nativeSignInError(failure));assertEquals(1,server.requestCount)
            if(role!=NativeSignInError.RATE_LIMIT)assertFalse(failure.message.orEmpty().contains("private-canary"))
        }
    }
}
