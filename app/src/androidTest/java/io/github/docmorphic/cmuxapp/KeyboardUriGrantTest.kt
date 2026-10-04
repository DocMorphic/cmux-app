package io.github.docmorphic.cmuxapp

import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real system IME tokens from a private provider in a different APK/UID.
 * Emulator-only: changes and restores the selected keyboard, never user app data. */
@OptIn(ExperimentalTestApi::class)
class KeyboardUriGrantTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixturePackage get() = instrumentation.context.packageName
    private val keyboard get() = "$fixturePackage/${GrantFixtureKeyboard::class.java.name}"
    private var originalKeyboard: String? = null
    private var wasEnabled = false
    private var showEditor by mutableStateOf(true)
    private val owned = mutableListOf<TerminalPasteContent>()
    private val errors = mutableListOf<String>()
    private var queue: TerminalInputQueue? = null
    private var scope: CoroutineScope? = null

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    @Before fun selectFixtureKeyboard() {
        Assume.assumeTrue("Only the existing emulator may change keyboards", Build.MODEL.startsWith("sdk_gphone"))
        val original = shell("settings get secure default_input_method")
        check(original.isNotBlank() && original != "null")
        originalKeyboard = original
        wasEnabled = shell("ime list -s").lineSequence().any { it.trim() == keyboard }
        assertTrue(shell("ime enable $keyboard").contains("enabled"))
        shell("ime set $keyboard")
        assertEquals(keyboard, shell("settings get secure default_input_method"))
    }

    @After fun restoreKeyboard() {
        try {
            compose.runOnIdle { owned.forEach { it.close() }; queue?.close(); scope?.cancel() }
        } finally {
            originalKeyboard?.let { original ->
                shell("ime set $original")
                if (!wasEnabled) shell("ime disable $keyboard")
                assertEquals(original, shell("settings get secure default_input_method"))
            }
        }
    }

    private fun command(uri: Uri? = null): Bundle {
        val response = AtomicReference<Bundle?>()
        context.sendOrderedBroadcast(Intent().setComponent(ComponentName(fixturePackage,
            GrantFixtureKeyboardCommand::class.java.name)).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .apply { uri?.let { putExtra("uri", it.toString()) } }, null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { response.set(getResultExtras(true)) }
            }, Handler(Looper.getMainLooper()), 0, null, null)
        compose.waitUntil(5_000) { response.get() != null }
        return response.get()!!
    }

    private fun show(receive: (TerminalPasteContent) -> Boolean) {
        compose.setContent {
            var text by remember { mutableStateOf("Keep this draft") }
            if (!showEditor) Text("Input finished")
            else RichContentEditor("grant-test", true, receive, { errors += it }) { modifier ->
                OutlinedTextField(text, { text = it }, modifier)
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(15_000) { command().getBoolean("ready") }
        val info = command()
        assertNotEquals(android.os.Process.myUid(), info.getInt("uid"))
        assertArrayEquals(arrayOf("image/*"), info.getStringArray("types"))
    }

    private fun uri() = Uri.parse("content://$fixturePackage.keyboard-grants/image/${UUID.randomUUID()}.png")
    private fun assertDenied(uri: Uri) {
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertTrue("Unowned provider bytes must be unreadable", runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.read() }
        }.isFailure)
    }

    @Test fun grantSurvivesEditorRemovalUntilDelayedCopyCompletes() {
        show { owned += it; true }
        val uri = uri(); assertDenied(uri)
        assertTrue(command(uri).getBoolean("accepted"))
        compose.runOnIdle { assertEquals(1, owned.size); assertTrue(errors.isEmpty()) }
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep this draft")
        // Remove the editor before copying, as happens during navigation.
        compose.runOnIdle { showEditor = false }
        compose.waitUntil(10_000) { !command().getBoolean("ready") }
        compose.onNodeWithText("Input finished").assertExists()
        val prepared = runBlocking { AttachmentFiles(context).prepare(uri, true) }
        try {
            BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size).also {
                assertEquals(android.graphics.Color.YELLOW, it.getPixel(3, 3)); it.recycle()
            }
        } finally { prepared.bytes.fill(0) }
        compose.runOnIdle { owned.single().close(); owned.single().close() }
        assertDenied(uri)
    }

    @Test fun declinedAndThrowingReceiversReleaseTheRealGrant() {
        var throwing by mutableStateOf(false)
        show {
            owned += it
            if (throwing) error("Fixture receiver failure")
            false
        }
        val declined = uri(); assertDenied(declined)
        assertFalse(command(declined).getBoolean("accepted")); assertDenied(declined)
        compose.runOnIdle { throwing = true }
        val failed = uri(); assertDenied(failed)
        assertFalse(command(failed).getBoolean("accepted")); assertDenied(failed)
        compose.runOnIdle { assertEquals(2, owned.size); assertEquals(listOf("Fixture receiver failure"), errors) }
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep this draft")
    }

    @Test fun cancelledInputQueueReleasesGrantBeforeReadingTheImage() {
        val entered = CompletableDeferred<Unit>()
        val inputScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        val inputQueue = TerminalInputQueue(inputScope) { error("No terminal input expected") }.also { queue = it }
        show { content -> inputQueue.offerAction(content::close) { entered.complete(Unit); awaitCancellation() } }
        val uri = uri(); assertDenied(uri)
        assertTrue(command(uri).getBoolean("accepted"))
        compose.waitUntil(5_000) { entered.isCompleted }
        assertEquals(PackageManager.PERMISSION_GRANTED,
            context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        compose.runOnIdle { inputQueue.close() }
        compose.waitForIdle()
        assertDenied(uri)
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep this draft")
    }
}
