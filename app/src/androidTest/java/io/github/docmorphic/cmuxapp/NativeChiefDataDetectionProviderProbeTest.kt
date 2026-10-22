package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.Looper
import android.provider.CalendarContract
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Opt-in real system provider observations on an owned emulator; never fires an action. */
class NativeChiefDataDetectionProviderProbeTest {
    @Suppress("DEPRECATION")
    @Test fun recordsRealSystemDateAddressEntitiesAndPublicActionMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("chiefDetectorProviderProbe") == "true")
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
        check(Build.VERSION.SDK_INT >= 30)
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        val context = instrumentation.targetContext
        val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier
        val reference = ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        val records = JSONArray()
        val samples = listOf(
            "en-US" to "Meet me tomorrow at 8am at 1600 Amphitheatre Parkway, Mountain View, CA 94043.",
            "en-US" to "The meeting is October 25, 2026 at 8am. Email test@example.com or visit https://example.com/2026/10/25.",
            "de-DE" to "Wir treffen uns morgen um 8 Uhr in der Invalidenstraße 116, 10115 Berlin.",
            "en-US" to "😀 Friday at 5:30pm, 350 Third Street, Cambridge MA 02142."
        )
        for ((locale, source) in samples) {
            val record = JSONObject().put("locale", locale).put("text", source)
            try {
                val locales = LocaleList.forLanguageTags(locale)
                val request = TextLinks.Request.Builder(source).setDefaultLocales(locales)
                    .setEntityConfig(TextClassifier.EntityConfig.createWithExplicitEntityList(listOf(TextClassifier.TYPE_ADDRESS,
                        TextClassifier.TYPE_DATE, TextClassifier.TYPE_DATE_TIME)))
                    .setReferenceTime(reference).build()
                val before = System.nanoTime()
                val result = classifier.generateLinks(request)
                record.put("generateMilliseconds", (System.nanoTime() - before) / 1_000_000.0)
                val links = JSONArray()
                for (link in result.links) {
                    val item = JSONObject().put("start", link.start).put("end", link.end)
                        .put("substring", source.substring(link.start, link.end))
                    val entities = JSONArray()
                    repeat(link.entityCount) { index -> val entity = link.getEntity(index)
                        entities.put(JSONObject().put("type", entity).put("confidence", link.getConfidenceScore(entity).toDouble())) }
                    item.put("entities", entities)
                    val classification = classifier.classifyText(TextClassification.Request.Builder(source, link.start, link.end)
                        .setDefaultLocales(locales).setReferenceTime(reference).build())
                    item.put("legacyIntentPresent", classification.intent != null)
                    classification.intent?.let { intent -> item.put("legacyAction", intent.action)
                        .put("legacyData", intent.dataString).put("legacyBeginMillis", if (intent.hasExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME))
                            intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0) else JSONObject.NULL) }
                    val actions = JSONArray()
                    classification.actions.forEach { action -> actions.put(JSONObject().put("title", action.title.toString())
                        .put("description", action.contentDescription.toString()).put("enabled", action.isEnabled)
                        .put("activity", if (Build.VERSION.SDK_INT >= 31) action.actionIntent.isActivity else JSONObject.NULL)
                        .put("creatorPackage", action.actionIntent.creatorPackage)) }
                    item.put("actions", actions); links.put(item)
                }
                record.put("links", links)
            } catch (failure: Exception) { record.put("failureClass", failure.javaClass.name) }
            records.put(record)
        }
        val calendar = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(reference.toInstant().toEpochMilli().toString()).build()
        val output = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("classifierClass", classifier.javaClass.name)
            .put("maxGenerateLength", classifier.maxGenerateLinksTextLength).put("referenceTime", reference.toString())
            .put("calendarHandler", Intent(Intent.ACTION_VIEW, calendar).resolveActivity(context.packageManager)?.packageName ?: JSONObject.NULL)
            .put("mapsHandler", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=350%20Third%20Street")).resolveActivity(context.packageManager)?.packageName ?: JSONObject.NULL)
            .put("records", records).put("actionFired", false)
            .put("scope", "Synthetic public samples, real owned-emulator system provider, no actions or real account/phone data. Observations are not detection/action parity acceptance.")
        val folder = File(context.getExternalFilesDir(null), "chief-detector-provider").apply { mkdirs() }
        File(folder, "provider.json").writeText(output.toString(2))
    }
}
