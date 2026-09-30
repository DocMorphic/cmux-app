package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RoutedBrowserStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun removesOnlyRetiredOwnedSuffixesWithoutFollowingLinks() {
        val data = temporary.newFolder("data"); val cache = temporary.newFolder("cache")
        val keep = "a".repeat(32); val retire = "b".repeat(32)
        val outside = temporary.newFolder("outside"); File(outside, "precious").writeText("keep")
        for ((root, prefix) in listOf(data to "app_webview_cmux_browser_", cache to "webview_cmux_browser_")) {
            File(root, prefix + keep).mkdirs()
            val stale = File(root, prefix + retire).apply { mkdirs() }
            File(stale, "nested").mkdirs(); File(stale, "nested/cookies").writeText("retire")
            Files.createSymbolicLink(File(stale, "external").toPath(), outside.toPath())
            File(root, prefix + "invalid").mkdirs()
        }
        val default = File(data, "app_webview").apply { mkdirs() }
        File(default, "cookies").writeText("phone")
        File(cache, "WebView").mkdirs(); File(data, "account").writeText("account")
        RoutedBrowserStorage(data, cache).retain(setOf(keep))
        for ((root, prefix) in listOf(data to "app_webview_cmux_browser_", cache to "webview_cmux_browser_")) {
            assertTrue(File(root, prefix + keep).isDirectory)
            assertFalse(File(root, prefix + retire).exists())
            assertTrue(File(root, prefix + "invalid").isDirectory)
        }
        assertEquals("keep", File(outside, "precious").readText())
        assertEquals("phone", File(default, "cookies").readText())
        assertEquals("account", File(data, "account").readText())
        assertTrue(File(cache, "WebView").isDirectory)
        RoutedBrowserStorage(data, cache).retain(emptySet())
        assertFalse(File(data, "app_webview_cmux_browser_$keep").exists())
    }
    @Test fun invalidRetainedIdFailsBeforeDeletingAnything() {
        val data = temporary.newFolder("data"); val cache = temporary.newFolder("cache")
        val owned = File(data, "app_webview_cmux_browser_" + "c".repeat(32)).apply { mkdirs() }
        assertTrue(runCatching { RoutedBrowserStorage(data, cache).retain(setOf("../bad")) }.isFailure)
        assertTrue(owned.exists())
    }
}
