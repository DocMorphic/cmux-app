package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class NativeCachedComputerProcessTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val manager get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val activity = NativeCachedComputerProcessActivity::class.java
    private val id = UUID.randomUUID().toString()
    private val server = MockWebServer()
    private fun marker(suffix: String) = File(context.filesDir, "cached-process-$id-$suffix")
    private fun pid() = manager.runningAppProcesses?.singleOrNull {
        it.processName == context.packageName + ":cached_computers_test"
    }?.pid
    private fun waitFor(test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!test() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Condition timed out", test())
    }
    private fun text(value: String): UiObject2 {
        device.wait(Until.findObject(By.text(value)), 1_500)?.let { return it }
        if (device.hasObject(By.scrollable(true))) {
            val scroll = UiScrollable(UiSelector().scrollable(true)).setAsVerticalList()
            scroll.scrollToBeginning(10)
            if (!device.hasObject(By.text(value))) scroll.scrollTextIntoView(value)
        }
        return device.wait(Until.findObject(By.text(value)), 10_000) ?: run {
            capture("missing-${value.hashCode()}")
            error("Missing: $value")
        }
    }
    private fun launch() {
        context.startActivity(Intent(context, activity).putExtra("fixtureId", id).putExtra("fixturePort", server.port)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor { marker("pid").exists() && marker("pid").readText().toIntOrNull() == pid() }
        text("No live team authority")
    }
    private fun kill(): Int {
        val child = checkNotNull(pid()); assertNotEquals(Process.myPid(), child)
        Process.killProcess(child); waitFor { pid() == null }; return child
    }
    private fun restart() {
        val parent = Process.myPid(); val old = kill(); launch()
        assertNotEquals(old, pid()); assertEquals(parent, Process.myPid())
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putInt("killed_computers_process", old); putInt("restarted_computers_process", checkNotNull(pid()))
        })
    }
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        assertNull(pid()); server.start()
    }
    @After fun cleanup() {
        manager.appTasks.filter { it.taskInfo?.baseIntent?.component?.className == activity.name }.forEach { it.finishAndRemoveTask() }
        if (pid() != null) kill()
        server.close(); context.deleteSharedPreferences("cached-process-$id")
        marker("pid").delete(); marker("seeded").delete()
        for (team in listOf("one", "two")) {
            val name = NativeMacAppearanceStore.scopeFile(context.packageName, NativeAccount.PROJECT_ID, "fixture-user-$id", team)
            File(context.noBackupFilesDir, "computer-appearance/$name").delete()
            File(context.noBackupFilesDir, "computer-appearance/$name.bak").delete()
        }
    }
    private fun profile(team: String = "two", twoAvailable: Boolean = true) {
        server.enqueue(MockResponse().setBody("""{"id":"fixture-user-$id","display_name":"Fixture Person","selected_team":{"id":"$team"}}"""))
        server.enqueue(MockResponse().setBody(if (twoAvailable)
            """{"items":[{"id":"one","display_name":"Team one"},{"id":"two","display_name":"Team two"}]}"""
            else """{"items":[{"id":"one","display_name":"Team one"}]}"""))
    }
    private fun refresh() = text("Refresh computers").click()
    private fun cached() = text("Showing saved computers. Connect to refresh your account.")
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), "cached-computers-process/$name.png")
        file.parentFile!!.mkdirs(); assertTrue(device.takeScreenshot(file))
        device.dumpWindowHierarchy(File(file.parentFile, "$name.xml"))
    }

    @Test fun killedProcessRestoresCustomRowsHiddenStateAndWarningsWithoutAuthority() {
        launch(); profile(); refresh(); text("Verified team authority"); text("Offline Studio")
        assertEquals(2, server.requestCount)
        restart(); cached(); text("Offline Studio").click(); text("Mac update required")
        text("Hidden Computers"); text("Hidden Studio")
        checkNotNull(device.findObject(By.desc("Show Hidden Studio on this phone"))).click()
        text("Selection callbacks: 0"); text("Visibility callbacks: 0"); text("Stored pairings: 3")
        assertFalse(device.hasObject(By.text("Other Team Mac"))); assertEquals(2, server.requestCount)
        text("Offline Studio"); capture("offline-after-process-death")
        server.enqueue(MockResponse().setResponseCode(503)); refresh()
        text("Account request failed (503)"); capture("after-temporary-failure"); cached(); text("Offline Studio")
        text("No live team authority"); assertEquals(3, server.requestCount)
        profile(); refresh(); text("Verified team authority"); text("Offline Studio").click()
        text("Selection callbacks: 1"); assertEquals(5, server.requestCount)
    }

    @Test fun changedMembershipAndRejectedSessionCannotRestorePreviousTeamsComputers() {
        launch(); profile(); refresh(); text("Verified team authority"); restart(); cached()
        profile("one", twoAvailable = false); refresh(); text("Verified team authority"); text("Other Team Mac")
        assertFalse(device.hasObject(By.text("Offline Studio")))
        assertFalse(device.hasObject(By.text("Hidden Studio")))
        restart(); cached(); text("Team one"); text("Other Team Mac").click()
        text("Selection callbacks: 0"); assertEquals(4, server.requestCount)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401)) }
        refresh(); text("Sign in to refresh your account"); text("No live team authority")
        assertFalse(device.hasObject(By.text("Other Team Mac")))
        assertFalse(device.hasObject(By.text("Showing saved computers. Connect to refresh your account.")))
        text("Stored pairings: 3"); assertEquals(6, server.requestCount)
        restart(); assertEquals(6, server.requestCount)
        assertFalse(device.hasObject(By.text("Other Team Mac")))
        assertFalse(device.hasObject(By.text("Offline Studio")))
        text("Stored pairings: 3")
    }
}
