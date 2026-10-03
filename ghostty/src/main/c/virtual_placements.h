#ifndef CMUX_VIRTUAL_PLACEMENTS_H
#define CMUX_VIRTUAL_PLACEMENTS_H
#include <ghostty/vt.h>

// Private bridge ABI, compiled with the exact pinned source and recorded hash.
typedef struct {
    uint32_t image_id, placement_id;
    int32_t column, row;
    uint32_t offset_x, offset_y;
    uint32_t source_x, source_y, source_width, source_height;
    uint32_t pixel_width, pixel_height, grid_columns, grid_rows;
} CmuxVirtualPlacement;
typedef bool (*CmuxVirtualPlacementCallback)(void *, const CmuxVirtualPlacement *);
GhosttyResult cmux_ghostty_virtual_placements(GhosttyTerminal, void *, CmuxVirtualPlacementCallback);
#endif
