package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class WhatsNewPresentation(val token: Long, val owner: String, val pages: List<WhatsNewPage>,
    val appeared: Boolean = false, val pageIndex: Int = 0)

/** The renderer remains owned outside the sheet, including across activity recreation. */
internal interface NativeWhatsNewPreloadedPage : AutoCloseable {
    val load: NativeWhatsNewWebLoad
    val isClosed: StateFlow<Boolean>
    fun theme(dark: Boolean)
}

/** Main-thread presentation owner retained across activity recreation. No staging writes seen markers. */
internal class NativeWhatsNewPresentation(private val center: NativeWhatsNewCenter,
    private val scope: CoroutineScope? = null,
    private val createWebPage: ((WhatsNewPage, String, Boolean) -> NativeWhatsNewPreloadedPage)? = null
) : AutoCloseable {
    private val mutable = MutableStateFlow<WhatsNewPresentation?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var owner: String? = null
    private var eligible = false
    private var dark = false
    private var pending: List<WhatsNewPage>? = null
    private var gate: Job? = null
    private val webPages = mutableMapOf<WhatsNewPage, NativeWhatsNewPreloadedPage>()
    private val failedThisLaunch = mutableSetOf<WhatsNewPage>()
    private val shownThisLaunch = mutableSetOf<String>()

    fun webPage(page: WhatsNewPage): NativeWhatsNewPreloadedPage? = webPages[page]
    fun theme(dark: Boolean) { this.dark = dark; webPages.values.forEach { it.theme(dark) } }
    private fun loadedKeys(): Set<String> = webPages.filterValues {
        !it.isClosed.value && it.load.phase.value == WhatsNewWebPhase.LOADED
    }.keys.map { it.key }.toSet()
    private fun retire() {
        generation++; gate?.cancel(); gate = null; pending = null
        webPages.values.toList().forEach { it.close() }; webPages.clear()
        mutable.value = null
    }
    fun reconcile(nextOwner: String?, canPresent: Boolean) {
        if (owner != nextOwner) { retire(); failedThisLaunch.clear(); owner = nextOwner }
        eligible = canPresent && nextOwner != null
        val active = mutable.value
        if (!eligible) {
            if (active?.appeared != true) retire()
            return
        }
        if (active != null) return
        val staged = center.state.value.unseen.filter { it.key !in shownThisLaunch && it !in failedThisLaunch }
        // This also waits for the initial remote-list attempt and checks current content/visibility.
        val candidates = center.preparePresentation(staged, staged.map { it.key }.toSet())
        if (pending == candidates) return
        retire()
        if (candidates.isEmpty()) return
        val login = checkNotNull(owner)
        if (candidates.none { it.body is WhatsNewBody.Web } || scope == null || createWebPage == null) {
            present(login, center.preparePresentation(candidates, emptySet()))
            return
        }
        pending = candidates
        val ticket = generation
        gate = scope.launch(start = CoroutineStart.LAZY) {
            // Constructors start every load before any outcome is awaited. All ten-second
            // deadlines include exchange, cookie seeding and page navigation concurrently.
            for (page in candidates.filter { it.body is WhatsNewBody.Web }) {
                try { webPages[page] = createWebPage.invoke(page, login, dark) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failedThisLaunch += page }
            }
            webPages.values.toList().forEach { it.load.outcome() }
            ensureActive()
            if (generation != ticket || owner != login || !eligible) return@launch
            val loaded = loadedKeys()
            failedThisLaunch += candidates.filter { it.body is WhatsNewBody.Web && it.key !in loaded }
            val ready = center.preparePresentation(candidates, loaded)
            webPages.keys.filter { it !in ready }.toList().forEach { webPages.remove(it)?.close() }
            pending = null; gate = null
            present(login, ready)
        }.also { it.start() }
    }
    private fun present(login: String, pages: List<WhatsNewPage>) {
        if (pages.isNotEmpty()) mutable.value = WhatsNewPresentation(++generation, login, pages.toList())
    }
    fun appeared(token: Long, currentOwner: String?, canPresent: Boolean) {
        val active = mutable.value ?: return
        if (!eligible || !canPresent || active.token != token || active.owner != currentOwner || active.appeared) return
        // A remote replacement, retirement or retraction during staging is never acknowledged.
        val loaded = loadedKeys()
        if (center.preparePresentation(active.pages, loaded) != active.pages) {
            failedThisLaunch += active.pages.filter { it.body is WhatsNewBody.Web && it.key !in loaded }
            retire()
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
            retire()
        }
    }
    override fun close() { eligible = false; owner = null; retire() }
}
