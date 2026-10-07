package io.github.docmorphic.cmuxapp

import org.xml.sax.Attributes

/** ODF repeats expand outside ZIP inflation. Bound them before handing XML to SheetJS. */
internal class OpenDocumentBudget(private val maxCells: Int, private val maxText: Long) {
    var spreadsheet = false; private set
    private var rowRepeat = 1L
    private var cellRepeat = 1L
    private var row = 0L
    private var column = 0L
    private var inRow = false
    private var inCell = false
    private var materialized = false
    private var cellText = 0L
    private var cells = 0L
    private var text = 0L

    fun start(uri: String, name: String, attributes: Attributes) {
        if (name == "spreadsheet" && uri == "urn:oasis:names:tc:opendocument:xmlns:office:1.0") spreadsheet = true
        // SheetJS also recognizes legacy UOS aliases; they are not ODF and must
        // not bypass the ODF repetition accounting through its permissive parser.
        check(name !in setOf("行", "数据", "文本串")) { "Legacy spreadsheet markup is not supported." }
        val attributeText = (0 until attributes.length).sumOf { attributes.getValue(it).length.toLong() }
        if (inCell) { cellText += attributeText; check(cellText <= maxText) }
        // Match local names like the bundled parser, including differently prefixed attributes.
        fun attribute(key: String): String? {
            val matches = (0 until attributes.length).filter { attributes.getLocalName(it) == key }
            check(matches.size <= 1) { "Ambiguous spreadsheet attribute." }
            return matches.singleOrNull()?.let(attributes::getValue)
        }
        fun count(key: String, maximum: Long): Long {
            val raw = attribute(key) ?: return 1
            check(raw.isNotEmpty() && raw.all { it in '0'..'9' }) { "Invalid spreadsheet repetition." }
            return checkNotNull(raw.toLongOrNull()).also { check(it in 1..maximum) { "Spreadsheet repetition is too large." } }
        }
        when (name) {
            "table" -> { check(!inRow && !inCell); row = 0 }
            "table-row" -> {
                check(!inRow); inRow = true; column = 0
                check(attribute("行号") == null) { "Legacy spreadsheet coordinates are not supported." }
                rowRepeat = count("number-rows-repeated", 1_048_576)
                check(row + rowRepeat <= 1_048_576) { "Too many spreadsheet rows." }
            }
            "table-cell", "covered-table-cell" -> {
                check(inRow && !inCell); inCell = true
                cellRepeat = count("number-columns-repeated", 16_384)
                check(column + cellRepeat <= 16_384) { "Too many spreadsheet columns." }
                for (key in listOf("number-rows-spanned", "number-matrix-rows-spanned"))
                    check(row + count(key, 1_048_576) <= 1_048_576)
                for (key in listOf("number-columns-spanned", "number-matrix-columns-spanned"))
                    check(column + count(key, 16_384) <= 16_384)
                materialized = attribute("value-type")?.let { it != "string" } == true ||
                    attribute("string-value") != null || attribute("formula") != null
                cellText = attributeText
            }
            "p" -> if (inCell) materialized = true
            "s" -> if (inCell) {
                cellText += count("c", maxText)
                check(cellText <= maxText) { "Spreadsheet text is too large." }
            }
        }
    }

    fun text(length: Int) { if (inCell) { cellText += length; check(cellText <= maxText) } }

    fun end(name: String) {
        when (name) {
            "table-cell", "covered-table-cell" -> {
                check(inCell)
                // Blank repeated tail cells carry coordinates without allocating a giant grid.
                if (materialized) {
                    val copies = rowRepeat * cellRepeat
                    cells += copies
                    check(cells <= maxCells) { "This spreadsheet expands into too many cells." }
                    check(cellText <= maxText / copies) { "Repeated spreadsheet text is too large." }
                    text += cellText * copies
                    check(text <= maxText) { "Repeated spreadsheet text is too large." }
                }
                column += cellRepeat; inCell = false
            }
            "table-row" -> { check(inRow && !inCell); row += rowRepeat; inRow = false; rowRepeat = 1 }
        }
    }
}
