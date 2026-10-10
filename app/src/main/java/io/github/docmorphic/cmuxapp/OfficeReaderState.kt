package io.github.docmorphic.cmuxapp

import org.json.JSONObject

internal object WorkbookPreviewPolicy {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val ODS_MIME = "application/vnd.oasis.opendocument.spreadsheet"
    fun matches(path: String, mime: String?) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in setOf("xlsx", "ods") ||
        mime?.substringBefore(';')?.trim()?.lowercase() in setOf(MIME, ODS_MIME)
}

internal object PresentationPreviewPolicy {
    const val MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    fun matches(path: String, mime: String?) = path.substringAfterLast('/').substringAfterLast('.', "").equals("pptx", true) ||
        mime?.substringBefore(';')?.trim()?.equals(MIME, true) == true
}

internal enum class OfficePreviewKind(val assetDirectory: String, val description: String, val scripts: List<String>) {
    WORD("docx-viewer", "Word document preview", listOf("jszip.min.js", "docx-preview.min.js")),
    WORKBOOK("workbook-viewer", "Spreadsheet preview", listOf("xlsx.full.min.js", "workbook-model.js", "workbook-drawings.js", "ods-presentation.js")),
    PRESENTATION("presentation-viewer", "PowerPoint presentation preview", listOf("aiden0z-pptx-renderer.browser.es.js"));
    val assets get() = mapOf("shell.html" to "text/html", "viewer.css" to "text/css", "viewer.js" to "application/javascript") +
        scripts.associateWith { "application/javascript" }
}

/** Only reader location goes into saved state; no cell values, formulas or workbook contents. */
internal class OfficeReaderState {
    val viewport = MarkdownViewportState()
    var sheet = 0; private set
    var row = 0; private set
    var column = 0; private set
    var slide = 0; private set
    fun save(): List<Any> = listOf(sheet, row, column) + viewport.save() + slide
    fun restore(values: List<Any>) {
        if (values.size !in 8..9) return
        sheet = coordinate(values[0], 2047); row = coordinate(values[1], 1_048_575); column = coordinate(values[2], 16_383)
        viewport.restore(values.subList(3, 8))
        slide = coordinate(values.getOrNull(8), 2047)
    }
    fun readWorkbookState(message: JSONObject) {
        sheet = coordinate(message.opt("sheet"), 2047)
        row = coordinate(message.opt("row"), 1_048_575)
        column = coordinate(message.opt("col"), 16_383)
    }
    fun readPresentationState(message: JSONObject) { slide = coordinate(message.opt("slide"), 2047) }
    private fun coordinate(value: Any?, maximum: Int): Int = (value as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.coerceIn(0.0, maximum.toDouble())?.toInt() ?: 0
}
