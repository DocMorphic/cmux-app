package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class WhatsNewPresentation(val token: Long, val owner: String, val pages: List<WhatsNewPage>,
    val appeared: Boolean = false, val pageIndex: Int = 0)

/** Main-thread presentation owner retained across activity recreation. No staging writes seen markers. */
internal class NativeWhatsNewPresentation(private val center: NativeWhatsNewCenter) {
    private val mutable = MutableStateFlow<WhatsNewPresentation?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var owner: String? = null
    private var eligible = false
    private val shownThisLaunch = mutableSetOf<String>()

    fun reconcile(nextOwner: String?, canPresent: Boolean) {
        if (owner != nextOwner) { owner = nextOwner; mutable.value = null; generation++ }
        eligible = canPresent && nextOwner != null
        val active = mutable.value
        if (!eligible) {
            if (active?.appeared == false) mutable.value = null
            return
        }
        if (active != null) return
        // Web entries will be admitted here only after the isolated preload owner supplies success.
        val staged = center.state.value.unseen.filter { it.key !in shownThisLaunch }
        val pages = center.preparePresentation(staged, emptySet())
        if (pages.isNotEmpty()) mutable.value = WhatsNewPresentation(++generation, checkNotNull(owner), pages.toList())
    }
    fun appeared(token: Long, currentOwner: String?, canPresent: Boolean) {
        val active = mutable.value ?: return
        if (!eligible || !canPresent || active.token != token || active.owner != currentOwner || active.appeared) return
        // A remote retraction/content change during staging must not be acknowledged as displayed.
        if (center.preparePresentation(active.pages, emptySet()) != active.pages) {
            mutable.value = null
            reconcile(currentOwner, canPresent)
            return
        }
        center.acknowledgeAppearance(active.pages)
        shownThisLaunch += active.pages.map { it.key }
        mutable.value = active.copy(appeared = true)
    }
    fun select(token: Long, index: Int) {
        val active = mutable.value ?: return
        if (active.token == token && index in active.pages.indices) mutable.value = active.copy(pageIndex = index)
    }
    fun dismiss(token: Long) {
        val active = mutable.value ?: return
        if (active.token == token) {
            if (active.appeared) shownThisLaunch += active.pages.map { it.key }
            mutable.value = null
        }
    }
}
