package io.github.docmorphic.cmuxapp.detectorspike;

import android.os.Build;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.entityextraction.*;
import com.google.mlkit.common.model.DownloadConditions;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in synthetic observations. No app/account text, action, or physical device. */
public class EntityExtractionProviderTest {
    @Test public void recordStructuredDatesAddressesAndDownloadedModelState() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("entityExtractionProbe")));
        boolean offline = "true".equals(InstrumentationRegistry.getArguments().getString("entityProbeOffline"));
        assertTrue(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"));
        assertNotSame(Looper.getMainLooper(), Looper.myLooper());
        byte[] fixtureBytes;
        try (java.io.InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("Cases.json");
             java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            fixtureBytes = buffer.toByteArray();
        }
        JSONObject fixture = new JSONObject(new String(fixtureBytes, StandardCharsets.UTF_8));
        assertEquals(1, fixture.getInt("schema"));
        long reference = fixture.getLong("referenceEpochMillis");
        String referenceOverride = InstrumentationRegistry.getArguments().getString("referenceEpochMillis");
        if (referenceOverride != null) reference = Long.parseLong(referenceOverride);
        String localeOverride = InstrumentationRegistry.getArguments().getString("preferredLocale");
        String zone = fixture.getString("timeZone");
        JSONArray samples = fixture.getJSONArray("samples");
        File folder = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "entity-extraction-probe");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        JSONObject output = new JSONObject().put("sdk", Build.VERSION.SDK_INT).put("referenceMillis", reference)
            .put("referenceTime", Instant.ofEpochMilli(reference).toString()).put("zone", zone)
            .put("sdkVersion", "16.0.0-beta6").put("actionFired", false).put("offlineRequested", offline)
            .put("scope", "Opt-in isolated SDK evaluation on synthetic public examples only. Models and SDK operational metrics may contact Google as documented. No production, physical, source-detector or full parity acceptance.");
        StringBuilder digest = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(fixtureBytes)) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
        output.put("fixtureSha256", digest.toString());
        output.put("referenceSource", referenceOverride == null ? "fixedFixture" : "explicitOverride")
            .put("preferredLocaleOverride", localeOverride == null ? JSONObject.NULL : localeOverride);
        JSONArray records = new JSONArray();
        output.put("records", records);
        android.net.ConnectivityManager network = InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(android.net.ConnectivityManager.class);
        output.put("activeNetworkPresent", network.getActiveNetwork() != null);
        if (offline) assertNull("Offline inference must have no active network", network.getActiveNetwork());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < samples.length(); index++) {
                JSONObject input = samples.getJSONObject(index);
                String[] sample = {input.getString("language"), input.getString("locale"), input.getString("text")};
                JSONObject record = new JSONObject().put("id", input.getString("id")).put("language", sample[0]).put("locale", sample[1]).put("source", sample[2]);
                records.put(record);
                String model = EntityExtractorOptions.fromLanguageTag(sample[0]);
                assertNotNull("Use public model identifiers, not language tags", model);
                record.put("modelIdentifier", model);
                EntityExtractor extractor = EntityExtraction.getClient(new EntityExtractorOptions.Builder(model).setExecutor(executor).build());
                try {
                    boolean downloaded = Tasks.await(extractor.isModelDownloaded(), 15, TimeUnit.SECONDS);
                    record.put("modelDownloadedBefore", downloaded);
                    long started = System.nanoTime();
                    if (offline) assertTrue("Offline run requires the previously downloaded model", downloaded);
                    // Initialize each new client through its public readiness
                    // method even when the manager reports a cached model.
                    Tasks.await(extractor.downloadModelIfNeeded(new DownloadConditions.Builder().build()), 120, TimeUnit.SECONDS);
                    record.put("modelReadyMilliseconds", (System.nanoTime() - started) / 1_000_000.0);
                    record.put("modelDownloadedAfter", Tasks.await(extractor.isModelDownloaded(), 15, TimeUnit.SECONDS));
                    Locale preferred = Locale.forLanguageTag(localeOverride == null ? sample[1] : localeOverride);
                    record.put("effectivePreferredLocale", preferred.toLanguageTag());
                    EntityExtractionParams params = new EntityExtractionParams.Builder(sample[2])
                        .setEntityTypesFilter(new HashSet<>(Arrays.asList(Entity.TYPE_ADDRESS, Entity.TYPE_DATE_TIME)))
                        .setPreferredLocale(preferred).setReferenceTime(reference)
                        .setReferenceTimeZone(TimeZone.getTimeZone(zone)).build();
                    started = System.nanoTime();
                    java.util.List<EntityAnnotation> annotations = Tasks.await(extractor.annotate(params), 30, TimeUnit.SECONDS);
                    record.put("annotateMilliseconds", (System.nanoTime() - started) / 1_000_000.0);
                    JSONArray matches = new JSONArray();
                    for (EntityAnnotation annotation : annotations) {
                        assertTrue(annotation.getStart() >= 0 && annotation.getEnd() <= sample[2].length() && annotation.getEnd() > annotation.getStart());
                        assertEquals(sample[2].substring(annotation.getStart(), annotation.getEnd()), annotation.getAnnotatedText());
                        JSONObject match = new JSONObject().put("start", annotation.getStart()).put("end", annotation.getEnd()).put("substring", annotation.getAnnotatedText());
                        JSONArray entities = new JSONArray();
                        for (Entity entity : annotation.getEntities()) {
                            JSONObject item = new JSONObject().put("type", entity.getType());
                            if (entity.getType() == Entity.TYPE_DATE_TIME) {
                                DateTimeEntity date = entity.asDateTimeEntity();
                                item.put("epochMillis", date.getTimestampMillis()).put("granularity", date.getDateTimeGranularity())
                                    .put("resolvedLocalTime", Instant.ofEpochMilli(date.getTimestampMillis()).atZone(ZoneId.of(zone)).toString());
                            }
                            entities.put(item);
                        }
                        match.put("entities", entities); matches.put(match);
                    }
                    record.put("matches", matches);
                } catch (Exception failure) {
                    record.put("failureClass", failure.getClass().getName());
                    java.io.StringWriter details = new java.io.StringWriter();
                    failure.printStackTrace(new java.io.PrintWriter(details));
                    record.put("failureStack", details.toString());
                    if (failure.getCause() != null) record.put("causeClass", failure.getCause().getClass().getName());
                } finally {
                    extractor.close();
                    Files.write(new File(folder, "observations.json").toPath(), output.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            }
        } finally { executor.shutdownNow(); }
    }
}
