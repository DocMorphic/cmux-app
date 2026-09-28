package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Does not alter account credentials; native callback and clipboard classification checks. */
class TerminalRichInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun imeAdvertisesImagesAndRejectsWrongTypesAndRetiredConnections() {
        instrumentation.runOnMainSync {
            val view = TerminalKeyboardView(context)
            val plain = EditorInfo()
            view.onCreateInputConnection(plain)
            assertNull(plain.contentMimeTypes)
            val received = mutableListOf<TerminalPasteContent>()
            val text = mutableListOf<String>()
            view.onContent = { received += it; true }
            view.onText = { text += it }
            val rich = EditorInfo()
            val connection = view.onCreateInputConnection(rich)!!
            assertArrayEquals(arrayOf("image/*"), rich.contentMimeTypes)
            val uri = Uri.parse("content://fixture/image.png")
            val image = InputContentInfo(uri, ClipDescription("Image", arrayOf("image/png")), null)
            val wrong = InputContentInfo(uri, ClipDescription("Text", arrayOf("text/plain")), null)
            assertFalse(connection.commitContent(wrong, 0, null))
            connection.setComposingText("界", 1)
            assertTrue(connection.commitContent(image, 0, null))
            assertEquals(listOf("界"), text)
            assertEquals(listOf(TerminalPasteContent.Item.Attachment(uri, true)), received.single().items)
            view.onCreateInputConnection(EditorInfo())
            assertFalse(connection.commitContent(image, 0, null))
            val current = view.onCreateInputConnection(EditorInfo())!!
            view.isEnabled = false
            assertFalse(current.commitContent(image, 0, null))
            view.dispose()
            received.forEach { it.close() }
            assertEquals(1, received.size)
        }
    }

    @Test fun clipboardPreservesImagesFilesAndTextWithoutCoercingIntentsOrLocalPaths() {
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val image = File(directory, "clipboard-fixture.png").apply { writeBytes(byteArrayOf(1)) }
        val file = File(directory, "clipboard-fixture.txt").apply { writeText("file content") }
        fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
        try {
            val imageUri = uri(image)
            val fileUri = uri(file)
            val clip = ClipData("Mixed", arrayOf("image/png", "text/plain"), ClipData.Item("caption", null, null, imageUri))
            clip.addItem(ClipData.Item(fileUri))
            clip.addItem(ClipData.Item("literal command"))
            val content = TerminalPasteContent.fromClipboard(context, clip)
            assertEquals(listOf(TerminalPasteContent.Item.Attachment(imageUri, true),
                TerminalPasteContent.Item.Attachment(fileUri, false), TerminalPasteContent.Item.Text("literal command")), content.items)
            val link = ClipData.newRawUri("Link", Uri.parse("https://cmux.com"))
            assertEquals(listOf(TerminalPasteContent.Item.Text("https://cmux.com")), TerminalPasteContent.fromClipboard(context, link).items)
            assertTrue(runCatching { TerminalPasteContent.fromClipboard(context, ClipData.newIntent("Intent", Intent(Intent.ACTION_VIEW))) }.isFailure)
            assertTrue(runCatching { TerminalPasteContent.fromClipboard(context, ClipData.newRawUri("File", Uri.fromFile(file))) }.isFailure)
        } finally { image.delete(); file.delete() }
    }

    @Test fun grantCleanupIsIdempotentAndClipboardBatchIsBounded() {
        var released = 0
        val content = TerminalPasteContent(emptyList()) { released++ }
        content.close(); content.close()
        assertEquals(1, released)
        val clip = ClipData.newPlainText("Large", "one")
        repeat(10) { clip.addItem(ClipData.Item("more")) }
        assertTrue(runCatching { TerminalPasteContent.fromClipboard(context, clip) }.isFailure)
    }
}
