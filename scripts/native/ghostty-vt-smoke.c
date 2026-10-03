// Synthetic Android ABI/runtime acceptance for the pinned Ghostty VT core.
// This is not an app renderer and cannot access a Mac, PTY or account.
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <ghostty/vt.h>

static void feed(GhosttyTerminal terminal, const char *text) {
    // All boundaries are exercised, including inside UTF-8 and escape sequences.
    for (size_t i = 0; i < strlen(text); i++)
        ghostty_terminal_vt_write(terminal, (const uint8_t *)text + i, 1);
}

static GhosttyGridRef ref_at(GhosttyTerminal terminal, uint16_t x, uint16_t y) {
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyPoint point = {.tag = GHOSTTY_POINT_TAG_ACTIVE,
                         .value.coordinate = {.x = x, .y = y}};
    assert(ghostty_terminal_grid_ref(terminal, point, &ref) == GHOSTTY_SUCCESS);
    return ref;
}

static uint32_t point_at(GhosttyTerminal terminal, uint16_t x, uint16_t y) {
    GhosttyGridRef ref = ref_at(terminal, x, y);
    GhosttyCell cell;
    uint32_t point = 0;
    assert(ghostty_grid_ref_cell(&ref, &cell) == GHOSTTY_SUCCESS);
    assert(ghostty_cell_get(cell, GHOSTTY_CELL_DATA_CODEPOINT, &point) == GHOSTTY_SUCCESS);
    return point;
}

static void check_terminal(void) {
    GhosttyTerminal terminal = NULL;
    GhosttyTerminalOptions options = {.cols = 12, .rows = 3, .max_scrollback = 100};
    assert(ghostty_terminal_new(NULL, &terminal, options) == GHOSTTY_SUCCESS);
    feed(terminal, "\033[1;4:3;38;2;18;52;86mA\033[0m\xc3\xa9\xe4\xb8\xad");
    assert(point_at(terminal, 0, 0) == 'A');
    assert(point_at(terminal, 1, 0) == 0xe9);
    assert(point_at(terminal, 2, 0) == 0x4e2d);
    GhosttyGridRef ref = ref_at(terminal, 0, 0);
    GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
    assert(ghostty_grid_ref_style(&ref, &style) == GHOSTTY_SUCCESS);
    assert(style.bold && style.underline == GHOSTTY_SGR_UNDERLINE_CURLY);
    assert(style.fg_color.tag == GHOSTTY_STYLE_COLOR_RGB);
    assert(style.fg_color.value.rgb.r == 18 && style.fg_color.value.rgb.g == 52 &&
           style.fg_color.value.rgb.b == 86);
    ref = ref_at(terminal, 2, 0);
    GhosttyCell cell;
    GhosttyCellWide wide;
    assert(ghostty_grid_ref_cell(&ref, &cell) == GHOSTTY_SUCCESS);
    assert(ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide) == GHOSTTY_SUCCESS);
    assert(wide == GHOSTTY_CELL_WIDE_WIDE);

    feed(terminal, "\033[?1h\033[?2004h\033[?1049hALT");
    bool mode = false;
    assert(ghostty_terminal_mode_get(terminal, GHOSTTY_MODE_DECCKM, &mode) == GHOSTTY_SUCCESS && mode);
    assert(ghostty_terminal_mode_get(terminal, GHOSTTY_MODE_BRACKETED_PASTE, &mode) == GHOSTTY_SUCCESS && mode);
    GhosttyTerminalScreen screen;
    assert(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &screen) == GHOSTTY_SUCCESS);
    assert(screen == GHOSTTY_TERMINAL_SCREEN_ALTERNATE);
    feed(terminal, "\033[?1049l");
    assert(point_at(terminal, 0, 0) == 'A');
    feed(terminal, "\r\nline1\r\nline2\r\nline3\r\nline4");
    size_t history = 0;
    assert(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &history) == GHOSTTY_SUCCESS);
    assert(history == 2);
    assert(ghostty_terminal_resize(terminal, 8, 4, 10, 20) == GHOSTTY_SUCCESS);
    uint16_t columns = 0;
    assert(ghostty_terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLS, &columns) == GHOSTTY_SUCCESS);
    assert(columns == 8);

    GhosttyRenderState render = NULL;
    GhosttyRenderStateRowIterator rows = NULL;
    assert(ghostty_render_state_new(NULL, &render) == GHOSTTY_SUCCESS);
    assert(ghostty_render_state_update(render, terminal) == GHOSTTY_SUCCESS);
    assert(ghostty_render_state_row_iterator_new(NULL, &rows) == GHOSTTY_SUCCESS);
    assert(ghostty_render_state_get(render, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &rows) == GHOSTTY_SUCCESS);
    size_t count = 0;
    while (ghostty_render_state_row_iterator_next(rows)) count++;
    assert(count == 4);
    ghostty_render_state_row_iterator_free(rows);
    ghostty_render_state_free(render);
    ghostty_terminal_free(terminal);
}

int main(void) {
    for (int i = 0; i < 100; i++) check_terminal();
    printf("PASS: 100 terminal lifecycles, split UTF-8/VT, RGB/curly underline, wide cells, "
           "modes, alternate restore, scrollback, resize and render snapshot; page_size=%ld\n",
           sysconf(_SC_PAGESIZE));
    return 0;
}
