package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Two separate instrumentation processes exercise the actual factory and SharedPreferences file.
 * No account store, RPC, message text, model download or settings UI is used. */
class NativeChiefTranslationPersistenceProbeTest {
    private class UnusedApi : NativeChiefApi {
        val calls = AtomicInteger()
        private fun unexpected(): Nothing { calls.incrementAndGet(); error("Persistence probe must not perform RPC") }
        override suspend fun status(): NativeChiefStatus = unexpected()
        override suspend fun conversations(afterCursor: String?, waitForChange: Boolean): NativeChiefSnapshot = unexpected()
        override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead = unexpected()
        override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent = unexpected()
        override suspend fun markRead(conversation: String?, upToSeq: ULong): NativeChiefMarked = unexpected()
        override suspend fun stop(conversation: String?): NativeChiefStopped = unexpected()
        override suspend fun approve(conversation: String?, request: String?, choice: String): NativeChiefApproved = unexpected()
        override suspend fun answer(conversation: String?, message: String?, choices: List<String>, text: String?): NativeChiefAnswered = unexpected()
        override suspend fun open(windowId: String?, workspaceId: String?): NativeChiefOpened = unexpected()
    }
    private fun disk(context: Context): Map<String, String> {
        val file = File(context.applicationInfo.dataDir, "shared_prefs/native_chief_translation.xml")
        if (!file.exists()) return emptyMap()
        return file.inputStream().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            buildMap {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") {
                        val key = parser.getAttributeValue(null, "name"); put(key, parser.nextText())
                    }
                    parser.next()
                }
            }
        }
    }
    private suspend fun awaitDisk(context: Context, matches: (Map<String, String>) -> Boolean): Map<String, String> = withTimeout(10_000) {
        while (true) {
            // apply() can be replacing the file; a partial read is not acceptance.
            val values = withContext(Dispatchers.IO) { runCatching { disk(context) }.getOrNull() }
            if (values != null && matches(values)) return@withTimeout values
            delay(25)
        }
        @Suppress("UNREACHABLE_CODE") emptyMap()
    }

    @Test fun productionPreferencesSurviveAColdProcessAndKeepTheirScope() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        val seed = args.getString("chiefTranslationPersistenceRun")
        assumeTrue("Opt-in cold-process driver required", seed != null)
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        check(Build.VERSION.SDK_INT >= 31)
        require(seed!!.matches(Regex("[a-f0-9]{32}")))
        val phase = args.getString("chiefTranslationPersistencePhase")!!
        require(phase in setOf("write", "read", "cleanup"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val storage = context.getSharedPreferences("native_chief_translation", Context.MODE_PRIVATE)
        val before = storage.all.toMap()
        val owner = NativeChiefTranslationPreferenceOwner("user:persistence:$seed", "probe-team", "probe-mac", "probe-installation")
        val api = UnusedApi()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sessions = mutableListOf<NativeChiefTranscriptSession>()
        suspend fun install(who: NativeChiefTranslationPreferenceOwner = owner, conversation: String = "probe-conversation", locale: Locale = Locale.ENGLISH) = withContext(Dispatchers.Main.immediate) {
            NativeChiefTranscriptSession(parent, api, conversation, { true }).also { session ->
                sessions += session
                installNativeChiefTranslation(session, context, NativeChiefTranslationConfiguration(who), locale)
                assertNotNull("Actual native factory must install a provider", session.translations.value)
            }
        }
        var preserveForColdRead = false
        var primary: NativeChiefTranscriptSession? = null
        try {
            primary = install()
            val model = primary.translations.value!!
            val proof = JSONObject().put("run", seed).put("phase", phase).put("pid", Process.myPid())
                .put("factory", "installNativeChiefTranslation").put("storage", "native_chief_translation")
                .put("api", Build.VERSION.SDK_INT).put("messageTextSubmitted", false)
            when (phase) {
                "write" -> {
                    withContext(Dispatchers.Main.immediate) {
                        assertNull(model.state.value.automatic)
                        model.startAutomatic(NativeChiefTranslationLanguages.fromTag("es")!!)
                    }
                    val added = storage.all.keys - before.keys
                    assertEquals(1, added.size)
                    val key = added.single()
                    val expected = storage.getString(key, null)!!
                    val json = JSONObject(expected)
                    assertEquals(setOf("version", "source", "target"), json.keys().asSequence().toSet())
                    assertEquals(1, json.getInt("version")); assertEquals("es", json.getString("source")); assertEquals("en", json.getString("target"))
                    val sentinel = install(conversation = "probe-sentinel").translations.value!!
                    withContext(Dispatchers.Main.immediate) { sentinel.startAutomatic(NativeChiefTranslationLanguages.fromTag("fr")!!) }
                    val sentinelKeys = storage.all.keys - before.keys - key
                    assertEquals(1, sentinelKeys.size)
                    val sentinelKey = sentinelKeys.single()
                    val sentinelValue = storage.getString(sentinelKey, null)!!
                    awaitDisk(context) { it[key] == expected && it[sentinelKey] == sentinelValue }
                    assertEquals(before, storage.all.filterKeys { it != key && it != sentinelKey })
                    proof.put("key", key).put("pair", json).put("actualDiskWrite", true)
                        .put("sentinelKey", sentinelKey).put("sentinelPair", JSONObject(sentinelValue))
                    preserveForColdRead = true
                }
                "read" -> {
                    val written = JSONObject(File(output, "chief-translation-persistence-write.json").readText())
                    assertEquals(seed, written.getString("run"))
                    assertNotEquals("Read must run in a genuinely new app process", written.getInt("pid"), Process.myPid())
                    val key = written.getString("key")
                    withContext(Dispatchers.Main.immediate) {
                        assertEquals("es", model.state.value.automatic?.source?.code)
                        assertEquals("en", model.state.value.automatic?.target?.code)
                        assertTrue(model.state.value.presentations.isEmpty())
                    }
                    assertEquals(JSONObject(before[key] as String).toString(), JSONObject(disk(context)[key]!!).toString())
                    val different = listOf(owner.copy(account = "user:other:$seed"), owner.copy(team = "other"),
                        owner.copy(device = "other"), owner.copy(installation = "other"))
                    for (other in different) assertNull(install(other).translations.value!!.state.value.automatic)
                    assertNull(install(conversation = "other").translations.value!!.state.value.automatic)
                    assertNull(install(locale = Locale.FRENCH).translations.value!!.state.value.automatic)
                    val sentinelKey = written.getString("sentinelKey")
                    val sentinelValue = before[sentinelKey] as String
                    val sentinel = install(conversation = "probe-sentinel").translations.value!!
                    assertEquals("fr", sentinel.state.value.automatic?.source?.code)
                    withContext(Dispatchers.Main.immediate) { model.stopAutomatic() }
                    awaitDisk(context) { key !in it && it[sentinelKey] == sentinelValue }
                    assertEquals(before.filterKeys { it != key }, storage.all)
                    proof.put("key", key).put("writerPid", written.getInt("pid")).put("coldRestore", true)
                        .put("isolatedOwnerDimensions", 4).put("isolatedConversation", true).put("differentTargetStartsFresh", true)
                        .put("stopRemovedDiskKey", true).put("unrelatedPreferencesPreserved", true).put("sentinelColdRestoreAndPreservation", true)
                }
                else -> {
                    val sentinel = install(conversation = "probe-sentinel").translations.value!!
                    withContext(Dispatchers.Main.immediate) { model.stopAutomatic(); sentinel.stopAutomatic() }
                    val writer = File(output, "chief-translation-persistence-write.json")
                    if (writer.exists()) {
                        val written = JSONObject(writer.readText())
                        assertEquals(seed, written.getString("run"))
                        awaitDisk(context) { written.getString("key") !in it && written.getString("sentinelKey") !in it }
                    }
                    proof.put("probeDiskKeysRemoved", true)
                }
            }
            assertEquals(0, api.calls.get())
            proof.put("backendCalls", api.calls.get())
            File(output, "chief-translation-persistence-$phase.json").writeText(proof.toString(2))
        } finally {
            withContext(Dispatchers.Main.immediate) {
                if (!preserveForColdRead) sessions.forEach { it.translations.value?.stopAutomatic() }
                sessions.forEach { it.close() }; parent.cancel()
            }
        }
    }
}
