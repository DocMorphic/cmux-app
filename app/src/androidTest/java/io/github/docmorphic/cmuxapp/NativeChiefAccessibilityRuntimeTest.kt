package io.github.docmorphic.cmuxapp

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual provider actions/events, without a Compose test rule resetting UiAutomation flags. */
class NativeChiefAccessibilityRuntimeTest {
    private fun main(action: () -> Unit) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        Handler(Looper.getMainLooper()).post { try { action() } catch (t: Throwable) { failure.set(t) } finally { done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS)) { "Main-thread action timed out" }
        failure.get()?.let { throw it }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { "Platform accessibility condition timed out" }; SystemClock.sleep(25) }
    }

    @Test fun actualPlatformCopyAndSemanticFeedbackKeepTheirOwner() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val date = Instant.parse("2026-10-20T12:00:00Z")
        val text = "An accessible Chief message"
        val message = NativeChiefMessage("accessible", 1uL, "chief", "Chief", "chief", text, date, emptyList(), emptyList(), null, null)
        val conversation = NativeChiefConversation("chief-main", "Chief", "Ready on your Mac", 0, true, false, date)
        val writes = AtomicInteger()
        val api = object : NativeChiefApi {
            override suspend fun status() = NativeChiefStatus(true, null, null, "chief", "Chief", "", "", 1, 1, emptyList(), "chief-main")
            override suspend fun conversations(afterCursor: String?, waitForChange: Boolean): NativeChiefSnapshot =
                if (waitForChange) awaitCancellation() else NativeChiefSnapshot(listOf(conversation), "home")
            override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead =
                if (waitForChange) awaitCancellation() else NativeChiefRead("chief-main", false, listOf(message), false, 1uL, 0, 1uL, "transcript")
            override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent { writes.incrementAndGet(); error("No send expected") }
            override suspend fun markRead(conversation: String?, upToSeq: ULong) = NativeChiefMarked("chief-main", upToSeq, true)
            override suspend fun stop(conversation: String?): NativeChiefStopped { writes.incrementAndGet(); error("No stop expected") }
            override suspend fun approve(conversation: String?, request: String?, choice: String): NativeChiefApproved { writes.incrementAndGet(); error("No approval expected") }
            override suspend fun answer(conversation: String?, message: String?, choices: List<String>, text: String?): NativeChiefAnswered { writes.incrementAndGet(); error("No answer expected") }
            override suspend fun open(windowId: String?, workspaceId: String?) = NativeChiefOpened(windowId, true)
        }
        val current = AtomicBoolean(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var computer: NativeChiefComputerSession? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var host: ComponentActivity
            scenario.onActivity { host = it }
            val directory = File(host.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            // All ActivityScenario/test-framework setup finishes before enabling
            // the real service. The remaining dispatch uses Handler and public
            // UiAutomation APIs directly, with no accessibility force flag.
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            fun setting(key: String) = Settings.Secure.getString(host.contentResolver, key)
            fun putSetting(key: String, value: String?) {
                automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                try { check(Settings.Secure.putString(host.contentResolver, key, value)) }
                finally { automation.dropShellPermissionIdentity() }
            }
            val keys = listOf("enabled_accessibility_services", "accessibility_enabled")
            val original = keys.associateWith(::setting)
            val originalFlags = automation.serviceInfo.flags
            val events = CopyOnWriteArrayList<JSONObject>()
            val proof = JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("composeTestRule", false)
                .put("settingsBefore", JSONObject(original))
            try {
                val component = instrumentation.context.packageName + "/io.github.docmorphic.cmuxapp.ChiefAccessibilityProbe"
                val prior = original[keys[0]]?.takeIf { it.isNotEmpty() }
                val enabled = (prior?.split(':').orEmpty() + component).distinct().joinToString(":")
                putSetting(keys[0], enabled)
                putSetting(keys[1], "1")
                val manager = host.getSystemService(AccessibilityManager::class.java)
                await { manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.name == "io.github.docmorphic.cmuxapp.ChiefAccessibilityProbe" } }
                proof.put("enabledServices", JSONArray(manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { it.resolveInfo.serviceInfo.name }))
                automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
                automation.setOnAccessibilityEventListener { event ->
                    if (event.packageName?.toString() == host.packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                        events += JSONObject().put("changes", event.contentChangeTypes).put("state", event.source?.stateDescription?.toString())
                }
                main {
                    val owner = NativeChiefComputerSession(scope, api) { current.get() }
                    computer = owner
                    host.setContent { CmuxTheme {
                        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().semantics { testTagsAsResourceId = true }) {
                            NativeChiefView(owner, true, Modifier.fillMaxSize())
                        }
                    } }
                }
                fun find(tag: String): AccessibilityNodeInfo? {
                    fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                        if (node.viewIdResourceName == tag) return node
                        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }?.let { return it }
                        return null
                    }
                    val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
                    return roots.firstOrNull { it.packageName?.toString() == host.packageName }?.let(::walk)
                }
                await { find("ChiefPinnedConversation") != null }
                assertTrue(find("ChiefPinnedConversation")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { find("ChiefReplyActions:accessible") != null }
                val node = checkNotNull(find("ChiefReplyActions:accessible"))
                val copy = node.actionList.single { it.label?.toString() == "Copy" }
                proof.put("registeredActions", JSONArray(node.actionList.map { JSONObject().put("id", it.id).put("label", it.label?.toString()) }))
                assertTrue(node.performAction(copy.id))
                main { assertEquals(text, host.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
                await { find("ChiefReplyActions:accessible")?.stateDescription?.toString() == "Message copied" }
                await { events.any { it.optString("state") == "Message copied" && it.getInt("changes") and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 } }
                proof.put("platformCopyAndFeedbackPassed", true)
                val acknowledgements = events.count { it.optString("state") == "Message copied" && it.getInt("changes") and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 }
                assertTrue(node.performAction(copy.id))
                await { events.count { it.optString("state") == "Message copied" && it.getInt("changes") and AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION != 0 } > acknowledgements }
                proof.put("repeatedCopyFeedbackPassed", true)
                assertTrue(find("ChiefReplyActions:accessible")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { find("ChiefMenu:MORE") != null }
                assertTrue(find("ChiefMenu:MORE")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { find("ChiefSelectionCancel") != null }
                val selected = checkNotNull(find("ChiefReplyActions:accessible"))
                val selectedCopy = selected.actionList.single { it.label?.toString() == "Copy" }
                assertTrue(selected.performAction(selectedCopy.id))
                main { assertEquals(text, host.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
                // Compose exports selection on a checkbox as checked; its
                // native isSelected property is reserved for tab roles.
                assertTrue(find("ChiefReplyActions:accessible")!!.isChecked)
                assertNotNull(find("ChiefSelectionCancel"))
                proof.put("morePlatformCopyPreservesSelection", true)
                assertTrue(find("ChiefSelectionCancel")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { find("ChiefSelectionCancel") == null }
                val parent = checkNotNull(find("ChiefReplyActions:accessible"))
                assertTrue(parent.performAction(parent.actionList.single { it.label?.toString() == "Reply" }.id))
                await { find("ChiefThreadMessage:accessible") != null }
                val thread = checkNotNull(find("ChiefThreadMessage:accessible"))
                val threadCopy = thread.actionList.single { it.label?.toString() == "Copy" }
                main { host.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "thread sentinel")) }
                assertTrue(thread.performAction(threadCopy.id))
                main { assertEquals(text, host.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
                proof.put("replyPlatformCopyPassed", true)
                await { find("ChiefReplyClose")?.isEnabled == true }
                assertTrue(find("ChiefReplyClose")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { find("ChiefReplyThread") == null }
                main { host.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "closed thread sentinel")) }
                assertFalse(thread.performAction(threadCopy.id))
                main { assertEquals("closed thread sentinel", host.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
                proof.put("closedThreadPlatformCopyRejected", true)
                await { find("ChiefReplyActions:accessible") != null }
                val retained = checkNotNull(find("ChiefReplyActions:accessible"))
                val retainedCopy = retained.actionList.single { it.label?.toString() == "Copy" }
                main {
                    host.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "platform owner sentinel"))
                    current.set(false)
                }
                assertFalse(retained.performAction(retainedCopy.id))
                main { assertEquals("platform owner sentinel", host.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
                assertEquals(0, writes.get())
                proof.put("retiredOwnerRejected", true).put("writes", writes.get())
            } finally {
                automation.setOnAccessibilityEventListener(null)
                main { computer?.close(); scope.cancel() }
                original.forEach(::putSetting)
                val after = keys.associateWith(::setting)
                proof.put("events", JSONArray(events)).put("settingsAfter", JSONObject(after)).put("settingsRestored", original == after)
                File(directory, "chief-copy-platform-real.json").writeText(proof.toString(2))
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                instrumentation.getUiAutomation(0)
                assertEquals(original, after)
            }
        }
    }
}
