package io.github.docmorphic.cmuxapp.noticespike;

import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.mozilla.geckoview.*;
import okhttp3.mockwebserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic TLS fixture in the separate experiment package. No certificate-error bypass. */
public final class PrivateHttpsProbe {
    private final JSONObject report = new JSONObject();
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void save() throws Exception {
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null);
        Files.writeString(new File(directory, "private-https-probe.json").toPath(), report.toString(2));
    }
    private static SSLSocketFactory serverTls() throws Exception {
        var assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        byte[] keyBytes;
        try (var input = assets.open("tls/server-test-key.pem")) {
            String pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            keyBytes = Base64.getDecoder().decode(pem.replaceAll("-----[^-]+-----|\\s", ""));
        }
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        java.security.cert.Certificate certificate;
        try (var input = assets.open("tls/server.pem")) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        char[] password = "synthetic-test-only".toCharArray();
        store.setKeyEntry("fixture", key, password, new java.security.cert.Certificate[] { certificate });
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(store, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(manager.getKeyManagers(), null, null);
        return tls.getSocketFactory();
    }
    private static GeckoSession open(GeckoRuntime runtime, String id) {
        AtomicReference<GeckoSession> ref = new AtomicReference<>();
        main(() -> {
            GeckoSession session = new GeckoSession(new GeckoSessionSettings.Builder().usePrivateMode(true).contextId(id).build());
            session.open(runtime); ref.set(session);
        });
        return ref.get();
    }
    private static void blank(GeckoSession session, String uri) throws Exception {
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        main(() -> {
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override public void onLocationChange(GeckoSession s, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                    if (uri.equals(url)) ready.complete(true);
                }
            });
            session.loadUri(uri);
        });
        assertTrue(ready.get(20, TimeUnit.SECONDS));
    }
    private static JSONObject checked(ProbeExtensionClient extension, JSONObject command) throws Exception {
        JSONObject response = extension.command(command);
        assertFalse(response.toString(), response.has("error"));
        return response;
    }
    private static void fixture(ProbeExtensionClient extension, String action) throws Exception {
        assertTrue(checked(extension, new JSONObject().put("op", "httpsFixture").put("action", action)).getBoolean("done"));
    }
    private static WebRequestError rejectedCertificate(GeckoSession session, String uri) throws Exception {
        CompletableFuture<WebRequestError> result = new CompletableFuture<>();
        main(() -> {
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override public GeckoResult<String> onLoadError(GeckoSession s, String url, WebRequestError error) {
                    if (uri.equals(url)) result.complete(error);
                    return null;
                }
            });
            session.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override public void onTitleChange(GeckoSession s, String title) {
                    if (title != null && title.startsWith("CMUX_HTTPS:")) result.completeExceptionally(new AssertionError("Untrusted TLS loaded"));
                }
            });
            session.loadUri(uri);
        });
        WebRequestError error = result.get(30, TimeUnit.SECONDS);
        assertEquals(WebRequestError.ERROR_CATEGORY_SECURITY, error.category);
        assertEquals(WebRequestError.ERROR_SECURITY_BAD_CERT, error.code);
        return error;
    }
    private static JSONObject page(GeckoSession session, MockWebServer server, boolean secure) throws Exception {
        String path = "/probe?step=" + UUID.randomUUID();
        String url = (secure ? "https" : "http") + "://cmux-notice.invalid:" + server.getPort() + path;
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        main(() -> {
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override public GeckoResult<String> onLoadError(GeckoSession s, String failed, WebRequestError error) {
                    if (url.equals(failed)) result.completeExceptionally(error);
                    return null;
                }
            });
            session.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override public void onTitleChange(GeckoSession s, String title) {
                    if (title != null && title.startsWith("CMUX_HTTPS:")) {
                        try { result.complete(new JSONObject(title.substring(11))); }
                        catch (Exception error) { result.completeExceptionally(error); }
                    }
                }
            });
            session.loadUri(url);
        });
        JSONObject observed = result.get(30, TimeUnit.SECONDS);
        RecordedRequest request;
        do { request = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull("Fixture request missing", request); }
        while (!path.equals(request.getPath()));
        observed.put("requestCookie", request.getHeader("Cookie") == null ? JSONObject.NULL : request.getHeader("Cookie"));
        observed.put("tlsHandshake", request.getHandshake() != null);
        assertEquals("TLS request transport", secure, request.getHandshake() != null);
        assertEquals("Page security context", secure, observed.getBoolean("secureContext"));
        assertEquals("HTTP-only cookie leaked to page", "", observed.getString("scriptCookie"));
        return observed;
    }
    @Test public void secureCookiesUseTrustedTlsAndStayIsolated() throws Exception {
        ArrayList<GeckoSession> sessions = new ArrayList<>();
        ProbeExtensionClient extension = null;
        try (ActivityScenario<ProbeActivity> activity = ActivityScenario.launch(ProbeActivity.class);
             MockWebServer https = new MockWebServer(); MockWebServer http = new MockWebServer()) {
            Dispatcher dispatcher = new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store")
                        .setBody("<!doctype html><title>Fixture</title><script>document.title='CMUX_HTTPS:'+JSON.stringify({scriptCookie:document.cookie,secureContext:isSecureContext})</script>");
                }
            };
            https.setDispatcher(dispatcher); http.setDispatcher(dispatcher);
            https.useHttps(serverTls(), false);
            https.start(InetAddress.getByName("127.0.0.1"), 0); http.start(InetAddress.getByName("127.0.0.1"), 0);
            AtomicReference<GeckoRuntime> ref = new AtomicReference<>();
            main(() -> ref.set(GeckoRuntime.create(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                new GeckoRuntimeSettings.Builder().configFilePath("").consoleOutput(false).remoteDebuggingEnabled(false).build())));
            GeckoRuntime runtime = ref.get();
            extension = new ProbeExtensionClient(runtime);
            fixture(extension, "resolve");
            String base = "https://cmux-notice.invalid:" + https.getPort() + "/";
            GeckoSession rejected = open(runtime, "tls-untrusted-" + UUID.randomUUID()); sessions.add(rejected);
            WebRequestError error = rejectedCertificate(rejected, base + "probe?untrusted");
            report.put("untrustedCertificate", new JSONObject().put("category", error.category).put("code", error.code)); save();
            main(rejected::close); sessions.remove(rejected);
            fixture(extension, "trust");
            for (String value : new String[] { "a", "b" }) {
                String id = "notice-tls-" + value + "-" + UUID.randomUUID();
                GeckoSession session = open(runtime, id); sessions.add(session);
                String blank = "about:blank#" + id;
                blank(session, blank);
                JSONObject seed = checked(extension, new JSONObject().put("op", "seed").put("scoped", true)
                    .put("tabUrl", blank).put("url", base).put("value", value));
                assertTrue(seed.getJSONObject("scoped").getBoolean("secure"));
                report.put("seed" + value, seed); save();
            }
            int index = 0;
            for (GeckoSession session : sessions) {
                String value = index++ == 0 ? "a" : "b";
                JSONObject tls = page(session, https, true);
                report.put("https" + value, tls); save();
                assertEquals("notice_probe=" + value, tls.getString("requestCookie"));
                JSONObject plain = page(session, http, false);
                report.put("http" + value, plain); save();
                assertTrue("Secure cookie leaked to plain HTTP", plain.isNull("requestCookie"));
                JSONObject restored = page(session, https, true);
                report.put("httpsAfterHttp" + value, restored); save();
                assertEquals("notice_probe=" + value, restored.getString("requestCookie"));
            }
            assertNotEquals(report.getJSONObject("seeda").getString("storeId"), report.getJSONObject("seedb").getString("storeId"));
            report.put("passed", true);
        } finally {
            main(() -> { for (GeckoSession session : sessions) session.close(); });
            if (extension != null) {
                fixture(extension, "clear");
                report.put("fixtureTrustAndDnsRestored", true);
            }
            save();
        }
    }
}
