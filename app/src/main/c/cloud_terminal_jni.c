// Android ownership adapter for the pinned cmux terminal C ABI. Kotlin serializes
// every use/close of a handle. Native callbacks only enqueue copied output bytes;
// they never call back into the client or touch a renderer/UI.
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include "cmux_terminal_client.h"

#define JNI(name) Java_io_github_docmorphic_cmuxapp_CloudNativeBindings_##name
#define MAX_BYTES (16u * 1024u * 1024u)
#define UNUSED(x) (void)(x)

extern bool cmux_android_initialize(void *env, void *context);
static atomic_bool runtime_ready = false;

typedef struct {
    CmuxTerminalClient *client;
    JavaVM *vm;
    jobject sink;
    jmethodID output;
    atomic_bool output_failed;
} AndroidClient;

static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass kind = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (kind) { (*env)->ThrowNew(env, kind, message); (*env)->DeleteLocalRef(env, kind); }
}

// Java uses standard UTF-8 byte arrays, not JNI's modified UTF-8 strings.
static char *text(JNIEnv *env, jbyteArray value, bool optional) {
    if ((*env)->ExceptionCheck(env)) return NULL;
    if (!value) { if (!optional) fail(env, "Missing Cloud argument"); return NULL; }
    jsize size = (*env)->GetArrayLength(env, value);
    if (size > 65536) { fail(env, "Cloud argument is too large"); return NULL; }
    char *copy = malloc((size_t)size + 1);
    if (!copy) { fail(env, "Cloud allocation failed"); return NULL; }
    (*env)->GetByteArrayRegion(env, value, 0, size, (jbyte *)copy);
    if ((*env)->ExceptionCheck(env)) { free(copy); return NULL; }
    if (memchr(copy, 0, (size_t)size)) { free(copy); fail(env, "Invalid Cloud argument"); return NULL; }
    copy[size] = 0;
    return copy;
}

static void clear_text(char *value) {
    if (!value) return;
    size_t size = strlen(value);
    volatile char *cursor = value;
    while (size--) *cursor++ = 0;
    free(value);
}

static void output(void *context, uint32_t kind, const uint8_t *bytes, size_t length, uint16_t cols, uint16_t rows) {
    AndroidClient *client = context;
    if (atomic_load(&client->output_failed)) return;
    if (length > MAX_BYTES || (length && !bytes) || kind < 1 || kind > 4) {
        atomic_store(&client->output_failed, true); return;
    }
    JNIEnv *env = NULL;
    bool attached = false;
    jint status = (*client->vm)->GetEnv(client->vm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*client->vm)->AttachCurrentThread(client->vm, &env, NULL) != JNI_OK) {
            atomic_store(&client->output_failed, true); return;
        }
        attached = true;
    } else if (status != JNI_OK) { atomic_store(&client->output_failed, true); return; }
    jbyteArray copy = (*env)->NewByteArray(env, (jsize)length);
    if (copy) {
        if (length) (*env)->SetByteArrayRegion(env, copy, 0, (jsize)length, (const jbyte *)bytes);
        if (!(*env)->ExceptionCheck(env)) (*env)->CallVoidMethod(env, client->sink, client->output, (jint)kind, copy, (jint)cols, (jint)rows);
        (*env)->DeleteLocalRef(env, copy);
    } else atomic_store(&client->output_failed, true);
    if ((*env)->ExceptionCheck(env)) {
        // Never unwind an exception through Rust or continue a partially lost stream.
        (*env)->ExceptionClear(env); atomic_store(&client->output_failed, true);
    }
    if (attached) (*client->vm)->DetachCurrentThread(client->vm);
}

JNIEXPORT void JNICALL JNI(initialize)(JNIEnv *env, jobject self, jobject context) {
    UNUSED(self);
    // Resolve through the app's class loader before any Rust TLS worker is started.
    jclass verifier = (*env)->FindClass(env, "org/rustls/platformverifier/CertificateVerifier");
    if (!verifier) return;
    (*env)->DeleteLocalRef(env, verifier);
    if (!context || !cmux_android_initialize(env, context)) {
        fail(env, "Cloud Android runtime could not initialize"); return;
    }
    atomic_store(&runtime_ready, true);
}

JNIEXPORT jlong JNICALL JNI(startTunnel)(JNIEnv *env, jobject self, jbyteArray config) {
    UNUSED(self);
    if (!atomic_load(&runtime_ready)) { fail(env, "Cloud Android runtime is not initialized"); return 0; }
    char *value = text(env, config, false);
    if (!value) return 0;
    CmuxWireGuardNet *net = cmux_wireguard_net_start(value, NULL, 0);
    clear_text(value);
    if (!net) fail(env, "Cloud tunnel could not start");
    return (jlong)(intptr_t)net;
}
JNIEXPORT void JNICALL JNI(freeTunnel)(JNIEnv *env, jobject self, jlong handle) {
    UNUSED(env); UNUSED(self); cmux_wireguard_net_free((CmuxWireGuardNet *)(intptr_t)handle);
}
JNIEXPORT jboolean JNICALL JNI(routeAllowed)(JNIEnv *env, jobject self, jlong handle, jbyteArray route) {
    UNUSED(self);
    char *value = text(env, route, false);
    if (!value) return false;
    bool allowed = cmux_wireguard_net_route_is_allowed((CmuxWireGuardNet *)(intptr_t)handle, value, NULL, 0);
    clear_text(value); return allowed;
}
JNIEXPORT jlong JNICALL JNI(connect)(JNIEnv *env, jobject self, jlong tunnel, jbyteArray route,
        jbyteArray directory, jbyteArray device, jbyteArray invitation, jboolean trusted, jlong timeout, jobject sink) {
    UNUSED(self);
    if (!atomic_load(&runtime_ready)) { fail(env, "Cloud Android runtime is not initialized"); return 0; }
    if (!sink || timeout <= 0) { fail(env, "Invalid Cloud connection options"); return 0; }
    char *r = text(env, route, false), *d = text(env, directory, false), *n = text(env, device, false);
    char *i = text(env, invitation, true);
    AndroidClient *client = NULL;
    if ((*env)->ExceptionCheck(env)) goto done;
    client = calloc(1, sizeof(*client));
    if (!client) { fail(env, "Cloud allocation failed"); goto done; }
    atomic_init(&client->output_failed, false);
    if ((*env)->GetJavaVM(env, &client->vm) != JNI_OK) { fail(env, "Cloud JVM unavailable"); goto done; }
    jclass type = (*env)->GetObjectClass(env, sink);
    if (!type) goto done;
    client->output = (*env)->GetMethodID(env, type, "onOutput", "(I[BII)V");
    (*env)->DeleteLocalRef(env, type);
    if ((*env)->ExceptionCheck(env)) goto done;
    client->sink = (*env)->NewGlobalRef(env, sink);
    if (!client->sink) { fail(env, "Cloud output registration failed"); goto done; }
    CmuxWireGuardNet *net = (CmuxWireGuardNet *)(intptr_t)tunnel;
    client->client = trusted ? cmux_terminal_client_connect_trusted_route(r, d, n, net, NULL, 0, (uint64_t)timeout)
        : cmux_terminal_client_connect_route(r, d, n, i, net, NULL, 0, (uint64_t)timeout);
    if (!client->client) { fail(env, "Cloud terminal connection failed"); goto done; }
    cmux_terminal_client_set_viewer_size_priority(client->client, true);
    cmux_terminal_client_set_output_callback(client->client, output, client);
done:
    clear_text(r); clear_text(d); clear_text(n); clear_text(i);
    if (client && (!client->client || (*env)->ExceptionCheck(env))) {
        if (client->client) cmux_terminal_client_disconnect(client->client);
        if (client->sink) (*env)->DeleteGlobalRef(env, client->sink);
        free(client); client = NULL;
    }
    return (jlong)(intptr_t)client;
}
JNIEXPORT void JNICALL JNI(disconnect)(JNIEnv *env, jobject self, jlong handle) {
    UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    if (!client) return;
    // Upstream synchronously clears callbacks before handing teardown to its worker.
    cmux_terminal_client_disconnect(client->client);
    (*env)->DeleteGlobalRef(env, client->sink);
    free(client);
}
JNIEXPORT jboolean JNICALL JNI(outputHealthy)(JNIEnv *env, jobject self, jlong handle) {
    UNUSED(env); UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    return client && !atomic_load(&client->output_failed);
}
JNIEXPORT void JNICALL JNI(attach)(JNIEnv *env, jobject self, jlong handle, jbyteArray terminal, jlong timeout) {
    UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    if (!client || timeout <= 0) { fail(env, "Invalid Cloud attachment"); return; }
    char *id = text(env, terminal, false);
    if (!id) return;
    bool ok = cmux_terminal_client_attach_with_timeout(client->client, id, NULL, 0, (uint64_t)timeout);
    clear_text(id);
    if (!ok) fail(env, "Cloud terminal attachment failed");
}
JNIEXPORT void JNICALL JNI(detach)(JNIEnv *env, jobject self, jlong handle) {
    UNUSED(env); UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    if (client) cmux_terminal_client_detach(client->client);
}
JNIEXPORT jbyteArray JNICALL JNI(catalog)(JNIEnv *env, jobject self, jlong handle, jint operation,
        jbyteArray workspace, jbyteArray name, jlong timeout) {
    UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    if (!client || timeout <= 0) { fail(env, "Invalid Cloud catalog request"); return NULL; }
    char *w = text(env, workspace, true), *n = text(env, name, true), *result = NULL;
    if (!(*env)->ExceptionCheck(env)) switch (operation) {
        case 0: result = cmux_terminal_client_session_snapshot(client->client, NULL, 0, (uint64_t)timeout); break;
        case 1: result = cmux_terminal_client_list_workspaces(client->client, NULL, 0, (uint64_t)timeout); break;
        case 2: result = cmux_terminal_client_list_terminals(client->client, NULL, 0, (uint64_t)timeout); break;
        case 3: result = cmux_terminal_client_create_workspace(client->client, n, NULL, 0, (uint64_t)timeout); break;
        case 4: result = cmux_terminal_client_create_terminal_in_workspace(client->client, w, n, NULL, 0, (uint64_t)timeout); break;
        default: fail(env, "Unknown Cloud catalog operation");
    }
    clear_text(w); clear_text(n);
    if (!result) { fail(env, "Cloud catalog request failed"); return NULL; }
    size_t size = strnlen(result, MAX_BYTES + 1);
    jbyteArray bytes = NULL;
    if (size > MAX_BYTES) fail(env, "Cloud catalog is too large");
    else {
        bytes = (*env)->NewByteArray(env, (jsize)size);
        if (bytes && size) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)size, (jbyte *)result);
    }
    cmux_terminal_client_string_free(result);
    return bytes;
}
JNIEXPORT jboolean JNICALL JNI(send)(JNIEnv *env, jobject self, jlong handle, jbyteArray value) {
    UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    if (!client || !value) return false;
    jsize size = (*env)->GetArrayLength(env, value);
    if ((uint32_t)size > MAX_BYTES) { fail(env, "Cloud input is too large"); return false; }
    jbyte *bytes = (*env)->GetByteArrayElements(env, value, NULL);
    if (!bytes) return false;
    bool ok = cmux_terminal_client_send(client->client, (uint8_t *)bytes, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, value, bytes, JNI_ABORT);
    return ok;
}
JNIEXPORT jlong JNICALL JNI(resize)(JNIEnv *env, jobject self, jlong handle, jint cols, jint rows) {
    UNUSED(env); UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    if (!client || cols < 1 || cols > UINT16_MAX || rows < 1 || rows > UINT16_MAX) return 0;
    uint64_t request = 0;
    return cmux_terminal_client_resize_with_request_id(client->client, (uint16_t)cols, (uint16_t)rows, &request) ? (jlong)request : 0;
}
JNIEXPORT jlongArray JNICALL JNI(resizeAck)(JNIEnv *env, jobject self, jlong handle) {
    UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    uint64_t request; uint16_t cols, rows; bool changed;
    if (!client || !cmux_terminal_client_last_resize_ack(client->client, &request, &cols, &rows, &changed)) return NULL;
    jlong values[] = {(jlong)request, cols, rows, changed};
    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
JNIEXPORT jboolean JNICALL JNI(hasExited)(JNIEnv *env, jobject self, jlong handle) {
    UNUSED(env); UNUSED(self);
    AndroidClient *client = (AndroidClient *)(intptr_t)handle;
    return !client || cmux_terminal_client_has_exited(client->client);
}
