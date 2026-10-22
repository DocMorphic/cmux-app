package io.github.docmorphic.cmuxapp

import android.os.Build
import android.os.Looper
import android.view.translation.TranslationManager
import android.view.translation.TranslationSpec
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

/** Actual provider discovery using neutral fixtures; never changes language models or settings. */
class NativeChiefTranslationPlatformTest {
    @Test fun platformAdapterUsesRealCapabilitiesAndDefersMissingModelsToTheHost() = runBlocking<Unit> {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        if (Build.VERSION.SDK_INT < 31) return@runBlocking
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val adapter = checkNotNull(NativeChiefPlatformTranslator.create(context))
        val source = NativeChiefTranslationLanguages.fromTag("fr")!!
        val target = NativeChiefTranslationLanguages.fromTag("en")!!
        val proof = JSONObject().put("api", Build.VERSION.SDK_INT)
        try {
            val languages = adapter.supportedLanguages()
            val availability = adapter.availability(source, target)
            proof.put("supportedLanguages", JSONArray(languages.map { it.tag })).put("availability", availability.name)
            assertTrue(languages.any { it.code == "fr" }); assertTrue(languages.any { it.code == "en" })
            val detected = adapter.detectLanguage("Bonjour, je voudrais discuter du projet et de la prochaine étape.")
            assertEquals("fr", detected?.code)
            proof.put("detected", detected?.tag).put("settingsAvailable", adapter.modelSettingsIntent() != null)
            if (availability == NativeChiefTranslationAvailability.DOWNLOADABLE) {
                try {
                    adapter.translate(mapOf("fixture" to "Bonjour"), source, target)
                    fail("Absent models must wait for host download admission")
                } catch (error: NativeChiefTranslationException) {
                    assertEquals(NativeChiefTranslationFailure.NOT_DOWNLOADED, error.reason)
                    proof.put("missingModelDefersWithoutSubmission", true)
                }
            }
            val vectors = JSONObject(instrumentation.context.assets.open("parity/chief-translation-languages.json").bufferedReader().use { it.readText() })
                .getJSONArray("languages")
            val values = (0 until vectors.length()).map { vectors.getJSONObject(it) }
            for (value in values) {
                val language = checkNotNull(NativeChiefTranslationLanguages.fromTag(value.getString("input")))
                assertEquals(value.getString("input"), value.getString("language"), language.code)
                assertEquals(value.getString("input"), value.getString("script"), language.script.orEmpty())
                for (other in values) {
                    val expected = value.getJSONArray("matches").let { tags -> (0 until tags.length()).any { tags.getString(it) == other.getString("input") } }
                    assertEquals(value.getString("input") + "/" + other.getString("input"), expected,
                        language.matches(NativeChiefTranslationLanguages.fromTag(other.getString("input"))!!))
                }
            }
            proof.put("nativeFoundationLanguagePairsMatched", values.size * values.size)
        } finally {
            adapter.close()
            val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(directory, "chief-translation-adapter.json").writeText(proof.toString(2))
        }
    }

    @Test fun recordActualOnDeviceTranslationAndLanguageDetectionCapabilities() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val proof = JSONObject().put("api", Build.VERSION.SDK_INT).put("model", Build.MODEL)
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(TranslationManager::class.java)
            proof.put("managerPresent", manager != null)
            try {
                val capabilities = manager?.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT).orEmpty()
                proof.put("capabilities", JSONArray(capabilities.map { cap ->
                    JSONObject().put("source", cap.sourceSpec.locale.toLanguageTag()).put("target", cap.targetSpec.locale.toLanguageTag())
                        .put("state", cap.state).put("flags", cap.supportedTranslationFlags)
                }))
                proof.put("settingsCreator", manager?.onDeviceTranslationSettingsActivityIntent?.creatorPackage ?: JSONObject.NULL)
            } catch (error: Exception) { proof.put("discoveryError", error.javaClass.name) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier
            proof.put("classifierClass", classifier.javaClass.name)
            val samples = listOf("Bonjour, je voudrais discuter du projet et de la prochaine étape.", "Hola, me gustaría hablar del proyecto y del siguiente paso.")
            proof.put("languageDetection", JSONArray(samples.map { text ->
                try {
                    val result = classifier.detectLanguage(TextLanguage.Request.Builder(text).build())
                    JSONObject().put("languages", JSONArray((0 until result.localeHypothesisCount).map { index ->
                        val locale = result.getLocale(index)
                        JSONObject().put("language", locale.toLanguageTag()).put("confidence", result.getConfidenceScore(locale))
                    }))
                } catch (error: Exception) { JSONObject().put("error", error.javaClass.name) }
            }))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val app = context.applicationContext
            proof.put("targetPackage", context.packageName).put("applicationPackage", app.packageName)
                .put("applicationClass", app.javaClass.name)
            val manager = app.getSystemService(TranslationManager::class.java)
            val counts = JSONArray()
            repeat(5) { index ->
                if (index > 0) android.os.SystemClock.sleep(1_000)
                counts.put(JSONObject().put("index", index).put("count", manager?.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT,
                    TranslationSpec.DATA_FORMAT_TEXT)?.size ?: -1))
            }
            proof.put("applicationCapabilitySamples", counts)
        }
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "chief-translation-platform.json").writeText(proof.toString(2))
    }
}
