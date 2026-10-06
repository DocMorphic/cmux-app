package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NativeWhatsNewFeedTest {
    private val content = """{"visibleEntryIds":["android.introduction.0.2.0","android.pairing.iroh-v2"],"announcements":[]}"""

    @Test fun successfulFetchIsAnonymousAndDoesNotReplayResponseCookies() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(content).addHeader("Set-Cookie", "unrelated=discard; Path=/"))
            server.enqueue(MockResponse().setBody(content))
            NativeWhatsNewFeed(server.url("/android/notices.json").toString()).use { feed ->
                repeat(2) {
                    assertEquals(content, feed.fetch())
                    val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("GET", request.method)
                    assertEquals("/android/notices.json", request.path)
                    assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
                    assertEquals(0L, request.bodySize)
                    assertEquals("application/json", request.getHeader("Accept"))
                }
            }
        }
    }

    @Test fun redirectsAndErrorsNeverBecomeVisibilityDataOrFollowupRequests() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (code in listOf(302, 204, 404, 503)) {
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", server.url("/unexpected")).setBody(content))
                NativeWhatsNewFeed(server.url("/notice").toString()).use { feed ->
                    assertTrue(runCatching { feed.fetch() }.exceptionOrNull() is IOException)
                }
            }
            assertEquals(4, server.requestCount)
            repeat(4) { assertEquals("/notice", server.takeRequest().path) }
        }
    }

    @Test fun byteLimitAppliesToDeclaredAndChunkedUtf8Bodies() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val tooLarge = "λ".repeat((NativeWhatsNewFeed.MAX_BYTES / 2 + 1).toInt())
            server.enqueue(MockResponse().setBody(tooLarge))
            server.enqueue(MockResponse().setChunkedBody(tooLarge, 8192))
            NativeWhatsNewFeed(server.url("/notice").toString()).use { feed ->
                repeat(2) { assertTrue(runCatching { feed.fetch() }.exceptionOrNull() is IOException) }
            }
        }
    }

    @Test fun cancellationAndOwnerCloseRetirePendingFetch() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            NativeWhatsNewFeed(server.url("/notice").toString()).use { feed ->
                val pending = async(Dispatchers.Default) { feed.fetch() }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(2_000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                feed.close()
                assertTrue(runCatching { feed.fetch() }.exceptionOrNull() is IOException)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun distributionEndpointIdentityIncludesPathWithoutTrustingItsWebOrigin() {
        NativeWhatsNewFeed("https://raw.githubusercontent.com/owner/a/main/notice.json").use { first ->
            NativeWhatsNewFeed("https://raw.githubusercontent.com/owner/b/main/notice.json").use { second ->
                assertNotEquals(first.cacheIdentity, second.cacheIdentity)
                assertFalse(WhatsNewWebPolicy(null).allows("https://raw.githubusercontent.com/owner/a/main/page.html"))
            }
        }
        for (bad in listOf("http://cmux.com/notices", "https://u:p@cmux.com/notices", "https://cmux.com/notices#fragment", "file:///tmp/notices")) {
            assertTrue(bad, runCatching { NativeWhatsNewFeed(bad).close() }.isFailure)
        }
    }

    @Test fun shippedFeedUsesExistingAndroidIdsAndLeavesProductionTargetingExplicit() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "distribution/android-notices.json").isFile }
        val feed = WhatsNewRemote.decode(File(root, "distribution/android-notices.json").readText())
        assertTrue(feed.visibleEntryIds.isNotEmpty())
        assertEquals(feed.visibleEntryIds.size, feed.visibleEntryIds.distinct().size)
        assertTrue(feed.visibleEntryIds.all { id -> NativeWhatsNewCatalog.pages.any { it.id == id } })
        assertTrue(feed.announcements.isEmpty())
        assertTrue(feed.entryChannels.isEmpty())
    }
}
