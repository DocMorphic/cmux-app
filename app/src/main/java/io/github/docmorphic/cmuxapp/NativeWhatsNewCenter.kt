package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Implementations must atomically persist all updates, reporting failure without partial writes. */
internal interface WhatsNewStorage {
    fun read(key: String): String?
    fun write(updates: Map<String, String?>): Boolean
}
internal data class WhatsNewState(
    val archive: List<WhatsNewPage> = emptyList(), val unseen: List<WhatsNewPage> = emptyList(),
    val initialRefreshComplete: Boolean = false, val fetchedThisLaunch: Boolean = false,
    val error: String? = null
)

/** Per-install notice state. No credentials, HTTP calls or UI side effects live here. */
internal class NativeWhatsNewCenter(
    private val catalog: List<WhatsNewPage>, private val version: String, private val channel: WhatsNewChannel,
    private val storage: WhatsNewStorage, apiBaseUrl: String? = null,
    private val languages: List<String> = listOf("en")
) : AutoCloseable {
    private val lock = Any()
    val webPolicy = WhatsNewWebPolicy(apiBaseUrl)
    private val cacheKey = "whatsNew.remote.v1.${webPolicy.originKey}"
    private var remote: WhatsNewRemote? = storage.read(cacheKey)?.let { runCatching { WhatsNewRemote.decode(it) }.getOrNull() }
    private var marker: String? = storage.read(MARKER)
    private var acknowledged: Set<String> = runCatching {
        val data = JSONArray(storage.read(ANNOUNCEMENTS) ?: "[]")
        (0 until data.length()).map { data.get(it) as String }.toSet()
    }.getOrDefault(emptySet())
    private var generation = 0L
    private var closed = false
    private var initialComplete = false
    private var fetched = false
    private var failure: String? = null
    private val mutableState = MutableStateFlow(WhatsNewState())
    val state = mutableState.asStateFlow()
    init {
        require(catalog.map { it.id }.distinct().size == catalog.size)
        require(catalog.all { it.kind == WhatsNewKind.ENTRY && it.id.isNotBlank() })
        publish()
    }

    /** The caller supplies a reviewed Android feed. Null explicitly means no feed configured. */
    suspend fun refresh(loader: (suspend () -> String)? = null) {
        val ticket = synchronized(lock) {
            if (closed) return
            ++generation
        }
        try {
            val raw = loader?.invoke()
            currentCoroutineContext().ensureActive()
            val next = raw?.let(WhatsNewRemote::decode)
            val coroutine = currentCoroutineContext()
            synchronized(lock) {
                if (closed || ticket != generation) return
                coroutine.ensureActive()
                if (next != null) {
                    val pruned = acknowledged.intersect(next.announcements.map { it.id }.toSet())
                    check(storage.write(mapOf(cacheKey to raw, ANNOUNCEMENTS to JSONArray(pruned.sorted()).toString()))) { SAVE_ERROR }
                    remote = next; acknowledged = pruned; fetched = true
                }
                initialComplete = true; failure = null; publish()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed || ticket != generation) return
                initialComplete = true; failure = REFRESH_ERROR; publish()
            }
        }
    }

    /** Recheck the staged content as well as identity: changed URLs need a fresh preload. */
    fun preparePresentation(staged: List<WhatsNewPage>, loadedWebKeys: Set<String>): List<WhatsNewPage> = synchronized(lock) {
        if (closed || !initialComplete) emptyList() else mutableState.value.unseen.filter {
            it in staged && (it.body !is WhatsNewBody.Web || it.key in loadedWebKeys)
        }.toList()
    }

    /** Invoke only when the frozen sheet is actually visible; replay never invokes this writer. */
    fun acknowledgeAppearance(pages: List<WhatsNewPage>): Boolean = synchronized(lock) {
        if (closed || pages.isEmpty()) return false
        val seen = acknowledged + pages.filter { it.kind == WhatsNewKind.ANNOUNCEMENT }.map { it.id }
        val shown = pages.filter { it.kind == WhatsNewKind.ENTRY }.mapNotNull { page ->
            catalog.indexOfFirst { it.id == page.id }.takeIf { it >= 0 }
        }.minOrNull()
        val existing = catalog.indexOfFirst { it.id == marker }
        // Unknown markers indicate a downgrade: preserve them even during explicit replay.
        val nextMarker = if (shown != null && (marker == null || (existing >= 0 && shown < existing))) catalog[shown].id else marker
        try {
            check(storage.write(mapOf(MARKER to nextMarker, ANNOUNCEMENTS to JSONArray(seen.sorted()).toString()))) { SAVE_ERROR }
            marker = nextMarker; acknowledged = seen; failure = null; publish(); true
        } catch (_: Exception) { failure = SAVE_ERROR; publish(); false }
    }

    private fun publish() {
        val native = catalog.filter { page ->
            channel.permits(remote?.entryChannels?.get(page.id) ?: page.channels) && WhatsNewVersion.within(version, page.minVersion, page.maxVersion)
        }.let { eligible ->
            val visible = remote?.visibleEntryIds?.toSet()
            when {
                visible == null -> eligible
                visible.isEmpty() -> emptyList()
                catalog.none { it.id in visible } -> eligible
                else -> eligible.filter { it.id in visible || it.requiredPairing }
            }
        }
        val nativeIds = native.map { it.id }.toSet()
        val announcements = remote?.announcements.orEmpty().mapNotNull { announcement ->
            if (!channel.permits(announcement.channels) || !WhatsNewVersion.within(version, announcement.minVersion, announcement.maxVersion) ||
                announcement.nativeEntryId in nativeIds) return@mapNotNull null
            val localized = announcement.localized(languages)
            val title = localized.title?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val body = when {
                localized.webUrl?.let(webPolicy::allows) == true -> WhatsNewBody.Web(localized.webUrl)
                !localized.features.isNullOrEmpty() -> WhatsNewBody.Features(localized.features)
                else -> return@mapNotNull null
            }
            WhatsNewPage(localized.id, title, body, localized.releaseLabel, WhatsNewKind.ANNOUNCEMENT)
        }
        val index = marker?.let { id -> catalog.indexOfFirst { it.id == id } }
        val unseen = announcements.filter { it.id !in acknowledged } + native.filter { page ->
            index == null || (index >= 0 && catalog.indexOfFirst { it.id == page.id } < index)
        }
        mutableState.value = WhatsNewState(announcements + native,
            unseen.filter { it.body !is WhatsNewBody.Web || fetched }, initialComplete, fetched, failure)
    }
    override fun close() { synchronized(lock) { closed = true; generation++ } }
    companion object {
        const val MARKER = "whatsNew.newestAcknowledged.v1"
        const val ANNOUNCEMENTS = "whatsNew.acknowledgedAnnouncements.v1"
        const val SAVE_ERROR = "Couldn't save What's New progress. It may appear again next time."
        const val REFRESH_ERROR = "Couldn't update What's New. Showing saved notices."
    }
}
