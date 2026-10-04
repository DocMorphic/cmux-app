package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/** SIGKILL the actual Gecko-owning process; instrumentation and loopback HTTP survive separately. */
class NativeNoticeProcessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private val manager get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val id = UUID.randomUUID().toString()
    private val directory get() = File(context.noBackupFilesDir, "notice-process-$id")
    private val store get() = NativeWhatsNewFileStore(directory)
    private val reports = LinkedBlockingQueue<JSONObject>()
    private val deferredReports = mutableListOf<JSONObject>()
    private val requests = CopyOnWriteArrayList<JSONObject>()
    private val server = MockWebServer()
    private fun pid() = manager.runningAppProcesses?.singleOrNull {
        it.processName == context.packageName + ":notice_process_test"
    }?.pid
    private fun waitFor(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (!test() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Condition timed out", test())
    }
    private fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15_000)) { "Missing $value" }
    private fun launch(hold: Boolean = false, oldContext: String? = null): Int {
        context.startActivity(Intent(context, NativeNoticeProcessActivity::class.java)
            .putExtra("fixtureId", id).putExtra("fixturePort", server.port).putExtra("holdExchange", hold)
            .apply { if (oldContext != null) putExtra("oldContext", oldContext) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor { File(directory, "pid").takeIf { it.exists() }?.readText()?.toIntOrNull() == pid() && pid() != null }
        return checkNotNull(pid())
    }
    private fun kill(): Int {
        val old = checkNotNull(pid()); assertNotEquals(Process.myPid(), old)
        Process.killProcess(old); waitFor { pid() == null }; return old
    }
    private fun report(path: String, pid: Int): JSONObject {
        fun matches(report: JSONObject) = report.getString("path") == path && report.getString("label") == pid.toString()
        deferredReports.firstOrNull(::matches)?.let { deferredReports.remove(it); return it }
        val end = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < end) {
            val report = reports.poll(1, TimeUnit.SECONDS) ?: continue
            if (matches(report)) return report
            deferredReports += report
        }
        error("Missing $path report for $pid: launch=${File(directory, "load-$pid").takeIf { it.exists() }?.readText()}, archive=${File(directory, "archive-$pid").takeIf { it.exists() }?.readText()}")
    }
    private fun captureRendered(name: String) {
        val end = SystemClock.elapsedRealtime() + 15_000
        do {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            var green = 0
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                val color = bitmap.getPixel(x, y)
                if (Color.red(color) in 18..26 && Color.green(color) in 159..167 && Color.blue(color) in 70..78) green++
            }
            if (green > 1000) {
                val file = File(context.getExternalFilesDir(null), "notice-process/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle(); return
            }
            bitmap.recycle(); SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        fail("No rendered notice pixels")
    }
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        assertNull(pid())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(JSONObject().put("path", request.path).put("cookie", request.getHeader("Cookie").orEmpty()))
                if (request.path == "/report") {
                    reports.add(JSONObject(request.body.readUtf8())); return MockResponse().setResponseCode(204)
                }
                val path = request.requestUrl!!.encodedPath
                val label = request.requestUrl!!.queryParameter("label").orEmpty()
                val cookie = request.getHeader("Cookie").orEmpty()
                return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                    .setBody("""<!doctype html><meta name="viewport" content="width=device-width">
                    <style>body{background:white;color:#111;font:24px sans-serif;margin:20px}
                    #proof{height:160px;background:rgb(22,163,74);padding:16px}</style>
                    <h1>Recovered notice</h1><div id="proof">Real private renderer</div><script>
                    const previous=localStorage.getItem('notice-process-value');
                    const before=document.cookie;
                    if (${JSONObject.quote(path)} === '/page') {
                      localStorage.setItem('notice-process-value',${JSONObject.quote(label)});
                      document.cookie='notice-durable='+${JSONObject.quote(label)}+'; Max-Age=86400; Path=/';
                    }
                    fetch('/report',{method:'POST',body:JSON.stringify({path:${JSONObject.quote(path)},label:${JSONObject.quote(label)},
                      cookie:${JSONObject.quote(cookie)},previous:previous,before:before,after:document.cookie,
                      stored:localStorage.getItem('notice-process-value')})});</script>""")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }
    @After fun cleanup() {
        val output = File(context.getExternalFilesDir(null), "notice-process").apply { mkdirs() }
        val statuses = JSONObject()
        directory.listFiles().orEmpty().filter { it.name.startsWith("load-") || it.name.startsWith("archive-") || it.name == "pid" }
            .forEach { statuses.put(it.name, it.readText()) }
        File(output, "diagnostics-$id.json").writeText(JSONObject().put("requests", JSONArray(requests))
            .put("statuses", statuses).toString(2))
        device.takeScreenshot(File(output, "end-$id.png"))
        manager.appTasks.filter { it.taskInfo?.baseIntent?.component?.className == NativeNoticeProcessActivity::class.java.name }
            .forEach { it.finishAndRemoveTask() }
        if (pid() != null) kill()
        server.close(); directory.deleteRecursively()
    }
    @Test fun killedEngineRetriesUnseenPreservesAcknowledgementAndLosesPrivateSessionState() {
        val parent = Process.myPid()
        val pending = launch(hold = true)
        waitFor { File(directory, "exchange-$pending").exists() }
        assertNull(store.read(NativeWhatsNewCenter.MARKER)); assertEquals(0, server.requestCount)
        assertEquals(pending, kill())

        val shown = launch()
        assertNotEquals(pending, shown)
        val before = report("/page", shown)
        assertEquals("stack-access=fixture-$shown", before.getString("cookie"))
        assertTrue(before.isNull("previous")); assertEquals("", before.getString("before"))
        assertEquals("notice-durable=$shown", before.getString("after")); assertEquals("$shown", before.getString("stored"))
        captureRendered("after-pending-process-death")
        waitFor { store.read(NativeWhatsNewCenter.MARKER) == "web" }
        val oldContext = File(directory, "context-$shown").readText()
        assertEquals(shown, kill()) // No graceful page close or cleanup callback.

        val restored = launch(oldContext = oldContext)
        assertNotEquals(shown, restored)
        text("Notice refresh complete")
        text("Unseen notices: 0")
        assertFalse(device.hasObject(By.text("Done")))
        assertFalse(File(directory, "exchange-$restored").exists())
        text("Open notice archive").click(); text("Process recovery notice").click()
        val fresh = report("/page", restored)
        assertEquals("stack-access=fixture-$restored", fresh.getString("cookie"))
        assertTrue(fresh.isNull("previous")); assertEquals("", fresh.getString("before"))
        captureRendered("archive-after-loaded-process-death")
        // The debug probe runs after production startup, while the new page is still
        // open. No last-private-page cleanup can explain an empty killed context.
        val retired = report("/probe", restored)
        assertEquals("", retired.getString("cookie")); assertEquals("", retired.getString("before"))
        assertTrue(retired.isNull("previous")); assertTrue(retired.isNull("stored"))
        text("‹ Back").click(); text("‹ Back").click()
        assertEquals("web", store.read(NativeWhatsNewCenter.MARKER)); assertEquals(parent, Process.myPid())
        val output = File(context.getExternalFilesDir(null), "notice-process/report.json")
        output.parentFile!!.mkdirs()
        output.writeText(JSONObject().put("pendingPid", pending).put("shownPid", shown).put("restoredPid", restored)
            .put("before", before).put("fresh", fresh).put("killedContext", retired).toString(2))
    }
}
