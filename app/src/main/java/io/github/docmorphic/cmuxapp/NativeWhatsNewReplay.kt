package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug replay has no center/storage reference and therefore cannot acknowledge a real update. */
internal class NativeWhatsNewReplay {
    private val mutable = MutableStateFlow<WhatsNewPresentation?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var owner: String? = null

    fun reconcile(login: String?, available: Boolean) {
        if (owner != login || !available) dismiss()
        owner = login.takeIf { available }
    }
    fun start(login: String, pages: List<WhatsNewPage>, firstKey: String, lastKey: String) {
        if (owner != login) return
        val frozen = range(pages, firstKey, lastKey) ?: return
        mutable.value = WhatsNewPresentation(++generation, login, frozen)
    }
    fun select(token: Long, index: Int) {
        val active = mutable.value ?: return
        if (active.token == token && index in active.pages.indices) mutable.value = active.copy(pageIndex = index)
    }
    fun dismiss() { mutable.value = null }

    companion object {
        fun range(pages: List<WhatsNewPage>, firstKey: String, lastKey: String): List<WhatsNewPage>? {
            val first = pages.indexOfFirst { it.key == firstKey }
            val last = pages.indexOfFirst { it.key == lastKey }
            if (first < 0 || last < 0) return null
            return pages.subList(minOf(first, last), maxOf(first, last) + 1).map { page ->
                val body = page.body
                if (body is WhatsNewBody.Features) page.copy(body = body.copy(rows = body.rows.toList())) else page
            }
        }
    }
}
