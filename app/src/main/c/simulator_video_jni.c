#include <jni.h>
#include <android/native_window_jni.h>
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/log.h>
#include <libswscale/swscale.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

#define MAX_DECODERS 4
#define MAX_PIXELS 16777216LL
#define MAX_PACKET (8 * 1024 * 1024 + 3 * 65536)

typedef struct {
    uint64_t id;
    AVCodecContext *context;
    AVFrame *frame;
    struct SwsContext *scaler;
    int width, height;
} Decoder;

/* IDs are not pointers. One lock fences lookup/use/destruction, including stale JNI callers. */
static pthread_mutex_t registry_lock = PTHREAD_MUTEX_INITIALIZER;
static Decoder decoders[MAX_DECODERS];
static uint64_t next_id = 1;

static void clear_decoder(Decoder *d) {
    sws_freeContext(d->scaler);
    av_frame_free(&d->frame);
    avcodec_free_context(&d->context);
    *d = (Decoder){0};
}
static Decoder *lookup(jlong id) {
    if (id <= 0) return NULL;
    for (int i = 0; i < MAX_DECODERS; i++) if (decoders[i].id == (uint64_t)id) return &decoders[i];
    return NULL;
}

JNIEXPORT jlong JNICALL Java_io_github_docmorphic_cmuxapp_SimVideoNative_create(
        JNIEnv *env, jobject self, jint codec, jint width, jint height, jbyteArray extra) {
    (void)self;
    if ((codec != 0 && codec != 1) || width < 1 || height < 1 || width > 8192 || height > 8192 ||
        (int64_t)width * height > MAX_PIXELS || !extra) return 0;
    jsize size = (*env)->GetArrayLength(env, extra);
    if (size <= 0 || size > 1024 * 1024) return 0;
    pthread_mutex_lock(&registry_lock);
    Decoder *d = NULL;
    for (int i = 0; i < MAX_DECODERS; i++) if (!decoders[i].id) { d = &decoders[i]; break; }
    if (!d || next_id > INT64_MAX) { pthread_mutex_unlock(&registry_lock); return 0; }
    av_log_set_level(AV_LOG_QUIET);
    const AVCodec *implementation = avcodec_find_decoder(codec == 0 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264);
    if (!implementation) goto failed;
    d->context = avcodec_alloc_context3(implementation);
    d->frame = av_frame_alloc();
    if (!d->context || !d->frame) goto failed;
    d->context->thread_count = 1;
    d->context->flags |= AV_CODEC_FLAG_LOW_DELAY;
    d->context->max_pixels = MAX_PIXELS;
    d->context->width = width; d->context->height = height;
    d->context->extradata = av_mallocz((size_t)size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!d->context->extradata) goto failed;
    d->context->extradata_size = size;
    (*env)->GetByteArrayRegion(env, extra, 0, size, (jbyte *)d->context->extradata);
    if ((*env)->ExceptionCheck(env) || avcodec_open2(d->context, implementation, NULL) < 0) goto failed;
    d->width = width; d->height = height; d->id = next_id++;
    jlong result = (jlong)d->id;
    pthread_mutex_unlock(&registry_lock);
    return result;
failed:
    clear_decoder(d);
    pthread_mutex_unlock(&registry_lock);
    return 0;
}

JNIEXPORT jboolean JNICALL Java_io_github_docmorphic_cmuxapp_SimVideoNative_render(
        JNIEnv *env, jobject self, jlong handle, jbyteArray bytes, jobject surface, jlong timestamp) {
    (void)self;
    if (!bytes || !surface) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, bytes);
    if (size <= 0 || size > MAX_PACKET) return JNI_FALSE;
    pthread_mutex_lock(&registry_lock);
    Decoder *d = lookup(handle);
    if (!d) { pthread_mutex_unlock(&registry_lock); return JNI_FALSE; }
    jboolean shown = JNI_FALSE;
    AVPacket *packet = av_packet_alloc();
    ANativeWindow *window = NULL;
    if (!packet || av_new_packet(packet, size) < 0) goto done;
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *)packet->data);
    if ((*env)->ExceptionCheck(env)) goto done;
    packet->pts = timestamp; packet->dts = timestamp;
    av_frame_unref(d->frame);
    if (avcodec_send_packet(d->context, packet) < 0 || avcodec_receive_frame(d->context, d->frame) < 0) goto done;
    AVFrame *f = d->frame;
    if (f->width != d->width || f->height != d->height || f->pts != timestamp ||
        f->width < 1 || f->height < 1 || (int64_t)f->width * f->height > MAX_PIXELS) goto done;
    d->scaler = sws_getCachedContext(d->scaler, f->width, f->height, f->format,
        f->width, f->height, AV_PIX_FMT_RGBA, SWS_BILINEAR, NULL, NULL, NULL);
    if (!d->scaler) goto done;
    int matrix = f->colorspace == AVCOL_SPC_BT709 ? SWS_CS_ITU709 :
        (f->colorspace == AVCOL_SPC_BT2020_NCL || f->colorspace == AVCOL_SPC_BT2020_CL) ? SWS_CS_BT2020 : SWS_CS_ITU601;
    const int *coefficients = sws_getCoefficients(matrix);
    if (sws_setColorspaceDetails(d->scaler, coefficients, f->color_range == AVCOL_RANGE_JPEG,
                                coefficients, 1, 0, 1 << 16, 1 << 16) < 0) goto done;
    window = ANativeWindow_fromSurface(env, surface);
    if (!window || (*env)->ExceptionCheck(env)) goto done;
    if (ANativeWindow_setBuffersGeometry(window, f->width, f->height, WINDOW_FORMAT_RGBA_8888) != 0) goto done;
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, NULL) != 0) goto done;
    if (buffer.width == f->width && buffer.height == f->height && buffer.stride >= f->width && buffer.stride <= 32768 && buffer.bits) {
        uint8_t *destinations[4] = {(uint8_t *)buffer.bits, NULL, NULL, NULL};
        int strides[4] = {buffer.stride * 4, 0, 0, 0};
        shown = sws_scale(d->scaler, (const uint8_t *const *)f->data, f->linesize, 0, f->height,
                          destinations, strides) == f->height ? JNI_TRUE : JNI_FALSE;
    }
    if (ANativeWindow_unlockAndPost(window) != 0) shown = JNI_FALSE;
done:
    if (window) ANativeWindow_release(window);
    av_packet_free(&packet);
    av_frame_unref(d->frame);
    pthread_mutex_unlock(&registry_lock);
    return shown;
}

JNIEXPORT void JNICALL Java_io_github_docmorphic_cmuxapp_SimVideoNative_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    pthread_mutex_lock(&registry_lock);
    Decoder *d = lookup(handle);
    if (d) clear_decoder(d);
    pthread_mutex_unlock(&registry_lock);
}
