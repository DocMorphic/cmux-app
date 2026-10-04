package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** SIGKILL the UI owner, then launch a fresh process; no controller/recreation simulation. */
class NativeAccountProcessDeathTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val manager get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val id = UUID.randomUUID().toString()
    private val server = MockWebServer()
    private val activity = NativeAccountProcessTestActivity::class.java
    private fun marker(suffix: String) = File(context.filesDir, "account-process-$id-$suffix")
    private fun pid() = manager.runningAppProcesses?.singleOrNull {
        it.processName == context.packageName + ":account_restore_test"
    }?.pid
    private fun waitFor(test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!test() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Condition timed out", test())
    }
    private fun text(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 20_000)) { "Missing: $value" }
    private fun launch(defer: Boolean = false) {
        context.startActivity(Intent(context, activity).putExtra("fixtureId", id).putExtra("fixturePort", server.port)
            .putExtra("deferOutcome", defer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor { marker("pid").exists() && marker("pid").readText().toIntOrNull() == pid() }
    }
    private fun kill(): Int {
        val child = checkNotNull(pid()); assertNotEquals(Process.myPid(), child)
        Process.killProcess(child); waitFor { pid() == null }; return child
    }
    private fun restart(defer: Boolean = false) {
        val parent = Process.myPid(); val old = kill()
        launch(defer); assertNotEquals(old, pid()); assertEquals(parent, Process.myPid())
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putInt("killed_account_process", old); putInt("restarted_account_process", checkNotNull(pid()))
        })
    }
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        assertNull(pid()); server.start()
    }
    @After fun cleanup() {
        manager.appTasks.filter { it.taskInfo?.baseIntent?.component?.className == activity.name }.forEach { it.finishAndRemoveTask() }
        if (pid() != null) kill()
        server.close()
        context.deleteSharedPreferences("account-process-$id")
        marker("pid").delete(); marker("seeded").delete()
    }
    private fun profile(name: String, selected: String) {
        server.enqueue(MockResponse().setBody("""{"id":"fixture-user","display_name":"$name","primary_email":"fixture@example.test","selected_team":{"id":"$selected"}}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"one","display_name":"Team one"},{"id":"two","display_name":"Team two"}]}"""))
    }
    private fun confirmDelete() {
        text("Delete Account").click(); text("Delete Account?")
        val buttons = device.findObjects(By.text("Delete Account"))
        assertTrue(buttons.isNotEmpty()); buttons.last().click()
    }
    @Test fun killedAccountProcessRestoresOnlyDisplayAndRevalidatesBeforeTeamUse() {
        launch(); profile("Process Person", "two"); text("Refresh account").click()
        text("Process Person"); text("Verified team authority"); assertEquals(2, server.requestCount)
        restart(); text("Process Person"); text("fixture@example.test"); text("No live team authority")
        // Compose exposes the label as enabled even when its enclosing button is
        // disabled. Exercise the actual tap instead of asserting label semantics.
        text("Team two").click(); device.waitForIdle(1000)
        assertFalse(device.hasObject(By.text("Team one")))
        text("Create Team").click(); device.waitForIdle(1000)
        assertFalse(device.hasObject(By.text("Team name")))
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(503)); text("Refresh account").click()
        text("Account request failed (503)"); text("Process Person"); text("No live team authority")
        profile("Refreshed Person", "one"); text("Refresh account").click()
        text("Refreshed Person"); text("Verified team authority")
        assertEquals(5, server.requestCount)
        server.enqueue(MockResponse().setBody("""{"id":"fixture-user","selected_team":{"id":"two"}}"""))
        text("Team one").click(); text("Team two").click()
        waitFor { server.requestCount == 6 }
        waitFor { !device.hasObject(By.text("Team one")) }
        text("Team two"); text("Verified team authority")
        capture("account-process-refreshed")
    }
    @Test fun deathWhileDeleteIsInFlightRestoresUnknownWithoutResending() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        launch(); confirmDelete()
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("DELETE", request.method); assertEquals("/api/account", request.path)
        text("Deleting Account…"); restart()
        text("Couldn't Delete Account"); text(NativeAccountDeletionResult.UNKNOWN.message)
        assertEquals(1, server.requestCount); capture("account-process-unknown")
        text("OK").click(); text("Fixture signed in"); text("Delete Account")
        assertEquals(1, server.requestCount)
    }
    @Test fun completedReceiptSurvivesDeathAndSignsOutWithoutAnotherDelete() {
        server.enqueue(MockResponse().setResponseCode(204))
        launch(defer = true); confirmDelete(); text("Receipt: COMPLETED")
        assertEquals(1, server.requestCount)
        restart(); text("Fixture signed out"); text("No live team authority")
        text("Delete Account").click(); device.waitForIdle(1000)
        assertFalse(device.hasObject(By.text("Delete Account?")))
        assertEquals(1, server.requestCount)
        restart(); text("Fixture signed out"); assertEquals(1, server.requestCount)
    }
    private fun capture(name: String) {
        device.waitForIdle(1000)
        val file = File(context.getExternalFilesDir(null), "screenshots/$name.png")
        file.parentFile!!.mkdirs(); assertTrue(device.takeScreenshot(file))
    }
}
