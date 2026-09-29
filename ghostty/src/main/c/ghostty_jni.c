#include <jni.h>
#include <ghostty/vt.h>
#include "virtual_placements.h"
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// IDs are never pointers and are never reused. The registry also fences a close
// racing with JNI entry; Kotlin serializes each owner's operations separately.
typedef struct Terminal {
    jlong id;
    GhosttyTerminal terminal;
    GhosttyRenderState render;
    GhosttyRenderStateRowIterator rows;
    GhosttyRenderStateRowCells cells;
    struct Terminal *next;
} Terminal;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static Terminal *terminals;
static jlong next_id = 1;
static int active_count;
#define JNI_METHOD(name) Java_io_github_docmorphic_cmuxapp_ghostty_GhosttyTerminal_##name
#define MAX_BYTES (16u * 1024u * 1024u)

static void fail(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass cls = (*env)->FindClass(env, type);
    if (cls) (*env)->ThrowNew(env, cls, message);
}
static bool ok(JNIEnv *env, GhosttyResult result) {
    if (result == GHOSTTY_SUCCESS) return true;
    fail(env, result == GHOSTTY_OUT_OF_MEMORY ? "java/lang/OutOfMemoryError" :
         "java/lang/IllegalStateException", "Ghostty terminal operation failed");
    return false;
}
static Terminal *lookup(JNIEnv *env, jlong id) {
    for (Terminal *entry = terminals; entry; entry = entry->next)
        if (entry->id == id) return entry;
    fail(env, "java/lang/IllegalStateException", "Ghostty terminal is closed");
    return NULL;
}
static void dispose(Terminal *entry) {
    ghostty_render_state_row_cells_free(entry->cells);
    ghostty_render_state_row_iterator_free(entry->rows);
    ghostty_render_state_free(entry->render);
    ghostty_terminal_free(entry->terminal);
    free(entry);
}
// Cached at library load so callbacks from any Java thread use the correct
// application class loader. No borrowed Java or native pixel buffer escapes.
static JavaVM *java_vm;
static jclass png_decoder;
static jmethodID png_decode;
#define IMAGE_BYTES 10000000u
#define IMAGE_COUNT 1024u
#define PLACEMENT_COUNT 4096u
#define SNAPSHOT_PLACEMENTS 65536u
_Static_assert(sizeof(CmuxVirtualPlacement) == 56, "Virtual placement ABI changed");

static uint32_t read_u32(const uint8_t *p) {
    return (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
}
static bool decode_png(void *userdata, const GhosttyAllocator *allocator,
        const uint8_t *data, size_t length, GhosttySysImage *out) {
    (void)userdata;
    // Parsing only runs synchronously inside nativeAppend on an attached thread.
    JNIEnv *env = NULL;
    if (length > IMAGE_BYTES || (*java_vm)->GetEnv(java_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK ||
        (*env)->ExceptionCheck(env)) return false;
    jbyteArray input = (*env)->NewByteArray(env, length);
    if (!input) return false;
    (*env)->SetByteArrayRegion(env, input, 0, length, (const jbyte *)data);
    jbyteArray decoded = NULL;
    if (!(*env)->ExceptionCheck(env))
        decoded = (*env)->CallStaticObjectMethod(env, png_decoder, png_decode, input);
    (*env)->DeleteLocalRef(env, input);
    if ((*env)->ExceptionCheck(env) || !decoded) return false;
    jsize size = (*env)->GetArrayLength(env, decoded);
    uint8_t header[8];
    bool success = false;
    if (size < 8 || (uint32_t)size > IMAGE_BYTES + 8) goto done;
    (*env)->GetByteArrayRegion(env, decoded, 0, 8, (jbyte *)header);
    uint32_t width = read_u32(header), height = read_u32(header + 4);
    if (!width || !height || width > 10000 || height > 10000 ||
        (uint64_t)width * height * 4 != (uint32_t)size - 8) goto done;
    uint8_t *pixels = ghostty_alloc(allocator, size - 8);
    if (!pixels) goto done;
    (*env)->GetByteArrayRegion(env, decoded, 8, size - 8, (jbyte *)pixels);
    if ((*env)->ExceptionCheck(env)) { ghostty_free(allocator, pixels, size - 8); goto done; }
    *out = (GhosttySysImage){.width = width, .height = height, .data = pixels, .data_len = size - 8};
    success = true;
done:
    (*env)->DeleteLocalRef(env, decoded);
    return success;
}
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "io/github/docmorphic/cmuxapp/ghostty/GhosttyPngDecoder");
    if (!cls) return JNI_ERR;
    png_decode = (*env)->GetStaticMethodID(env, cls, "decode", "([B)[B");
    if (!png_decode) { (*env)->DeleteLocalRef(env, cls); return JNI_ERR; }
    png_decoder = (*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);
    if (!png_decoder) return JNI_ERR;
    java_vm = vm;
    if (ghostty_sys_set(GHOSTTY_SYS_OPT_DECODE_PNG, decode_png) != GHOSTTY_SUCCESS) {
        (*env)->DeleteGlobalRef(env, png_decoder); png_decoder = NULL; return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

static bool dimensions(JNIEnv *env, jint cols, jint rows) {
    if (cols >= 2 && cols <= 1000 && rows >= 2 && rows <= 1000) return true;
    fail(env, "java/lang/IllegalArgumentException", "Invalid Ghostty dimensions");
    return false;
}

JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreate)(JNIEnv *env, jobject self, jint cols, jint rows, jint history) {
    (void)self;
    if (!dimensions(env, cols, rows)) return 0;
    if (history < 0 || history > 64 * 1024 * 1024) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid Ghostty history limit"); return 0;
    }
    pthread_mutex_lock(&lock);
    Terminal *entry = NULL;
    jlong id = 0;
    if (active_count >= 128 || next_id == INT64_MAX) {
        fail(env, "java/lang/IllegalStateException", "Too many native terminals"); goto done;
    }
    entry = calloc(1, sizeof(*entry));
    if (!entry) { fail(env, "java/lang/OutOfMemoryError", "Ghostty allocation failed"); goto done; }
    // Screen.init uses bytes, despite the pinned public header saying lines.
    GhosttyTerminalOptions options = {.cols = cols, .rows = rows, .max_scrollback = history};
    if (!ok(env, ghostty_terminal_new(NULL, &entry->terminal, options)) ||
        !ok(env, ghostty_render_state_new(NULL, &entry->render)) ||
        !ok(env, ghostty_render_state_row_iterator_new(NULL, &entry->rows)) ||
        !ok(env, ghostty_render_state_row_cells_new(NULL, &entry->cells))) goto done;
    GhosttyColorRgb fg = {.r = 0xe0, .g = 0xe5, .b = 0xeb};
    GhosttyColorRgb bg = {.r = 0x11, .g = 0x13, .b = 0x16};
    if (!ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg)) ||
        !ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg))) goto done;
    uint64_t image_bytes = IMAGE_BYTES, image_count = IMAGE_COUNT, placement_count = PLACEMENT_COUNT;
    bool disabled = false;
    if (!ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT, &image_bytes)) ||
        !ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_COUNT_LIMIT, &image_count)) ||
        !ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_KITTY_PLACEMENT_COUNT_LIMIT, &placement_count)) ||
        !ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE, &disabled)) ||
        !ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE, &disabled)) ||
        !ok(env, ghostty_terminal_set(entry->terminal, GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM, &disabled))) goto done;
    // No PTY, clipboard, title, filesystem or other effect callbacks are installed.
    id = entry->id = next_id++;
    entry->next = terminals; terminals = entry; active_count++;
done:
    if (entry && !id) dispose(entry);
    pthread_mutex_unlock(&lock);
    return id;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeDestroy)(JNIEnv *env, jobject self, jlong id) {
    (void)env; (void)self;
    pthread_mutex_lock(&lock);
    for (Terminal **position = &terminals; *position; position = &(*position)->next) {
        Terminal *entry = *position;
        if (entry->id != id) continue;
        *position = entry->next; active_count--; dispose(entry); break;
    }
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeActiveHandles)(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    pthread_mutex_lock(&lock); int count = active_count; pthread_mutex_unlock(&lock);
    return count;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeAppend)(JNIEnv *env, jobject self, jlong id, jbyteArray bytes) {
    (void)self;
    if (!bytes) { fail(env, "java/lang/IllegalArgumentException", "Missing terminal bytes"); return; }
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length > 2 * 1024 * 1024) {
        fail(env, "java/lang/IllegalArgumentException", "Terminal bytes exceed limit"); return;
    }
    pthread_mutex_lock(&lock);
    Terminal *entry = lookup(env, id);
    if (entry && length) {
        // Do not hold a critical JVM array while Ghostty allocates or parses.
        jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
        if (data) {
            ghostty_terminal_vt_write(entry->terminal, (const uint8_t *)data, length);
            (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
        }
    }
    pthread_mutex_unlock(&lock);
}

JNIEXPORT void JNICALL JNI_METHOD(nativeResize)(JNIEnv *env, jobject self, jlong id,
        jint cols, jint rows, jint width, jint height) {
    (void)self;
    if (!dimensions(env, cols, rows)) return;
    if (width < 1 || width > 4096 || height < 1 || height > 4096) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid terminal cell size"); return;
    }
    pthread_mutex_lock(&lock);
    Terminal *entry = lookup(env, id);
    if (entry) ok(env, ghostty_terminal_resize(entry->terminal, cols, rows, width, height));
    pthread_mutex_unlock(&lock);
}

typedef struct { uint8_t *data; size_t length, capacity; bool failed; } Buffer;
static void bytes(Buffer *b, const void *data, size_t length) {
    if (b->failed) return;
    if (length > MAX_BYTES - b->length) { b->failed = true; return; }
    size_t needed = b->length + length;
    if (needed > b->capacity) {
        size_t capacity = b->capacity ? b->capacity : 4096;
        while (capacity < needed) capacity *= 2;
        uint8_t *next = realloc(b->data, capacity);
        if (!next) { b->failed = true; return; }
        b->data = next; b->capacity = capacity;
    }
    if (length) memcpy(b->data + b->length, data, length);
    b->length += length;
}
static void set_integer(Buffer *b, size_t position, uint32_t value) {
    if (b->failed) return;
    b->data[position] = value >> 24; b->data[position + 1] = value >> 16;
    b->data[position + 2] = value >> 8; b->data[position + 3] = value;
}
static void integer(Buffer *b, uint32_t value) {
    size_t position = b->length; const uint8_t zero[4] = {0};
    bytes(b, zero, 4); set_integer(b, position, value);
}
static uint32_t rgb(GhosttyColorRgb color) { return color.r << 16 | color.g << 8 | color.b; }
static uint32_t style_color(GhosttyStyleColor color, const GhosttyRenderStateColors *colors) {
    if (color.tag == GHOSTTY_STYLE_COLOR_RGB) return rgb(color.value.rgb);
    if (color.tag == GHOSTTY_STYLE_COLOR_PALETTE) return rgb(colors->palette[color.value.palette]);
    return UINT32_MAX;
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeSnapshot)(JNIEnv *env, jobject self, jlong id, jint offset) {
    (void)self;
    if (offset < 0) { fail(env, "java/lang/IllegalArgumentException", "Negative scroll offset"); return NULL; }
    pthread_mutex_lock(&lock);
    Terminal *entry = lookup(env, id);
    Buffer output = {0};
    jbyteArray result = NULL;
    if (!entry) goto done;
    size_t history = 0;
    uint16_t cols = 0, rows = 0, cx = 0, cy = 0;
    GhosttyTerminalScreen screen;
    GhosttyTerminalCursorStyle cursor_style;
    GhosttyColorRgb fg, bg;
    bool reverse = false, app_cursor = false, paste = false, visible = false, blink = false;
#define GET(key, value) if (!ok(env, ghostty_terminal_get(entry->terminal, key, &(value)))) goto done
    GET(GHOSTTY_TERMINAL_DATA_COLS, cols); GET(GHOSTTY_TERMINAL_DATA_ROWS, rows);
    GET(GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, history);
    GET(GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, screen);
    GET(GHOSTTY_TERMINAL_DATA_CURSOR_X, cx); GET(GHOSTTY_TERMINAL_DATA_CURSOR_Y, cy);
    GET(GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE, visible);
    GET(GHOSTTY_TERMINAL_DATA_CURSOR_BLINKING, blink);
    GET(GHOSTTY_TERMINAL_DATA_CURSOR_VISUAL_STYLE, cursor_style);
    GET(GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND, fg); GET(GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, bg);
#undef GET
    if (!ok(env, ghostty_terminal_mode_get(entry->terminal, GHOSTTY_MODE_REVERSE_COLORS, &reverse)) ||
        !ok(env, ghostty_terminal_mode_get(entry->terminal, GHOSTTY_MODE_DECCKM, &app_cursor)) ||
        !ok(env, ghostty_terminal_mode_get(entry->terminal, GHOSTTY_MODE_BRACKETED_PASTE, &paste))) goto done;
    if (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) history = 0;
    if (history > INT32_MAX) {
        fail(env, "java/lang/IllegalStateException", "Ghostty history count exceeds limit"); goto done;
    }
    size_t actual_offset = (size_t)offset < history ? (size_t)offset : history;
    GhosttyTerminalScrollViewport viewport = {.tag = GHOSTTY_SCROLL_VIEWPORT_ROW,
                                               .value.row = history - actual_offset};
    ghostty_terminal_scroll_viewport(entry->terminal, viewport);
    if (!ok(env, ghostty_render_state_update(entry->render, entry->terminal))) goto done;
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    if (!ok(env, ghostty_render_state_colors_get(entry->render, &colors)) ||
        !ok(env, ghostty_render_state_get(entry->render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &entry->rows))) goto done;
    integer(&output, 0x47565431); integer(&output, cols); integer(&output, rows);
    integer(&output, rgb(fg)); integer(&output, rgb(bg));
    integer(&output, colors.cursor_has_value ? rgb(colors.cursor) : UINT32_MAX);
    integer(&output, reverse | (app_cursor << 1) | (paste << 2) |
            ((screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) << 3) |
            ((visible && !actual_offset) << 4) | (blink << 5));
    integer(&output, cx); integer(&output, cy); integer(&output, cursor_style);
    integer(&output, history); integer(&output, actual_offset); integer(&output, rows);
    size_t row_count = 0;
    while (ghostty_render_state_row_iterator_next(entry->rows) && !output.failed) {
        row_count++;
        if (!ok(env, ghostty_render_state_row_get(entry->rows, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &entry->cells))) goto done;
        size_t count_position = output.length;
        integer(&output, 0);
        uint32_t column = 0, count = 0;
        while (ghostty_render_state_row_cells_next(entry->cells) && !output.failed) {
            GhosttyCell raw;
            GhosttyCellWide wide;
            GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
            if (!ok(env, ghostty_render_state_row_cells_get(entry->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw)) ||
                !ok(env, ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide))) goto done;
            uint32_t col = column++;
            if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL || wide == GHOSTTY_CELL_WIDE_SPACER_HEAD) continue;
            if (!ok(env, ghostty_render_state_row_cells_get(entry->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style))) goto done;
            GhosttyColorRgb cell_fg, cell_bg;
            GhosttyResult fg_result = ghostty_render_state_row_cells_get(entry->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &cell_fg);
            GhosttyResult bg_result = ghostty_render_state_row_cells_get(entry->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &cell_bg);
            if (fg_result != GHOSTTY_SUCCESS && fg_result != GHOSTTY_INVALID_VALUE) { ok(env, fg_result); goto done; }
            if (bg_result != GHOSTTY_SUCCESS && bg_result != GHOSTTY_INVALID_VALUE) { ok(env, bg_result); goto done; }
            // Match the existing compatibility renderer's bright ANSI bold policy.
            if (style.bold && style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE && style.fg_color.value.palette < 8)
                cell_fg = colors.palette[style.fg_color.value.palette + 8];
            uint8_t text[16384];
            GhosttyBuffer grapheme = {.ptr = text, .cap = sizeof(text)};
            if (!ok(env, ghostty_render_state_row_cells_get(entry->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &grapheme))) goto done;
            if (!grapheme.len) { text[0] = ' '; grapheme.len = 1; }
            integer(&output, col); integer(&output, wide == GHOSTTY_CELL_WIDE_WIDE ? 2 : 1);
            integer(&output, fg_result == GHOSTTY_SUCCESS ? rgb(cell_fg) : UINT32_MAX);
            integer(&output, bg_result == GHOSTTY_SUCCESS ? rgb(cell_bg) : UINT32_MAX);
            integer(&output, style_color(style.underline_color, &colors));
            integer(&output, style.bold | (style.italic << 1) | (style.faint << 2) | (style.blink << 3) |
                    (style.inverse << 4) | (style.invisible << 5) | (style.strikethrough << 6) | (style.overline << 7));
            integer(&output, style.underline); integer(&output, grapheme.len);
            bytes(&output, text, grapheme.len); count++;
        }
        set_integer(&output, count_position, count);
    }
    if (output.failed || row_count != rows) {
        fail(env, "java/lang/IllegalStateException", "Ghostty snapshot exceeds limits or is incomplete"); goto done;
    }
    result = (*env)->NewByteArray(env, output.length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, output.length, (const jbyte *)output.data);
done:
    // A read of history must not change how the next live byte chunk is parsed.
    if (entry) ghostty_terminal_scroll_viewport(entry->terminal,
        (GhosttyTerminalScrollViewport){.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM});
    free(output.data);
    pthread_mutex_unlock(&lock);
    return result;
}

typedef struct { Buffer *output; uint32_t *count; uint16_t rows; } VirtualContext;
static bool append_virtual_placement(void *context, const CmuxVirtualPlacement *p) {
    VirtualContext *state = context;
    if (*state->count >= SNAPSHOT_PLACEMENTS) return false;
    Buffer *output = state->output;
    integer(output, p->image_id); integer(output, p->placement_id); integer(output, (uint32_t)-1);
    integer(output, 8 | ((p->row >= 0 && p->row < state->rows) ? 4 : 0));
    integer(output, p->offset_x); integer(output, p->offset_y);
    integer(output, p->column); integer(output, p->row);
    integer(output, p->pixel_width); integer(output, p->pixel_height);
    integer(output, p->grid_columns); integer(output, p->grid_rows);
    integer(output, p->source_x); integer(output, p->source_y);
    integer(output, p->source_width); integer(output, p->source_height);
    (*state->count)++;
    return !output->failed;
}

static int compare_generation(const void *left, const void *right) {
    uint64_t a = *(const uint64_t *)left, b = *(const uint64_t *)right;
    return (a > b) - (a < b);
}
JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeGraphicsSnapshot)(JNIEnv *env, jobject self, jlong id,
        jint offset, jlongArray cached) {
    (void)self;
    if (offset < 0) { fail(env, "java/lang/IllegalArgumentException", "Negative scroll offset"); return NULL; }
    if (!cached) { fail(env, "java/lang/IllegalArgumentException", "Missing image generations"); return NULL; }
    jsize known_count = (*env)->GetArrayLength(env, cached);
    if ((uint32_t)known_count > IMAGE_COUNT) {
        fail(env, "java/lang/IllegalArgumentException", "Too many cached image generations"); return NULL;
    }
    jlong java_generations[IMAGE_COUNT];
    uint64_t known[IMAGE_COUNT];
    (*env)->GetLongArrayRegion(env, cached, 0, known_count, java_generations);
    if ((*env)->ExceptionCheck(env)) return NULL;
    for (jsize i = 0; i < known_count; i++) known[i] = (uint64_t)java_generations[i];
    qsort(known, known_count, sizeof(*known), compare_generation);
    pthread_mutex_lock(&lock);
    Terminal *entry = lookup(env, id);
    GhosttyKittyGraphicsImageIterator images = NULL;
    GhosttyKittyGraphicsPlacementIterator placements = NULL;
    Buffer output = {0};
    jbyteArray result = NULL;
    if (!entry) goto done;
    size_t history = 0;
    GhosttyTerminalScreen screen;
    if (!ok(env, ghostty_terminal_get(entry->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &history)) ||
        !ok(env, ghostty_terminal_get(entry->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen))) goto done;
    if (screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE) history = 0;
    size_t actual_offset = (size_t)offset < history ? (size_t)offset : history;
    ghostty_terminal_scroll_viewport(entry->terminal,
        (GhosttyTerminalScrollViewport){.tag = GHOSTTY_SCROLL_VIEWPORT_ROW, .value.row = history - actual_offset});
    GhosttyKittyGraphics graphics;
    uint64_t generation;
    if (!ok(env, ghostty_terminal_get(entry->terminal, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &graphics)) ||
        !ok(env, ghostty_kitty_graphics_get(graphics, GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION, &generation)) ||
        !ok(env, ghostty_kitty_graphics_image_iterator_new(NULL, graphics, &images)) ||
        !ok(env, ghostty_kitty_graphics_placement_iterator_new(NULL, &placements)) ||
        !ok(env, ghostty_kitty_graphics_get(graphics, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR, &placements))) goto done;
    integer(&output, 0x47564932); // GVI2: generation references plus viewport-relative placements.
    integer(&output, generation >> 32); integer(&output, generation);
    integer(&output, actual_offset); integer(&output, 0); integer(&output, 0);
    uint32_t image_count = 0, placement_count = 0;
    bool has_virtual = false;
    size_t image_bytes = 0;
    GhosttyKittyGraphicsImage image;
    while ((image = ghostty_kitty_graphics_image_next(images))) {
        uint32_t image_id, width, height;
        uint64_t stamp;
        GhosttyKittyImageFormat format;
        size_t length;
        const uint8_t *data;
#define IMAGE_GET(key, value) if (!ok(env, ghostty_kitty_graphics_image_get(image, key, &(value)))) goto done
        IMAGE_GET(GHOSTTY_KITTY_IMAGE_DATA_ID, image_id);
        IMAGE_GET(GHOSTTY_KITTY_IMAGE_DATA_WIDTH, width);
        IMAGE_GET(GHOSTTY_KITTY_IMAGE_DATA_HEIGHT, height);
        IMAGE_GET(GHOSTTY_KITTY_IMAGE_DATA_FORMAT, format);
        IMAGE_GET(GHOSTTY_KITTY_IMAGE_DATA_GENERATION, stamp);
        IMAGE_GET(GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN, length);
        IMAGE_GET(GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR, data);
#undef IMAGE_GET
        if (++image_count > IMAGE_COUNT || length > IMAGE_BYTES - image_bytes) {
            fail(env, "java/lang/IllegalStateException", "Ghostty image snapshot exceeds limits"); goto done;
        }
        image_bytes += length;
        integer(&output, image_id); integer(&output, stamp >> 32); integer(&output, stamp);
        bool reuse = bsearch(&stamp, known, known_count, sizeof(*known), compare_generation) != NULL;
        integer(&output, width); integer(&output, height); integer(&output, format);
        integer(&output, reuse ? UINT32_MAX : (uint32_t)length);
        if (!reuse) bytes(&output, data, length);
    }
    while (ghostty_kitty_graphics_placement_next(placements)) {
        uint32_t image_id, placement_id, x_offset, y_offset;
        int32_t z;
        bool virtual, internal;
#define PLACE_GET(key, value) if (!ok(env, ghostty_kitty_graphics_placement_get(placements, key, &(value)))) goto done
        PLACE_GET(GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID, image_id);
        PLACE_GET(GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID, placement_id);
        PLACE_GET(GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET, x_offset);
        PLACE_GET(GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET, y_offset);
        PLACE_GET(GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z, z);
        PLACE_GET(GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_VIRTUAL, virtual);
        has_virtual |= virtual;
        PLACE_GET(GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IS_INTERNAL, internal);
#undef PLACE_GET
        image = ghostty_kitty_graphics_image(graphics, image_id);
        GhosttyKittyGraphicsPlacementRenderInfo info = GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsPlacementRenderInfo);
        if (!ok(env, ghostty_kitty_graphics_placement_render_info(placements, image, entry->terminal, &info))) goto done;
        if (++placement_count > PLACEMENT_COUNT) {
            fail(env, "java/lang/IllegalStateException", "Ghostty placement snapshot exceeds limits"); goto done;
        }
        integer(&output, image_id); integer(&output, placement_id); integer(&output, z);
        integer(&output, virtual | (internal << 1) | (info.viewport_visible << 2));
        integer(&output, x_offset); integer(&output, y_offset);
        integer(&output, info.viewport_col); integer(&output, info.viewport_row);
        integer(&output, info.pixel_width); integer(&output, info.pixel_height);
        integer(&output, info.grid_cols); integer(&output, info.grid_rows);
        integer(&output, info.source_x); integer(&output, info.source_y);
        integer(&output, info.source_width); integer(&output, info.source_height);
    }
    uint16_t viewport_rows;
    if (!ok(env, ghostty_terminal_get(entry->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &viewport_rows))) goto done;
    VirtualContext virtual = {.output = &output, .count = &placement_count, .rows = viewport_rows};
    // Ghostty scans placeholder cells only when a virtual placement exists.
    if (has_virtual && !ok(env, cmux_ghostty_virtual_placements(entry->terminal, &virtual, append_virtual_placement))) goto done;
    set_integer(&output, 16, image_count); set_integer(&output, 20, placement_count);
    if (output.failed) { fail(env, "java/lang/IllegalStateException", "Ghostty graphics snapshot exceeds limits"); goto done; }
    result = (*env)->NewByteArray(env, output.length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, output.length, (const jbyte *)output.data);
done:
    ghostty_kitty_graphics_image_iterator_free(images);
    ghostty_kitty_graphics_placement_iterator_free(placements);
    if (entry) ghostty_terminal_scroll_viewport(entry->terminal,
        (GhosttyTerminalScrollViewport){.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM});
    free(output.data);
    pthread_mutex_unlock(&lock);
    return result;
}
