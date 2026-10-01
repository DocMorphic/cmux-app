package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SshFilePathsTest {
    @Test fun remoteNamesAndGlobsAreLiteralPosixComponents() {
        val name = "file *?\\ 中.txt"
        assertTrue(SshFilePaths.validName(name))
        assertEquals("/home/$name", SshFilePaths.join("/home/", name))
        assertEquals("/home/file \\*\\?\\\\ 中.txt", SshFilePaths.literal("/home/$name"))
        listOf("", " ", ".", "..", "x/y", "x\u0000y").forEach { assertFalse(SshFilePaths.validName(it)) }
        assertEquals("/", SshFilePaths.parent("/home"))
    }
    @Test fun rootTrailDoesNotConfuseCommonPrefixesOrWindowsSeparators() {
        assertEquals(listOf("/home/me", "/home/me/a", "/home/me/a/b"), SshFilePaths.trail("/home/me", "/home/me/a/b"))
        assertEquals(listOf("/", "/home", "/home/med", "/home/med/x"), SshFilePaths.trail("/home/me", "/home/med/x"))
        assertEquals(listOf("/", "/one\\two"), SshFilePaths.trail("/", "/one\\two"))
    }
    @Test fun uploadSuffixesPreserveExtensionsAndDotfiles() {
        assertEquals("a 3.txt", SshFilePaths.unique("a.txt", setOf("a.txt", "a 2.txt")))
        assertEquals(".env 2", SshFilePaths.unique(".env", setOf(".env")))
        assertEquals("a.tar 2.gz", SshFilePaths.unique("a.tar.gz", setOf("a.tar.gz")))
    }
    @Test fun terminalInsertionCannotTurnFilenameControlsIntoKeystrokes() {
        assertTrue(SshFilePaths.canInsert("/a *?\\ 中.txt"))
        listOf("/line\ncommand", "/escape\u001b[31m", "/return\r", "/tab\t", "/delete\u007f").forEach {
            assertFalse(SshFilePaths.canInsert(it))
        }
    }
}
