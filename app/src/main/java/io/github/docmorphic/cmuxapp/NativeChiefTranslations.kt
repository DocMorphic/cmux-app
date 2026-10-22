package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A connected platform provider can briefly return no pairs while initializing. */
internal suspend fun <T> discoverNativeChiefTranslationCapabilities(query: suspend () -> List<T>): List<T> {
    for (wait in listOf(0L, 250L, 750L, 1_500L)) {
        if (wait > 0) delay(wait)
        currentCoroutineContext().ensureActive()
        val capabilities = query()
        if (capabilities.isNotEmpty()) return capabilities
    }
    return emptyList()
}

/** Scripts are resolved by the platform language factory, including likely subtags. */
internal data class NativeChiefTranslationLanguage(val tag: String, val code: String, val script: String?) {
    fun matches(other: NativeChiefTranslationLanguage): Boolean = code == other.code &&
        (script == null || other.script == null || script == other.script)
}
internal enum class NativeChiefTranslationAvailability { INSTALLED, DOWNLOADABLE, UNSUPPORTED }
internal enum class NativeChiefTranslationFailure { UNSUPPORTED, NOT_DOWNLOADED, UNIDENTIFIED_LANGUAGE, FAILED }
internal class NativeChiefTranslationException(val reason: NativeChiefTranslationFailure) : Exception(reason.name)
internal enum class NativeChiefTranslationOutcome { STARTED, NEEDS_SOURCE_LANGUAGE, UNAVAILABLE }
internal enum class NativeChiefTranslationCaption { TRANSLATING, SHOW_ORIGINAL, VIEW_TRANSLATION, NOT_DOWNLOADED, UNSUPPORTED }
internal enum class NativeChiefTranslationIndicator { TRANSLATING, WAITING_FOR_DOWNLOAD, UNSUPPORTED }
internal data class NativeChiefAutoTranslation(val source: NativeChiefTranslationLanguage, val target: NativeChiefTranslationLanguage)
internal data class NativeChiefTranslationRow(val key: String, val text: String, val fromMe: Boolean)
internal fun NativeChiefTimelineItem.translationRow() = NativeChiefTranslationRow(key,
    message?.let { NativeChiefPresentation.receipt(it) ?: NativeChiefPresentation.displayText(it) } ?: outgoing!!.text, fromMe)
internal data class NativeChiefTranslationPresentation(val text: String, val caption: NativeChiefTranslationCaption,
    val source: NativeChiefTranslationLanguage)
internal data class NativeChiefTranslationState(
    val presentations: Map<String, NativeChiefTranslationPresentation> = emptyMap(),
    val automatic: NativeChiefAutoTranslation? = null,
    val automaticAvailability: NativeChiefTranslationAvailability? = null,
    val indicator: NativeChiefTranslationIndicator? = null,
    val retired: Boolean = false
)

internal interface NativeChiefTranslating : AutoCloseable {
    suspend fun availability(source: NativeChiefTranslationLanguage, target: NativeChiefTranslationLanguage): NativeChiefTranslationAvailability
    suspend fun supportedLanguages(): List<NativeChiefTranslationLanguage>
    suspend fun detectLanguage(text: String): NativeChiefTranslationLanguage?
    suspend fun translate(texts: Map<String, String>, source: NativeChiefTranslationLanguage,
        target: NativeChiefTranslationLanguage): Map<String, String>
    override fun close() = Unit
}

/** The caller supplies account/computer/conversation-scoped preferences; text is never persisted. */
internal interface NativeChiefTranslationPreferences {
    fun load(): NativeChiefAutoTranslation?
    fun save(value: NativeChiefAutoTranslation?)
}

/** Source ConversationTranslations contract, confined to its retained transcript's dispatcher. */
internal class NativeChiefTranslations(parent: CoroutineScope, private val translator: NativeChiefTranslating,
    val target: NativeChiefTranslationLanguage, private val permits: () -> Boolean,
    private val preferences: NativeChiefTranslationPreferences? = null) : AutoCloseable {
    private sealed interface Phase {
        data object Translating : Phase
        data class Translated(val text: String) : Phase
        data class Failed(val reason: NativeChiefTranslationFailure) : Phase
    }
    private data class Entry(val original: String, val source: NativeChiefTranslationLanguage, val phase: Phase,
        val automatic: Boolean, val request: Long, val showsOriginal: Boolean = false)
    private data class Detection(val text: String, val language: NativeChiefTranslationLanguage?)
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val entries = linkedMapOf<String, Entry>()
    private val detected = mutableMapOf<String, Detection>()
    private var rows = emptyList<NativeChiefTranslationRow>()
    private var automatic: NativeChiefAutoTranslation? = null
    private var availability: NativeChiefTranslationAvailability? = null
    private var waitsForDownload = false
    private var request = 0L
    private var automaticEpoch = 0L
    private var availabilityEpoch = 0L
    private var previousWork: Job? = null
    private var scheduler: Job? = null
    private var scheduleRequested = false
    private var closed = false
    private val mutableState = MutableStateFlow(NativeChiefTranslationState())
    val state = mutableState.asStateFlow()

    init {
        if (owns()) {
            automatic = preferences?.load()?.takeIf { it.target.matches(target) }?.copy(target = target)
            if (automatic != null) refreshAvailability()
            publish()
        }
    }

    private fun owns(): Boolean {
        if (closed) return false
        if (!job.isActive || !permits()) { close(); return false }
        return true
    }
    private fun checkOwner() { check(owns()) { "Chief computer or account changed" } }
    private fun row(key: String) = rows.firstOrNull { it.key == key }
    fun canTranslate(key: String): Boolean = !closed && row(key)?.text?.let { NativeChiefTextPolicy.trim(it).isNotEmpty() } == true
    fun hasTranslation(key: String) = state.value.presentations[key]?.caption in
        setOf(NativeChiefTranslationCaption.SHOW_ORIGINAL, NativeChiefTranslationCaption.VIEW_TRANSLATION)
    fun isShowingTranslation(key: String) = state.value.presentations[key]?.caption == NativeChiefTranslationCaption.SHOW_ORIGINAL

    suspend fun sourceLanguages(): List<NativeChiefTranslationLanguage> {
        checkOwner()
        val languages = translator.supportedLanguages()
        checkOwner()
        return languages.filter { it.code != target.code }.distinctBy { it.code }
    }

    fun updateRows(value: List<NativeChiefTranslationRow>) {
        checkOwner()
        rows = value.toList()
        val stale = mutableListOf<String>()
        for (row in rows) {
            val entry = entries[row.key] ?: continue
            if (entry.original != row.text) {
                entries[row.key] = entry.copy(original = row.text, phase = Phase.Translating,
                    showsOriginal = false, request = ++request)
                stale += row.key
            }
        }
        if (stale.isNotEmpty()) queue(stale)
        if (waitsForDownload) refreshAvailability() else scheduleAutomatic()
        publish()
    }

    private suspend fun detect(row: NativeChiefTranslationRow): NativeChiefTranslationLanguage? {
        detected[row.key]?.takeIf { it.text == row.text }?.let { return it.language }
        val language = try { translator.detectLanguage(row.text) }
        catch (error: Exception) { currentCoroutineContext().ensureActive(); null }
        if (!owns()) return null
        if (this.row(row.key)?.text == row.text) detected[row.key] = Detection(row.text, language)
        return language
    }

    suspend fun translateMessage(key: String, source: NativeChiefTranslationLanguage? = null): NativeChiefTranslationOutcome {
        checkOwner()
        val row = row(key)?.takeIf { canTranslate(key) } ?: return NativeChiefTranslationOutcome.UNAVAILABLE
        val language = source ?: detect(row)
        checkOwner()
        if (this.row(key)?.text != row.text) return NativeChiefTranslationOutcome.UNAVAILABLE
        if (language == null || language.matches(target)) return NativeChiefTranslationOutcome.NEEDS_SOURCE_LANGUAGE
        val previous = entries[key]
        if (previous?.original == row.text && previous.phase is Phase.Translated) entries[key] = previous.copy(showsOriginal = false)
        else {
            entries[key] = Entry(row.text, language, Phase.Translating, false, ++request)
            queue(listOf(key))
        }
        publish()
        return NativeChiefTranslationOutcome.STARTED
    }

    suspend fun translateConversation(key: String, source: NativeChiefTranslationLanguage? = null): NativeChiefTranslationOutcome {
        checkOwner()
        val row = row(key) ?: return NativeChiefTranslationOutcome.UNAVAILABLE
        val language = source ?: detect(row)
        checkOwner()
        if (this.row(key)?.text != row.text) return NativeChiefTranslationOutcome.UNAVAILABLE
        if (language == null || language.matches(target)) return NativeChiefTranslationOutcome.NEEDS_SOURCE_LANGUAGE
        startAutomatic(language)
        // The pressed row is included even when it is mine or detection was uncertain.
        if (canTranslate(key) && !isShowingTranslation(key)) {
            entries[key] = Entry(row.text, language, Phase.Translating, true, ++request)
            queue(listOf(key))
        }
        publish()
        return NativeChiefTranslationOutcome.STARTED
    }

    fun startAutomatic(source: NativeChiefTranslationLanguage) {
        checkOwner()
        automatic = NativeChiefAutoTranslation(source, target)
        automaticEpoch++
        availability = null
        waitsForDownload = false
        preferences?.save(automatic)
        refreshAvailability()
        scheduleAutomatic()
        publish()
    }

    fun stopAutomatic() {
        checkOwner()
        automatic = null
        automaticEpoch++
        availabilityEpoch++
        availability = null
        waitsForDownload = false
        entries.entries.removeAll { it.value.automatic }
        preferences?.save(null)
        publish()
    }

    fun toggleOriginal(key: String) {
        checkOwner()
        val entry = entries[key] ?: return
        when (entry.phase) {
            is Phase.Translated -> entries[key] = entry.copy(showsOriginal = !entry.showsOriginal)
            is Phase.Failed -> {
                entries[key] = entry.copy(phase = Phase.Translating, request = ++request)
                queue(listOf(key))
            }
            Phase.Translating -> return
        }
        publish()
    }

    fun retryAutomatic() {
        checkOwner()
        if (automatic == null) return
        availability = null
        waitsForDownload = false
        entries.entries.removeAll { it.value.automatic && it.value.phase is Phase.Failed }
        scheduleAutomatic()
        publish()
    }

    fun refreshAvailability() {
        checkOwner()
        val pair = automatic ?: return
        val epoch = ++availabilityEpoch
        scope.launch {
            yield()
            val result = try { translator.availability(pair.source, pair.target) }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); NativeChiefTranslationAvailability.UNSUPPORTED }
            if (!owns() || automatic != pair || epoch != availabilityEpoch) return@launch
            availability = result
            if (result == NativeChiefTranslationAvailability.INSTALLED && waitsForDownload) {
                waitsForDownload = false
                scheduleAutomatic()
            }
            publish()
        }
    }

    private fun scheduleAutomatic() {
        if (automatic == null || waitsForDownload || availability == NativeChiefTranslationAvailability.UNSUPPORTED) return
        scheduleRequested = true
        if (scheduler?.isActive == true) return
        scheduler = scope.launch {
            yield()
            while (scheduleRequested && owns()) {
                scheduleRequested = false
                val pair = automatic ?: break
                val epoch = automaticEpoch
                val queued = mutableListOf<String>()
                for (row in rows.toList()) {
                    if (row.fromMe || entries[row.key] != null || NativeChiefTextPolicy.trim(row.text).isEmpty()) continue
                    val language = detect(row)
                    if (!owns()) return@launch
                    if (epoch != automaticEpoch || waitsForDownload || availability == NativeChiefTranslationAvailability.UNSUPPORTED) break
                    if (this@NativeChiefTranslations.row(row.key)?.text != row.text || entries[row.key] != null) continue
                    if (language?.matches(pair.source) == true) {
                        entries[row.key] = Entry(row.text, pair.source, Phase.Translating, true, ++request)
                        queued += row.key
                    }
                }
                if (queued.isNotEmpty()) { queue(queued); publish() }
            }
        }
    }

    private fun queue(keys: List<String>) {
        val previous = previousWork
        previousWork = scope.launch {
            yield()
            previous?.join()
            if (!owns()) return@launch
            val batches = keys.distinct().mapNotNull { key -> entries[key]?.takeIf { it.phase == Phase.Translating }?.let { key to it } }
                .groupBy { it.second.source }
            for ((source, batch) in batches) {
                val texts = batch.associate { (key, entry) -> key to entry.original }
                val result = try { Result.success(translator.translate(texts, source, target)) }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    Result.failure<Map<String, String>>(error)
                }
                if (!owns()) return@launch
                for ((key, captured) in batch) {
                    val live = entries[key] ?: continue
                    // Android detection/translation callbacks can outlive an edit,
                    // stop or same-text retry. Never give an old request a new entry.
                    if (live.request != captured.request || live.original != captured.original || live.phase != Phase.Translating) continue
                    if (result.isSuccess) {
                        val text = result.getOrThrow()[key]
                        entries[key] = live.copy(phase = if (!text.isNullOrEmpty()) Phase.Translated(text) else Phase.Failed(NativeChiefTranslationFailure.FAILED))
                        if (!text.isNullOrEmpty() && live.automatic && automatic?.source?.matches(source) == true)
                            availability = NativeChiefTranslationAvailability.INSTALLED
                    } else {
                        val failure = (result.exceptionOrNull() as? NativeChiefTranslationException)?.reason ?: NativeChiefTranslationFailure.FAILED
                        if (live.automatic) {
                            entries.remove(key)
                            if (automatic?.source?.matches(source) == true) when (failure) {
                                NativeChiefTranslationFailure.NOT_DOWNLOADED -> { availability = NativeChiefTranslationAvailability.DOWNLOADABLE; waitsForDownload = true }
                                NativeChiefTranslationFailure.UNSUPPORTED -> availability = NativeChiefTranslationAvailability.UNSUPPORTED
                                else -> Unit
                            }
                        } else entries[key] = live.copy(phase = Phase.Failed(failure))
                    }
                }
                publish()
            }
        }
    }

    private fun publish() {
        if (closed) return
        val visible = rows.mapNotNull { row ->
            val entry = entries[row.key]?.takeIf { it.original == row.text } ?: return@mapNotNull null
            val presentation = when (val phase = entry.phase) {
                Phase.Translating -> NativeChiefTranslationPresentation(row.text, NativeChiefTranslationCaption.TRANSLATING, entry.source)
                is Phase.Translated -> NativeChiefTranslationPresentation(if (entry.showsOriginal) row.text else phase.text,
                    if (entry.showsOriginal) NativeChiefTranslationCaption.VIEW_TRANSLATION else NativeChiefTranslationCaption.SHOW_ORIGINAL, entry.source)
                is Phase.Failed -> NativeChiefTranslationPresentation(row.text,
                    if (phase.reason == NativeChiefTranslationFailure.NOT_DOWNLOADED) NativeChiefTranslationCaption.NOT_DOWNLOADED else NativeChiefTranslationCaption.UNSUPPORTED, entry.source)
            }
            row.key to presentation
        }.toMap()
        val indicator = if (automatic == null) null else when {
            availability == NativeChiefTranslationAvailability.UNSUPPORTED -> NativeChiefTranslationIndicator.UNSUPPORTED
            availability == NativeChiefTranslationAvailability.DOWNLOADABLE && entries.values.none { it.automatic && it.phase is Phase.Translated } -> NativeChiefTranslationIndicator.WAITING_FOR_DOWNLOAD
            else -> NativeChiefTranslationIndicator.TRANSLATING
        }
        mutableState.value = NativeChiefTranslationState(visible, automatic, availability, indicator)
    }

    override fun close() {
        if (closed) return
        closed = true
        job.cancel()
        entries.clear(); detected.clear(); rows = emptyList(); automatic = null; availability = null
        mutableState.value = NativeChiefTranslationState(retired = true)
        runCatching { translator.close() }
    }
}
