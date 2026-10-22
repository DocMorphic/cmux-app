package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun translationLanguage(code: String, script: String? = "Latn") = NativeChiefTranslationLanguage(code, code, script)

internal class ChiefTranslationTestEngine : NativeChiefTranslating {
    data class Call(val texts: Map<String, String>, val source: NativeChiefTranslationLanguage, val target: NativeChiefTranslationLanguage)
    var availabilityResult = NativeChiefTranslationAvailability.INSTALLED
    var failure: NativeChiefTranslationFailure? = null
    var translationGate: CompletableDeferred<Unit>? = null
    var detectionGate: CompletableDeferred<Unit>? = null
    var availabilityGate: CompletableDeferred<Unit>? = null
    var response: Map<String, String>? = null
    var ignoreCancellation = false
    var closeCount = 0
    var active = 0
    var maxActive = 0
    val calls = mutableListOf<Call>()
    var languages = listOf("en", "es", "ja", "fr").map { translationLanguage(it) }
    override suspend fun availability(source: NativeChiefTranslationLanguage, target: NativeChiefTranslationLanguage): NativeChiefTranslationAvailability {
        val captured = availabilityResult
        availabilityGate?.await()
        return captured
    }
    override suspend fun supportedLanguages() = languages
    override suspend fun detectLanguage(text: String): NativeChiefTranslationLanguage? {
        detectionGate?.await()
        val code = text.substringBefore(':').takeIf { it.length == 2 } ?: return null
        return translationLanguage(code)
    }
    override suspend fun translate(texts: Map<String, String>, source: NativeChiefTranslationLanguage,
        target: NativeChiefTranslationLanguage): Map<String, String> {
        calls += Call(texts.toMap(), source, target)
        active++; maxActive = maxOf(maxActive, active)
        try {
            if (ignoreCancellation) withContext(NonCancellable) { translationGate?.await() } else translationGate?.await()
            failure?.let { throw NativeChiefTranslationException(it) }
            return response ?: texts.mapValues { "[${target.code}] ${it.value}" }
        } finally { active-- }
    }
    override fun close() { closeCount++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NativeChiefTranslationsTest {
    @Test fun providerDiscoveryRecoversBeforeReportingUnavailable() = runTest {
        var calls = 0
        val pairs = discoverNativeChiefTranslationCapabilities { if (++calls < 3) emptyList() else listOf("fr-en") }
        assertEquals(listOf("fr-en"), pairs)
        assertEquals(1_000L, currentTime)
        assertEquals(3, calls)
    }

    @Test fun anEmptyProviderHasABoundedDiscoveryWindow() = runTest {
        var calls = 0
        assertTrue(discoverNativeChiefTranslationCapabilities<String> { calls++; emptyList() }.isEmpty())
        assertEquals(4, calls)
        assertEquals(2_500L, currentTime)
    }

    @Test fun canceledDiscoveryNeverQueriesAgain() = runTest {
        var calls = 0
        val result = async { discoverNativeChiefTranslationCapabilities<String> { calls++; emptyList() } }
        runCurrent()
        assertEquals(1, calls)
        result.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    private val english = translationLanguage("en")
    private val spanish = translationLanguage("es")
    private val rows = listOf(NativeChiefTranslationRow("spanish", "es: hola", false),
        NativeChiefTranslationRow("mine", "es: gracias", true), NativeChiefTranslationRow("japanese", "ja: おはよう", false),
        NativeChiefTranslationRow("unknown", "lol", false), NativeChiefTranslationRow("english", "en: hello", false))
    private fun checkModel(block: suspend TestScope.(NativeChiefTranslations, ChiefTranslationTestEngine) -> Unit) = runTest {
        val engine = ChiefTranslationTestEngine()
        val model = NativeChiefTranslations(this, engine, english, { true })
        model.updateRows(rows)
        try { block(model, engine) } finally { model.close() }
    }

    @Test fun oneMessageUsesOriginalInputThenTogglesWithoutRetranslating() = checkModel { model, engine ->
        assertEquals(NativeChiefTranslationOutcome.STARTED, model.translateMessage("spanish"))
        assertEquals(NativeChiefTranslationCaption.TRANSLATING, model.state.value.presentations["spanish"]!!.caption)
        runCurrent()
        assertEquals("[en] es: hola", model.state.value.presentations["spanish"]!!.text)
        model.toggleOriginal("spanish")
        assertEquals("es: hola", model.state.value.presentations["spanish"]!!.text)
        assertEquals(NativeChiefTranslationCaption.VIEW_TRANSLATION, model.state.value.presentations["spanish"]!!.caption)
        model.translateMessage("spanish")
        assertEquals(NativeChiefTranslationCaption.SHOW_ORIGINAL, model.state.value.presentations["spanish"]!!.caption)
        assertEquals(1, engine.calls.size)
        assertEquals(mapOf("spanish" to "es: hola"), engine.calls.single().texts)
    }

    @Test fun uncertaintyAndSameTargetAskForSourceButEmptyTextDoesNotTranslate() = checkModel { model, engine ->
        assertEquals(NativeChiefTranslationOutcome.NEEDS_SOURCE_LANGUAGE, model.translateMessage("unknown"))
        assertEquals(NativeChiefTranslationOutcome.NEEDS_SOURCE_LANGUAGE, model.translateMessage("english"))
        model.updateRows(rows + NativeChiefTranslationRow("blank", " \n", false))
        assertEquals(NativeChiefTranslationOutcome.UNAVAILABLE, model.translateMessage("blank", spanish))
        assertEquals(NativeChiefTranslationOutcome.STARTED, model.translateMessage("unknown", spanish))
        runCurrent()
        assertEquals("[en] lol", model.state.value.presentations["unknown"]!!.text)
        assertEquals(1, engine.calls.size)
    }

    @Test fun conversationCoversOnlyOthersInTheSourceLanguageAndLaterArrivals() = checkModel { model, engine ->
        model.translateConversation("spanish")
        assertEquals(NativeChiefTranslationIndicator.TRANSLATING, model.state.value.indicator)
        runCurrent()
        assertTrue(model.isShowingTranslation("spanish"))
        assertNull(model.state.value.presentations["mine"])
        assertNull(model.state.value.presentations["japanese"])
        model.updateRows(rows + NativeChiefTranslationRow("later", "es: ¿vienes?", false))
        runCurrent()
        assertTrue(model.isShowingTranslation("later"))
        assertTrue(engine.calls.all { it.source == spanish && it.target == english })
        model.stopAutomatic()
        assertNull(model.state.value.indicator)
        assertTrue(model.state.value.presentations.isEmpty())
    }

    @Test fun pressedOutgoingOrAmbiguousMessageIsIncludedInConversationTranslation() = checkModel { model, _ ->
        model.translateConversation("mine")
        runCurrent()
        assertTrue(model.isShowingTranslation("mine"))
        model.stopAutomatic()
        model.translateConversation("unknown", spanish)
        runCurrent()
        assertTrue(model.isShowingTranslation("unknown"))
    }

    @Test fun stoppingKeepsIndividualTranslations() = checkModel { model, _ ->
        model.translateMessage("spanish"); runCurrent()
        model.updateRows(rows + NativeChiefTranslationRow("other", "es: dos", false))
        model.translateConversation("other"); runCurrent()
        model.stopAutomatic()
        assertTrue(model.isShowingTranslation("spanish"))
        assertNull(model.state.value.presentations["other"])
    }

    @Test fun editInvalidatesPendingTextAndBatchesStaySerial() = checkModel { model, engine ->
        val gate = CompletableDeferred<Unit>(); engine.translationGate = gate
        model.translateMessage("spanish"); runCurrent()
        model.updateRows(rows.map { if (it.key == "spanish") it.copy(text = "es: hola, amigos") else it })
        model.translateMessage("japanese"); runCurrent()
        assertEquals(1, engine.calls.size)
        assertEquals("es: hola, amigos", model.state.value.presentations["spanish"]!!.text)
        gate.complete(Unit); runCurrent()
        assertEquals("[en] es: hola, amigos", model.state.value.presentations["spanish"]!!.text)
        assertEquals("[en] ja: おはよう", model.state.value.presentations["japanese"]!!.text)
        assertEquals(1, engine.maxActive)
        assertEquals(listOf("es: hola", "es: hola, amigos", "ja: おはよう"), engine.calls.flatMap { it.texts.values })
    }

    @Test fun declinedManualDownloadShowsOriginalAndRetriesOnCaptionTap() = checkModel { model, engine ->
        engine.failure = NativeChiefTranslationFailure.NOT_DOWNLOADED
        model.translateMessage("spanish"); runCurrent()
        assertEquals(NativeChiefTranslationCaption.NOT_DOWNLOADED, model.state.value.presentations["spanish"]!!.caption)
        engine.failure = null
        model.toggleOriginal("spanish"); runCurrent()
        assertTrue(model.isShowingTranslation("spanish"))
    }

    @Test fun automaticMissingModelWaitsWithoutRepromptingAndResumesWhenInstalled() = checkModel { model, engine ->
        engine.failure = NativeChiefTranslationFailure.NOT_DOWNLOADED
        engine.availabilityResult = NativeChiefTranslationAvailability.DOWNLOADABLE
        model.translateConversation("spanish"); runCurrent()
        assertEquals(NativeChiefTranslationIndicator.WAITING_FOR_DOWNLOAD, model.state.value.indicator)
        assertNull(model.state.value.presentations["spanish"])
        val attempts = engine.calls.size
        model.updateRows(rows + NativeChiefTranslationRow("later", "es: luego", false)); runCurrent()
        assertEquals(attempts, engine.calls.size)
        engine.failure = null; engine.availabilityResult = NativeChiefTranslationAvailability.INSTALLED
        model.refreshAvailability(); runCurrent()
        assertTrue(model.isShowingTranslation("spanish")); assertTrue(model.isShowingTranslation("later"))
        assertEquals(NativeChiefTranslationIndicator.TRANSLATING, model.state.value.indicator)
    }

    @Test fun automaticDownloadCanBeRequestedAgainExplicitly() = checkModel { model, engine ->
        engine.failure = NativeChiefTranslationFailure.NOT_DOWNLOADED
        model.translateConversation("spanish"); runCurrent()
        engine.failure = null
        model.retryAutomatic(); runCurrent()
        assertTrue(model.isShowingTranslation("spanish"))
    }

    @Test fun unsupportedAndIncompleteResultsHaveSourceCaptions() = checkModel { model, engine ->
        engine.failure = NativeChiefTranslationFailure.UNSUPPORTED
        model.translateMessage("spanish"); runCurrent()
        assertEquals(NativeChiefTranslationCaption.UNSUPPORTED, model.state.value.presentations["spanish"]!!.caption)
        engine.failure = null; engine.response = emptyMap()
        model.toggleOriginal("spanish"); runCurrent()
        assertEquals(NativeChiefTranslationCaption.UNSUPPORTED, model.state.value.presentations["spanish"]!!.caption)
        engine.failure = NativeChiefTranslationFailure.UNSUPPORTED
        engine.availabilityResult = NativeChiefTranslationAvailability.UNSUPPORTED
        model.translateConversation("mine"); runCurrent()
        assertEquals(NativeChiefTranslationIndicator.UNSUPPORTED, model.state.value.indicator)
        assertNull(model.state.value.presentations["mine"])
    }

    @Test fun sameTextRestartCannotAcceptAnOlderAutomaticRequest() = checkModel { model, engine ->
        val gate = CompletableDeferred<Unit>(); engine.translationGate = gate
        model.translateConversation("spanish"); runCurrent()
        model.stopAutomatic()
        model.translateMessage("spanish", translationLanguage("fr")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("es", "fr"), engine.calls.map { it.source.code })
        assertEquals("fr", model.state.value.presentations["spanish"]!!.source.code)
    }

    @Test fun preferencesRestoreOnlyTheSameTargetAndStoppingClearsOnlyThePair() = runTest {
        var saved: NativeChiefAutoTranslation? = null
        val preferences = object : NativeChiefTranslationPreferences {
            override fun load() = saved
            override fun save(value: NativeChiefAutoTranslation?) { saved = value }
        }
        val first = NativeChiefTranslations(this, ChiefTranslationTestEngine(), english, { true }, preferences)
        val second = NativeChiefTranslations(this, ChiefTranslationTestEngine(), english, { true }, preferences)
        var restored: NativeChiefTranslations? = null
        var otherTarget: NativeChiefTranslations? = null
        try {
            first.updateRows(rows); first.translateConversation("spanish"); runCurrent()
            restored = NativeChiefTranslations(this, ChiefTranslationTestEngine(), english, { true }, preferences)
            restored.updateRows(rows); runCurrent()
            assertTrue(restored.isShowingTranslation("spanish"))
            otherTarget = NativeChiefTranslations(this, ChiefTranslationTestEngine(), translationLanguage("fr"), { true }, preferences)
            assertNull(otherTarget.state.value.automatic)
            first.close()
            assertNotNull(saved)
            restored.stopAutomatic()
            assertNull(saved)
            assertNull(second.state.value.automatic)
        } finally { first.close(); second.close(); restored?.close(); otherTarget?.close() }
    }

    @Test fun retirementClearsProtectedTextAndRejectsLateTranslation() = runTest {
        val engine = ChiefTranslationTestEngine().apply { translationGate = CompletableDeferred(); ignoreCancellation = true }
        var current = true
        val model = NativeChiefTranslations(this, engine, english, { current })
        try {
            model.updateRows(rows); model.translateMessage("spanish"); runCurrent()
            current = false
            assertThrows(IllegalStateException::class.java) { model.toggleOriginal("spanish") }
            assertTrue(model.state.value.retired); assertTrue(model.state.value.presentations.isEmpty())
            engine.translationGate!!.complete(Unit); runCurrent()
            assertTrue(model.state.value.presentations.isEmpty()); assertEquals(1, engine.closeCount)
        } finally { engine.translationGate!!.complete(Unit); model.close() }
    }

    @Test fun lateDetectionCannotAdmitAChangedOwner() = runTest {
        val engine = ChiefTranslationTestEngine().apply { detectionGate = CompletableDeferred() }
        var current = true
        val model = NativeChiefTranslations(this, engine, english, { current })
        try {
            model.updateRows(rows)
            supervisorScope {
                val action = async { model.translateMessage("spanish") }; runCurrent()
                current = false; engine.detectionGate!!.complete(Unit)
                try { action.await(); fail("Retired detection must reject admission") } catch (_: IllegalStateException) { }
            }
            assertTrue(model.state.value.retired); assertTrue(engine.calls.isEmpty())
        } finally { model.close() }
    }

    @Test fun pendingClientKeyKeepsTranslationWhenTheServerAcknowledgesIt() = checkModel { model, engine ->
        val date = java.time.Instant.parse("2026-10-20T12:00:00Z")
        val pending = NativeChiefOutgoing("stable", "es: hola", date, NativeChiefDelivery.QUEUED)
        model.updateRows(NativeChiefTimeline.items(NativeChiefTranscriptState(outgoing = listOf(pending))).map { it.translationRow() })
        model.translateMessage("c:stable"); runCurrent()
        val echo = NativeChiefMessage("server", 1uL, "me", "You", "human", pending.text, date, emptyList(), emptyList(), null, "stable")
        model.updateRows(NativeChiefTimeline.items(NativeChiefTranscriptState(messages = listOf(echo))).map { it.translationRow() })
        runCurrent()
        assertTrue(model.isShowingTranslation("c:stable")); assertEquals(1, engine.calls.size)
    }

    @Test fun sourceLanguageChoicesExcludeTheTargetCodeAndKeepTheFirstVariant() = checkModel { model, engine ->
        engine.languages = listOf(english, translationLanguage("es-MX"), translationLanguage("es-ES"), translationLanguage("ja"))
            .map { if (it.code.startsWith("es-")) it.copy(code = "es") else it }
        assertEquals(listOf("es-MX", "ja"), model.sourceLanguages().map { it.tag })
    }

    @Test fun scriptAndRegionMatchingUsesEveryIndependentPinnedFoundationVector() {
        val vectors = JSONObject(javaClass.getResourceAsStream("/parity/chief-translation-languages.json")!!.bufferedReader().use { it.readText() })
            .getJSONArray("languages")
        val values = (0 until vectors.length()).map { vectors.getJSONObject(it) }
        assertEquals(18, values.size)
        for (a in values) for (b in values) {
            fun language(value: JSONObject) = NativeChiefTranslationLanguage(value.getString("input"), value.getString("language"), value.getString("script").takeIf { it.isNotEmpty() })
            val expected = a.getJSONArray("matches").let { list -> (0 until list.length()).any { list.getString(it) == b.getString("input") } }
            assertEquals("${a.getString("input")} / ${b.getString("input")}", expected, language(a).matches(language(b)))
        }
    }
}
