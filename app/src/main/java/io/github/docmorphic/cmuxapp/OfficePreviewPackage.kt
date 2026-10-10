package io.github.docmorphic.cmuxapp

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory

internal object DocxPreviewPolicy {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    fun matches(path: String, mime: String?) = path.substringAfterLast('/').substringAfterLast('.', "").equals("docx", true) ||
        mime?.substringBefore(';')?.trim()?.equals(MIME, true) == true
}

internal data class OfficePackageLimits(
    val archiveBytes: Long = 64L * 1024 * 1024,
    val expandedBytes: Long = 64L * 1024 * 1024,
    val entryBytes: Int = 16 * 1024 * 1024,
    val xmlBytes: Int = 8 * 1024 * 1024,
    val entries: Int = 2048,
    val elements: Int = 200_000,
    val depth: Int = 256,
)

/** An owned, bounded ZIP snapshot: the JavaScript decoder never consumes unchecked ZIP metadata. */
internal class OfficePreviewPackage private constructor(val file: File, private val lease: AutoCloseable) : AutoCloseable {
    override fun close() { try { file.parentFile?.deleteRecursively() } finally { lease.close() } }

    companion object {
        fun prepareRichText(source: File, root: File, limits: RtfPreviewLimits = RtfPreviewLimits(), checkActive: () -> Unit = {}): OfficePreviewPackage {
            check(source.length() in 1..limits.bytes.toLong()) { "This rich text document is too large to preview." }
            check(root.isDirectory || root.mkdirs()) { "Could not prepare the rich text preview." }
            val directory = File(root, UUID.randomUUID().toString())
            val lease = ArtifactExportCache.hold(directory)
            try {
                ArtifactExportCache.prune(root)
                check(directory.mkdir()) { "Could not prepare the rich text preview." }
                val output = File(directory, "document.rtf")
                source.inputStream().use { input -> output.outputStream().use { target ->
                    val buffer = ByteArray(16 * 1024)
                    var count = 0L
                    while (true) {
                        checkActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        check(count <= limits.bytes) { "This rich text document is too large to preview." }
                        target.write(buffer, 0, read)
                    }
                    target.fd.sync()
                } }
                RtfPreviewBudget.validate(output.readBytes(), limits, checkActive)
                return OfficePreviewPackage(output, lease)
            } catch (failure: Throwable) {
                directory.deleteRecursively(); lease.close(); throw failure
            }
        }

        fun prepare(source: File, root: File, limits: OfficePackageLimits = OfficePackageLimits(), checkActive: () -> Unit = {}): OfficePreviewPackage {
            check(source.length() in 1..limits.archiveBytes) { "This Office document is too large to preview." }
            check(root.isDirectory || root.mkdirs()) { "Could not prepare the Office preview." }
            val directory = File(root, UUID.randomUUID().toString())
            val lease = ArtifactExportCache.hold(directory)
            try {
                ArtifactExportCache.prune(root)
                check(directory.mkdir()) { "Could not prepare the Office preview." }
                val output = File(directory, "document.zip")
                var total = 0L
                var elements = 0
                val names = mutableSetOf<String>()
                var odsMime = false
                var odsSpreadsheet = false
                ZipFile(source).use { zip ->
                    check(zip.size() in 1..limits.entries) { "This Office document contains too many parts." }
                    ZipOutputStream(output.outputStream()).use { target ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            checkActive()
                            val entry = entries.nextElement()
                            val name = entry.name
                            check(name.length in 1..512 && !name.contains('\\') && !name.any { it.isISOControl() } &&
                                name.trimEnd('/').split('/').all { it.isNotEmpty() && it != "." && it != ".." } && names.add(name)) {
                                "This Office document has invalid or duplicate parts."
                            }
                            if (entry.isDirectory) continue
                            val xml = name.endsWith(".xml", true) || name.endsWith(".rels", true)
                            val limit = if (xml) minOf(limits.xmlBytes, limits.entryBytes) else limits.entryBytes
                            check(entry.size in 0..limit.toLong()) { "A part of this Office document is too large to preview." }
                            val bytes = zip.getInputStream(entry).use { input ->
                                val buffer = ByteArray(16 * 1024)
                                val data = ByteArrayOutputStream()
                                while (true) {
                                    checkActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(data.size().toLong() + count <= limit && total <= limits.expandedBytes) {
                                        "This Office document expands beyond the preview limit."
                                    }
                                    data.write(buffer, 0, count)
                                }
                                data.toByteArray()
                            }
                            val crc = CRC32().apply { update(bytes) }.value
                            check(bytes.size.toLong() == entry.size && crc == entry.crc) { "This Office document is damaged." }
                            if (name == "mimetype") odsMime = bytes.contentEquals(WorkbookPreviewPolicy.ODS_MIME.toByteArray())
                            val ods = if (name == "content.xml") OpenDocumentBudget(limits.elements, limits.expandedBytes) else null
                            if (xml) validateXml(bytes, limits, checkActive, ods) { elements++; check(elements <= limits.elements) {
                                "This Office document is too complex to preview."
                            } }
                            if (ods != null) odsSpreadsheet = ods.spreadsheet
                            // STORED avoids a second decompression and recreates all lengths/CRCs from actual bytes.
                            target.putNextEntry(ZipEntry(name).apply {
                                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; this.crc = crc
                            })
                            target.write(bytes); target.closeEntry()
                        }
                    }
                }
                check(("[Content_Types].xml" in names && "_rels/.rels" in names) ||
                    (odsMime && odsSpreadsheet && "META-INF/manifest.xml" in names)) { "This file is not a supported Office document." }
                checkActive()
                return OfficePreviewPackage(output, lease)
            } catch (failure: Throwable) {
                directory.deleteRecursively(); lease.close(); throw failure
            }
        }

        private fun validateXml(bytes: ByteArray, limits: OfficePackageLimits, checkActive: () -> Unit, ods: OpenDocumentBudget?, element: () -> Unit) {
            var depth = 0
            val handler = object : DefaultHandler2() {
                override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("Document types are not supported in Office previews.") }
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entities are not supported.") }
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    checkActive(); element(); check(++depth <= limits.depth) { "This Office document is too deeply nested." }
                    ods?.start(uri.orEmpty(), localName.orEmpty(), checkNotNull(attributes))
                }
                override fun endElement(uri: String?, localName: String?, qName: String?) { ods?.end(localName.orEmpty()); depth-- }
                override fun characters(ch: CharArray, start: Int, length: Int) { ods?.text(length) }
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
                override fun error(error: org.xml.sax.SAXParseException) { throw error }
            }
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            reader.contentHandler = handler; reader.entityResolver = handler; reader.errorHandler = handler
            // The lexical callback rejects DTDs before any entity resolution, including internal entity bombs.
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            bytes.inputStream().use { reader.parse(InputSource(it)) }
        }
    }
}
