package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Locale

class NativeSdkTransportTest {
    private fun normalized(value:Any?):Any?=when(value) {
        null,JSONObject.NULL->null
        is JSONObject->value.keys().asSequence().toList().sorted().associateWith{normalized(value.opt(it))}
        is JSONArray->(0 until value.length()).map{normalized(value.opt(it))}
        is Number->java.math.BigDecimal(value.toString()).stripTrailingZeros()
        else->value
    }
    @Test fun completeOriginalTransportReference()=runBlocking<Unit> {
        val reference=JSONObject(checkNotNull(javaClass.getResource("/auth/native-sdk-transport-reference.json")).readText())
        val cases=reference.getJSONArray("cases")
        assertEquals(82,cases.length())
        for(i in 0 until cases.length()) {
            val row=cases.getJSONObject(i);val input=row.getJSONObject("input");val name=input.getString("case")
            val authenticated=input.optBoolean("authenticated",true)
            val initial=nativeSdkRequest("https://fixture.invalid/api/v1/users/me".toHttpUrl(),"fixture-project","fixture-key",
                input.optString("method","GET"),input.optJSONObject("body"),
                "old-access".takeIf{authenticated},"stored-refresh".takeIf{authenticated})
            val requests=mutableListOf<Request>();val rejected=JSONArray();val delays=JSONArray();val events=input.getJSONArray("events")
            val outcome=try {
                val result=nativeSdkSend(initial,authenticated,refreshRejected={failed->rejected.put(failed);input.opt("refresh") as? String},
                    sleep={delays.put(it)}) {request->
                    requests+=request
                    val event=events.getJSONObject((requests.size-1).coerceAtMost(events.length()-1))
                    if(event.optBoolean("network"))throw IOException("fixture offline")
                    if(event.optBoolean("cancel"))throw CancellationException()
                    val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(event.getInt("status"))
                        .message("fixture").body(event.getString("body").toResponseBody(null)).apply {
                            val headers=event.getJSONObject("headers")
                            val flat=headers.keys().asSequence().flatMap{sequenceOf(it,headers.getString(it))}.toList().toTypedArray()
                            // Inert source HTTPURLResponse preserves raw header values. Preserve them here too;
                            // real HTTP carrier whitespace handling is tested separately through MockWebServer.
                            val rawHeaders=Headers::class.java.getDeclaredConstructor(Array<String>::class.java)
                                .apply{isAccessible=true}.newInstance(flat as Any)
                            headers(rawHeaders)
                        }.build()
                    response.use{nativeSdkCheckResponse(it,event.getString("body").toByteArray());it.code}
                }
                JSONObject().put("success",true).put("physicalStatus",result)
            } catch(failure:NativeSdkRequestFailure) {
                JSONObject().put("success",false).put("code",failure.code)
                    .put("message",if(failure.code=="network_error")"<platform network message>" else failure.message)
                    .put("details",failure.details ?: JSONObject.NULL)
            } catch(_:CancellationException) {JSONObject().put("success",false).put("cancelled",true)}
            assertEquals(name+" outcome",normalized(row.getJSONObject("outcome")),normalized(outcome))
            assertEquals(name+" refresh args",normalized(row.getJSONArray("rejectedAccess")),normalized(rejected))
            assertEquals(name+" backoff",normalized(row.getJSONArray("delaysMillis")),normalized(delays))
            assertEquals(name+" count",row.getJSONArray("requests").length(),requests.size)
            assertEquals(name+" nonce lifetime",row.getBoolean("stableNonce"),requests.map{it.header("x-hexclave-random-nonce")}.toSet().size==1)
            for(j in requests.indices) {
                val expected=row.getJSONArray("requests").getJSONObject(j);val actual=requests[j]
                assertEquals(name,expected.getString("method"),actual.method)
                val headers=JSONObject();actual.headers.forEach{(k,v)->headers.put(k.lowercase(Locale.ROOT),v)}
                val nonce=checkNotNull(actual.header("x-hexclave-random-nonce"))
                assertTrue(name,nonce.matches(Regex("[A-F0-9]{8}(-[A-F0-9]{4}){3}-[A-F0-9]{12}")))
                headers.put("x-hexclave-random-nonce","UUID")
                // Platform identity is intentionally Android; the source identifies its Swift SDK.
                assertEquals("cmux-android@0.2.0",headers.getString("x-hexclave-client-version"))
                headers.put("x-hexclave-client-version","swift@1.0.0")
                assertEquals(name+" headers",normalized(expected.getJSONObject("headers")),normalized(headers))
                val body=actual.body?.let{okio.Buffer().also(it::writeTo).readUtf8()}
                assertEquals(name,expected.opt("body").takeUnless{it===JSONObject.NULL},body)
            }
        }
    }

    @Test fun cancellationDuringBackoffRetiresTheLogicalRequest()=runTest {
        var attempts=0;val sleeping=CompletableDeferred<Unit>()
        val request=nativeSdkRequest("https://fixture.invalid/api/v1/users/me".toHttpUrl(),"p","k")
        val operation=launch {nativeSdkSend<Unit>(request,false,sleep={sleeping.complete(Unit);delay(it)}) {attempts++;throw IOException("offline")}}
        sleeping.await();operation.cancelAndJoin();advanceTimeBy(100_000)
        assertEquals(1,attempts)
    }

    @Test fun changedAccountBetweenAttemptsPreventsReplay()=runBlocking<Unit> {
        var attempts=0;var current=true
        val request=nativeSdkRequest("https://fixture.invalid/api/v1/users/me".toHttpUrl(),"p","k")
        val failure=runCatching {nativeSdkSend<Unit>(request,false,checkCurrent={if(!current)throw CancellationException("changed")},
            sleep={current=false}) {attempts++;throw IOException("offline")}}.exceptionOrNull()
        assertTrue(failure is CancellationException);assertEquals(1,attempts)
    }
}
