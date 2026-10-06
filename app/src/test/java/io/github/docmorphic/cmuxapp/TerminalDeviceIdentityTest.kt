package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class TerminalDeviceIdentityTest {
    @Test fun modelNamesAreSingleLineBoundedAndDoNotSplitUnicode() {
        assertEquals("Pixel 6a", TerminalDeviceIdentity(" \nPixel\u0000\t 6a\u00a0 ").name)
        assertEquals("Android", TerminalDeviceIdentity("\u0000\t ").name)
        assertEquals("Android", TerminalDeviceIdentity(null).name)
        val name = TerminalDeviceIdentity("😀".repeat(80)).name
        assertEquals(64, name.codePointCount(0, name.length))
        assertEquals("😀".repeat(64), name)
        assertFalse(TerminalDeviceIdentity("x".repeat(63) + " long").name.endsWith(' '))
    }

    @Test fun persistedIdentitySurvivesNewStoreAndConcurrentCreationButIsUniquePerInstall() {
        val directory = Files.createTempDirectory("cmux-device-test").toFile()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val ids = executor.invokeAll((1..12).map { Callable {
                TerminalDeviceIdentityStore(File(directory, "first")).loadOrCreate()
            } }).map { it.get() }
            assertEquals(1, ids.distinct().size)
            assertEquals(ids.first(), TerminalDeviceIdentityStore(File(directory, "first")).loadOrCreate())
            assertNotEquals(ids.first(), TerminalDeviceIdentityStore(File(directory, "second")).loadOrCreate())
            assertEquals(ids.first(), TerminalDeviceIdentity.canonicalId(ids.first()))
            assertEquals(listOf("terminal-device-id"), File(directory, "first").list()!!.toList())
        } finally { executor.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun unreadableOrCorruptStorageDoesNotInventAnUnpersistedIdentity() {
        val directory = Files.createTempDirectory("cmux-device-test").toFile()
        try {
            val path = File(directory, "terminal-device-id")
            path.writeText("corrupt")
            assertTrue(runCatching { TerminalDeviceIdentityStore(directory).loadOrCreate() }.isFailure)
            assertEquals("corrupt", path.readText())
            val fileInsteadOfDirectory = File(directory, "not-a-directory").apply { writeText("x") }
            assertTrue(runCatching { TerminalDeviceIdentityStore(fileInsteadOfDirectory).loadOrCreate() }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun optionalIdentityIsCanonicalAndRejectsArbitraryValues() {
        assertNull(TerminalDeviceIdentity("Pixel").deviceId)
        val id = "f2f98081-af0d-4dbe-a590-aad4d516f450"
        assertEquals(id, TerminalDeviceIdentity.canonicalId(id.uppercase()))
        for (bad in listOf("", "1-1-1-1-1", "phone\nname", id.uppercase())) {
            assertTrue(runCatching { TerminalDeviceIdentity("Pixel", bad) }.isFailure)
        }
    }
}
