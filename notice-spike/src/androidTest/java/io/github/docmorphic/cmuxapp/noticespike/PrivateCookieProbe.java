package io.github.docmorphic.cmuxapp.noticespike;

import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.mozilla.geckoview.*;
import okhttp3.mockwebserver.*;
import java.io.File;
import java.nio.file.Files;
import java.net.InetAddress;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Separate package and engine; synthetic loopback fixtures only, never the signed-in app. */
public final class PrivateCookieProbe {
    private final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<>();
    private final AtomicReference<WebExtension.Port> port = new AtomicReference<>();
    private final JSONObject report = new JSONObject();
    private int requestId;

    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private <T> T await(Supplier<GeckoResult<T>> operation) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        main(() -> {
            try { operation.get().accept(future::complete, future::completeExceptionally); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future.get(45, TimeUnit.SECONDS);
    }
    private JSONObject command(JSONObject command) throws Exception {
        int id = ++requestId; command.put("id", id);
        main(() -> port.get().postMessage(command));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            JSONObject reply = messages.poll(1, TimeUnit.SECONDS);
            if (reply != null && reply.optInt("id", -1) == id) return reply;
        }
        throw new AssertionError("Extension command timed out");
    }
    private void save() throws Exception {
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        Files.writeString(new File(directory, "private-cookie-probe.json").toPath(), report.toString(2));
    }
    private GeckoSession session(GeckoRuntime runtime, String context) {
        AtomicReference<GeckoSession> session = new AtomicReference<>();
        main(() -> {
            GeckoSession s = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(true).contextId(context).build());
            s.open(runtime); session.set(s);
        });
        return session.get();
    }
    private void load(GeckoSession session, String url) throws Exception {
        CompletableFuture<Boolean> loaded = new CompletableFuture<>();
        main(() -> {
            boolean[] matched = { false };
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override public void onLocationChange(GeckoSession s, String current,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                    matched[0] = url.equals(current);
                    if (matched[0] && url.startsWith("about:blank#")) loaded.complete(true);
                }
            });
            session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override public void onPageStop(GeckoSession s, boolean success) {
                    if (matched[0]) loaded.complete(success);
                }
            });
            session.loadUri(url);
        });
        assertTrue("Fixture page failed", loaded.get(30, TimeUnit.SECONDS));
    }

    @Test public void seedPrivateContainersAndObserveRealRequests() throws Exception {
        boolean scoped = "scoped".equals(InstrumentationRegistry.getArguments().getString("cmux_cookie_api"));
        report.put("api", scoped ? "experimental origin attributes" : "public cookies API");
        GeckoSession first = null, second = null;
        try (ActivityScenario<ProbeActivity> activity = ActivityScenario.launch(ProbeActivity.class);
             MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            String base = "http://127.0.0.1:" + server.getPort() + "/";
            AtomicReference<GeckoRuntime> runtimeRef = new AtomicReference<>();
            main(() -> runtimeRef.set(GeckoRuntime.create(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new GeckoRuntimeSettings.Builder().configFilePath("").consoleOutput(false).remoteDebuggingEnabled(false).build())));
            GeckoRuntime runtime = runtimeRef.get();
            WebExtension installed = await(() -> runtime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/probe/", "notice-probe@cmux-app.invalid"));
            WebExtension extension = await(() -> runtime.getWebExtensionController().setAllowedInPrivateBrowsing(installed, true));
            main(() -> extension.setMessageDelegate(new WebExtension.MessageDelegate() {
                @Override public void onConnect(WebExtension.Port connected) {
                    if (connected.sender.session != null || connected.sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION) return;
                    port.set(connected);
                    connected.setDelegate(new WebExtension.PortDelegate() {
                        @Override public void onPortMessage(Object value, WebExtension.Port sender) {
                            if (value instanceof JSONObject && sender == port.get()) messages.add((JSONObject) value);
                        }
                    });
                }
            }, "notice_probe"));
            JSONObject ready = messages.poll(30, TimeUnit.SECONDS);
            assertNotNull("Background extension did not connect", ready);
            assertTrue(ready.optBoolean("ready"));
            String suffix = UUID.randomUUID().toString();
            first = session(runtime, "notice-a-" + suffix);
            second = session(runtime, "notice-b-" + suffix);
            String firstUrl = "about:blank#notice-a-" + suffix;
            String secondUrl = "about:blank#notice-b-" + suffix;
            load(first, firstUrl); load(second, secondUrl);
            report.put("tabs", command(new JSONObject().put("op", "tabs"))); save();
            JSONObject a = command(new JSONObject().put("op", "seed").put("scoped", scoped).put("tabUrl", firstUrl).put("url", base).put("value", "a"));
            report.put("seedA", a); save(); assertFalse(a.toString(), a.has("error"));
            JSONObject b = command(new JSONObject().put("op", "seed").put("scoped", scoped).put("tabUrl", secondUrl).put("url", base).put("value", "b"));
            report.put("seedB", b); save(); assertFalse(b.toString(), b.has("error"));
            boolean requestsIsolated = true;
            for (GeckoSession s : new GeckoSession[] { first, second }) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<!doctype html><title>Fixture</title>Private notice fixture"));
                load(s, base);
                RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(request);
                String expected = s == first ? "a" : "b";
                String cookie = request.getHeader("Cookie");
                report.put("request" + expected.toUpperCase(), cookie == null ? JSONObject.NULL : cookie); save();
                requestsIsolated &= ("notice_probe=" + expected).equals(cookie);
            }
            report.put("requestIsolation", requestsIsolated); save();
            assertTrue("Synthetic cookies must reach only their intended private context; see probe report", requestsIsolated);
            assertNotEquals("Private pages must have distinct stores", a.getString("storeId"), b.getString("storeId"));
        } finally {
            GeckoSession a = first, b = second;
            main(() -> { if (a != null) a.close(); if (b != null) b.close(); });
            save();
        }
    }
}
