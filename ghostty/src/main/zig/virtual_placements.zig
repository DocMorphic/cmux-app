//! Android-only C bridge to the pinned Ghostty renderer's placeholder resolver.
//! Copied into src/terminal/c by the core builder; upstream input stays unchanged.
const std = @import("std");
const lib = @import("../lib.zig");
const terminal_c = @import("terminal.zig");
const Result = @import("result.zig").Result;
const unicode = @import("../kitty/graphics_unicode.zig");
const options = @import("terminal_options");

pub const Placement = extern struct {
    image_id: u32,
    placement_id: u32,
    column: i32,
    row: i32,
    offset_x: u32,
    offset_y: u32,
    source_x: u32,
    source_y: u32,
    source_width: u32,
    source_height: u32,
    pixel_width: u32,
    pixel_height: u32,
    grid_columns: u32,
    grid_rows: u32,
};
const Callback = *const fn (?*anyopaque, *const Placement) callconv(lib.calling_conv) bool;

// Runs synchronously under the JNI owner's lock. Callback data is borrowed only
// for that callback; neither side may mutate the terminal while iterating.
pub fn visit(handle: terminal_c.Terminal, context: ?*anyopaque, callback: ?Callback) callconv(lib.calling_conv) Result {
    if (comptime !options.kitty_graphics) return .no_value;
    const wrapper = handle orelse return .invalid_value;
    const emit = callback orelse return .invalid_value;
    const terminal = wrapper.terminal;
    const storage = &terminal.screens.active.kitty_images;
    if (storage.images.count() == 0) return .success;
    const pages = &terminal.screens.active.pages;
    const top = pages.getTopLeft(.viewport);
    const bottom = pages.getBottomRight(.viewport) orelse return .success;
    // Include one following history row for the partially visible bottom edge.
    const limit = bottom.down(1) orelse bottom;
    const top_y = (pages.pointFromPin(.screen, top) orelse return .invalid_value).screen.y;
    const cell_width = @max(1, terminal.width_px / terminal.cols);
    const cell_height = @max(1, terminal.height_px / terminal.rows);
    var iterator = unicode.placementIterator(top, limit);
    while (iterator.next()) |placeholder| {
        const image = storage.imageById(placeholder.image_id) orelse continue;
        const resolved = placeholder.renderPlacement(storage, &image, cell_width, cell_height) catch continue;
        if (resolved.dest_width == 0 or resolved.dest_height == 0) continue;
        const point = pages.pointFromPin(.screen, resolved.top_left) orelse continue;
        const row = std.math.cast(i32, @as(i64, point.screen.y) - @as(i64, top_y)) orelse continue;
        const placement: Placement = .{
            .image_id = placeholder.image_id, .placement_id = placeholder.placement_id,
            .column = resolved.top_left.x, .row = row,
            .offset_x = resolved.offset_x, .offset_y = resolved.offset_y,
            .source_x = resolved.source_x, .source_y = resolved.source_y,
            .source_width = resolved.source_width, .source_height = resolved.source_height,
            .pixel_width = resolved.dest_width, .pixel_height = resolved.dest_height,
            .grid_columns = placeholder.width, .grid_rows = placeholder.height,
        };
        if (!emit(context, &placement)) return .invalid_value;
    }
    return .success;
}
