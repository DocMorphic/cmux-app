package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

/** The runner stays alive while Android restores a killed preview process behind DocumentsUI. */
class FileSaveProcessDeathTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val manager = context.getSystemService(ActivityManager::class.java)
    private val activity = FileSaveProcessTestActivity::class.java
    private val files = FileSaveWork.files(context)
    private fun pid() = manager.runningAppProcesses?.singleOrNull { it.processName == context.packageName + ":file_save_test" }?.pid
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(50) }
        fail(message)
    }
    private fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 20_000)) { "Missing $selector" }
    private fun read(directory: File) = runCatching { JSONObject(File(directory, "status").readText()) }.getOrNull()
    private fun marker(directory: File, name: String) = runCatching { File(directory, name).readText() }.getOrNull()

    @Test fun saveResultAfterProcessDeathUsesTheSealedCopyWhenThePreviewSourceIsGone() = exercise(save = true)
    @Test fun cancellationAfterProcessDeathReleasesThePendingSnapshot() = exercise(save = false)

    private fun exercise(save: Boolean) {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        check(pid() == null && files.recoveryCandidates().isEmpty()) { "Resolve existing fixture process or pending saves first" }
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "file-save-process-$id.txt")
        val expected = "Exact process-restored save 日本語\nsecond line\n".repeat(4096).toByteArray()
        source.writeBytes(expected)
        val directory = File(context.filesDir, "file-save-process-$id")
        var request: FileSaveSnapshot? = null
        var destination: Uri? = null
        try {
            context.startActivity(Intent(context, activity).putExtra("fixtureId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            find(By.desc("Viewer actions")).click(); find(By.text("Save")).click()
            find(By.pkg("com.google.android.documentsui"))
            waitFor("Picker request was not durable") {
                request = files.entries().singleOrNull { it.filename == source.name }
                request?.phase == FileSavePhase.WAITING
            }
            val original = checkNotNull(request)
            assertArrayEquals(expected, files.file(original).readBytes())
            val child = checkNotNull(pid()); assertNotEquals(Process.myPid(), child)
            val task = manager.appTasks.single { it.taskInfo?.baseIntent?.component?.className == activity.name }
            val taskId = checkNotNull(task.taskInfo).taskId
            // Wait for system_server's retained Bundle, not just our onSave callback.
            val headerPattern = Regex("(?m)^\\s*\\* Hist\\s+#\\d+: ActivityRecord\\{[^\\n]+")
            waitFor("Android has not saved the stopped preview behind its picker") {
                if (marker(directory, "saved") != child.toString() || marker(directory, "stopped") != child.toString()) false
                else {
                    val dump = device.executeShellCommand("dumpsys activity activities")
                    val header = headerPattern.findAll(dump).singleOrNull { it.value.contains(activity.name) && it.value.contains(" t$taskId") }
                    val record = header?.let { dump.substring(it.range.last + 1).lineSequence()
                        .takeWhile { line -> !line.contains("* Hist") }.map(String::trim).toList() }.orEmpty()
                    record.any { it.startsWith("mHaveState=true ") } && record.any { it.startsWith("state=STOPPED ") }
                }
            }
            Process.killProcess(child); waitFor("Old preview process survived kill") { pid() == null }
            assertTrue(source.delete())
            // Android's still-open picker delivers the result and relaunches the original task.
            find(By.pkg("com.google.android.documentsui"))
            if (save) find(By.text(Pattern.compile("save", Pattern.CASE_INSENSITIVE))).click() else device.pressBack()
            waitFor("Picker result was not restored in the new process") {
                val state = read(directory)
                state != null && state.optInt("pid") != child && state.optBoolean("restored") &&
                    !state.optBoolean("restoring", true) && state.isNull("pending") && state.isNull("failure") &&
                    (!save || !state.isNull("uri"))
            }
            val state = checkNotNull(read(directory))
            assertNotEquals(child, pid()); assertEquals("true", marker(directory, "created-${state.getInt("pid")}"))
            assertEquals(taskId, checkNotNull(manager.appTasks.single { it.taskInfo?.baseIntent?.component?.className == activity.name }.taskInfo).taskId)
            find(By.text("Preview source removed"))
            val final = checkNotNull(files.load(original.id))
            assertEquals(if (save) FileSavePhase.COMPLETED else FileSavePhase.CANCELLED, final.phase)
            assertFalse(files.file(original).exists()); assertFalse(final.ownsGrant)
            if (save) {
                destination = Uri.parse(state.getString("uri"))
                assertEquals(final.destination, destination.toString())
                assertArrayEquals(expected, context.contentResolver.openInputStream(destination)!!.use { it.readBytes() })
            } else assertTrue(state.isNull("uri"))
            val capture = File(context.getExternalFilesDir(null), "file-save-process/${if (save) "saved" else "cancelled"}.png")
            capture.parentFile!!.mkdirs(); device.takeScreenshot(capture)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putInt("killed_preview_process", child); putInt("restored_preview_process", state.getInt("pid"))
                putBoolean("android_restored_bundle", true); putInt("restored_task", taskId)
                putString("save_outcome", final.phase.name); putInt("verified_bytes", if (save) expected.size else 0)
            })
        } finally {
            // Retain the Activity's temporary URI grant until the generated destination is deleted.
            destination?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            manager.appTasks.filter { it.taskInfo?.baseIntent?.component?.className == activity.name }.forEach { it.finishAndRemoveTask() }
            pid()?.let { child -> check(child != Process.myPid()); Process.killProcess(child); waitFor("Fixture did not stop") { pid() == null } }
            request?.let { runBlocking { FileSaveWork.transfer(context).cancel(it) }; files.remove(it) }
            source.delete(); directory.deleteRecursively()
        }
    }
}
