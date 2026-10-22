package io.github.docmorphic.cmuxapp

import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Platform UI exploration on the disposable emulator; no model is downloaded by this probe. */
class NativeChiefTranslationSettingsProbeTest {
    /** Explicitly admitted model installation is performed in the provider's own UI first. */
    @Test fun installedModelsTranslateRealMessages() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("chiefInstalledTranslationProbe") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        check(Build.VERSION.SDK_INT >= 31)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val adapter = checkNotNull(NativeChiefPlatformTranslator.create(context))
        val source = NativeChiefTranslationLanguages.fromTag("fr")!!
        val target = NativeChiefTranslationLanguages.fromTag("en")!!
        val proof = JSONObject().put("api", Build.VERSION.SDK_INT)
        try {
            val availability = adapter.availability(source, target)
            proof.put("availability", availability.name)
            assertEquals(NativeChiefTranslationAvailability.INSTALLED, availability)
            val texts = linkedMapOf("first" to "Bonjour, je voudrais discuter du projet et de la prochaine étape.",
                "second" to "Nous avons terminé le travail et nous sommes prêts à partir demain matin.")
            val translated = withTimeout(60_000) { adapter.translate(texts, source, target) }
            proof.put("originals", JSONObject(texts)).put("translated", JSONObject(translated))
            assertEquals(texts.keys, translated.keys)
            for ((key, text) in translated) {
                assertTrue(text.isNotBlank()); assertNotEquals(texts[key], text)
                assertEquals("en", adapter.detectLanguage(text)?.code)
            }
            proof.put("actualInferencePassed", true)
        } finally {
            adapter.close()
            val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(directory, "chief-translation-inference.json").writeText(proof.toString(2))
        }
    }

    @Test fun recordActualModelSettingsHost() = runBlocking<Unit> {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use the disposable emulator" }
        check(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val proof = JSONObject().put("api", Build.VERSION.SDK_INT)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            device.waitForIdle(5_000)
            check(device.currentPackageName == context.packageName) { "A visible foreground host is required" }
            proof.put("beforePackage", device.currentPackageName).put("foregroundHostVerified", true)
            val adapter = checkNotNull(NativeChiefPlatformTranslator.create(context))
            try {
                val intent = checkNotNull(adapter.modelSettingsIntent())
                proof.put("creator", intent.creatorPackage)
                scenario.onActivity { host -> host.startIntentSenderForResult(intent.intentSender, 21, null, 0, 0, 0) }
                device.waitForIdle(5_000)
                SystemClock.sleep(1_000)
                proof.put("afterPackage", device.currentPackageName)
                check(device.takeScreenshot(File(directory, "chief-translation-settings.png")))
                device.dumpWindowHierarchy(File(directory, "chief-translation-settings.xml"))
                check(device.currentPackageName == "com.google.android.as") { "The actual provider settings must be visible" }
            } finally {
                adapter.close()
                File(directory, "chief-translation-settings.json").writeText(proof.toString(2))
            }
        }
    }
}
