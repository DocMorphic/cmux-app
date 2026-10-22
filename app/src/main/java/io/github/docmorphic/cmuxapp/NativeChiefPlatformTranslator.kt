package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import android.view.translation.*
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The host handles the platform's model-download UI; a declined request never submits text. */
@RequiresApi(31)
internal class NativeChiefPlatformTranslator private constructor(private val context: Context,
    private val manager: TranslationManager,
    private val download: suspend (NativeChiefTranslationLanguage, NativeChiefTranslationLanguage) -> Boolean) : NativeChiefTranslating {
    private val closed = AtomicBoolean()
    private val sessions = Collections.synchronizedSet(mutableSetOf<Translator>())
    private val signals = Collections.synchronizedSet(mutableSetOf<CancellationSignal>())
    private val pending = Collections.synchronizedSet(mutableSetOf<CancellableContinuation<*>>())

    companion object {
        suspend fun create(context: Context,
            download: suspend (NativeChiefTranslationLanguage, NativeChiefTranslationLanguage) -> Boolean = { _, _ -> false }): NativeChiefPlatformTranslator? {
            if (Build.VERSION.SDK_INT < 31) return null
            val app = context.applicationContext
            val manager = app.getSystemService(TranslationManager::class.java) ?: return null
            val capabilities = withContext(Dispatchers.IO) {
                discoverNativeChiefTranslationCapabilities {
                    manager.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT).toList()
                }
            }
            return if (capabilities.isEmpty()) null else NativeChiefPlatformTranslator(app, manager, download)
        }
    }

    private fun checkOpen() { if (closed.get()) throw CancellationException("Translation owner closed") }
    private suspend fun capabilities(): List<TranslationCapability> = withContext(Dispatchers.IO) {
        checkOpen()
        discoverNativeChiefTranslationCapabilities {
            checkOpen()
            manager.getOnDeviceTranslationCapabilities(TranslationSpec.DATA_FORMAT_TEXT, TranslationSpec.DATA_FORMAT_TEXT).toList().also { checkOpen() }
        }
    }
    private fun TranslationCapability.matches(source: NativeChiefTranslationLanguage, target: NativeChiefTranslationLanguage) =
        NativeChiefTranslationLanguages.fromTag(sourceSpec.locale.toLanguageTag())?.matches(source) == true &&
            NativeChiefTranslationLanguages.fromTag(targetSpec.locale.toLanguageTag())?.matches(target) == true

    override suspend fun availability(source: NativeChiefTranslationLanguage, target: NativeChiefTranslationLanguage): NativeChiefTranslationAvailability {
        val matches = capabilities().filter { it.matches(source, target) }
        return when {
            matches.any { it.state == TranslationCapability.STATE_ON_DEVICE } -> NativeChiefTranslationAvailability.INSTALLED
            matches.any { it.state == TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD || it.state == TranslationCapability.STATE_DOWNLOADING } -> NativeChiefTranslationAvailability.DOWNLOADABLE
            else -> NativeChiefTranslationAvailability.UNSUPPORTED
        }
    }

    override suspend fun supportedLanguages(): List<NativeChiefTranslationLanguage> = capabilities()
        .filter { it.state != TranslationCapability.STATE_NOT_AVAILABLE }
        .flatMap { listOf(it.sourceSpec.locale, it.targetSpec.locale) }
        .mapNotNull { NativeChiefTranslationLanguages.fromTag(it.toLanguageTag()) }.distinct()

    override suspend fun detectLanguage(text: String): NativeChiefTranslationLanguage? = withContext(Dispatchers.IO) {
        checkOpen()
        val result = context.getSystemService(TextClassificationManager::class.java).textClassifier
            .detectLanguage(TextLanguage.Request.Builder(text).build())
        checkOpen()
        (0 until result.localeHypothesisCount).map { result.getLocale(it) }
            .maxByOrNull { result.getConfidenceScore(it) }?.takeIf { result.getConfidenceScore(it) >= .5f }
            ?.let { NativeChiefTranslationLanguages.fromTag(it.toLanguageTag()) }
    }

    /** Public immutable PendingIntent, launched by a foreground host after user admission. */
    fun modelSettingsIntent() = manager.onDeviceTranslationSettingsActivityIntent.also { checkOpen() }

    override suspend fun translate(texts: Map<String, String>, source: NativeChiefTranslationLanguage,
        target: NativeChiefTranslationLanguage): Map<String, String> {
        checkOpen()
        if (texts.isEmpty()) return emptyMap()
        when (availability(source, target)) {
            NativeChiefTranslationAvailability.UNSUPPORTED -> throw NativeChiefTranslationException(NativeChiefTranslationFailure.UNSUPPORTED)
            NativeChiefTranslationAvailability.DOWNLOADABLE -> {
                if (!download(source, target)) throw NativeChiefTranslationException(NativeChiefTranslationFailure.NOT_DOWNLOADED)
                checkOpen()
                if (availability(source, target) != NativeChiefTranslationAvailability.INSTALLED)
                    throw NativeChiefTranslationException(NativeChiefTranslationFailure.NOT_DOWNLOADED)
            }
            NativeChiefTranslationAvailability.INSTALLED -> Unit
        }
        val capability = capabilities().firstOrNull { it.state == TranslationCapability.STATE_ON_DEVICE && it.matches(source, target) }
            ?: throw NativeChiefTranslationException(NativeChiefTranslationFailure.NOT_DOWNLOADED)
        val translationContext = TranslationContext.Builder(capability.sourceSpec, capability.targetSpec).build()
        val translator = suspendCancellableCoroutine<Translator> { continuation ->
            pending += continuation
            continuation.invokeOnCancellation { pending -= continuation }
            if (closed.get()) { continuation.cancel(); return@suspendCancellableCoroutine }
            try {
                manager.createOnDeviceTranslator(translationContext, context.mainExecutor) { value ->
                    pending -= continuation
                    if (!continuation.isActive || closed.get()) { value?.destroy(); continuation.cancel(); return@createOnDeviceTranslator }
                    if (value == null) continuation.resumeWithException(NativeChiefTranslationException(NativeChiefTranslationFailure.FAILED))
                    else { sessions += value; continuation.resume(value) }
                }
            } catch (error: Exception) {
                pending -= continuation
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        try {
            checkOpen()
            val translated = linkedMapOf<String, String>()
            // Keep Binder payloads bounded without splitting a message's language context.
            val chunks = mutableListOf<MutableList<Map.Entry<String, String>>>()
            var size = 0
            for (entry in texts.entries) {
                if (chunks.isEmpty() || size + entry.value.length > 60_000) { chunks += mutableListOf<Map.Entry<String, String>>(); size = 0 }
                chunks.last().add(entry); size += entry.value.length
            }
            for (chunk in chunks) {
                checkOpen()
                val request = TranslationRequest.Builder().setFlags(TranslationRequest.FLAG_TRANSLATION_RESULT)
                    .setTranslationRequestValues(chunk.map { TranslationRequestValue.forText(it.value) }).build()
                val signal = CancellationSignal()
                signals += signal
                try {
                    val response = suspendCancellableCoroutine<TranslationResponse> { continuation ->
                        pending += continuation
                        continuation.invokeOnCancellation { pending -= continuation; signal.cancel() }
                        if (closed.get()) { continuation.cancel(); return@suspendCancellableCoroutine }
                        try {
                            translator.translate(request, signal, context.mainExecutor) { result ->
                                if (result.isFinalResponse) {
                                    pending -= continuation
                                    if (continuation.isActive) continuation.resume(result)
                                }
                            }
                        } catch (error: Exception) {
                            pending -= continuation
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    checkOpen()
                    if (response.translationStatus != TranslationResponse.TRANSLATION_STATUS_SUCCESS)
                        throw NativeChiefTranslationException(if (response.translationStatus == TranslationResponse.TRANSLATION_STATUS_CONTEXT_UNSUPPORTED)
                            NativeChiefTranslationFailure.UNSUPPORTED else NativeChiefTranslationFailure.FAILED)
                    chunk.forEachIndexed { index, entry ->
                        response.translationResponseValues[index]?.takeIf { it.statusCode == TranslationResponseValue.STATUS_SUCCESS }
                            ?.text?.toString()?.let { translated[entry.key] = it }
                    }
                } finally { signals -= signal }
            }
            return translated
        } finally { sessions -= translator; if (!translator.isDestroyed) translator.destroy() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(pending) { pending.toList() }.forEach { it.cancel() }
        synchronized(signals) { signals.toList() }.forEach { it.cancel() }
        synchronized(sessions) { sessions.toList() }.forEach { if (!it.isDestroyed) it.destroy() }
        pending.clear(); signals.clear(); sessions.clear()
    }
}
