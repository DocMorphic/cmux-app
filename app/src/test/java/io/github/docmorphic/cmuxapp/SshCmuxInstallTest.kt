package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class SshCmuxInstallTest {
    @Test fun platformAndPinnedUrlsAreExactAndUnsupportedHostsCannotSelectAnArchive() {
        assertEquals("cmux-tui-linux-arm64", SshCmuxPlatform.parse("Linux\naarch64\n").packageName)
        assertEquals("cmux-tui-darwin-x64", SshCmuxPlatform("Darwin", "x86_64").packageName)
        assertNull(SshCmuxPlatform("Windows_NT", "AMD64").packageName)
        assertNull(SshCmuxPlatform("Linux", "riscv64").packageName)
        assertEquals(4, SshCmuxRelease.integrity.size)
        SshCmuxRelease.integrity.forEach { (name, hash) ->
            assertEquals(64, Base64.getDecoder().decode(hash).size)
            assertEquals("https://registry.npmjs.org/$name/-/$name-0.13.4.tgz", SshCmuxRelease.url(name))
        }
        assertThrows(IllegalArgumentException::class.java) { SshCmuxRelease.url("../../unexpected") }
        for (raw in listOf("Linux", "Linux\narm64\nextra", "Linux\narm\r64\n"))
            assertThrows(IllegalArgumentException::class.java) { SshCmuxPlatform.parse(raw) }
    }
    @Test fun corruptDownloadsCannotBecomeCachedOrReachRemoteInstallation() = runBlocking {
        val root = Files.createTempDirectory("cmux-archive-test").toFile()
        try {
            var calls = 0
            val archive = SshCmuxArchive(root) { url, file ->
                assertTrue(url.startsWith("https://registry.npmjs.org/cmux-tui-darwin-arm64/"))
                calls++; file.writeText("corrupt archive")
            }
            val installer = SshCmuxInstaller(archive)
            val error = runCatching { installer.install(SshCmuxPlatform("Darwin", "arm64"),
                { error("Must not touch remote computer before integrity verification") }, { _, _ -> error("Must not upload") }, {}) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("checksum")); assertEquals(1, calls)
            assertTrue(root.listFiles()!!.isEmpty())
            runCatching { archive.get(SshCmuxPlatform("Other", "x64")) }
            assertEquals(1, calls)
        } finally { root.deleteRecursively() }
    }
    @Test fun canceledDownloadRemovesPartialFileWithoutRemoteMutation() = runBlocking {
        val root = Files.createTempDirectory("cmux-cancel-test").toFile()
        try {
            val started = CompletableDeferred<Unit>()
            val archive = SshCmuxArchive(root) { _, file -> file.writeText("partial"); started.complete(Unit); awaitCancellation() }
            val download = launch { archive.get(SshCmuxPlatform("Linux", "x86_64")) }
            started.await(); download.cancelAndJoin(); assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun checksumVerificationRejectsEmptyChangedAndOversizedFiles() {
        val file = File.createTempFile("cmux-checksum", ".tgz")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(file.readBytes()))
            SshCmuxRelease.verify(file, hash)
            file.appendBytes(byteArrayOf(4))
            assertThrows(IllegalArgumentException::class.java) { SshCmuxRelease.verify(file, hash) }
            file.writeText("")
            assertThrows(IllegalArgumentException::class.java) { SshCmuxRelease.verify(file, hash) }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(SshCmuxRelease.MAX_ARCHIVE + 1) }
            assertThrows(IllegalArgumentException::class.java) { SshCmuxRelease.verify(file, hash) }
        } finally { file.delete() }
    }
    @Test fun cleanupAndActivationCannotTargetUnrelatedPaths() {
        val token = UUID.randomUUID().toString()
        val stage = "/tmp/home 'quoted/.local/bin/.cmux-android-install-$token.aB123456"
        assertTrue(SshCmuxInstallScripts.validStage(stage, token))
        assertTrue(SshCmuxInstallScripts.cleanup(stage, token).contains("rm -rf --"))
        for (bad in listOf("/", "/tmp", stage + "/..", stage.replace(token, UUID.randomUUID().toString()), stage + "\n"))
            assertThrows(IllegalArgumentException::class.java) { SshCmuxInstallScripts.cleanup(bad, token) }
        assertThrows(IllegalArgumentException::class.java) { SshCmuxInstallScripts.prepare("bad; command") }
    }
    @Test fun verifiedPublishedArchiveInstallsAtomicallyAndNeverReplacesAnExistingBinary() = runBlocking {
        val input = System.getenv("CMUX_TUI_TEST_ARCHIVE")
        assumeTrue("Opt-in verified npm tarball not configured", input?.startsWith('/') == true)
        val source = File(input!!); require(source.isFile)
        val root = Files.createTempDirectory(File("/tmp").toPath(), "ci-").toFile()
        val home = root.resolve("home with 'quotes").apply { mkdir() }
        var downloads = 0; var uploads = 0
        val archive = SshCmuxArchive(root.resolve("cache")) { _, file -> downloads++; source.copyTo(file, overwrite = true); Unit }
        val installer = SshCmuxInstaller(archive)
        suspend fun exec(command: String): SshExecResult = withContext(Dispatchers.IO) {
            val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                environment().clear(); environment().putAll(mapOf("HOME" to home.absolutePath, "PATH" to "/usr/bin:/bin", "LANG" to "en_US.UTF-8"))
                redirectError(ProcessBuilder.Redirect.appendTo(root.resolve("stderr.txt")))
            }.start()
            check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "Install fixture timed out" }
            SshExecResult(process.inputStream.readBytes(), byteArrayOf(), process.exitValue())
        }
        val upload: suspend (File, String) -> Unit = { from, to ->
            require(File(to).canonicalPath.startsWith(home.canonicalPath + "/"))
            uploads++; from.copyTo(File(to), overwrite = false); Unit
        }
        fun stages() = home.resolve(".local/bin").listFiles().orEmpty().filter { it.name.startsWith(".cmux-android-install-") }
        try {
            val progress = mutableListOf<String>()
            val binary = installer.install(SshCmuxPlatform("Darwin", "arm64"), ::exec, upload, progress::add)
            assertEquals(home.resolve(".local/bin/cmux-tui").absolutePath, binary)
            assertTrue(File(binary).canExecute()); assertTrue(stages().isEmpty())
            assertEquals(1, downloads); assertEquals(1, uploads); assertEquals(3, progress.size)
            val original = MessageDigest.getInstance("SHA-256").digest(File(binary).readBytes())
            val second = runCatching { installer.install(SshCmuxPlatform("Darwin", "arm64"), ::exec, upload, {}) }.exceptionOrNull()
            assertTrue(second?.message.orEmpty().contains("appeared during installation"))
            assertEquals(1, downloads); assertEquals(2, uploads); assertTrue(stages().isEmpty())
            assertArrayEquals(original, MessageDigest.getInstance("SHA-256").digest(File(binary).readBytes()))
            val failed = runCatching { installer.install(SshCmuxPlatform("Darwin", "arm64"), ::exec,
                { from, to -> upload(from, to); error("Fixture interrupted upload") }, {}) }.exceptionOrNull()
            assertEquals("Fixture interrupted upload", failed?.message); assertTrue(stages().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
