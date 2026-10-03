package io.github.docmorphic.cmuxapp.noticespike;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.mozilla.geckoview.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Test-only native control of the bundled fixture extension, with no content messaging. */
final class ProbeExtensionClient {
    private final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<>();
    private final AtomicReference<WebExtension.Port> port = new AtomicReference<>();
    private int serial;
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static <T> T await(Supplier<GeckoResult<T>> operation) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        main(() -> {
            try { operation.get().accept(result::complete, result::completeExceptionally); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
    ProbeExtensionClient(GeckoRuntime runtime) throws Exception {
        WebExtension installed = await(() -> runtime.getWebExtensionController()
            .ensureBuiltIn("resource://android/assets/probe/", "notice-probe@cmux-app.invalid"));
        WebExtension extension = await(() -> runtime.getWebExtensionController().setAllowedInPrivateBrowsing(installed, true));
        main(() -> extension.setMessageDelegate(new WebExtension.MessageDelegate() {
            @Override public void onConnect(WebExtension.Port connected) {
                if (connected.sender.session != null || connected.sender.environmentType != WebExtension.MessageSender.ENV_TYPE_EXTENSION) return;
                port.set(connected);
                connected.setDelegate(new WebExtension.PortDelegate() {
                    @Override public void onPortMessage(Object value, WebExtension.Port sender) {
                        if (sender == port.get() && value instanceof JSONObject) messages.add((JSONObject) value);
                    }
                });
            }
        }, "notice_probe"));
        JSONObject ready = messages.poll(30, TimeUnit.SECONDS);
        if (ready == null || !ready.optBoolean("ready")) throw new AssertionError("Extension not ready");
    }
    JSONObject command(JSONObject value) throws Exception {
        int id = ++serial; value.put("id", id);
        main(() -> port.get().postMessage(value));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            JSONObject reply = messages.poll(1, TimeUnit.SECONDS);
            if (reply != null && reply.optInt("id", -1) == id) return reply;
        }
        throw new AssertionError("Extension command timed out");
    }
}
