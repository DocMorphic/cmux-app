#include <jni.h>
#include <ghostty/vt.h>
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
