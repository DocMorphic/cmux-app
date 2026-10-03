package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files

/** Only our validated WebView suffixes; default phone browsing and account files are outside this namespace. */
internal class RoutedBrowserStorage(private val data: File, private val cache: File) {
    fun retain(ids: Set<String>) {
        require(ids.all { it.matches(Regex("[a-f0-9]{32}")) })
        for ((root, prefix) in listOf(data to "app_webview_cmux_browser_", cache to "webview_cmux_browser_")) {
            for (file in root.listFiles().orEmpty()) {
                val id = file.name.removePrefix(prefix)
                if (!file.name.startsWith(prefix) || !id.matches(Regex("[a-f0-9]{32}")) || id in ids) continue
                // Files.walk does not follow symbolic links, including links inside Chromium's directory.
                Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }
}
