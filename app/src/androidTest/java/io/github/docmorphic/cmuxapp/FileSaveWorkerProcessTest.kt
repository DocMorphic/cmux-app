package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Deliberate runner-process death: invoke ONLY through check-file-save-process.py. */
class FileSaveWorkerProcessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val files = FileSaveWork.files(context)
    private val size = 32L * 1024 * 1024
    private fun id(phase: String): String {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the explicit process-death runner", args.getString("fileSaveWorkerPhase") == phase)
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        check(Application.getProcessName() == context.packageName)
        return checkNotNull(args.getString("fileSaveWorkerFixture")).also { check(UUID.fromString(it).toString() == it) }
    }
    private fun root(id: String) = File(context.filesDir, "file-save-worker-$id")
    private fun await(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; SystemClock.sleep(50) }
        fail(message)
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").let { hash ->
        file.inputStream().use { input -> val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        }; hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Test fun interruptRealWorker() {
        val id = id("interrupt")
        check(files.recoveryCandidates().isEmpty()) { "Resolve pending saves before the isolated fixture" }
        val root = root(id); check(root.mkdirs())
        val request = FileSaveSnapshot(id, "worker-recovery-$id.bin", "application/octet-stream", FileSavePhase.PREPARING)
        // Keep a real foreground Activity while WorkManager admits its foreground service.
        ActivityScenario.launch(ComponentActivity::class.java).use {
            runBlocking {
                files.prepareStream(request, size) { append ->
                    val block = ByteArray(65536) { index -> ((index * 31 + index / 251) and 255).toByte() }
                    repeat((size / block.size).toInt()) { append(block, block.size) }
                }
                val config = JSONObject().put("bytes", size).put("sha256", digest(files.file(request)))
                File(root, "config.json").writeText(config.toString())
                val writing = request.copy(phase = FileSavePhase.WRITING,
                    destination = "content://${context.packageName}.file-save-fixture/$id")
                files.record(writing)
                FileSaveWork.dispatch(context, writing)
            }
            await("Real worker never reached the destination's partial-write gate") { File(root, "blocked-1.json").isFile }
            val blocked = JSONObject(File(root, "blocked-1.json").readText())
            assertEquals(Process.myPid(), blocked.getInt("caller_pid"))
            assertTrue(blocked.getLong("bytes") in 1 until size)
            assertEquals(FileSavePhase.WRITING, files.load(id)?.phase)
            assertEquals(blocked.getLong("bytes"), File(root, "output-1").length())
            File(root, "interrupted.json").writeText(blocked.put("worker_phase", "WRITING").toString())
            // No coroutine cancellation or finally cleanup: the actual process owning
            // FileSaveWorker and its open ContentResolver stream disappears here.
            Process.killProcess(Process.myPid())
            error("Process kill returned")
        }
    }
    @Test fun verifyRecoveredWorker() {
        val id = id("verify"); val root = root(id)
        val interrupted = JSONObject(File(root, "interrupted.json").readText())
        assertNotEquals(interrupted.getInt("caller_pid"), Process.myPid())
        val config = JSONObject(File(root, "config.json").readText())
        try { ActivityScenario.launch(ComponentActivity::class.java).use {
            File(root, "release").writeText("resume")
            // Application.onCreate and WorkManager's persisted work own recovery.
            // Do not enqueue replacement work from this test.
            await("Persisted Save worker did not complete after process death") { files.load(id)?.phase == FileSavePhase.COMPLETED }
            val attempts = File(root, "attempts").readText().toInt()
            assertTrue("Destination was not reopened", attempts >= 2)
            await("Provider did not finish the recovered output") { File(root, "closed-$attempts").isFile }
            val output = File(root, "output-$attempts")
            assertEquals(config.getLong("bytes"), output.length())
            assertEquals(config.getString("sha256"), digest(output))
            assertFalse(files.file(checkNotNull(files.load(id))).exists())
            assertFalse(checkNotNull(files.load(id)).ownsGrant)
            val receipt = JSONObject().put("old_pid", interrupted.getInt("caller_pid"))
                .put("new_pid", Process.myPid()).put("attempts", attempts).put("bytes", output.length())
                .put("sha256", digest(output)).put("phase", "COMPLETED")
            File(root, "verified.json").writeText(receipt.toString())
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("worker_recovery", receipt.toString()) })
        } } finally { stopWork(id) }
    }
    @Test fun cleanFixture() {
        val id = id("cleanup")
        stopWork(id)
        files.load(id)?.let(files::remove)
        root(id).deleteRecursively()
    }
    private fun stopWork(id: String) {
        val root = root(id)
        if (root.isDirectory) File(root, "release").writeText("cleanup")
        WorkManager.getInstance(context).cancelUniqueWork("cmux.file-save.$id").result.get()
        runBlocking { files.load(id)?.let { FileSaveWork.transfer(context).cancel(it) } }
        context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.singleOrNull { it.processName == context.packageName + ":file_save_provider" }?.let {
                check(it.pid != Process.myPid()); Process.killProcess(it.pid)
            }
    }
}
