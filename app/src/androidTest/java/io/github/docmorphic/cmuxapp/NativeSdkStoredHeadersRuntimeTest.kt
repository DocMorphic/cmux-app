package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NativeSdkStoredHeadersRuntimeTest {
    @org.junit.Before fun emulatorOnly(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))}
    @Test fun onlyUserPresencePreflightsAndAllSdkRequestsReadFreshStoredHeaders()=runBlocking<Unit> {
        MockWebServer().use{server->val preflights=AtomicInteger();val snapshots=AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                // The presence token may be replaced/removed before sendRequest reads storage.
                assertNull(request.getHeader("x-hexclave-access-token"))
                assertEquals("wire-refresh",request.getHeader("x-hexclave-refresh-token"))
                return when {
                    request.method=="PATCH"->MockResponse().setResponseCode(204)
                    request.path=="/api/v1/users/me"->MockResponse().setBody("{\"id\":\"user\",\"selected_team\":{\"id\":\"one\"}}")
                    request.path=="/api/v1/teams?user_id=me"->MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}")
                    else->MockResponse().setResponseCode(599)
                }
            }}
            NativeAccountTeams({force->assertFalse(force);preflights.incrementAndGet();"presence-access"},{"login"},server.url("/api/v1/"),
                storedTokens={snapshots.incrementAndGet();NativeSdkTokens("wire-refresh",null)}).use{teams->
                assertEquals("one",teams.refresh().scope?.teamId);assertEquals(2,preflights.get());assertEquals(3,snapshots.get())
                teams.select("one");assertEquals(3,preflights.get());assertEquals(5,snapshots.get());assertEquals(5,server.requestCount)
            }
        }
    }
}
