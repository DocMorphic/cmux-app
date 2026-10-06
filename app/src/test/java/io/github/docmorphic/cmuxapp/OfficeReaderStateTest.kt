package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfficeReaderStateTest {
    @Test fun savedLocationRestoresWorkbookWindowAndViewportWithoutContent() {
        val state = OfficeReaderState()
        state.readWorkbookState(JSONObject().put("sheet", 3).put("row", 200).put("col", 64))
        state.viewport.x = 90f; state.viewport.y = 250f; state.viewport.zoom = 2f
        val restored = OfficeReaderState().apply { restore(state.save()) }
        assertEquals(3, restored.sheet); assertEquals(200, restored.row); assertEquals(64, restored.column)
        assertEquals(90f, restored.viewport.x); assertEquals(250f, restored.viewport.y); assertEquals(2f, restored.viewport.zoom)
        assertEquals(8, state.save().size)
    }
    @Test fun malformedCoordinatesAreBoundedBeforeTheyReachTheViewerUrl() {
        val state = OfficeReaderState()
        state.restore(listOf(Double.POSITIVE_INFINITY, -12, 999999, 0f, 0f, 1f, -1, 0f))
        assertEquals(0, state.sheet); assertEquals(0, state.row); assertEquals(16383, state.column)
        state.readWorkbookState(JSONObject().put("sheet", "1;unsafe()").put("row", 1.5).put("col", JSONObject.NULL))
        assertEquals(0, state.sheet); assertEquals(0, state.row); assertEquals(0, state.column)
    }
    @Test fun workbookRoutingUsesSharedPrecedenceAndDoesNotClaimOtherFormats() {
        assertEquals(ChangesPreviewRoute.WORKBOOK, filePreviewRoute("binary", null, "/work/BOOK.XLSX"))
        assertEquals(ChangesPreviewRoute.WORKBOOK, filePreviewRoute("binary", "${WorkbookPreviewPolicy.MIME}; charset=utf-8", "download"))
        assertEquals(ChangesPreviewRoute.TEXT, filePreviewRoute("text", null, "book.xlsx"))
        assertEquals(ChangesPreviewRoute.IMAGE, filePreviewRoute("image", null, "book.xlsx"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, filePreviewRoute("binary", null, "book.xls"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, filePreviewRoute("binary", null, "book.numbers"))
    }
}
