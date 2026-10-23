package io.github.docmorphic.cmuxapp

import java.net.URI

/** One native record per shell, shared by the UI bridge and WebView request thread. */
internal class MarkdownImageApprovals {
    private val urls = mutableSetOf<String>()

    @Synchronized fun approve(raw: String): String? {
        if (MarkdownImagePolicy.url(raw) == null) return null
        urls.add(raw)
        return raw
    }

    @Synchronized fun approved(raw: String): Boolean = raw in urls
    @Synchronized fun revokeAll() { urls.clear() }

    companion object {
        /** Opening a placeholder is distinct from downloading it in the privileged viewer. */
        fun openable(raw: String): Boolean = runCatching {
            URI(raw).let { it.scheme?.lowercase() in setOf("https", "http") &&
                it.rawUserInfo == null && !it.host.isNullOrEmpty() }
        }.getOrDefault(false)
    }
}

internal enum class MarkdownNavigationDecision { ALLOW, OPEN_EXTERNALLY, CANCEL }

/** Pinned source ordering: main-document fragment, activated link, then host shell load. */
internal object MarkdownNavigationPolicy {
    fun shellDocument(raw: String): Boolean = raw == "about:blank"

    fun decide(raw: String?, userActivated: Boolean, mainFrame: Boolean,
        shellLoad: Boolean = false): MarkdownNavigationDecision {
        if (raw == null) return MarkdownNavigationDecision.CANCEL
        if (mainFrame && MarkdownPreviewPolicy.inPage(raw)) return MarkdownNavigationDecision.ALLOW
        if (userActivated) return MarkdownNavigationDecision.OPEN_EXTERNALLY
        if (mainFrame && shellLoad) return MarkdownNavigationDecision.ALLOW
        return MarkdownNavigationDecision.CANCEL
    }
}
