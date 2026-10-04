package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mozilla.geckoview.*
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Test-only sessions populate cross-site state in the exact contexts retired by production.
 * No production URL policy, DNS, TLS trust or storage preference is modified. */
class NativeNoticePartitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reports = LinkedBlockingQueue<JSONObject>()
    private val observations = JSONArray()
    private var serial = 0
    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun <T> field(instance: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance) as T
    }
    private fun ready(page: NativeNoticeRenderer) {
        val phase = runBlocking { withTimeout(25_000) { page.load.outcome() } }
        assertEquals(page.diagnostic, WhatsNewWebPhase.LOADED, phase)
    }
    private fun runCase(recovery: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val one = MockWebServer(); val two = MockWebServer()
        val html = instrumentation.context.assets.open("notice/partition-storage.html").bufferedReader().use { it.readText() }
        var owner: NativeNoticeRenderer? = null; var fresh: NativeNoticeRenderer? = null
        var control: GeckoSession? = null; var borrowed: GeckoSession? = null
        val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "notice-partitions").apply { mkdirs() }
        var passed = false
        var phase = "preparing"
        fun persist() { java.io.File(output,if(recovery) "recovery.json" else "retirement.json").writeText(
            JSONObject().put("passed",passed).put("phase",phase).put("observations",observations).toString(2)) }
        try {
            val dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    return when (url.encodedPath) {
                        "/owner" -> MockResponse().setHeader("Content-Type","text/html").setBody("<title>Owned context</title>")
                        "/outer" -> MockResponse().setHeader("Content-Type","text/html").setHeader("Cache-Control","no-store")
                            .setBody("<iframe src=\"http://localhost:${one.port}/frame?${url.encodedQuery}\"></iframe>")
                        "/frame" -> MockResponse().setHeader("Content-Type","text/html").setHeader("Cache-Control","no-store").setBody(html).apply {
                            url.queryParameter("write")?.let { value ->
                                require(value.matches(Regex("[AB][12]")))
                                setHeader("Set-Cookie", "__Host-partition_probe=$value; Path=/; HttpOnly; SameSite=None; Secure; Partitioned")
                            }
                        }
                        "/report" -> {
                            reports.add(JSONObject(request.body.readUtf8()).put("id",url.queryParameter("id"))
                                .put("cookie",request.getHeader("Cookie").orEmpty()))
                            MockResponse().setResponseCode(204)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            one.dispatcher = dispatcher; two.dispatcher = dispatcher
            one.start(InetAddress.getByName("127.0.0.1"),0)
            two.start(InetAddress.getByName("127.0.0.2"),0)
            val origin = "http://127.0.0.1:${one.port}"
            owner = main { NativeNoticeRenderer(instrumentation.targetContext,scope,WhatsNewWebPolicy(origin),
                "$origin/owner",false,20_000,{true}) { emptyList() } }
            ready(owner)
            val context = field<String>(owner,"contextId")
            val engine = field<NativeNoticeEngine>(owner,"engine")
            val runtime = field<GeckoRuntime>(engine,"runtime")
            val controlContext = "partition-control-${UUID.randomUUID()}"
            control = main { GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(controlContext).build()).also {
                it.open(runtime); it.loadUri("$origin/owner")
            } }
            fun probe(contextId: String, site: Int, label: String, write: String? = null): JSONObject {
                val id = "${++serial}-$label"
                val top = if (site == 1) "http://127.0.0.1:${one.port}" else "http://127.0.0.2:${two.port}"
                // These auxiliary test sessions share only the selected private context.
                // They create fixture state beyond the normal notice host allowlist.
                borrowed = main { GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).contextId(contextId).build()).also {
                    it.open(runtime); it.loadUri("$top/outer?id=$id" + (write?.let { value -> "&write=$value" } ?: ""))
                } }
                try {
                    val result = reports.poll(20,TimeUnit.SECONDS) ?: throw AssertionError("No partition report for $id")
                    observations.put(result.put("site",site).put("case",label)); persist()
                    assertEquals(id,result.getString("id"))
                    assertFalse(result.toString(),result.has("error")); assertTrue(result.getBoolean("secureContext"))
                    assertEquals("",result.getString("scriptCookie"))
                    return result
                } finally { main { borrowed?.close(); borrowed = null } }
            }
            fun values(value: JSONObject, expected: String?) {
                for (key in listOf("local","indexed","cache")) {
                    if (expected == null) assertTrue("$key: $value",value.isNull(key))
                    else assertEquals(expected,value.getString(key))
                }
            }
            for (site in 1..2) {
                val a = probe(context,site,"write-A$site","A$site")
                values(a.getJSONObject("before"),null); values(a.getJSONObject("after"),"A$site")
                assertEquals("__Host-partition_probe=A$site",a.getString("cookie"))
                val b = probe(controlContext,site,"write-B$site","B$site")
                values(b.getJSONObject("before"),null); values(b.getJSONObject("after"),"B$site")
                assertEquals("__Host-partition_probe=B$site",b.getString("cookie"))
            }
            // Reading both again proves that these are actually distinct top-site partitions.
            for (site in 1..2) {
                val read = probe(context,site,"before-retire-A$site")
                values(read.getJSONObject("before"),"A$site")
                assertEquals("__Host-partition_probe=A$site",read.getString("cookie"))
            }
            phase = if (recovery) "recovery-disconnect" else "retirement"; persist()
            if (recovery) {
                val disabled = CompletableDeferred<Unit>()
                main {
                    val extension = field<WebExtension.Port>(engine,"port").sender.webExtension
                    runtime.webExtensionController.setAllowedInPrivateBrowsing(extension,false).accept(
                        { disabled.complete(Unit) },{ disabled.completeExceptionally(it ?: IllegalStateException("Disable failed")) })
                }
                runBlocking { withTimeout(15_000) { disabled.await(); while (!main { owner.isClosed.value }) delay(25) } }
                fresh = main { NativeNoticeRenderer(instrumentation.targetContext,scope,WhatsNewWebPolicy(origin),
                    "$origin/owner",false,20_000,{true}) { emptyList() } }
                ready(fresh)
            } else {
                main { owner.close() }
                assertTrue(runBlocking { withTimeout(10_000) { owner.awaitRetired() } })
            }
            // Public storage clearing is asynchronous. Observe the actual retired context,
            // without substituting a new UUID; keep every observation if clearing takes time.
            phase = "verify-cleanup"; persist()
            val until = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            for (site in 1..2) {
                var empty: JSONObject
                do {
                    empty = probe(context,site,"retired-A$site")
                    val before = empty.getJSONObject("before")
                    if (empty.getString("cookie").isEmpty() && listOf("local","indexed","cache").all(before::isNull)) break
                } while (System.nanoTime()<until)
                values(empty.getJSONObject("before"),null); assertEquals("",empty.getString("cookie"))
                val intact = probe(controlContext,site,"preserved-B$site")
                values(intact.getJSONObject("before"),"B$site")
                assertEquals("__Host-partition_probe=B$site",intact.getString("cookie"))
            }
            passed = true; phase = "complete"
        } finally {
            persist()
            main { borrowed?.close(); control?.close(); owner?.close(); fresh?.close() }
            scope.cancel(); one.shutdown(); two.shutdown()
        }
    }
    @Test fun retirementClearsBothActualTopSitePartitionsAndPreservesAnotherPrivateContext() = runCase(false)
    @Test fun extensionRecoveryClearsBothActualTopSitePartitionsAndPreservesUnownedContext() = runCase(true)
}
