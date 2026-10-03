package io.github.docmorphic.cmuxapp.noticespike;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.mozilla.geckoview.*;
import okhttp3.mockwebserver.*;
import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Logical private-storage lifetime checks. Run restart phases in separate processes. */
public final class PrivateStorageProbe {
    private static Scope retained;
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static File receipt() {
        return new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getNoBackupFilesDir(), "private-storage-restart.json");
    }
    private static void save(String name, JSONObject value) throws Exception {
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        Files.writeString(new File(root, name + ".json").toPath(), value.toString(2));
    }
    private static final class Scope implements AutoCloseable {
        final ActivityScenario<ProbeActivity> activity;
        final GeckoRuntime runtime;
        final MockWebServer server = new MockWebServer();
        final ArrayList<GeckoSession> sessions = new ArrayList<>();
        final IdentityHashMap<GeckoSession, String> urls = new IdentityHashMap<>();
        final ProbeExtensionClient extension;
        Scope(int port) throws Exception {
            String html;
            try (var input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("private-storage.html")) {
                html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    if (!request.getPath().startsWith("/probe?")) return new MockResponse().setResponseCode(204);
                    MockResponse response = new MockResponse().setHeader("Content-Type", "text/html")
                        .setHeader("Cache-Control", "no-store").setBody(html);
                    String value = request.getRequestUrl().queryParameter("write");
                    if ("a".equals(value) || "b".equals(value)) response.setHeader("Set-Cookie",
                        "notice_probe=" + value + "; HttpOnly; SameSite=Lax; Path=/; Max-Age=3600");
                    return response;
                }
            });
            server.start(InetAddress.getByName("127.0.0.1"), port);
            activity = ActivityScenario.launch(ProbeActivity.class);
            AtomicReference<GeckoRuntime> ref = new AtomicReference<>();
            main(() -> ref.set(GeckoRuntime.create(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new GeckoRuntimeSettings.Builder().configFilePath("").consoleOutput(false).remoteDebuggingEnabled(false).build())));
            runtime = ref.get();
            extension = "scoped".equals(InstrumentationRegistry.getArguments().getString("cmux_storage_cleanup"))
                ? new ProbeExtensionClient(runtime) : null;
        }
        GeckoSession open(String context) {
            AtomicReference<GeckoSession> ref = new AtomicReference<>();
            main(() -> {
                GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder()
                    .usePrivateMode(true).contextId(context).build());
                session.open(runtime); ref.set(session);
            });
            sessions.add(ref.get()); return ref.get();
        }
        void retire(GeckoSession session, String context) throws Exception {
            String lease = null;
            if (extension != null) {
                JSONObject captured = extension.command(new JSONObject().put("op", "capture").put("tabUrl", urls.get(session)));
                assertFalse(captured.toString(), captured.has("error")); lease = captured.getString("lease");
            }
            main(() -> { session.close(); runtime.getStorageController().clearDataForSessionContext(context); });
            sessions.remove(session); urls.remove(session);
            if (extension != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                JSONObject reply;
                do {
                    reply = extension.command(new JSONObject().put("op", "clear").put("lease", lease));
                    if (!reply.optString("error").contains("Tab still open")) break;
                    Thread.sleep(50);
                } while (System.nanoTime() < deadline);
                assertTrue(reply.toString(), reply.optBoolean("cleared"));
            }
        }
        JSONObject page(GeckoSession session, String write) throws Exception {
            String path = "/probe?step=" + UUID.randomUUID() + (write == null ? "" : "&write=" + write);
            String url = "http://127.0.0.1:" + server.getPort() + path;
            CompletableFuture<JSONObject> result = new CompletableFuture<>();
            main(() -> {
                session.setContentDelegate(new GeckoSession.ContentDelegate() {
                    @Override public void onTitleChange(GeckoSession s, String title) {
                        try {
                            if (title != null && title.startsWith("CMUX_PROBE:")) result.complete(new JSONObject(title.substring(11)));
                            if (title != null && title.startsWith("CMUX_ERROR:")) result.completeExceptionally(new AssertionError(title));
                        } catch (Exception error) { result.completeExceptionally(error); }
                    }
                });
                session.loadUri(url);
            });
            JSONObject observed = result.get(30, TimeUnit.SECONDS);
            RecordedRequest request;
            do { request = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull("Missing fixture request", request); }
            while (!path.equals(request.getPath()));
            observed.put("requestCookie", request.getHeader("Cookie") == null ? JSONObject.NULL : request.getHeader("Cookie"));
            urls.put(session, url);
            return observed;
        }
        @Override public void close() throws Exception {
            main(() -> { for (GeckoSession session : sessions) session.close(); });
            sessions.clear(); activity.close(); server.close();
        }
    }
    private static void state(JSONObject observation, String expected) throws Exception {
        JSONObject after = observation.getJSONObject("after");
        assertEquals("HTTP-only cookie leaked to page script", "", after.getString("scriptCookie"));
        for (String key : new String[] { "local", "indexed", "cache" }) {
            if (expected == null) assertTrue(key + " retained data", after.isNull(key));
            else assertEquals(key, expected, after.getString(key));
        }
    }
    private static boolean empty(JSONObject value) throws Exception {
        JSONObject after = value.getJSONObject("after");
        return value.isNull("requestCookie") && after.isNull("local") && after.isNull("indexed") && after.isNull("cache");
    }

    @Test public void scopedClearRemovesPrivateStorageAndPreservesOtherPage() throws Exception {
        JSONObject report = new JSONObject().put("cleanup", InstrumentationRegistry.getArguments().getString("cmux_storage_cleanup", "public"));
        try (Scope scope = new Scope(0)) {
            String aId = "storage-a-" + UUID.randomUUID(), bId = "storage-b-" + UUID.randomUUID();
            GeckoSession a = scope.open(aId), b = scope.open(bId);
            JSONObject seededA = scope.page(a, "a"), seededB = scope.page(b, "b");
            report.put("seedA", seededA).put("seedB", seededB); save("private-storage-clear", report);
            state(seededA, "a"); state(seededB, "b");
            assertTrue(seededA.isNull("requestCookie")); assertTrue(seededB.isNull("requestCookie"));
            JSONObject before = scope.page(a, null);
            report.put("beforeClear", before); save("private-storage-clear", report);
            assertEquals("notice_probe=a", before.getString("requestCookie")); state(before, "a");
            scope.retire(a, aId);
            GeckoSession reopened = scope.open(aId);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            JSONObject cleared; int attempts = 0;
            do { cleared = scope.page(reopened, null); attempts++; }
            while (!empty(cleared) && System.nanoTime() < deadline);
            report.put("afterClear", cleared).put("clearObservationAttempts", attempts); save("private-storage-clear", report);
            assertTrue("Scoped asynchronous cleanup did not complete", empty(cleared)); state(cleared, null);
            JSONObject control = scope.page(b, null);
            report.put("otherPage", control); save("private-storage-clear", report);
            assertEquals("notice_probe=b", control.getString("requestCookie")); state(control, "b");
            report.put("passed", true);
        } finally { save("private-storage-clear", report); }
    }

    @Test public void prepareProcessDeath() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("cmux_storage_process_probe")));
        assertFalse("Finish the previous restart phase first", receipt().exists());
        Scope scope = new Scope(0);
        JSONObject report = new JSONObject().put("context", "storage-restart-" + UUID.randomUUID())
            .put("port", scope.server.getPort()).put("prepared", false);
        Files.writeString(receipt().toPath(), report.toString(2));
        try {
            GeckoSession page = scope.open(report.getString("context"));
            state(scope.page(page, "a"), "a");
            JSONObject before = scope.page(page, null);
            assertEquals("notice_probe=a", before.getString("requestCookie")); state(before, "a");
            report.put("beforeProcessDeath", before).put("prepared", true);
            Files.writeString(receipt().toPath(), report.toString(2)); save("private-storage-before-restart", report);
            retained = scope; // Intentionally leave the private session live for force-stop.
        } catch (Throwable failure) { scope.close(); throw failure; }
    }

    @Test public void inspectAfterProcessDeath() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("cmux_storage_process_probe")));
        JSONObject report = new JSONObject(Files.readString(receipt().toPath()));
        assertTrue(report.getBoolean("prepared"));
        // Rebind the exact origin and context. A different random port would make this check vacuous.
        try (Scope scope = new Scope(report.getInt("port"))) {
            JSONObject after = scope.page(scope.open(report.getString("context")), null);
            report.put("afterProcessDeath", after); save("private-storage-after-restart", report);
            assertTrue("Private data survived process death", empty(after)); state(after, null);
            assertFalse(after.getBoolean("databaseExisted")); assertFalse(after.getBoolean("cacheExisted"));
            report.put("passed", true); save("private-storage-after-restart", report);
            assertTrue(receipt().delete());
        }
    }
}
