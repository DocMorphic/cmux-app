package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SshKeyImportTest {
    @Test fun acceptsUtf8BelowTheFileLimit() {
        val text = "fixture λ key\n"
        assertEquals(text, readSshKeyText(text.toByteArray().inputStream()))
        assertEquals(65535, readSshKeyText(ByteArray(65535) { 65 }.inputStream()).length)
    }
    @Test fun refusesLargeFilesWithoutReadingTheWholeProviderStream() {
        val input = ByteArrayInputStream(ByteArray(1024 * 1024) { 65 })
        assertThrows(IllegalStateException::class.java) { readSshKeyText(input) }
        assertEquals(1024 * 1024 - 65536, input.available())
    }
    @Test fun rejectsMalformedUtf8InsteadOfSilentlyReplacingKeyBytes() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            readSshKeyText(byteArrayOf(0xc3.toByte(), 0x28).inputStream())
        }
    }
}
